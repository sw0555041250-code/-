package com.chdv.searchplus

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.chdv.searchplus.core.Ref
import com.chdv.searchplus.core.RefParser
import java.io.File
import java.util.concurrent.Executors

/**
 * The voice search screen: a big microphone, what was heard (editable), what it was understood as,
 * and where it leads – every edition of the book at that place, or the books that contain the words.
 * Every step is shown, so the way from the spoken words to the open book is always visible.
 */
class VoiceActivity : BaseActivity() {
    private val exec = Executors.newSingleThreadExecutor()
    private lateinit var heard: EditText
    private lateinit var understood: TextView
    private lateinit var results: LinearLayout
    private lateinit var status: TextView
    private var token = 0
    private lateinit var loading: LinearLayout
    private lateinit var bar: android.widget.ProgressBar
    private lateinit var secs: TextView
    private var started = 0L
    private var target = 0
    private val tick = object : Runnable {
        override fun run() {
            if (loading.visibility != View.VISIBLE) return
            // creep towards the current stage, so the bar always moves a little
            if (bar.progress < target) bar.progress = minOf(target, bar.progress + 3)
            else if (bar.progress < 97) bar.progress = bar.progress + 1
            secs.text = "⏱ ${(System.currentTimeMillis() - started) / 1000} שנ׳"
            loading.postDelayed(this, if (Col.isEink) 700 else 250)
        }
    }

    private fun startLoading() {
        started = System.currentTimeMillis(); target = 8; bar.progress = 0
        loading.visibility = View.VISIBLE
        loading.removeCallbacks(tick); loading.post(tick)
    }

