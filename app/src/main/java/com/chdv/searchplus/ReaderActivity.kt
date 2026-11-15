package com.chdv.searchplus

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import java.util.concurrent.Executors

/**
 * Built-in PDF reader: opens on the page of the result and marks the searched words on the page.
 * Made for E-ink: whole pages, no animation, page turning by tapping the side of the screen,
 * with the volume keys or with the buttons at the bottom.
 */
class ReaderActivity : BaseActivity() {

    companion object {
        fun open(c: Context, path: String, page: Int, terms: List<String> = emptyList(), hits: IntArray? = null,
                 refLabel: String? = null, amudIndex: Int = -1, exact: Boolean = true, phrase: String? = null) {
            if (c is android.app.Activity && !Prefs.b("readerChosen", false)) {
                Library.chooseDefault(c) { open(c, path, page, terms, hits, refLabel, amudIndex, exact, phrase) }
                return
            }
            // a book in Google Drive: downloaded first (the last ones opened stay in a small cache)
            if (Cloud.isCloud(path) && c is android.app.Activity && Cloud.cached(c, path) == null) {
                Cloud.open(c, path) { open(c, path, page, terms, hits, refLabel, amudIndex, exact, phrase) }
                return
            }
            if (Prefs.s("openWith", "internal") == "external" && c is android.app.Activity) {
                Library.addRecent(path, page)
                Library.openExternal(c, path, page, terms, chooser = false, phrase = phrase)
                return
            }
            c.startActivity(Intent(c, ReaderActivity::class.java)
                .putExtra("path", path).putExtra("page", page)
                .putExtra("terms", terms.toTypedArray())
                .putExtra("hits", hits)
                .putExtra("refLabel", refLabel).putExtra("amudIndex", amudIndex).putExtra("exact", exact)
                .putExtra("phrase", phrase))
        }
    }

    private val exec = Executors.newSingleThreadExecutor()
    private var path: String? = null
    /** The file on the device ([path] itself, or the downloaded copy of a Drive book). */
    private val file: String? get() = path?.let { p -> if (Cloud.isCloud(p)) Cloud.cached(this, p)?.absolutePath ?: p else p }
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var doc: PDDocument? = null
    private var pageCount = 0
    private var page = 1
    private var terms: List<String> = emptyList()
    /** The sentence to mark (from question & answer). */
    private var phrase: String? = null
    private var phrasePage = 0
    private var hits: IntArray = IntArray(0)
    private lateinit var view: PageView
    private lateinit var pageLabel: TextView
    private lateinit var hitLabel: TextView
    private lateinit var titleView: TextView
    private var renderToken = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Indexer.initPdfBox(this)
        var uri: Uri? = null
        if (intent.action == Intent.ACTION_VIEW) {
            uri = intent.data
            if (uri?.scheme == "file") path = uri.path
        } else path = intent.getStringExtra("path")
        page = savedInstanceState?.getInt("page") ?: intent.getIntExtra("page", 0).let { if (it <= 0 && path != null) Library.lastPage(path!!) else it.coerceAtLeast(1) }
        terms = intent.getStringArrayExtra("terms")?.toList() ?: emptyList()
        phrase = intent.getStringExtra("phrase")
        phrasePage = page
        hits = intent.getIntArrayExtra("hits") ?: IntArray(0)

