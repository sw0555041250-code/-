package com.chdv.searchplus

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors

/** Tidy the library on the device: duplicates, smart sorting into folders, smart names. Everything asks first and can be undone. */
class OrganizeActivity : BaseActivity() {
    private val bg = Executors.newSingleThreadExecutor()
    private lateinit var tabs: LinearLayout
    private lateinit var box: LinearLayout
    private var tab = 0
    private var busy = false

    private var dups: List<Organizer.DupGroup>? = null
    private var suggestions: List<Organizer.Suggestion>? = null
    private var targets: List<Organizer.Target> = emptyList()
    private var renames: List<Organizer.Rename>? = null
    private var plan: Organizer.Plan? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = vbox()
        tabs = hbox().apply { setPadding(dp(10), dp(8), dp(10), dp(6)) }
        body.addView(tabs, lp(MATCH, WRAP))
        box = vbox().apply { setPadding(dp(12), dp(4), dp(12), dp(30)) }
        body.addView(ScrollView(this).apply { addView(box) }, lp(MATCH, 0, 1f))
        screen("סידור המאגר", body, listOf(headerButton("↶ ביטול") { undo() }))
        tab = intent.getIntExtra("tab", 0)
        render()
    }

    private fun render() {
        tabs.removeAllViews()
        listOf("ספרים כפולים", "סידור לתיקיות", "שמות חכמים").forEachIndexed { i, t ->
            tabs.addView(chip(t, i == tab) { if (!busy) { tab = i; plan = null; render() } }, lp(0, WRAP, 1f).m(dp(3), 0, dp(3), 0))
        }
        box.removeAllViews()
        when (tab) { 0 -> renderDups(); 1 -> renderSort(); else -> renderNames() }
    }

    private fun note(t: String) = box.addView(label(t, 14.5f, Col.SUB).apply { setPadding(dp(4), dp(8), dp(4), dp(10)); setLineSpacing(0f, 1.12f) })

    private fun card(): LinearLayout = vbox().apply {
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = roundBg(Col.CARD, dp(14).toFloat(), dp(1), Col.LINE)
        box.addView(this, lp(MATCH, WRAP).m(0, dp(5), 0, dp(5)))
    }

    private fun run(title: String, job: ((String) -> Unit) -> Unit, done: () -> Unit) {
        if (busy) return
        busy = true
        box.removeAllViews()
        val st = label(title, 16f, Col.ACC, true).apply { setPadding(dp(6), dp(30), dp(6), dp(30)) }
        box.addView(st)
        bg.execute {
            try { job { s -> ui { st.text = "$title\n$s" } } } catch (e: Throwable) { ui { toast("שגיאה: ${e.message}") } }
            ui { busy = false; done() }
        }
    }

    // ---------------- duplicates ----------------

    private fun renderDups() {
        val d = dups
        if (d == null) {
            note("התוכנה משווה את התוכן של הספרים – לא את שמות הקבצים. כך היא מוצאת את אותו ספר גם כשהוא שמור פעמיים בשמות שונים, בתיקיות שונות, או כסריקה אחרת של אותו ספר.\n\nשום דבר לא נמחק בלי אישור שלך.")
            if (Db.get().count("SELECT COUNT(*) FROM files WHERE status=0") > 0) note("⚠ חלק מהספרים עוד לא נסרקו – ספרים שלא נסרקו נבדקים רק כקובץ זהה.")
            box.addView(pill("חיפוש ספרים כפולים") {
                run("מחפש ספרים כפולים…", { p -> dups = Organizer.findDuplicates(p) }) { render() }
            }, lp(MATCH, WRAP))
            return
        }
        if (d.isEmpty()) { note("לא נמצאו ספרים כפולים 👍"); box.addView(pill("בדיקה שוב", false) { dups = null; render() }); return }
        note("נמצאו ${d.size} קבוצות של ספרים כפולים (${d.sumOf { it.books.size - 1 }} עותקים מיותרים). בכל קבוצה בחרו איזה עותק להשאיר.")
        box.addView(pill("השארת עותק אחד בכל הקבוצות (הקובץ בתיקייה המסודרת ביותר)", false) {
            askRemove(d.flatMap { g -> val keep = best(g.books); g.books.filter { it.id != keep.id } }) { dups = d.map { g -> Organizer.DupGroup(g.kind, g.books.filter { File(it.path).exists() }, g.similarity) }.filter { it.books.size > 1 }; render() }
        }, lp(MATCH, WRAP).m(0, 0, 0, dp(6)))
        d.forEach { g ->
            val c = card()
            c.addView(label(g.kind.title + if (g.kind == Organizer.DupKind.SAME_TEXT) "  ·  דמיון ${g.similarity}%" else "", 13.5f, Col.ACC, true))
            g.books.forEach { b ->
                val row = vbox().apply { setPadding(0, dp(8), 0, dp(6)) }
                row.addView(label("📖 ${b.title}", 15.5f, Col.TEXT, true))
                row.addView(label(Library.categoryOf(b.dir).joinToString(" › ").ifEmpty { b.dir } + "  ·  ${human(b.size)}  ·  ${b.pages} עמ׳", 12.5f, Col.SUB))
                val btns = hbox()
                btns.addView(chip("פתיחה", false) { ReaderActivity.open(this, b.path, 1) }, lp(WRAP, WRAP).m(0, dp(4), dp(6), 0))
                btns.addView(chip("להשאיר רק את זה", false) {
                    askRemove(g.books.filter { it.id != b.id }) { dups = d.map { x -> Organizer.DupGroup(x.kind, x.books.filter { File(it.path).exists() }, x.similarity) }.filter { it.books.size > 1 }; render() }
                }, lp(WRAP, WRAP).m(0, dp(4), 0, 0))
                row.addView(btns)
                c.addView(row)
            }
        }
    }

    /** The copy to keep: deepest folder (most sorted), then more pages with text, then bigger file. */
    private fun best(bs: List<Book>): Book = bs.maxWith(compareBy<Book>({ Library.categoryOf(it.dir).size }, { it.textPages }, { it.size }))

    private fun askRemove(remove: List<Book>, after: () -> Unit) {
        if (remove.isEmpty()) return
        Sheet.choice(this, "מה לעשות עם ${remove.size} העותקים המיותרים?", listOf(
            "move" to "להעביר לתיקייה \"${Organizer.DUP_DIR}\" (מומלץ – אפשר לבדוק ולמחוק אחר כך)",
            "delete" to "למחוק לצמיתות",
        ), null) { how ->
            Sheet.confirm(this, "אישור", (if (how == "delete") "למחוק לצמיתות" else "להעביר") + " ${remove.size} קבצים?\n\n" +
                remove.take(15).joinToString("\n") { "• " + it.title } + if (remove.size > 15) "\n…" else "", "כן") {
                run("מטפל בקבצים…", { p ->
                    Organizer.beginBatch()
                    remove.forEachIndexed { i, b ->
                        p("${i + 1}/${remove.size}")
                        if (how == "delete") { if (File(b.path).delete()) Db.get().removeFile(b.id) }
                        else Organizer.moveBook(b.path, Organizer.dupDir())?.let { Db.get().bookByPath(it.absolutePath)?.let { nb -> Db.get().removeFile(nb.id) } }
                    }
                }) { toast("בוצע"); after() }
            }
        }
    }

    // ---------------- smart sorting ----------------

    private fun renderSort() {
        plan?.let { renderPlan(it); return }
        val s = suggestions
        if (s == null) {
            box.addView(label("סידור תיקייה – עם תצוגה מקדימה", 17f, Col.TEXT, true).apply { setPadding(dp(4), dp(10), dp(4), dp(2)) })
            note("בוחרים תיקייה ושיטת מיון. התוכנה מראה איך התיקייה תיראה אחרי הסידור; אפשר לשנות שמות של תיקיות, להעביר ספר מקבוצה לקבוצה או לבטל קבוצה – ורק כשמאשרים, הקבצים זזים. לא אהבתם? בוחרים שיטה אחרת.")
            box.addView(pill("בחירת תיקייה לסידור") {
                FolderPicker.pick(this) { d -> chooseScheme(File(d)) }
            }, lp(MATCH, WRAP).m(0, dp(4), 0, dp(4)))
            box.addView(divider(), lp(MATCH, 1).m(0, dp(14), 0, dp(4)))
            box.addView(label("סידור ספרים לתיקיות המאגר", 17f, Col.TEXT, true).apply { setPadding(dp(4), dp(10), dp(4), dp(2)) })
            note("התוכנה לומדת מה יש בכל תיקייה במאגר (לפי התוכן של הספרים שכבר נמצאים בה ולפי שם התיקייה), ומציעה לכל ספר את התיקייה המתאימה לו – למשל ספר על הלכות שבת → הלכה › שבת.\n\nאתם רואים את כל ההצעות, משנים או מבטלים מה שרוצים, ורק אז הקבצים מועברים. אפשר לבטל את כל הסידור בכפתור \"ביטול\".")
            box.addView(pill("ספרים שלא נמצאים בשום תיקייה") { doSuggest(null, false) }, lp(MATCH, WRAP).m(0, dp(4), 0, dp(4)))
            box.addView(pill("ספרים מתיקייה אחרת (למשל הורדות)…", false) {
                FolderPicker.pick(this) { dir -> doSuggest(dir, false) }
            }, lp(MATCH, WRAP).m(0, dp(4), 0, dp(4)))
            box.addView(pill("בדיקת ספרים שנראים במקום הלא נכון", false) { doSuggest(null, true) }, lp(MATCH, WRAP).m(0, dp(4), 0, dp(4)))
            return
        }
        if (s.isEmpty()) { note("אין הצעות – הכול נראה מסודר 👍"); box.addView(pill("חזרה", false) { suggestions = null; render() }); return }
        val sel = s.count { it.selected && it.dest != null }
        note("${s.size} הצעות. לחיצה על תיקיית היעד משנה אותה. ☑ = יועבר.")
        box.addView(pill("העברת $sel הספרים המסומנים") { applySort(s) }, lp(MATCH, WRAP).m(0, 0, 0, dp(6)))
        s.forEach { g ->
            val c = card()
            val top = hbox()
            top.addView(label(if (g.selected) "☑" else "☐", 20f, Col.ACC, true).apply {
                setPadding(0, 0, dp(10), 0); setOnClickListener { g.selected = !g.selected; render() }
            })
            top.addView(label("📖 " + g.title, 15.5f, Col.TEXT, true), lp(0, WRAP, 1f))
            c.addView(top)
            c.addView(label("עכשיו: " + (g.currentCat.joinToString(" › ").ifEmpty { File(g.path).parent ?: "" }), 12.5f, Col.SUB))
            c.addView(label("← " + (g.dest?.cat?.joinToString(" › ") ?: "לא נבחר") + "   (${(g.score * 100).toInt()}%) ✎", 15f, Col.ACC, true).apply {
                setPadding(0, dp(4), 0, 0)
                setOnClickListener {
                    Sheet.choice(this@OrganizeActivity, "לאיזו תיקייה?", targets.map { it.dir to it.cat.joinToString(" › ") }, g.dest?.dir) { d ->
                        g.dest = targets.firstOrNull { it.dir == d }; g.selected = true; render()
                    }
                }
            })
            c.addView(chip("פתיחה", false) { ReaderActivity.open(this, g.path, 1) }, lp(WRAP, WRAP).m(0, dp(6), 0, 0))
        }
    }

    private fun chooseScheme(dir: File) {
        val n = dir.listFiles()?.count { it.isFile && it.name.endsWith(".pdf", true) } ?: 0
        if (n == 0) { Sheet.info(this, "אין ספרים בתיקייה", "בתיקייה ${dir.name} אין קובצי PDF ישירות (רק תיקיות משנה). בחרו תיקייה שיש בה ספרים."); return }
        Sheet.choice(this, "איך לסדר את \"${dir.name}\" ($n ספרים)?", Organizer.Scheme.values().map { it.name to it.title + "\n" + it.about }, null) { k ->
            makePlan(dir, Organizer.Scheme.valueOf(k))
        }
    }

    private fun makePlan(dir: File, scheme: Organizer.Scheme) {
        run("מכין הצעה: ${scheme.title}…", { p -> plan = Organizer.plan(this, dir, scheme, p) }) { render() }
    }

    private fun renderPlan(p: Organizer.Plan) {
        box.addView(label("תצוגה מקדימה: ${p.dir.name}", 17f, Col.TEXT, true).apply { setPadding(dp(4), dp(8), dp(4), 0) })
        note("שיטה: ${p.scheme.title}. לחיצה על ✎ משנה את שם התיקייה, לחיצה על ספר מעבירה אותו לקבוצה אחרת.")
        val other = FlowLayout(this)
        Organizer.Scheme.values().filter { it != p.scheme }.forEach { sc -> other.addView(chip("נסו: ${sc.title}", false) { makePlan(p.dir, sc) }) }
        box.addView(other, lp(MATCH, WRAP).m(0, 0, 0, dp(8)))
        val moving = p.groups.sumOf { it.books.size }
        val row = hbox()
        row.addView(pill("החלת השינויים ($moving ספרים)") {
            if (moving == 0) return@pill
            Sheet.confirm(this, "החלת הסידור", "ייווצרו ${p.groups.count { it.books.isNotEmpty() && it.dest == null }} תיקיות ויועברו $moving ספרים. אפשר לבטל אחר כך בכפתור \"ביטול\".", "החלה") {
                run("מסדר…", { pr -> val k = Organizer.applyPlan(p, pr); ui { toast("הועברו $k ספרים") } }) { plan = null; render() }
            }
        }, lp(0, WRAP, 1f).m(0, 0, dp(4), 0))
        row.addView(pill("ביטול", false) { plan = null; render() }, lp(WRAP, WRAP))
        box.addView(row, lp(MATCH, WRAP).m(0, 0, 0, dp(6)))

        fun moveDialog(f: File, from: MutableList<File>) {
            val opts = p.groups.mapIndexed { i, g -> i.toString() to "📁 " + g.name } + listOf("stay" to "להשאיר במקום", "new" to "➕ תיקייה חדשה…")
            Sheet.choice(this, f.nameWithoutExtension, opts, null) { k ->
                when (k) {
                    "stay" -> { from.remove(f); p.stay.add(f); render() }
                    "new" -> Sheet.input(this, "שם התיקייה החדשה", "", "שם") { n ->
                        if (n.isNotBlank()) { from.remove(f); p.groups.add(Organizer.PGroup(n.trim(), mutableListOf(f))); render() }
                    }
                    else -> { from.remove(f); p.groups[k.toInt()].books.add(f); render() }
                }
            }
        }

        p.groups.filter { it.books.isNotEmpty() }.forEach { g ->
            val c = card()
            val top = hbox()
            top.addView(label("📁 " + g.name + "  (${g.books.size})", 16f, Col.ACC, true), lp(0, WRAP, 1f))
            if (g.dest == null) top.addView(label("✎", 20f, Col.ACC, true).apply {
                setPadding(dp(10), 0, dp(10), 0)
                setOnClickListener { Sheet.input(this@OrganizeActivity, "שם התיקייה", g.name, "שם") { n -> if (n.isNotBlank()) { g.name = n.trim(); render() } } }
            })
            top.addView(label("✕", 18f, Col.SUB, true).apply {
                setPadding(dp(10), 0, dp(4), 0)
                setOnClickListener { p.stay.addAll(g.books); g.books.clear(); render() }
            })
            c.addView(top)
            g.books.toList().forEach { f ->
                c.addView(label("   📖 " + f.nameWithoutExtension, 14.5f, Col.TEXT).apply {
                    setPadding(0, dp(6), 0, dp(6))
                    setOnClickListener { moveDialog(f, g.books) }
                })
            }
        }
        if (p.stay.isNotEmpty()) {
            val c = card()
            c.addView(label("נשארים במקום (${p.stay.size})", 15f, Col.SUB, true))
            p.stay.toList().forEach { f ->
                c.addView(label("   📖 " + f.nameWithoutExtension, 14.5f, Col.SUB).apply {
                    setPadding(0, dp(6), 0, dp(6)); setOnClickListener { moveDialog(f, p.stay) }
                })
            }
        }
    }

    private fun doSuggest(dir: String?, checkAll: Boolean) {
        run("מנתח את המאגר…", { p ->
            val r = Organizer.suggest(this, dir, checkAll, p)
            targets = Organizer.buildTargetsCache ?: emptyList()
            suggestions = r
        }) { render() }
    }

    private fun applySort(s: List<Organizer.Suggestion>) {
        val todo = s.filter { it.selected && it.dest != null }
        if (todo.isEmpty()) return
        Sheet.confirm(this, "העברת ספרים", "להעביר ${todo.size} ספרים לתיקיות שנבחרו? (אפשר לבטל אחר כך)", "העברה") {
            run("מעביר…", { p ->
                Organizer.beginBatch()
                var ok = 0
                todo.forEachIndexed { i, g -> p("${i + 1}/${todo.size}"); if (Organizer.moveBook(g.path, File(g.dest!!.dir)) != null) ok++ }
                ui { toast("הועברו $ok ספרים") }
            }) { suggestions = null; render() }
        }
    }

    // ---------------- smart names ----------------

    private fun renderNames() {
        val r = renames
        if (r == null) {
            note("לקבצים עם שמות לא ברורים (scan0012, 45821, HebrewBooks_org…) התוכנה קוראת את דף השער ומציעה את שם הספר. לתיקיות עם שם לא ברור (New folder…) היא מציעה שם לפי הספרים שבתוכן.\n\nעל כל שם תישאלו: לשנות, להשאיר, או לערוך.")
            box.addView(pill("שמות לא ברורים בלבד (מומלץ)") { doNames(false) }, lp(MATCH, WRAP).m(0, dp(4), 0, dp(4)))
            box.addView(pill("כל הקבצים והתיקיות (איטי)", false) { doNames(true) }, lp(MATCH, WRAP).m(0, dp(4), 0, dp(4)))
            return
        }
        if (r.isEmpty()) { note("לא נמצאו שמות לשיפור 👍"); box.addView(pill("חזרה", false) { renames = null; render() }); return }
        note("${r.size} הצעות לשמות.")
        r.forEach { x ->
            val c = card()
            c.addView(label((if (x.isDir) "📁 תיקייה: " else "📖 קובץ: ") + x.file.name, 13.5f, Col.SUB))
            c.addView(label("← " + x.newName, 16.5f, Col.TEXT, true).apply { setPadding(0, dp(4), 0, dp(6)) })
            val b = hbox()
            b.addView(chip("לשנות", false) {
                bg.execute { val ok = Organizer.rename(x); ui { toast(if (ok) "השם שונה" else "לא ניתן לשנות את השם"); renames = r - x; render() } }
            }, lp(WRAP, WRAP).m(0, 0, dp(6), 0))
            b.addView(chip("להשאיר", false) { renames = r - x; render() }, lp(WRAP, WRAP).m(0, 0, dp(6), 0))
            b.addView(chip("עריכה ✎", false) {
                Sheet.input(this, "שם חדש", x.newName, "שם") { n -> if (n.isNotBlank()) { x.newName = n.trim(); render() } }
            }, lp(WRAP, WRAP).m(0, 0, dp(6), 0))
            if (!x.isDir) b.addView(chip("פתיחה", false) { ReaderActivity.open(this, x.file.absolutePath, 1) })
            c.addView(b)
        }
    }

    private fun doNames(all: Boolean) {
        run("מחפש שמות לשיפור…", { p -> Organizer.beginBatch(); renames = Organizer.suggestNames(Library.roots(), all, p) }) { render() }
    }

    private fun undo() {
        val n = Organizer.undoCount()
        if (n == 0) { Sheet.info(this, "אין מה לבטל", "לא בוצעו העברות או שינויי שמות מאז הפעולה האחרונה."); return }
        Sheet.confirm(this, "ביטול הפעולה האחרונה", "להחזיר $n קבצים/תיקיות למקום ולשם הקודמים?", "ביטול הפעולה") {
            run("מחזיר…", { _ -> val k = Organizer.undo(); ui { toast("הוחזרו $k") } }) { dups = null; suggestions = null; renames = null; plan = null; render() }
        }
    }

    override fun onDestroy() { super.onDestroy(); bg.shutdown() }
}
