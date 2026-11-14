package com.chdv.searchplus

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import com.chdv.searchplus.core.Abbr

/** מילון ראשי תיבות: look up any abbreviation, or by its meaning. The same dictionary the search uses. */
class AbbrActivity : BaseActivity() {
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val box = vbox().apply { setPadding(dp(14), dp(10), dp(14), dp(30)) }
        box.addView(vbox().apply {
            background = Col.gradient(Col.TILES[1].first, Col.TILES[1].second, dp(20).toFloat())
            setPadding(dp(18), dp(16), dp(18), dp(16))
            addView(label("📖 מילון ראשי תיבות", 21f, android.graphics.Color.WHITE, true))
            addView(label("אדה״ז, צ״צ, לקו״ש, שליט״א, עכ״ל… – כותבים, ורואים מה הפירוש. החיפוש בתוכנה מכיר את כולם.", 14f, 0xEEFFFFFF.toInt()).apply { setPadding(0, dp(6), 0, 0) })
        }, lp(MATCH, WRAP).m(0, dp(4), 0, dp(12)))
        val q = EditText(this).apply {
            hint = "ראשי תיבות או מילה…"; setSingleLine(); textDirection = View.TEXT_DIRECTION_RTL
            background = roundBg(Col.CARD, dp(20).toFloat(), dp(1.5f), Col.ACC); setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        box.addView(q, lp(MATCH, WRAP))
        list = vbox()
        box.addView(list, lp(MATCH, WRAP).m(0, dp(10), 0, 0))
        q.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) { show(e.toString()) }
        })
        screen("📖 ראשי תיבות", ScrollView(this).apply { addView(box) })
        show("")
    }

    private fun show(q: String) {
        list.removeAllViews()
        val items = Abbr.lookup(q).distinctBy { it.second + "|" + it.first.replace("\"", "").replace("״", "") }.take(200)
        if (items.isEmpty()) { list.addView(label("לא נמצא במילון.", 15f, Col.SUB)); return }
        for ((k, v) in items) list.addView(row(this, k, v))
    }

    companion object {
        fun row(a: Activity, k: String, v: String): View = a.hbox().apply {
            setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10))
            background = roundBg(Col.CARD, a.dp(12).toFloat(), a.dp(1), Col.LINE)
            addView(a.label(pretty(k), 17f, Col.ACC, true), lp(a.dp(110), WRAP))
            addView(a.label(v, 16f, Col.TEXT), lp(0, WRAP, 1f))
            layoutParams = lp(MATCH, WRAP).m(0, a.dp(3), 0, a.dp(3))
        }

        /** "אדהז" → "אדה״ז" for display. */
        fun pretty(k: String): String =
            if (k.contains('״') || k.contains('׳') || k.contains(' ') || k.length < 2) k else k.dropLast(1) + "״" + k.last()

        /** The abbreviations that appear on a page, with their meaning. */
        fun onPage(text: String): List<Pair<String, String>> {
            val out = LinkedHashMap<String, String>()
            for (w in text.split(Regex("[\\s,.:;()\\[\\]]+"))) {
                if (!(w.contains('"') || w.contains('״') || w.contains('\'') || w.contains('׳'))) continue
                val m = Abbr.of(w) ?: Abbr.of(w.drop(1))?.let { "${w.first()}+ $it" } ?: continue
                out.putIfAbsent(w.trim('\'', '׳'), m)
            }
            return out.entries.map { it.key to it.value }
        }
    }
}