        build()
        try {
            pfd = if (path != null) ParcelFileDescriptor.open(File(file!!), ParcelFileDescriptor.MODE_READ_ONLY)
            else contentResolver.openFileDescriptor(uri!!, "r")
            renderer = PdfRenderer(pfd!!)
            pageCount = renderer!!.pageCount
        } catch (e: Exception) {
            Sheet.info(this, "לא ניתן לפתוח את הקובץ", "ייתכן שהקובץ פגום או מוגן בסיסמה.\n\n" + (e.message ?: ""))
            return
        }
        titleView.text = path?.let { File(it).name.removeSuffix(".pdf").removeSuffix(".PDF") } ?: "PDF"
        show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("page", page)
    }

    private fun build() {
        val root = vbox().apply { setBackgroundColor(Col.BG); layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val head = hbox().apply { setPadding(dp(6), dp(4), dp(6), dp(4)) }
        head.addView(label("→", 24f, Col.ACC, true).apply { setPadding(dp(10), 0, dp(10), 0); setOnClickListener { finish() } })
        titleView = label("", 16f, Col.TEXT, true).apply { setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END }
        head.addView(titleView, lp(0, WRAP, 1f))
        if (terms.isNotEmpty() || phrase != null) head.addView(headerButton("✨") { showAgain() }.apply { contentDescription = "להדגיש שוב" })
        head.addView(headerButton("חיפוש בספר") { searchInBook() })
        head.addView(headerButton("⋯") { menu() })
        root.addView(head, lp(MATCH, WRAP))
        root.addView(divider())

        // opened from a reference (ברכות דף כז): let the user confirm or correct the place, and remember it
        val refLabel = intent.getStringExtra("refLabel")
        val amudIndex = intent.getIntExtra("amudIndex", -1)
        if (refLabel != null) {
            val rb = hbox().apply { setPadding(dp(12), dp(6), dp(8), dp(6)); setBackgroundColor(Col.ACC_LIGHT) }
            rb.addView(label("⤶ " + refLabel + if (!intent.getBooleanExtra("exact", true)) " · משוער" else "", 14.5f, Col.ACC, true), lp(0, WRAP, 1f))
            if (amudIndex >= 0) rb.addView(headerButton("✓ זה המקום") {
                path?.let { Jump.calibrate(it, page, amudIndex) }
                toast("נשמר – בפעם הבאה ייפתח כאן")
                rb.visibility = View.GONE
            })
            rb.addView(headerButton("✕") { rb.visibility = View.GONE })
            root.addView(rb, lp(MATCH, WRAP))
            if (amudIndex >= 0) root.addView(label("לא הגעתם בדיוק? דפדפו לדף הנכון ולחצו \"✓ זה המקום\" – התוכנה תלמד את הספר", 12.5f, Col.SUB).apply {
                setPadding(dp(12), dp(2), dp(12), dp(4)); id = 77
            }, lp(MATCH, WRAP))
        }

        val frame = FrameLayout(this)
        view = PageView(this)
        view.onTap = { fx ->
            when {
                fx < 0.3f -> next()
                fx > 0.7f -> prev()
                else -> menu()
            }
        }
        frame.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(frame, lp(MATCH, 0, 1f))

        root.addView(divider())
        val bar = hbox().apply { setPadding(dp(6), dp(4), dp(6), dp(4)) }
        bar.addView(barBtn("הקודם ▶") { prev() }, lp(0, WRAP, 1f))
        pageLabel = barBtn("") { goTo() }
        bar.addView(pageLabel, lp(0, WRAP, 1.2f))
        hitLabel = barBtn("") { nextHit() }
        hitLabel.setOnLongClickListener { prevHit(); true }
        bar.addView(hitLabel, lp(0, WRAP, 1.3f))
        bar.addView(barBtn("◀ הבא") { next() }, lp(0, WRAP, 1f))
        root.addView(bar, lp(MATCH, WRAP))
        setContentView(root)
    }

    private fun barBtn(t: String, onClick: () -> Unit) = label(t, 15f, Col.ACC, true).apply {
        gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER
        setPadding(dp(4), dp(10), dp(4), dp(10))
        background = pressBg(Color.TRANSPARENT, Col.ACC_LIGHT, 12)
        setOnClickListener { onClick() }
    }

    private fun updateBar() {
        pageLabel.text = "עמוד $page / $pageCount"
        if (hits.isEmpty()) {
            hitLabel.text = ""
            hitLabel.visibility = View.GONE
        } else {
            val idx = hits.indexOf(page)
            hitLabel.visibility = View.VISIBLE
            hitLabel.text = if (idx >= 0) "תוצאה ${idx + 1}/${hits.size} ◀" else "לתוצאה הבאה ◀"
        }
    }

    private fun show() {
        if (renderer == null) return
        page = page.coerceIn(1, pageCount.coerceAtLeast(1))
        updateBar()
        path?.let { Library.addRecent(it, page) }
        val token = ++renderToken
        val w = view.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val p = page
        exec.execute {
            if (token != renderToken) return@execute
            val r = renderer ?: return@execute
            val rendered: Pair<Bitmap, Float> = try {
                r.openPage(p - 1).use { pg ->
                    val bw = (w * 1.5f).toInt().coerceIn(600, 2000)
                    val bh = (bw.toLong() * pg.height / pg.width.coerceAtLeast(1)).toInt().coerceIn(200, 4000)
                    val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                    b.eraseColor(Color.WHITE)
                    pg.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    b to pg.width.toFloat()
                }
            } catch (e: Throwable) {
                ui { toast("לא ניתן להציג את העמוד") }
                return@execute
            }
            val bmp = rendered.first
            val pageW = rendered.second
            ui { if (token == renderToken) view.setPage(bmp, emptyList()) else bmp.recycle() }
            if ((terms.isNotEmpty() || phrase != null) && path != null) {
                val rects = try { highlights(p, bmp.width / pageW, bmp.width) } catch (e: Throwable) { emptyList() }
                ui { if (token == renderToken && rects.isNotEmpty()) view.setHighlights(rects) }
            }
        }
    }

    /** Find the boxes of the searched words on the page with PDFBox (positions of every letter). */
    /**
     * Boxes to mark on the page. With a [phrase] (the sentence that answers a question) the run of
     * words that best matches it is marked as one passage; otherwise every word that contains a
     * searched word.
     */
    private fun highlights(p: Int, scale: Float, bmpW: Int = 0): List<RectF> {
        val d = doc ?: Indexer.loadDoc(File(file!!)).also { doc = it }
        val plain = PDFTextStripper().apply { startPage = p; endPage = p }
        val raw = plain.getText(d)
        val method = Heb.repairPage(raw).method
        val words = ArrayList<Pair<String, RectF>>()
        val st = object : PDFTextStripper() {
            override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
                val tps = textPositions ?: return
                val word = ArrayList<TextPosition>()
                fun flush() {
                    if (word.isEmpty()) return
                    val s = Heb.clean(Heb.applyMethod(word.joinToString("") { it.unicode ?: "" }, method)).filter { it.isLetterOrDigit() }
                    if (s.isNotEmpty()) {
                        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -1f; var b = -1f
                        for (tp in word) {
                            val x = tp.xDirAdj; val y = tp.yDirAdj
                            val h = tp.heightDir.coerceAtLeast(tp.fontSizeInPt * 0.7f)
                            l = minOf(l, x); r = maxOf(r, x + tp.widthDirAdj)
                            t = minOf(t, y - h); b = maxOf(b, y)
                        }
                        val pad = 2.5f
                        words.add(s to RectF((l - pad) * scale, (t - pad) * scale, (r + pad) * scale, (b + pad * 2) * scale))
                    }
                    word.clear()
                }
                for (tp in tps) {
                    val u = tp.unicode ?: ""
                    if (u.isBlank() || u == "-" || u == "," || u == "." || u == ":") flush() else word.add(tp)
                }
                flush()
            }
        }
        st.sortByPosition = Prefs.b("sortByPosition", false)
        st.startPage = p; st.endPage = p
        st.getText(d)
        // an old scanned page has no text layer: find the words on the image itself (OCR)
        if (words.size < 5 && bmpW > 0) {
            ui { toast("מחפש את המילים בעמוד הסרוק…") }
            words.clear()
            words.addAll(Ocr.wordBoxes(this, file!!, p - 1, bmpW))
        }

        val ph = phrase?.takeIf { p == phrasePage }?.let { Heb.words(it).filter { w -> w.length > 1 } } ?: emptyList()
        if (ph.size >= 3 && words.isNotEmpty()) {
            // slide a window as long as the sentence and keep the place where most of its words are
            val n = minOf(ph.size, 40)
            val want = ph.take(n)
            var best = -1; var bestScore = 0
            for (i in words.indices) {
                var sc = 0
                for (k in 0 until n) {
                    val w = words.getOrNull(i + k)?.first ?: break
                    if (want.any { it == w || (it.length > 2 && (w.endsWith(it) || it.endsWith(w))) }) sc++
                }
                if (sc > bestScore) { bestScore = sc; best = i }
            }
            if (best >= 0 && bestScore >= minOf(3, n)) return words.subList(best, minOf(words.size, best + n)).map { it.second }
        }
        // final letters compared as regular ones (מלך / מלכים), so every form of the word is marked
        fun fin(x: String) = x.replace('ך', 'כ').replace('ם', 'מ').replace('ן', 'נ').replace('ף', 'פ').replace('ץ', 'צ')
        val want = terms.map { fin(it.removeSuffix("*")) }.filter { it.isNotEmpty() }
        val minLen = if (want.size == 1) 1 else 2
        return words.filter { (w, _) -> val fw = fin(w); want.any { it.length >= minLen && fw.contains(it) } }.map { it.second }
    }

    private fun next() { if (page < pageCount) { page++; show() } }
    private fun prev() { if (page > 1) { page--; show() } }

    private fun nextHit() {
        val n = hits.firstOrNull { it > page } ?: hits.firstOrNull() ?: return
        page = n; show()
    }

    private fun prevHit() {
        val n = hits.lastOrNull { it < page } ?: hits.lastOrNull() ?: return
        page = n; show()
    }

    private fun goTo() {
        Sheet.input(this, "מעבר לעמוד", "", "1 – $pageCount", numeric = true) { s ->
            s.trim().toIntOrNull()?.let { page = it; show() }
        }
    }

    private fun searchInBook() {
        val p = path ?: return
        val b = Db.get().bookByPath(p)
        if (b == null || b.status != St.DONE) {
            Sheet.info(this, "הספר עוד לא נסרק", "החיפוש בתוך הספר יהיה זמין אחרי שהסריקה תגיע אליו.")
            return
        }
        Sheet.input(this, "חיפוש בתוך הספר", terms.joinToString(" ") { it.removeSuffix("*") }, "מה לחפש?") { q ->
            toast("מחפש…")
            // the search and the excerpts are read in the background – the page never freezes
            Thread {
                val m = Match.valueOf(Prefs.s("match", "PHRASE")); val pre = Prefs.b("prefixes", true)
                val r = try { Search.run(q, m, pre, b.id) } catch (e: Throwable) { null }
                val pages = r?.books?.firstOrNull()?.pages ?: emptyList()
                val expr = if (r != null) Search.buildMatch(r.terms, m, pre) else ""
                val lines = pages.take(60).map { ph ->
                    ph to (Search.snippet(expr, ph.pageId)?.replace("\u0001", "«")?.replace("\u0002", "»")?.replace('\n', ' ') ?: "")
                } + pages.drop(60).take(140).map { it to "" }
                ui {
                    if (isFinishing) return@ui
                    if (r == null || pages.isEmpty()) { Sheet.info(this, "לא נמצא", "\"$q\" לא נמצא בספר הזה."); return@ui }
                    terms = r.terms
                    phrase = null
                    hits = pages.map { it.page }.distinct().toIntArray()
                    Sheet.actions(this, "נמצא ב-${hits.size} עמודים", lines.map { (ph, sn) ->
                        "עמוד ${ph.page}:  $sn" to { page = ph.page; show() }
                    })
                }
            }.start()
        }
    }

    private fun menu() {
        val p = path
        val items = ArrayList<Pair<String, () -> Unit>>()
        items.add("מעבר לעמוד…" to { goTo() })
        if (terms.isNotEmpty() || phrase != null) items.add("✨ להדגיש שוב את מה שחיפשתי" to { showAgain() })
        if (p != null) {
            val key = "$p|$page"
            items.add((if (Library.isFavorite(key)) "הסרה מהמועדפים" else "הוספת העמוד למועדפים ★") to {
                val on = Library.toggleFavorite(key); toast(if (on) "נוסף למועדפים" else "הוסר מהמועדפים")
            })
            items.add("📄 תצוגת טקסט – שורות מסודרות" to { textView() })
            items.add("העתקת הטקסט של העמוד" to { copyPage() })
            items.add("שיתוף הקובץ" to {
                val i = Intent(Intent.ACTION_SEND).setType("application/pdf")
                    .putExtra(Intent.EXTRA_STREAM, Library.uriFor(this, File(file ?: p)))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                safeStart(Intent.createChooser(i, "שיתוף"))
            })
            items.add("פתיחה באפליקציה אחרת" to { Library.openExternal(this, p, page, terms, chooser = true, phrase = phrase?.takeIf { page == phrasePage }) })
        }
        items.add(if (view.zoomed()) "חזרה לגודל מלא" to { view.reset() } else "הגדלה ×2" to { view.zoomTo(2f) })
        Sheet.actions(this, "עמוד $page", items)
    }

    /**
     * The page as clean text: lines joined into paragraphs, large letters, the searched words or the
     * answering sentence marked. Reads well even when the PDF is an old scan (its text comes from OCR).
     */
    private fun textView() {
        val p = path ?: return
        val b = Db.get().bookByPath(p)
        var pg = page
        val body = vbox().apply { setPadding(dp(18), dp(14), dp(18), dp(14)) }
        val head = label("", 15f, Col.ACC, true)
        val tv = label("", 19f, Col.TEXT).apply { setLineSpacing(0f, 1.35f); setTextIsSelectable(true) }
        fun render() {
            head.text = "עמוד $pg" + (b?.let { " · " + it.title } ?: "")
            val raw = b?.let { Db.get().pageText(it.id, pg) }
            if (raw.isNullOrBlank()) { tv.text = "אין עדיין טקסט לעמוד הזה (אם הוא סרוק – הזיהוי (OCR) עוד לא הגיע אליו)."; return }
            // join broken lines; a line that ends a sentence starts a new paragraph
            val sb = StringBuilder()
            for (line in raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }) {
                if (sb.isNotEmpty()) sb.append(if (sb.last() in ".:?!") "\n\n" else " ")
                sb.append(line)
            }
            val text = sb.toString()
            val sp = android.text.SpannableString(text)
            fun mark(st: Int, en: Int) {
                sp.setSpan(android.text.style.BackgroundColorSpan(if (Col.isEink) 0x40000000 else 0x66FFB300), st, en, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sp.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), st, en, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val ph = phrase?.takeIf { pg == phrasePage }
            val at = ph?.let { text.indexOf(it.take(40)) } ?: -1
            if (ph != null && at >= 0) mark(at, minOf(text.length, at + ph.length))
            else for (t in terms.map { it.removeSuffix("*") }.filter { it.length > 1 }) {
                var i = text.indexOf(t)
                while (i >= 0) {
                    var st = i; var en = i + t.length
                    while (st > 0 && text[st - 1].isLetter()) st--
                    while (en < text.length && text[en].isLetter()) en++
                    mark(st, en); i = text.indexOf(t, en)
                }
            }
            tv.text = sp
        }
        body.addView(head)
        body.addView(android.widget.ScrollView(this).apply { addView(tv) }, lp(MATCH, dp(480)).m(0, dp(8), 0, dp(8)))
        val row = hbox()
        row.addView(pill("◀ הבא", false) { if (pg < pageCount) { pg++; render() } }, lp(0, WRAP, 1f).m(dp(3), 0, dp(3), 0))
        row.addView(pill("לעמוד בתמונה") { page = pg; show(); dlg?.dismiss() }, lp(0, WRAP, 1.3f).m(dp(3), 0, dp(3), 0))
        row.addView(pill("הקודם ▶", false) { if (pg > 1) { pg--; render() } }, lp(0, WRAP, 1f).m(dp(3), 0, dp(3), 0))
        body.addView(row)
        render()
        dlg = Sheet.make(this, body).also { it.show() }
    }
    private var dlg: android.app.Dialog? = null

    /** Mark again where the searched words are (after the marking was calmed by a touch, or on another page). */
    private fun showAgain() {
        if (terms.isEmpty() && phrase == null) { toast("אין מילים לסמן – חפשו בספר"); return }
        if (hits.isNotEmpty() && page !in hits) { nextHit(); return }
        phrasePage = if (phrase != null) page else phrasePage
        show()
    }

    private fun copyPage() {
        val p = path ?: return
        val b = Db.get().bookByPath(p)
        val t = b?.let { Db.get().pageText(it.id, page) }
        if (t.isNullOrBlank()) { toast("אין טקסט שמור לעמוד הזה"); return }
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("page", "$t\n\n(${b.title}, עמוד $page)"))
        toast("הטקסט הועתק")
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_DPAD_LEFT -> { next(); return true }
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_RIGHT -> { prev(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        renderToken++
        exec.execute {
            try { renderer?.close() } catch (e: Exception) {}
            try { pfd?.close() } catch (e: Exception) {}
            try { doc?.close() } catch (e: Exception) {}
        }
        exec.shutdown()
    }
}