    private fun stopLoading() { bar.progress = 100; loading.removeCallbacks(tick); loading.visibility = View.GONE }
    private val typing by lazy { intent.getStringExtra("mode") == "type" }
    private val live = Runnable { val t = heard.text.toString().trim(); if (t.length >= 3) handle(t, false, quiet = true) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val box = vbox().apply { setPadding(dp(16), dp(10), dp(16), dp(30)) }

        // the big microphone
        val mic = vbox().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(22), dp(16), dp(18))
            background = Col.gradient(Col.TILES[0].first, Col.TILES[0].second, dp(26).toFloat())
            setOnClickListener { listen() }
        }
        mic.addView(label("🎤", 64f, Col.ACC, true).apply {
            gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER
            background = roundBg(Color.WHITE, dp(70).toFloat(), dp(4), if (Col.isEink) Col.ACC else 0x55FFFFFF)
        }, lp(dp(140), dp(140)))
        mic.addView(label("לחצו ודברו", 22f, Color.WHITE, true).apply { gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER; setPadding(0, dp(12), 0, 0) }, lp(MATCH, WRAP))
        status = label(engineNote(), 13.5f, 0xFFE6E9FF.toInt()).apply { gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER }
        mic.addView(status, lp(MATCH, WRAP))
        if (!typing) box.addView(mic, lp(MATCH, WRAP).m(0, dp(4), 0, dp(14)))
        else box.addView(vbox().apply {
            background = Col.gradient(Col.TILES[3].first, Col.TILES[3].second, dp(20).toFloat())
            setPadding(dp(18), dp(16), dp(18), dp(16))
            addView(label("⚡ לאן לקפוץ?", 22f, Color.WHITE, true))
            addView(label("כותבים ספר ומקום – והוא נפתח. למשל: \"לקו״ש חלק כ״ד עמוד 358\", \"ברכות כ״ג\", \"שו״ע אדה״ז סימן רע״ח\"", 14f, 0xEEFFFFFF.toInt()).apply { setPadding(0, dp(6), 0, 0) })
        }, lp(MATCH, WRAP).m(0, dp(4), 0, dp(14)))

        // what was heard – can be corrected by hand
        box.addView(label("שמעתי", 13.5f, Col.SUB, true))
        val row = hbox().apply { background = roundBg(Col.CARD, dp(22).toFloat(), dp(1.5f), Col.ACC); setPadding(dp(14), 0, dp(4), 0) }
        heard = EditText(this).apply {
            hint = "מה שתגידו יופיע כאן – אפשר גם לתקן ולהקליד"
            background = null; setSingleLine(); textDirection = View.TEXT_DIRECTION_FIRST_STRONG_RTL
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f * scale())
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_SEARCH) { handle(text.toString(), false); true } else false }
        }
        row.addView(heard, lp(0, WRAP, 1f))
        if (typing) row.addView(label("🎤", 22f, Col.ACC, true).apply { setPadding(dp(8), dp(4), dp(8), dp(4)); setOnClickListener { listen() } })
        row.addView(pill(if (typing) "לפתוח" else "חיפוש") { handle(heard.text.toString(), typing) }, lp(WRAP, WRAP))
        if (typing) heard.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) {
                // live: what was understood and where it leads, while typing
                heard.removeCallbacks(live)
                heard.postDelayed(live, 550)
            }
        })
        box.addView(row, lp(MATCH, WRAP).m(0, dp(4), 0, dp(10)))

        understood = label("", 16f, Col.ACC, true)
        box.addView(understood, lp(MATCH, WRAP).m(dp(4), 0, dp(4), dp(6)))
        // a loading bar that moves, with seconds – so it is clear the search is working and how far it got
        loading = hbox().apply { visibility = View.GONE }
        bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = 0 }
        loading.addView(bar, lp(0, dp(14), 1f))
        secs = label("", 13.5f, Col.SUB, true).apply { setPadding(dp(10), 0, dp(4), 0) }
        loading.addView(secs)
        box.addView(loading, lp(MATCH, WRAP).m(dp(4), 0, dp(4), dp(8)))
        results = vbox()
        box.addView(results, lp(MATCH, WRAP))

        // recent searches – one tap away
        val hist = Library.history().take(8)
        if (hist.isNotEmpty()) {
            box.addView(label("🕘 אחרונים", 13.5f, Col.SUB, true).apply { setPadding(0, dp(16), 0, dp(4)) })
            val hf = FlowLayout(this)
            hist.forEach { h -> hf.addView(chip(h, false) { heard.setText(h); handle(h, true) }) }
            box.addView(hf, lp(MATCH, WRAP))
        }
        // examples
        box.addView(label(if (typing) "אפשר לכתוב למשל" else "אפשר לומר למשל", 13.5f, Col.SUB, true).apply { setPadding(0, dp(16), 0, dp(4)) })
        val ex = FlowLayout(this)
        listOf("ברכות דף כ״ז עמוד א", "לקו״ש חלק כ״ד עמוד 358", "ליקוטי שיחות חלק ד שיחה לפרשת נח", "שו״ע אדה״ז סימן רע״ח", "תניא פרק ה", "מה מברכים על פרי העץ").forEach { e ->
            ex.addView(chip("“$e”", false) { heard.setText(e); handle(e, false) })
        }
        box.addView(ex, lp(MATCH, WRAP))

        screen(if (typing) "⚡ מעבר מהיר" else "🎤 חיפוש קולי", ScrollView(this).apply { addView(box) })
        if (typing) heard.post { heard.requestFocus(); (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(heard, 0) }
        intent.getStringExtra("q")?.let { heard.setText(it); handle(it, true) }
        if (intent.getBooleanExtra("listen", false) && savedInstanceState == null && intent.getStringExtra("q") == null) heard.post { listen() }
    }

    private fun engineNote() = if (VoiceSearch.engine(this) == "system") "זיהוי: שירות הדיבור של המכשיר" else
        "זיהוי בתוך המכשיר – בלי אינטרנט" + if (!VoiceModels.ready(this)) " (צריך להוריד פעם אחת)" else ""

    private fun listen() {
        VoiceSearch.start(this) { t ->
            heard.setText(t); heard.setSelection(heard.text.length)
            // "transcribe only": the words are written in the box, the search waits for the user
            if (Prefs.b("voiceSearchAuto", true)) handle(t, true)
            else { understood.text = "✍ נכתב. אפשר לתקן, ואז \"${if (typing) "לפתוח" else "חיפוש"}\""; heard.requestFocus() }
        }
    }

    override fun onResume() { super.onResume(); status.text = engineNote() }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == VoiceSearch.REQ_MIC) VoiceSearch.onPermission(this, grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED)
    }

    /** Spoken (or typed) words → a place in a book, or the books that contain them. */
    private fun handle(raw: String, auto: Boolean, quiet: Boolean = false) {
        val text = raw.trim().trimEnd('.', '?', '!')
        if (text.isEmpty()) return
        if (!quiet) Library.addHistory(text)
        val my = ++token
        results.removeAllViews()
        understood.text = "🔎 מתחיל לחפש…"
        startLoading()
        exec.execute {
            val ref = try { RefParser.parse(text) } catch (e: Throwable) { null }
            val noDaf = ref != null && ref.kind == Ref.Kind.GEMARA && ref.daf == null
            // a newer request (typing on) makes this one stale: stop at once, don't block the next
            if (my != token) return@execute
            val stale = { my != token }
            val prog: (String, Int) -> Unit = { t, pct -> ui { if (my == token) { understood.text = t; target = maxOf(target, pct) } } }
            if (ref != null) prog("💡 הבנתי: ${ref.label()}", 10)
            val targets = if (ref != null && !noDaf) try { Jump.resolve(ref, text, stale, prog) } catch (e: Throwable) { emptyList() } else emptyList()
            if (targets.isEmpty() && !stale()) prog("🔤 מחפש את המילים בתוך הספרים…", 90)
            if (stale()) return@execute
            // no place in a book – maybe the name of a book or a folder (as they are on the device)
            val named = if (ref == null) try { Catalog.find(text, null, 8).filter { it.score >= 5f } } catch (e: Throwable) { emptyList() } else emptyList()
            if (stale()) return@execute
            // not a place in a book (or no such book): the books that contain the words
            val words = if (targets.isEmpty()) try {
                Search.run(text, Match.ALL, true).takeIf { it.books.isNotEmpty() } ?: Search.run(text, Match.ANY, true)
            } catch (e: Throwable) { null } else null
            ui {
                if (my != token || isFinishing) return@ui
                show(text, ref, noDaf, targets, words, auto, named)
            }
        }
    }

    private fun show(text: String, ref: Ref?, noDaf: Boolean, targets: List<Jump.Target>, words: Results?, auto: Boolean, named: List<Catalog.Match> = emptyList()) {
        stopLoading()
        results.removeAllViews()
        when {
            noDaf -> understood.text = "הבנתי שמדובר במסכת ${ref!!.work} – איזה דף? למשל: \"${ref.work} דף כ״ז עמוד א\""
            ref != null && targets.size == 1 -> understood.text = "✓ מצאתי! ${ref.label()} – " + if (targets[0].exact) "לחצו לפתיחה" else "בעמוד המשוער, אפשר לתקן בקורא"
            ref != null && targets.isNotEmpty() -> understood.text = "✓ מצאתי לך ${targets.size} אפשרויות ל${ref.label()} – באיזו לפתוח?"
            ref != null -> understood.text = "חיפשתי ${ref.label()} – אבל לא מצאתי ספר כזה במאגר" + (if (Progress.running) " (הסריקה עדיין רצה)" else "")
            else -> understood.text = "חיפוש המילים: \"$text\""
        }
        // the request is the name of books / a folder on the device
        if (named.isNotEmpty()) {
            understood.text = "📚 מצאתי ${if (named.size == 1) "ספר" else "${named.size} ספרים"} בשם הזה" + if (named.size > 1) " – איזה לפתוח?" else ""
            for (m in named) results.addView(card("📖 ${m.book.title}", Library.categoryOf(m.book.dir).joinToString(" › ").ifEmpty { "תיקייה ראשית" } + " · ${m.book.pages} עמ׳", true) {
                ReaderActivity.open(this, m.book.path, Library.lastPage(m.book.path))
            })
            if (words != null && words.books.any { b -> b.pages.isNotEmpty() }) results.addView(label("ובתוך הספרים, המילים מופיעות כאן:", 14.5f, Col.SUB, true).apply { setPadding(dp(4), dp(12), dp(4), dp(2)) })
        }
        // no such book: the closest names in the library
        if (ref != null && targets.isEmpty()) {
            val sug = try { Jump.suggest(text) } catch (e: Throwable) { emptyList() }
            if (sug.isNotEmpty()) {
                results.addView(label("אולי התכוונת ל:", 14.5f, Col.SUB, true).apply { setPadding(dp(4), dp(4), dp(4), dp(2)) })
                for (b in sug) results.addView(card("📖 ${b.title}", Library.categoryOf(b.dir).joinToString(" › "), false) { ReaderActivity.open(this, b.path, 1) })
            }
        }
        val nums = "①②③④⑤⑥⑦⑧⑨⑩⑪⑫"
        for ((k, t) in targets.withIndex()) results.addView(card((if (targets.size > 1) "${nums.getOrElse(k) { '•' }}  " else "📖 ") + t.book.title, Library.categoryOf(t.book.dir).joinToString(" › ") + " · " + t.note, true) {
            ReaderActivity.open(this, t.book.path, t.page, t.marks, refLabel = t.ref.label(), amudIndex = t.amudIndex ?: -1, exact = t.exact)
        })
        if (words != null) {
            val books = words.books.sortedByDescending { it.score }.take(25)
            if (books.isEmpty()) results.addView(label("לא נמצא. נסו לומר אחרת, או לשאול לפי משמעות ⬇", 15f, Col.SUB).apply { setPadding(dp(4), dp(6), dp(4), dp(6)) })
            for (h in books) {
                val first = h.pages.firstOrNull()?.page ?: 1
                results.addView(card("📖 ${h.book.title}", h.category.joinToString(" › ").ifEmpty { "תיקייה ראשית" } +
                    if (h.pages.isNotEmpty()) " · ${h.pages.size} עמודים" else " · לפי השם", false) {
                    ReaderActivity.open(this, h.book.path, first, words.terms, h.pages.map { it.page }.distinct().sorted().toIntArray())
                })
            }
            if (words.books.size > books.size) results.addView(pill("לכל ${words.books.size} התוצאות", false) { openMain("words", text) }, lp(MATCH, WRAP).m(0, dp(8), 0, 0))
        }
        results.addView(pill("💡 לשאול את זה לפי משמעות", false) { openMain("meaning", text) }, lp(MATCH, WRAP).m(0, dp(10), 0, 0))
        // nothing useful came out: offer the more accurate speech recognition
        val weak = noDaf || (targets.isEmpty() && (words == null || words.books.isEmpty())) || (ref != null && targets.isEmpty())
        val next = VoiceModels.nextSpec()
        if (weak && next != null && VoiceSearch.engine(this) == "whisper") {
            results.addView(pill("🎤 לא הבין נכון? לנסות זיהוי ${next.title} (${next.note})") {
                Prefs.put("whisperModel", next.key); VoiceModels.Engine.release()
                if (VoiceModels.ready(this, next.key)) listen() else VoiceSearch.downloadNow(this, next.key) { listen() }
            }, lp(MATCH, WRAP).m(0, dp(10), 0, 0))
        } else if (weak && VoiceSearch.engine(this) == "whisper" && VoiceSearch.systemAvailable(this)) {
            // the most advanced: the device's online speech service (Google), much stronger in Hebrew
            results.addView(pill("🌐 לא הבין נכון? לנסות זיהוי מתקדם דרך האינטרנט") {
                Prefs.put("voiceEngine", "auto"); Prefs.put("systemFailed", false); listen()
            }, lp(MATCH, WRAP).m(0, dp(10), 0, 0))
        }
        if (auto && targets.size == 1) results.postDelayed({ if (!isFinishing) results.getChildAt(0)?.performClick() }, 700)
    }

    private fun openMain(screen: String, q: String) =
        startActivity(Intent(this, MainActivity::class.java).putExtra("screen", screen).putExtra("q", q).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))

    private fun card(title: String, sub: String, strong: Boolean, onClick: () -> Unit): View = vbox().apply {
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = pressBg(if (strong) Col.ACC_LIGHT else Col.CARD, Col.ACC_LIGHT, 14, dp(1), if (strong) Col.ACC else Col.LINE)
        addView(label(title, 16.5f, Col.TEXT, true).apply { maxLines = 2 })
        addView(label(sub, 13f, Col.SUB))
        setOnClickListener { onClick() }
        layoutParams = lp(MATCH, WRAP).m(0, dp(4), 0, dp(4))
    }

    override fun onDestroy() { super.onDestroy(); exec.shutdown() }
}
