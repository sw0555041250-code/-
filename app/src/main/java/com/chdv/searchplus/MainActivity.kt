package com.chdv.searchplus

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.concurrent.Executors

class MainActivity : BaseActivity() {

    private val exec = Executors.newSingleThreadExecutor()
    private val snipExec = Executors.newSingleThreadExecutor()
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var modeRow: LinearLayout
    private lateinit var optRow: LinearLayout
    private lateinit var crumbs: FlowLayout
    private lateinit var cats: FlowLayout
    private lateinit var summary: TextView
    private lateinit var list: RecyclerView
    private lateinit var home: ScrollView
    private lateinit var homeBox: LinearLayout
    private lateinit var setupCard: LinearLayout
    private val adapter = ResultAdapter()

    private var semantic = false
    private var results: Results? = null
    private var prefix: List<String> = emptyList()
    private var expr = ""
    private val expanded = HashSet<Long>()
    private var searchToken = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        semantic = Prefs.b("lastSemantic", false)
        build()
    }

    override fun onResume() {
        super.onResume()
        refreshSetup()
        if (Library.hasAccess(this) && Library.roots().isNotEmpty() && !Progress.running &&
            System.currentTimeMillis() - Progress.lastFinished > 10 * 60 * 1000 && Prefs.b("autoScan", true)) {
            Indexer.start(this)
        }
        Updater.dailyCheck(this)
        tick.run()
        if (results == null) showHome()
    }

    override fun onPause() {
        super.onPause()
        App.main.removeCallbacks(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            val t = Progress.text()
            if (t.isEmpty()) {
                status.visibility = View.GONE
            } else {
                status.visibility = View.VISIBLE
                status.text = "⟳ $t"
            }
            App.main.postDelayed(this, 2000)
        }
    }

    // ---------------- layout ----------------

    private fun build() {
        val root = vbox().apply { setBackgroundColor(Col.BG); layoutDirection = View.LAYOUT_DIRECTION_RTL }

        // header
        val head = hbox().apply { setPadding(dp(14), dp(10), dp(8), dp(6)) }
        head.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) }, lp(dp(38), dp(38)).m(0, 0, 0, 0))
        head.addView(vbox().apply {
            addView(label("חיפוש פלוס", 22f, Col.TEXT, true))
            addView(label("חיפוש מעמיק בתוך הספרים שלך", 12.5f, Col.SUB))
            setPadding(dp(10), 0, dp(10), 0)
        }, lp(0, WRAP, 1f))
        head.addView(headerButton("📚 ספרים") { startActivity(Intent(this, BrowseActivity::class.java)) })
        head.addView(headerButton("🗂 סידור") { startActivity(Intent(this, OrganizeActivity::class.java)) })
        head.addView(headerButton("⚙") { startActivity(Intent(this, SettingsActivity::class.java)) })
        root.addView(head, lp(MATCH, WRAP))

        status = label("", 13f, Col.ACC).apply {
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = roundBg(Col.ACC_LIGHT, dp(10).toFloat())
            visibility = View.GONE
            setOnClickListener { progressSheet() }
        }
        root.addView(status, lp(MATCH, WRAP).m(dp(12), dp(2), dp(12), dp(4)))

        setupCard = vbox()
        root.addView(setupCard, lp(MATCH, WRAP))

        // search box
        val box = hbox().apply {
            background = roundBg(Col.CARD, dp(26).toFloat(), dp(1.5f), Col.ACC)
            setPadding(dp(14), dp(2), dp(4), dp(2))
        }
        input = EditText(this).apply {
            hint = "מה לחפש? לדוגמה: רבי שמעון"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f * scale())
            setTextColor(Col.TEXT)
            setHintTextColor(0xFF8A8A8A.toInt())
            background = null
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG_RTL
            setOnEditorActionListener { _, a, e ->
                if (a == EditorInfo.IME_ACTION_SEARCH || e?.keyCode == KeyEvent.KEYCODE_ENTER) { doSearch(); true } else false
            }
        }
        box.addView(input, lp(0, WRAP, 1f))
        box.addView(label("✕", 18f, Col.SUB).apply {
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener { input.setText(""); results = null; showHome() }
        })
        box.addView(pill("חיפוש") { doSearch() }, lp(WRAP, WRAP))
        root.addView(box, lp(MATCH, WRAP).m(dp(12), dp(4), dp(12), dp(6)))

        modeRow = hbox().apply { setPadding(dp(12), 0, dp(12), 0) }
        root.addView(modeRow, lp(MATCH, WRAP))
        optRow = hbox().apply { setPadding(dp(12), dp(6), dp(12), 0) }
        root.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(optRow)
        }, lp(MATCH, WRAP))
        buildModes()

        crumbs = FlowLayout(this).apply { setPadding(dp(12), dp(8), dp(12), 0); gap = dp(2) }
        root.addView(crumbs, lp(MATCH, WRAP))
        cats = FlowLayout(this).apply { setPadding(dp(12), dp(6), dp(12), dp(2)) }
        root.addView(cats, lp(MATCH, WRAP))
        summary = label("", 13.5f, Col.SUB).apply { setPadding(dp(14), dp(6), dp(14), dp(4)) }
        root.addView(summary, lp(MATCH, WRAP))
        root.addView(divider())

        val stack = android.widget.FrameLayout(this)
        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
            itemAnimator = null
        }
        stack.addView(list, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
        homeBox = vbox().apply { setPadding(dp(14), dp(8), dp(14), dp(24)) }
        home = ScrollView(this).apply { addView(homeBox) }
        stack.addView(home, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(stack, lp(MATCH, 0, 1f))
        setContentView(root)
    }

    private fun buildModes() {
        modeRow.removeAllViews()
        modeRow.addView(chip("🔤 חיפוש מילים", !semantic) { setMode(false) }, lp(0, WRAP, 1f).m(dp(3), 0, dp(3), 0))
        modeRow.addView(chip("💡 חיפוש לפי משמעות", semantic) { setMode(true) }, lp(0, WRAP, 1f).m(dp(3), 0, dp(3), 0))

        optRow.removeAllViews()
        if (!semantic) {
            val m = Match.valueOf(Prefs.s("match", "PHRASE"))
            optRow.addView(chip("התאמה: ${m.title} ▾", false) {
                Sheet.choice(this, "איך המילים צריכות להופיע?", Match.values().map { it.name to it.title }, m.name) {
                    Prefs.put("match", it); buildModes(); if (results != null) doSearch()
                }
            }, lp(WRAP, WRAP).m(dp(3), 0, dp(3), 0))
            val pre = Prefs.b("prefixes", true)
            optRow.addView(chip((if (pre) "☑" else "☐") + " כולל ו׳ ה׳ ב׳ ל׳ ש׳…", pre) {
                Prefs.put("prefixes", !pre); buildModes(); if (results != null) doSearch()
            }, lp(WRAP, WRAP).m(dp(3), 0, dp(3), 0))
        }
        val sort = Prefs.s("sort", "relevance")
        optRow.addView(chip("מיון: ${sortTitle(sort)} ▾", false) {
            Sheet.choice(this, "מיון התוצאות", SORTS, sort) { Prefs.put("sort", it); buildModes(); render() }
        }, lp(WRAP, WRAP).m(dp(3), 0, dp(3), 0))
    }

    private val SORTS = listOf(
        "relevance" to "רלוונטיות (הכי הרבה תוצאות)",
        "name" to "שם הספר (א-ת)",
        "folder" to "תיקייה",
        "size" to "גודל הקובץ",
        "date" to "תאריך הקובץ (חדש קודם)",
        "pages" to "מספר העמודים בספר",
    )

    private fun sortTitle(k: String) = when (k) {
        "name" -> "שם"; "folder" -> "תיקייה"; "size" -> "גודל"; "date" -> "תאריך"; "pages" -> "עמודים"; else -> "רלוונטיות"
    }

    private fun setMode(sem: Boolean) {
        if (sem && !Semantic.ready(this)) {
            Sheet.confirm(this, "חיפוש לפי משמעות",
                "בחיפוש הזה כותבים רעיון או שאלה במילים שלך, והתוכנה מוצאת קטעים שעוסקים בזה – גם אם המילים עצמן לא מופיעים שם.\n\n" +
                    "לשם כך צריך להוריד פעם אחת מודל בינה מלאכותית (כ-120MB), שעובד בתוך המכשיר בלי אינטרנט. " +
                    "אחר כך התוכנה בונה אינדקס משמעות לספרים (זה לוקח זמן, ברקע).",
                "להגדרות והורדה") { startActivity(Intent(this, SettingsActivity::class.java).putExtra("focus", "semantic")) }
            return
        }
        semantic = sem
        Prefs.put("lastSemantic", sem)
        buildModes()
        if (input.text.isNotBlank()) doSearch()
    }

    private fun refreshSetup() {
        setupCard.removeAllViews()
        val card = vbox().apply {
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundBg(0xFFFFF7DD.toInt(), dp(14).toFloat(), dp(1), 0xFFE0C060.toInt())
        }
        when {
            !Library.hasAccess(this) -> {
                card.addView(label("שלב 1: הרשאה לקרוא את הקבצים", 17f, Col.TEXT, true))
                card.addView(label("כדי לסרוק את הספרים (גם בכרטיס הזיכרון) צריך לאשר לחיפוש פלוס גישה לכל הקבצים. במסך שייפתח – הפעילו את המתג וחזרו.", 14.5f, Col.SUB).apply { setPadding(0, dp(4), 0, dp(10)) })
                card.addView(pill("מתן הרשאה") { Library.askAccess(this) }, lp(MATCH, WRAP))
            }
            Library.roots().isEmpty() -> {
                card.addView(label("שלב 2: איפה הספרים שלך?", 17f, Col.TEXT, true))
                card.addView(label("בחרו את התיקייה הראשית של מאגר הספרים (למשל בכרטיס הזיכרון). התיקיות שבתוכה – הלכה, חסידות, נגלה… – יהפכו לקטגוריות בחיפוש.", 14.5f, Col.SUB).apply { setPadding(0, dp(4), 0, dp(10)) })
                card.addView(pill("בחירת תיקיית הספרים") {
                    FolderPicker.pick(this) { dir ->
                        Library.setRoots(listOf(dir))
                        refreshSetup()
                        Indexer.start(this)
                        Sheet.info(this, "מתחילים לסרוק", "הסריקה רצה ברקע ואפשר כבר לחפש בספרים שנסרקו. בפעם הראשונה זה לוקח זמן – תלוי בכמות הספרים. אפשר לסגור את התוכנה; הסריקה ממשיכה.")
                    }
                }, lp(MATCH, WRAP))
            }
            else -> return
        }
        setupCard.addView(card, lp(MATCH, WRAP).m(dp(12), dp(4), dp(12), dp(6)))
    }

    private fun progressSheet() {
        Sheet.confirm(this, "סריקת הספרים", Progress.text() + "\n\nהסריקה רצה ברקע. אפשר להמשיך לחפש בינתיים בספרים שכבר נסרקו.", "עצירת הסריקה") {
            Progress.cancel = true
        }
    }

    // ---------------- home ----------------

    private fun showHome() {
        list.visibility = View.GONE
        home.visibility = View.VISIBLE
        crumbs.removeAllViews(); cats.removeAllViews()
        summary.text = ""
        homeBox.removeAllViews()
        // welcome card
        val hero = vbox().apply {
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = roundBg(Col.ACC, dp(18).toFloat())
        }
        hero.addView(label("שלום! מה נלמד היום?", 19f, android.graphics.Color.WHITE, true))
        val heroStats = label("טוען את נתוני המאגר…", 14f, 0xFFDDE6F2.toInt()).apply { setPadding(0, dp(4), 0, dp(12)) }
        hero.addView(heroStats)
        val quick = hbox()
        fun q(t: String, onClick: () -> Unit) = label(t, 14.5f, Col.ACC, true).apply {
            gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER
            setPadding(dp(6), dp(10), dp(6), dp(10))
            background = pressBg(android.graphics.Color.WHITE, Col.ACC_LIGHT, 14)
            setOnClickListener { onClick() }
        }
        quick.addView(q("📚 עיון") { startActivity(Intent(this, BrowseActivity::class.java)) }, lp(0, WRAP, 1f).m(dp(3), 0, dp(3), 0))
        quick.addView(q("💡 שאלה") { setMode(true); input.requestFocus() }, lp(0, WRAP, 1f).m(dp(3), 0, dp(3), 0))
        quick.addView(q("🗂 סידור") { startActivity(Intent(this, OrganizeActivity::class.java)) }, lp(0, WRAP, 1f).m(dp(3), 0, dp(3), 0))
        hero.addView(quick, lp(MATCH, WRAP))
        homeBox.addView(hero, lp(MATCH, WRAP).m(0, dp(6), 0, dp(6)))
        exec.execute {
            val st = try { Db.get().stats() } catch (e: Exception) { null }
            ui {
                heroStats.text = when {
                    st == null || st.books == 0 -> "עוד אין ספרים במאגר – בחרו את תיקיית הספרים כדי להתחיל."
                    else -> "במאגר ${st.books} ספרים · ${st.textPages} עמודים שאפשר לחפש בהם" +
                        (if (st.pending > 0) "\nעוד ${st.pending} ספרים בתור לסריקה" else "")
                }
            }
        }
        val hist = Library.history()
        if (hist.isNotEmpty()) {
            homeBox.addView(section("חיפושים אחרונים"))
            val f = FlowLayout(this)
            hist.take(16).forEach { q -> f.addView(chip(q, false) { input.setText(q); doSearch() }) }
            homeBox.addView(f, lp(MATCH, WRAP).m(0, 0, 0, dp(10)))
        }
        val favs = Library.favorites()
        if (favs.isNotEmpty()) {
            homeBox.addView(section("★ מועדפים"))
            favs.take(30).forEach { k ->
                val p = k.substringBefore('|'); val pg = k.substringAfter('|').toIntOrNull() ?: 1
                homeBox.addView(bookLine(File(p).nameWithoutExtension, "עמוד $pg", p, pg) {
                    Sheet.confirm(this, "הסרה מהמועדפים", File(p).nameWithoutExtension + ", עמוד $pg", "הסרה") { Library.toggleFavorite(k); showHome() }
                })
            }
        }
        val rec = Library.recent()
        if (rec.isNotEmpty()) {
            homeBox.addView(section("נפתחו לאחרונה"))
            rec.take(12).forEach { k ->
                val p = k.substringBefore('|'); val pg = k.substringAfter('|').toIntOrNull() ?: 1
                homeBox.addView(bookLine(File(p).nameWithoutExtension, "המשך מעמוד $pg", p, pg, null))
            }
        }
        homeBox.addView(section("איך מחפשים?"))
        homeBox.addView(label(
            "• חיפוש מילים: כותבים מילה או ביטוי – \"רבי שמעון\", \"שיחת ויצא\". אפשר לבחור ביטוי מדויק, כל המילים, או מילים קרובות.\n" +
                "• כוכבית בסוף מילה מוצאת את כל ההמשכים שלה: ויצ* ימצא ויצא, ויצאו…\n" +
                "• \"כולל ו׳ ה׳ ב׳…\" – מוצא גם \"ורבי שמעון\", \"לרבי שמעון\".\n" +
                "• אחרי החיפוש לוחצים על קטגוריה (תיקייה) כדי למקד – ושוב על תת-תיקייה, וכן הלאה.\n" +
                "• חיפוש לפי משמעות: כותבים רעיון במילים שלך ומקבלים קטעים שעוסקים בו.\n" +
                "• לחיצה על תוצאה פותחת את הספר בדיוק בעמוד, והמילים מסומנות.", 14.5f, Col.SUB).apply { setLineSpacing(0f, 1.15f) })
        homeBox.addView(label("\nפותח על ידי שניאור וילהלם · מופץ לתועלת הרבים", 12.5f, Col.SUB).apply { gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER })
    }

    private fun section(t: String) = label(t, 16.5f, Col.ACC, true).apply { setPadding(0, dp(12), 0, dp(6)) }

    private fun bookLine(title: String, sub: String, path: String, page: Int, onLong: (() -> Unit)?): View = vbox().apply {
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = pressBg(Col.CARD, Col.ACC_LIGHT, 12)
        addView(label("📖 $title", 15.5f, Col.TEXT, true).apply { setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END })
        addView(label(sub, 13f, Col.SUB))
        setOnClickListener { ReaderActivity.open(this@MainActivity, path, page) }
        if (onLong != null) setOnLongClickListener { onLong(); true }
        layoutParams = lp(MATCH, WRAP).m(0, dp(3), 0, dp(3))
    }

    // ---------------- search ----------------

    private fun doSearch() {
        val q = input.text.toString().trim()
        if (q.isEmpty()) return
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
        Library.addHistory(q)
        prefix = emptyList()
        expanded.clear()
        home.visibility = View.GONE
        list.visibility = View.VISIBLE
        summary.text = if (semantic) "מחפש לפי משמעות… (בפעם הראשונה זה לוקח כמה שניות)" else "מחפש…"
        crumbs.removeAllViews(); cats.removeAllViews()
        adapter.set(emptyList())
        val token = ++searchToken
        val sem = semantic
        val m = Match.valueOf(Prefs.s("match", "PHRASE"))
        val pre = Prefs.b("prefixes", true)
        exec.execute {
            val r = if (sem) Search.runSemantic(this, q) else Search.run(q, m, pre)
            val e = if (sem) "" else Search.buildMatch(r.terms, m, pre)
            ui {
                if (token != searchToken) return@ui
                results = r; expr = e
                render()
            }
        }
    }

    private fun visibleBooks(): List<BookHit> {
        val r = results ?: return emptyList()
        return r.books.filter { h -> h.category.size >= prefix.size && h.category.subList(0, prefix.size) == prefix }
    }

    private fun render() {
        val r = results ?: return
        crumbs.removeAllViews(); cats.removeAllViews()
        if (r.error != null) { summary.text = r.error; adapter.set(emptyList()); return }
        val vis = visibleBooks()

        // breadcrumbs: הכל › חסידות חב"ד › ...
        crumbs.addView(crumb("הכל", prefix.isEmpty()) { prefix = emptyList(); render() })
        prefix.forEachIndexed { i, s ->
            crumbs.addView(label("›", 15f, Col.SUB).apply { setPadding(dp(2), dp(6), dp(2), 0) })
            crumbs.addView(crumb(s, i == prefix.size - 1) { prefix = prefix.subList(0, i + 1); render() })
        }

        // categories at the next level
        val groups = LinkedHashMap<String, IntArray>() // name -> [books, pages]
        for (h in vis) {
            if (h.category.size > prefix.size) {
                val g = groups.getOrPut(h.category[prefix.size]) { IntArray(2) }
                g[0]++; g[1] += h.pages.size
            }
        }
        groups.entries.sortedByDescending { it.value[1] + it.value[0] }.forEach { (name, c) ->
            cats.addView(chip("📁 $name  (${if (c[1] > 0) c[1] else c[0]})", false) { prefix = prefix + name; expanded.clear(); render() })
        }

        if (r.semantic && r.books.isNotEmpty()) findAnswer(r)
        val pages = vis.sumOf { it.pages.size }
        summary.text = when {
            vis.isEmpty() && r.semantic -> "לא נמצאו קטעים. ייתכן שאינדקס המשמעות עדיין נבנה – אפשר לראות את ההתקדמות בהגדרות."
            vis.isEmpty() -> "לא נמצא. נסו \"כל המילים בעמוד\", הוסיפו כוכבית (*) או בדקו את האיות." +
                (if (Progress.running) "\n(הסריקה עדיין רצה – חלק מהספרים עוד לא נסרקו)" else "")
            r.semantic -> "נמצאו ${vis.size} ספרים עם קטעים קרובים במשמעות – הקרובים ביותר ראשונים"
            else -> "נמצאו $pages עמודים ב-${vis.size} ספרים" + (if (r.truncated) " (מוצגות התוצאות הראשונות)" else "")
        }

        val sorted = when (Prefs.s("sort", "relevance")) {
            "name" -> vis.sortedBy { it.book.title }
            "folder" -> vis.sortedWith(compareBy({ it.book.dir }, { it.book.title }))
            "size" -> vis.sortedByDescending { it.book.size }
            "date" -> vis.sortedByDescending { it.book.mtime }
            "pages" -> vis.sortedByDescending { it.book.pages }
            else -> vis.sortedByDescending { it.score }
        }
        if (sorted.size == 1) expanded.add(sorted[0].book.id)
        val rows = ArrayList<Row>()
        if (r.semantic && vis.isNotEmpty() && prefix.isEmpty()) rows.add(Row.Answer(answer))
        for (h in sorted) {
            rows.add(Row.BookRow(h))
            if (h.book.id in expanded) {
                val pp = if (r.semantic) h.pages else h.pages
                val show = pp.take(if (h.book.id in showAll) 5000 else 60)
                show.forEach { rows.add(Row.PageRow(h, it)) }
                if (pp.size > show.size) rows.add(Row.More(h, pp.size - show.size))
            }
        }
        adapter.set(rows)
    }

    private val showAll = HashSet<Long>()
    private var answer: Semantic.Answer? = null
    private var answerFor: Results? = null

    /** For a question: read the closest passages sentence by sentence and pick the sentence that answers it. */
    private fun findAnswer(r: Results) {
        if (answerFor === r) return
        answerFor = r; answer = null
        val top = r.books.flatMap { b -> b.pages.map { b to it } }.sortedByDescending { it.second.score }.take(4)
        exec.execute {
            val a = try { Semantic.answer(this, r.query, top.map { it.second.pageId }) } catch (e: Throwable) { null }
            ui { if (results === r) { answer = a ?: Semantic.Answer("", -1, 0f); render() } }
        }
    }

    private fun crumb(t: String, cur: Boolean, onClick: () -> Unit) = label(t, 15f, if (cur) Col.TEXT else Col.ACC, true).apply {
        setPadding(dp(6), dp(4), dp(6), dp(4))
        if (!cur) { paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG; setOnClickListener { onClick() } }
    }

    private fun openHit(h: BookHit, page: Int) {
        val r = results ?: return
        val hitPages = h.pages.map { it.page }.distinct().sorted().toIntArray()
        ReaderActivity.open(this, h.book.path, page, r.terms, hitPages)
    }

    private fun bookMenu(h: BookHit) {
        val b = h.book
        Sheet.actions(this, b.title, listOf(
            "פתיחה בעמוד הראשון שנמצא" to { openHit(h, h.pages.firstOrNull()?.page ?: 1) },
            "פתיחה באפליקציה אחרת" to { Library.openExternal(this, b.path, h.pages.firstOrNull()?.page ?: 1) },
            "חיפוש רק בתיקייה של הספר הזה" to { prefix = h.category; render() },
            "פרטי הספר" to {
                Sheet.info(this, b.title, "תיקייה: ${b.dir}\nגודל: ${human(b.size)}\nעמודים: ${b.pages}\nעמודים עם טקסט: ${b.textPages}" +
                    (if (b.emptyPages > 0) "\nעמודים סרוקים בלי טקסט: ${b.emptyPages}" else ""))
            },
        ))
    }

    // ---------------- list ----------------

    sealed class Row {
        class BookRow(val hit: BookHit) : Row()
        class PageRow(val hit: BookHit, val page: PageHit) : Row()
        class More(val hit: BookHit, val rest: Int) : Row()
        class Answer(val a: Semantic.Answer?) : Row()
    }

    private val snippetCache = HashMap<Long, CharSequence>()

    private fun marked(s: String): CharSequence {
        val sb = SpannableStringBuilder()
        var i = 0
        while (i < s.length) {
            val a = s.indexOf('\u0001', i)
            if (a < 0) { sb.append(s.substring(i)); break }
            sb.append(s.substring(i, a))
            val b = s.indexOf('\u0002', a).let { if (it < 0) s.length else it }
            val st = sb.length
            sb.append(s.substring(a + 1, b))
            sb.setSpan(StyleSpan(Typeface.BOLD), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(BackgroundColorSpan(0xFFFFE9A8.toInt()), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(UnderlineSpan(), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            i = b + 1
        }
        return sb
    }

    inner class ResultAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var rows: List<Row> = emptyList()
        fun set(r: List<Row>) { rows = r; notifyDataSetChanged() }
        override fun getItemCount() = rows.size
        override fun getItemViewType(position: Int) = when (rows[position]) { is Row.BookRow -> 0; is Row.PageRow -> 1; is Row.More -> 2; is Row.Answer -> 3 }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val c = this@MainActivity
            val v: View = when (viewType) {
                0 -> c.vbox().apply {
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    background = pressBg(Col.BG, Col.ACC_LIGHT, 0)
                    val top = c.hbox()
                    top.addView(c.label("", 16.5f, Col.TEXT, true).apply { id = 1; maxLines = 2 }, lp(0, WRAP, 1f))
                    top.addView(c.label("", 13.5f, Color.WHITE, true).apply {
                        id = 2; setPadding(dp(9), dp(3), dp(9), dp(3)); background = roundBg(Col.ACC, dp(12).toFloat())
                    })
                    addView(top, lp(MATCH, WRAP))
                    addView(c.label("", 12.5f, Col.SUB).apply { id = 3; setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.START }, lp(MATCH, WRAP))
                }
                1 -> c.vbox().apply {
                    setPadding(dp(26), dp(8), dp(14), dp(10))
                    background = pressBg(Col.CARD, Col.ACC_LIGHT, 0)
                    addView(c.label("", 13.5f, Col.ACC, true).apply { id = 1 })
                    addView(c.label("", 15f, Col.TEXT).apply { id = 2; setLineSpacing(0f, 1.12f) })
                }
                3 -> c.vbox().apply {
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    background = roundBg(0xFFFFF7DD.toInt(), dp(14).toFloat(), dp(1.5f), 0xFFD8B040.toInt())
                    addView(c.label("💡 התשובה מתוך הספרים שלך", 14f, Col.ACC, true).apply { id = 1 })
                    addView(c.label("", 16.5f, Col.TEXT).apply { id = 2; setPadding(0, dp(6), 0, dp(6)); setLineSpacing(0f, 1.15f) })
                    addView(c.label("", 13.5f, Col.ACC, true).apply { id = 3 })
                }
                else -> c.label("", 14.5f, Col.ACC, true).apply {
                    gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER
                    setPadding(dp(10), dp(12), dp(10), dp(12)); background = pressBg(Col.CARD, Col.ACC_LIGHT, 0)
                }
            }
            v.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP).apply { bottomMargin = 1 }
            return object : RecyclerView.ViewHolder(v) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val v = holder.itemView
            when (val row = rows[position]) {
                is Row.BookRow -> {
                    val h = row.hit
                    val open = h.book.id in expanded
                    v.findViewById<TextView>(1).text = (if (open) "▾ " else "◂ ") + h.book.title
                    v.findViewById<TextView>(2).apply {
                        text = when {
                            results?.semantic == true -> "${(h.score * 100).toInt()}%"
                            h.pages.isEmpty() && h.nameMatch -> "שם"
                            else -> "${h.pages.size}"
                        }
                    }
                    v.findViewById<TextView>(3).text = (h.category.joinToString(" › ").ifEmpty { "תיקייה ראשית" }) +
                        "  ·  ${h.book.pages} עמ׳  ·  ${human(h.book.size)}"
                    v.setOnClickListener {
                        if (h.pages.isEmpty()) { ReaderActivity.open(this@MainActivity, h.book.path, 1); return@setOnClickListener }
                        if (open) expanded.remove(h.book.id) else expanded.add(h.book.id)
                        render()
                    }
                    v.setOnLongClickListener { bookMenu(h); true }
                }
                is Row.PageRow -> {
                    val ph = row.page
                    v.findViewById<TextView>(1).text = "עמוד ${ph.page}" + if (results?.semantic == true) "   (התאמה ${(ph.score * 100).toInt()}%)" else ""
                    val tv = v.findViewById<TextView>(2)
                    tv.tag = ph.pageId
                    val cached = snippetCache[ph.pageId]
                    if (cached != null) tv.text = cached else {
                        tv.text = "…"
                        val sem = results?.semantic == true
                        val terms = results?.terms ?: emptyList()
                        val e = expr
                        snipExec.execute {
                            val s = (if (sem) Search.semanticSnippet(ph.pageId, terms) else Search.snippet(e, ph.pageId))?.replace('\n', ' ') ?: ""
                            val cs = marked(s)
                            ui {
                                snippetCache[ph.pageId] = cs
                                if (tv.tag == ph.pageId) tv.text = cs
                            }
                        }
                    }
                    v.setOnClickListener { openHit(row.hit, ph.page) }
                    v.setOnLongClickListener {
                        val key = "${row.hit.book.path}|${ph.page}"
                        val on = Library.toggleFavorite(key)
                        toast(if (on) "העמוד נוסף למועדפים ★" else "הוסר מהמועדפים")
                        true
                    }
                }
                is Row.Answer -> {
                    val a = row.a
                    val wrap = holder.itemView.layoutParams as RecyclerView.LayoutParams
                    wrap.setMargins(dp(10), dp(8), dp(10), dp(8))
                    val src = a?.let { x -> Db.get().pageRef(x.pageId) }?.let { ref -> Db.get().book(ref.first)?.let { it to ref.second } }
                    v.findViewById<TextView>(2).text = when {
                        a == null -> "קורא את הקטעים הקרובים ומחפש את המשפט שעונה על השאלה…"
                        a.pageId < 0 || src == null -> "לא נמצא משפט שעונה במדויק. הקטעים הקרובים ביותר מופיעים למטה."
                        else -> "‟" + a.text + "”"
                    }
                    v.findViewById<TextView>(3).text = if (src != null && a != null && a.pageId >= 0) "📖 ${src.first.title}, עמוד ${src.second}  ·  לפתיחה ◂" else ""
                    v.setOnClickListener {
                        if (src != null) ReaderActivity.open(this@MainActivity, src.first.path, src.second, Heb.words(a!!.text).filter { it.length > 2 }.take(6))
                    }
                }
                is Row.More -> {
                    (v as TextView).text = "הצגת עוד ${row.rest} עמודים"
                    v.setOnClickListener { showAll.add(row.hit.book.id); render() }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        exec.shutdown(); snipExec.shutdown()
    }
}
