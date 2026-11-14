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
        fun open(c: Context, path: String, page: Int, terms: List<String> = emptyList(), hits: IntArray? = null) {
            if (Prefs.s("openWith", "internal") == "external" && c is android.app.Activity) {
                Library.addRecent(path, page)
                Library.openExternal(c, path, page)
                return
            }
            c.startActivity(Intent(c, ReaderActivity::class.java)
                .putExtra("path", path).putExtra("page", page)
                .putExtra("terms", terms.toTypedArray())
                .putExtra("hits", hits))
        }
    }

    private val exec = Executors.newSingleThreadExecutor()
    private var path: String? = null
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var doc: PDDocument? = null
    private var pageCount = 0
    private var page = 1
    private var terms: List<String> = emptyList()
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
        hits = intent.getIntArrayExtra("hits") ?: IntArray(0)

        build()
        try {
            pfd = if (path != null) ParcelFileDescriptor.open(File(path!!), ParcelFileDescriptor.MODE_READ_ONLY)
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
        head.addView(headerButton("חיפוש בספר") { searchInBook() })
        head.addView(headerButton("⋯") { menu() })
        root.addView(head, lp(MATCH, WRAP))
        root.addView(divider())

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
            if (terms.isNotEmpty() && path != null) {
                val rects = try { highlights(p, bmp.width / pageW) } catch (e: Throwable) { emptyList() }
                ui { if (token == renderToken && rects.isNotEmpty()) view.setHighlights(rects) }
            }
        }
    }

    /** Find the boxes of the searched words on the page with PDFBox (positions of every letter). */
    private fun highlights(p: Int, scale: Float): List<RectF> {
        val d = doc ?: Indexer.loadDoc(File(path!!)).also { doc = it }
        val plain = PDFTextStripper().apply { startPage = p; endPage = p }
        val raw = plain.getText(d)
        val method = Heb.repairPage(raw).method
        val want = terms.map { it.removeSuffix("*") }.filter { it.isNotEmpty() }
        val minLen = if (want.size == 1) 1 else 2
        val out = ArrayList<RectF>()
        val st = object : PDFTextStripper() {
            override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
                val tps = textPositions ?: return
                val word = ArrayList<TextPosition>()
                fun flush() {
                    if (word.isEmpty()) return
                    val s = Heb.clean(Heb.applyMethod(word.joinToString("") { it.unicode ?: "" }, method))
                    val letters = s.filter { it.isLetterOrDigit() }
                    if (want.any { it.length >= minLen && letters.contains(it) }) {
                        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -1f; var b = -1f
                        for (tp in word) {
                            val x = tp.xDirAdj; val y = tp.yDirAdj
                            val h = tp.heightDir.coerceAtLeast(tp.fontSizeInPt * 0.7f)
                            l = minOf(l, x); r = maxOf(r, x + tp.widthDirAdj)
                            t = minOf(t, y - h); b = maxOf(b, y)
                        }
                        val pad = 1.5f
                        out.add(RectF((l - pad) * scale, (t - pad) * scale, (r + pad) * scale, (b + pad * 2) * scale))
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
        st.startPage = p; st.endPage = p
        st.getText(d)
        return out
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
            val r = Search.run(q, Match.valueOf(Prefs.s("match", "PHRASE")), Prefs.b("prefixes", true), b.id)
            val pages = r.books.firstOrNull()?.pages ?: emptyList()
            if (pages.isEmpty()) { Sheet.info(this, "לא נמצא", "\"$q\" לא נמצא בספר הזה."); return@input }
            terms = r.terms
            hits = pages.map { it.page }.distinct().toIntArray()
            val expr = Search.buildMatch(r.terms, Match.valueOf(Prefs.s("match", "PHRASE")), Prefs.b("prefixes", true))
            Sheet.actions(this, "נמצא ב-${hits.size} עמודים", pages.take(200).map { ph ->
                val sn = Search.snippet(expr, ph.pageId)?.replace("\u0001", "«")?.replace("\u0002", "»")?.replace('\n', ' ') ?: ""
                "עמוד ${ph.page}:  $sn" to { page = ph.page; show() }
            })
        }
    }

    private fun menu() {
        val p = path
        val items = ArrayList<Pair<String, () -> Unit>>()
        items.add("מעבר לעמוד…" to { goTo() })
        if (p != null) {
            val key = "$p|$page"
            items.add((if (Library.isFavorite(key)) "הסרה מהמועדפים" else "הוספת העמוד למועדפים ★") to {
                val on = Library.toggleFavorite(key); toast(if (on) "נוסף למועדפים" else "הוסר מהמועדפים")
            })
            items.add("העתקת הטקסט של העמוד" to { copyPage() })
            items.add("שיתוף הקובץ" to {
                val i = Intent(Intent.ACTION_SEND).setType("application/pdf")
                    .putExtra(Intent.EXTRA_STREAM, Library.uriFor(this, File(p)))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                safeStart(Intent.createChooser(i, "שיתוף"))
            })
            items.add("פתיחה באפליקציה אחרת" to { Library.openExternal(this, p, page) })
        }
        items.add(if (view.zoomed()) "חזרה לגודל מלא" to { view.reset() } else "הגדלה ×2" to { view.zoomTo(2f) })
        Sheet.actions(this, "עמוד $page", items)
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
    private val hl = Paint().apply { color = Col.HL; style = Paint.Style.FILL }
    private val hlLine = Paint().apply { color = 0xFF000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f }

    fun setPage(b: Bitmap, r: List<RectF>) {
        val old = bmp
        bmp = b; rects = r
        zoom = 1f; tx = 0f; ty = 0f
        if (old != null && old !== b) old.recycle()
        invalidate()
    }

    fun setHighlights(r: List<RectF>) { rects = r; invalidate() }
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
        hlLine.strokeWidth = 2f / s
        for (r in rects) { canvas.drawRect(r, hl); canvas.drawRect(r.left, r.bottom - 3f / s, r.right, r.bottom, hlLine) }
        canvas.restore()
    }

    private val scaleDet = ScaleGestureDetector(c, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
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
