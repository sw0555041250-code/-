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
        box.addView(mic, lp(MATCH, WRAP).m(0, dp(4), 0, dp(14)))

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
        row.addView(pill("חיפוש") { handle(heard.text.toString(), false) }, lp(WRAP, WRAP))
        box.addView(row, lp(MATCH, WRAP).m(0, dp(4), 0, dp(10)))

        understood = label("", 16f, Col.ACC, true)
        box.addView(understood, lp(MATCH, WRAP).m(dp(4), 0, dp(4), dp(6)))
        results = vbox()
        box.addView(results, lp(MATCH, WRAP))

        // examples
        box.addView(label("אפשר לומר למשל", 13.5f, Col.SUB, true).apply { setPadding(0, dp(16), 0, dp(4)) })
        val ex = FlowLayout(this)
        listOf("ברכות דף כ״ז עמוד א", "ליקוטי שיחות חלק ד עמוד 58", "ליקוטי שיחות חלק ד שיחה לפרשת נח", "תניא פרק ה", "שולחן ערוך אורח חיים סימן קכח", "מה מברכים על פרי העץ").forEach { e ->
            ex.addView(chip("“$e”", false) { heard.setText(e); handle(e, false) })
        }
        box.addView(ex, lp(MATCH, WRAP))

        screen("🎤 חיפוש קולי", ScrollView(this).apply { addView(box) })
        intent.getStringExtra("q")?.let { heard.setText(it); handle(it, true) }
        if (intent.getBooleanExtra("listen", false) && savedInstanceState == null && intent.getStringExtra("q") == null) heard.post { listen() }
    }

    private fun engineNote() = if (VoiceSearch.engine(this) == "system") "זיהוי: שירות הדיבור של המכשיר" else
        "זיהוי בתוך המכשיר – בלי אינטרנט" + if (!VoiceModels.ready(this)) " (צריך להוריד פעם אחת)" else ""

    private fun listen() {
        VoiceSearch.start(this) { t -> heard.setText(t); handle(t, true) }
    }

    override fun onResume() { super.onResume(); status.text = engineNote() }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == VoiceSearch.REQ_MIC) VoiceSearch.onPermission(this, grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED)
    }

    /** Spoken (or typed) words → a place in a book, or the books that contain them. */
    private fun handle(raw: String, auto: Boolean) {
        val text = raw.trim().trimEnd('.', '?', '!')
        if (text.isEmpty()) return
        Library.addHistory(text)
        val my = ++token
        results.removeAllViews()
        understood.text = "מחפש…"
        exec.execute {
            val ref = try { RefParser.parse(text) } catch (e: Throwable) { null }
            val noDaf = ref != null && ref.kind == Ref.Kind.GEMARA && ref.daf == null
            val targets = if (ref != null && !noDaf) try { Jump.resolve(ref) } catch (e: Throwable) { emptyList() } else emptyList()
            // not a place in a book (or no such book): the books that contain the words
            val words = if (targets.isEmpty()) try {
                Search.run(text, Match.ALL, true).takeIf { it.books.isNotEmpty() } ?: Search.run(text, Match.ANY, true)
            } catch (e: Throwable) { null } else null
            ui {
                if (my != token || isFinishing) return@ui
                show(text, ref, noDaf, targets, words, auto)
            }
        }
    }

    private fun show(text: String, ref: Ref?, noDaf: Boolean, targets: List<Jump.Target>, words: Results?, auto: Boolean) {
        results.removeAllViews()
        when {
            noDaf -> understood.text = "הבנתי: מסכת ${ref!!.work} – אבל לא את מספר הדף. נסו שוב: \"${ref.work} דף כ״ז עמוד א\""
            ref != null && targets.isNotEmpty() -> understood.text = "הבנתי: ${ref.label()}" + if (targets.size > 1) " · נמצא ב-${targets.size} ספרים – בחרו" else ""
            ref != null -> understood.text = "הבנתי: ${ref.label()} – אבל אין ספר כזה במאגר" + if (Progress.running) " (הסריקה עדיין רצה)" else ". הנה הספרים עם המילים:"
            else -> understood.text = "חיפוש המילים: \"$text\""
        }
        for (t in targets) results.addView(card("📖 ${t.book.title}", Library.categoryOf(t.book.dir).joinToString(" › ") + " · " + t.note, true) {
            ReaderActivity.open(this, t.book.path, t.page, refLabel = t.ref.label(), amudIndex = t.amudIndex ?: -1, exact = t.exact)
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