/** Shows one page; pinch to zoom, drag when zoomed, double tap to zoom in/out, tap to turn pages. */
@SuppressLint("ViewConstructor")
class PageView(c: Context) : View(c) {
    var onTap: ((Float) -> Unit)? = null
    private var bmp: Bitmap? = null
    private var rects: List<RectF> = emptyList()
    private var zoom = 1f
    private var tx = 0f
    private var ty = 0f
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val hl = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (Col.isEink) 0x40000000 else 0x66FFB300; style = Paint.Style.FILL }
    private val hlLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (Col.isEink) 0xFF000000.toInt() else 0xFFE65100.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (Col.isEink) 0xFF000000.toInt() else 0xFFE65100.toInt(); style = Paint.Style.FILL }
    /** 0..1 – the "look here" pulse after the words were found */
    private var pulse = 0f
    /** Strong marking, until the first touch. */
    private var emph = false

    /** The user touched the page: the marking stays, quieter. */
    fun calm() {
        if (!emph) return
        emph = false
        pulseAnim?.cancel(); pulse = 0f
        invalidate()
    }
    private var pulseAnim: android.animation.ValueAnimator? = null

    /** Words next to each other on the same line become one box, so a whole phrase is framed together. */
    private fun merge(r: List<RectF>): List<RectF> {
        val out = ArrayList<RectF>()
        for (x in r.sortedWith(compareBy({ it.centerY() }, { it.left }))) {
            val last = out.lastOrNull()
            val h = x.height()
            if (last != null && kotlin.math.abs(last.centerY() - x.centerY()) < h * 0.5f &&
                (x.left - last.right < h * 1.6f && last.left - x.right < h * 1.6f)) {
                last.union(x)
            } else out.add(RectF(x))
        }
        return out
    }

    fun setPage(b: Bitmap, r: List<RectF>) {
        val old = bmp
        bmp = b; rects = r
        zoom = 1f; tx = 0f; ty = 0f
        if (old != null && old !== b) old.recycle()
        invalidate()
    }

    fun setHighlights(r: List<RectF>) {
        rects = merge(r)
        // a phone screen: zoom in on the first place, so it is seen at once
        val b = bmp
        if (b != null && rects.isNotEmpty() && width < context.dp(600) && zoom <= 1.01f) {
            val f = rects[0]
            zoom = 1.8f
            val s = base() * zoom
            tx = width / 2f - f.centerX() * s
            ty = height / 3f - f.centerY() * s
            clamp()
        }
        // "it is here": strong until the user touches the page; on colour screens it keeps breathing
        emph = rects.isNotEmpty()
        pulseAnim?.cancel()
        pulseAnim = android.animation.ValueAnimator.ofFloat(0f, 1f, 0f).apply {
            duration = if (Col.isEink) 900 else 1100
            repeatCount = if (Col.isEink) 2 else android.animation.ValueAnimator.INFINITE
            addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() { override fun onAnimationEnd(a: android.animation.Animator) { pulse = 0f; invalidate() } })
            start()
        }
        invalidate()
    }
    fun zoomed() = zoom > 1.01f
    fun reset() { zoom = 1f; tx = 0f; ty = 0f; invalidate() }
    fun zoomTo(z: Float) { zoom = z; tx = -(width * (z - 1) / 2); ty = -(height * (z - 1) / 2); clamp(); invalidate() }

    private fun base(): Float {
        val b = bmp ?: return 1f
        return minOf(width / b.width.toFloat(), height / b.height.toFloat())
    }

    private fun clamp() {
        val b = bmp ?: return
        val s = base() * zoom
        val cw = b.width * s; val ch = b.height * s
        tx = if (cw <= width) (width - cw) / 2 else tx.coerceIn(width - cw, 0f)
        ty = if (ch <= height) (height - ch) / 2 else ty.coerceIn(height - ch, 0f)
    }

    override fun onDraw(canvas: Canvas) {
        val b = bmp ?: return
        clamp()
        val s = base() * zoom
        canvas.save()
        canvas.translate(tx, ty)
        canvas.scale(s, s)
        canvas.drawBitmap(b, 0f, 0f, paint)
        val d = context.resources.displayMetrics.density
        hlLine.strokeWidth = ((if (emph) 4f else 2f) + 3f * pulse) * d / s
        hl.color = if (Col.isEink) (if (emph) 0x55000000 else 0x30000000) else (if (emph) 0x88FFB300.toInt() else 0x50FFB300)
        val grow = 6f * pulse * d / s
        val rad = 5f * d / s
        for (r in rects) {
            val rr = RectF(r.left - grow, r.top - grow, r.right + grow, r.bottom + grow)
            canvas.drawRoundRect(rr, rad, rad, hl)
            canvas.drawRoundRect(rr, rad, rad, hlLine)
            // a marker in the margin of that line
            val cy = r.centerY(); val m = 7f * d / s
            val x = b.width - 4f * d / s
            val path = android.graphics.Path().apply { moveTo(x, cy - m); lineTo(x - m * 1.3f, cy); lineTo(x, cy + m); close() }
            canvas.drawPath(path, mark)
        }
        canvas.restore()
    }

    private val scaleDet = ScaleGestureDetector(c, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            calm()
            val nz = (zoom * d.scaleFactor).coerceIn(1f, 5f)
            val f = nz / zoom
            tx = d.focusX - (d.focusX - tx) * f
            ty = d.focusY - (d.focusY - ty) * f
            zoom = nz
            invalidate()
            return true
        }
    })

    private val gest = GestureDetector(c, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            calm()
            if (!zoomed()) onTap?.invoke(e.x / width.coerceAtLeast(1))
            else onTap?.invoke(0.5f)
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoomed()) reset() else {
                val nz = 2.2f
                tx = e.x - (e.x - tx) * nz; ty = e.y - (e.y - ty) * nz; zoom = nz
                invalidate()
            }
            return true
        }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            calm()
            if (!zoomed()) return false
            tx -= dx; ty -= dy; invalidate(); return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (zoomed() || e1 == null) return false
            val dx = e2.x - e1.x
            if (kotlin.math.abs(dx) > width * 0.2f && kotlin.math.abs(dx) > kotlin.math.abs(e2.y - e1.y)) {
                // Hebrew books: swipe right = next page
                onTap?.invoke(if (dx > 0) 0f else 1f)
                return true
            }
            return false
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDet.onTouchEvent(event)
        if (!scaleDet.isInProgress) gest.onTouchEvent(event)
        return true
    }
}
