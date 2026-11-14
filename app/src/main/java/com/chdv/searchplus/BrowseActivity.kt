package com.chdv.searchplus

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

/** Browse the library folders and open books without searching. */
class BrowseActivity : BaseActivity() {
    private var dir: File? = null
    private lateinit var pathLabel: TextView
    private lateinit var list: RecyclerView
    private var items: List<File> = emptyList()
    private var filter = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = vbox()
        pathLabel = label("", 13f, Col.SUB).apply { setPadding(dp(14), dp(6), dp(14), dp(6)) }
        body.addView(pathLabel, lp(MATCH, WRAP))
        val search = android.widget.EditText(this).apply {
            hint = "סינון לפי שם…"
            setSingleLine()
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG_RTL
            background = roundBg(Col.CARD, dp(18).toFloat(), dp(1), Col.LINE)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { filter = s.toString().trim(); load() }
            })
        }
        body.addView(search, lp(MATCH, WRAP).m(dp(12), 0, dp(12), dp(6)))
        list = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@BrowseActivity); itemAnimator = null }
        body.addView(list, lp(MATCH, 0, 1f))
        screen("עיון בספרים", body)
        list.adapter = Adapter()
        load()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val d = dir
        if (d == null) { super.onBackPressed(); return }
        dir = if (Library.roots().contains(d.absolutePath)) null else d.parentFile
        filter = ""
        load()
    }

    private fun load() {
        val d = dir
        if (d == null) {
            pathLabel.text = "תיקיות הספרים"
            items = Library.roots().map { File(it) }
        } else {
            pathLabel.text = Library.categoryOf(d.absolutePath).joinToString(" › ").ifEmpty { Library.rootName(d.absolutePath) }
            val kids = d.listFiles()?.filter { !it.name.startsWith(".") && (it.isDirectory || it.name.endsWith(".pdf", true)) } ?: emptyList()
            items = kids.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name }))
        }
        if (filter.isNotEmpty()) items = items.filter { it.name.contains(filter, true) }
        list.adapter?.notifyDataSetChanged()
    }

    private fun fileMenu(f: File) {
        val b = Db.get().bookByPath(f.absolutePath)
        val items = ArrayList<Pair<String, () -> Unit>>()
        items.add("פתיחה" to { ReaderActivity.open(this, f.absolutePath, 0) })
        items.add("פתיחה באפליקציה אחרת" to { Library.openExternal(this, f.absolutePath, 1, chooser = true) })
        if (b != null) {
            items.add("קריאה מחדש של הספר (סריקה)" to { Indexer.reindex(this, b); toast("הספר נכנס לתור הסריקה") })
            if (b.emptyPages > 0) items.add("זיהוי טקסט (OCR) ל-${b.emptyPages} העמודים הסרוקים" to {
                if (!Ocr.ready(this)) toast("קודם יש להוריד את רכיב ה-OCR בהגדרות")
                else { Progress.ocrFile = b.id; Indexer.start(this); toast("זיהוי הטקסט התחיל ברקע") }
            })
            items.add("מצב בסריקה" to {
                val st = when (b.status) { St.DONE -> "נסרק ✓"; St.ERROR -> "שגיאה בקריאה"; else -> "ממתין לסריקה" }
                Sheet.info(this, b.title, "$st\nעמודים: ${b.pages}\nעמודים עם טקסט: ${b.textPages}\nעמודים סרוקים בלי טקסט: ${b.emptyPages}\nגודל: ${human(b.size)}")
            })
        }
        Sheet.actions(this, f.nameWithoutExtension, items)
    }

    inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val c = this@BrowseActivity
            val v = c.vbox().apply {
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = pressBg(Col.BG, Col.ACC_LIGHT, 0)
                addView(c.label("", 16f, Col.TEXT, true).apply { id = 1; maxLines = 2 })
                addView(c.label("", 12.5f, Col.SUB).apply { id = 2 })
                layoutParams = RecyclerView.LayoutParams(MATCH, WRAP).apply { bottomMargin = 1 }
            }
            return object : RecyclerView.ViewHolder(v) {}
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            val f = items[position]
            val v = h.itemView
            if (f.isDirectory) {
                v.findViewById<TextView>(1).text = "📁 " + (if (dir == null) Library.rootName(f.absolutePath) else f.name)
                v.findViewById<TextView>(2).text = if (dir == null) f.absolutePath else ""
                v.findViewById<TextView>(2).visibility = if (dir == null) View.VISIBLE else View.GONE
                v.setOnClickListener { dir = f; filter = ""; load() }
                v.setOnLongClickListener(null)
            } else {
                val b = Db.get().bookByPath(f.absolutePath)
                v.findViewById<TextView>(1).text = "📖 " + f.nameWithoutExtension
                v.findViewById<TextView>(2).visibility = View.VISIBLE
                v.findViewById<TextView>(2).text = human(f.length()) + "  ·  " + when (b?.status) {
                    St.DONE -> "נסרק ✓ (${b.pages} עמ׳" + (if (b.emptyPages > 0) ", ${b.emptyPages} סרוקים" else "") + ")"
                    St.ERROR -> "לא ניתן לקרוא"
                    else -> "ממתין לסריקה"
                }
                v.setOnClickListener { ReaderActivity.open(this@BrowseActivity, f.absolutePath, 0) }
                v.setOnLongClickListener { fileMenu(f); true }
            }
        }
    }
}

/** In-app folder chooser (works with the internal memory and the SD card). */
object FolderPicker {
    fun pick(a: Activity, onPick: (String) -> Unit) {
        val box = a.vbox().apply { setPadding(a.dp(16), a.dp(14), a.dp(16), a.dp(12)) }
        val title = a.label("בחירת תיקייה", 18f, Col.TEXT, true)
        val path = a.label("", 13f, Col.SUB).apply { setPadding(0, a.dp(4), 0, a.dp(8)) }
        val inner = a.vbox()
        val scroll = ScrollView(a).apply { addView(inner) }
        box.addView(title); box.addView(path)
        box.addView(scroll, LinearLayout.LayoutParams(MATCH, a.dp(380)))
        lateinit var d: Dialog
        var cur: File? = null
        lateinit var chooseBtn: TextView

        fun show(dir: File?) {
            cur = dir
            inner.removeAllViews()
            if (dir == null) {
                path.text = "בחרו אחסון:"
                Library.volumes(a).forEachIndexed { i, v ->
                    val name = if (i == 0) "💾 זיכרון פנימי" else "💳 כרטיס זיכרון (${v.name})"
                    inner.addView(row(a, name) { show(v) })
                }
                chooseBtn.visibility = View.GONE
                return
            }
            path.text = dir.absolutePath
            chooseBtn.visibility = View.VISIBLE
            inner.addView(row(a, "⬆ למעלה") {
                val vols = Library.volumes(a).map { it.absolutePath }
                show(if (dir.absolutePath in vols) null else dir.parentFile)
            })
            val kids = dir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.sortedBy { it.name } ?: emptyList()
            if (kids.isEmpty()) inner.addView(a.label("(אין תיקיות משנה)", 14f, Col.SUB).apply { setPadding(a.dp(8), a.dp(10), 0, 0) })
            kids.forEach { k ->
                val pdfs = k.listFiles()?.count { it.name.endsWith(".pdf", true) } ?: 0
                inner.addView(row(a, "📁 ${k.name}" + if (pdfs > 0) "   ($pdfs PDF)" else "") { show(k) })
            }
        }

        val btns = a.hbox()
        chooseBtn = a.pill("בחירת התיקייה הזו") { cur?.let { d.dismiss(); onPick(it.absolutePath) } }
        btns.addView(chooseBtn, LinearLayout.LayoutParams(0, WRAP, 1f).m(a.dp(3), 0, a.dp(3), 0))
        btns.addView(a.pill("ביטול", false) { d.dismiss() }, LinearLayout.LayoutParams(0, WRAP, 1f).m(a.dp(3), 0, a.dp(3), 0))
        box.addView(btns, LinearLayout.LayoutParams(MATCH, WRAP).m(0, a.dp(10), 0, 0))
        d = Sheet.make(a, box)
        // with an SD card, start at the storage list so the card is easy to find
        show(if (Library.volumes(a).size > 1) null else Library.storageRoot())
        d.show()
    }

    private fun row(c: Context, t: String, onClick: () -> Unit) = c.label(t, 16f, Col.TEXT).apply {
        setPadding(c.dp(8), c.dp(12), c.dp(8), c.dp(12))
        background = c.pressBg(android.graphics.Color.TRANSPARENT, Col.ACC_LIGHT, 10)
        setOnClickListener { onClick() }
    }
}
