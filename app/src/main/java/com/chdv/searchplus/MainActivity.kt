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
import com.chdv.searchplus.core.RefParser
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
    private lateinit var jumpBar: TextView
    private lateinit var gate: LinearLayout
    private lateinit var gateBar: android.widget.ProgressBar
    private lateinit var gateText: TextView
    private lateinit var gateSkip: TextView
    private var gateSkipped = false
    private var gateShownAt = 0L

    private fun updateGate() {
        val show = !gateSkipped && Library.hasAccess(this) && Library.roots().isNotEmpty() && !Prefs.b("firstScanDone", false)
        if (!show) { gate.visibility = View.GONE; return }
        if (gate.visibility != View.VISIBLE) { gate.visibility = View.VISIBLE; gateShownAt = System.currentTimeMillis() }
        val reading = Progress.phase == "קורא ספרים" && Progress.total > 0
        gateBar.isIndeterminate = !reading
        if (reading) gateBar.progress = (Progress.done * 1000L / Progress.total).toInt()
        gateText.text = if (Progress.running) Progress.text() else "מתחיל…"
        // a way out after a while (e.g. a library so big that it takes all night)
        if (System.currentTimeMillis() - gateShownAt > 60_000) gateSkip.visibility = View.VISIBLE
    }
    private lateinit var optScroll: View

    /** The rows that only matter for results; hidden at home so a small phone screen has room to scroll. */
    private fun searchChrome(on: Boolean) {
        val v = if (on) View.VISIBLE else View.GONE
        optScroll.visibility = v; crumbs.visibility = v; cats.visibility = v; summary.visibility = v
        if (!on) jumpBar.visibility = View.GONE
    }
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
        applyScreen(intent)
    }

    /** Which screen this is: home, "meaning" (question & answer only) or "words" (word search only). */
    private var screenKind = "home"
    private lateinit var titleView: TextView
    private lateinit var subView: TextView

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyScreen(intent)
    }

    private fun applyScreen(i: Intent?) {
        val k = i?.getStringExtra("screen") ?: return
        screenKind = k
        when (k) {
            "meaning" -> {
                if (!Semantic.ready(this)) { semantic = false; setMode(true) } else semantic = true
                titleView.text = "💡 שאלה ותשובה"; subView.text = "שואלים במילים שלך – ומקבלים את המשפט שעונה"
                input.hint = "שאלו שאלה… למשל: מה מברכים על פרי העץ?"
            }
            "words" -> {
                semantic = false
                titleView.text = "🔤 חיפוש מילים"; subView.text = "מילה או ביטוי בתוך כל הספרים"
                input.hint = "מילה או ביטוי… למשל: רבי שמעון"
            }
            else -> { titleView.text = "חיפוש פלוס"; subView.text = "חיפוש מעמיק בתוך הספרים שלך"; input.hint = "מה לחפש? או לחצו 🎤 ודברו" }
        }
        modeRow.visibility = if (k == "home") View.VISIBLE else View.GONE
        buildModes()
        val q = i.getStringExtra("q")
        if (!q.isNullOrBlank()) { input.setText(q); doSearch() } else if (results == null) showHome()
    }

    private fun openVoice() = startActivity(Intent(this, VoiceActivity::class.java).putExtra("listen", true))

    override fun onResume() {
        super.onResume()
        refreshSetup()
        // the library is read once; afterwards only a quick check for new books, once a day
        val first = !Prefs.b("firstScanDone", false)
        val due = System.currentTimeMillis() - Prefs.l("lastAutoScan", 0L) > 24L * 3600 * 1000
        if (Library.hasAccess(this) && Library.roots().isNotEmpty() && !Progress.running && (first || (due && Prefs.b("autoScan", true)))) {
            Prefs.put("lastAutoScan", System.currentTimeMillis())
            Indexer.start(this)
        }
        updateGate()
        Updater.dailyCheck(this)
        checkLastExit()
        val crash = File(filesDir, "crash.txt")
        if (crash.exists()) {
            val t = try { crash.readText() } catch (e: Exception) { "" }
            crash.delete()
            Sheet.confirm(this, "התוכנה נתקלה בשגיאה", "בפעם הקודמת משהו השתבש. הפרטים הטכניים נשמרו – אפשר להעתיק אותם ולשלוח למפתח.", "העתקת הפרטים") {
                (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("crash", t))
                toast("הועתק")
            }
        }
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
            if (gate.visibility == View.VISIBLE || !Prefs.b("firstScanDone", false)) updateGate()
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
        // tapping the name ("פלוס") or ☰ opens the side menu with every part of the app
        head.addView(Nav.button(this))
        head.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_launcher); setOnClickListener { Nav.open(this@MainActivity) } }, lp(dp(38), dp(38)).m(0, 0, 0, 0))
        titleView = label("חיפוש פלוס", 22f, Col.TEXT, true)
        subView = label("חיפוש מעמיק בתוך הספרים שלך", 12.5f, Col.SUB)
        head.addView(vbox().apply {
            addView(titleView); addView(subView)
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener { Nav.open(this@MainActivity) }
        }, lp(0, WRAP, 1f))
        head.addView(headerButton("🎤") { openVoice() })
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
            hint = "מה לחפש? או לחצו 🎤 ודברו"
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
            setOnClickListener { input.setText(""); results = null; jumpBar.visibility = View.GONE; showHome() }
        })
        box.addView(label("🎤", 22f, Col.ACC, true).apply {
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = pressBg(Color.TRANSPARENT, Col.ACC_LIGHT, 18)
            contentDescription = "חיפוש קולי"
            setOnClickListener { openVoice() }
        })
        box.addView(pill("חיפוש") { doSearch() }, lp(WRAP, WRAP))
        root.addView(box, lp(MATCH, WRAP).m(dp(12), dp(4), dp(12), dp(6)))

        modeRow = hbox().apply { setPadding(dp(12), 0, dp(12), 0) }
        root.addView(modeRow, lp(MATCH, WRAP))
        optRow = hbox().apply { setPadding(dp(12), dp(6), dp(12), 0) }
        optScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(optRow)
        }
        root.addView(optScroll, lp(MATCH, WRAP))
        buildModes()

        crumbs = FlowLayout(this).apply { setPadding(dp(12), dp(8), dp(12), 0); gap = dp(2) }
        root.addView(crumbs, lp(MATCH, WRAP))
        cats = FlowLayout(this).apply { setPadding(dp(12), dp(6), dp(12), dp(2)) }
        root.addView(cats, lp(MATCH, WRAP))
        jumpBar = label("", 15.5f, Color.WHITE, true).apply {
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = pressBg(Col.ACC, 0xFF0F2540.toInt(), 14)
            visibility = View.GONE
        }
        root.addView(jumpBar, lp(MATCH, WRAP).m(dp(12), dp(6), dp(12), 0))
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
        // first time: a full screen until the whole library has been read once
        gate = vbox().apply {
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
            background = Col.gradient(Col.ACC, Col.ACC2, 0f)
            isClickable = true
            visibility = View.GONE
        }
        gate.addView(label("📚", 64f, Color.WHITE).apply { gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER })
        gate.addView(label("לומד את הספרייה שלך", 26f, Color.WHITE, true).apply { gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER; setPadding(0, dp(12), 0, dp(8)) })
        gate.addView(label("פעם אחת בלבד: התוכנה קוראת כל ספר ושומרת את מה שכתוב בו. אחר כך כל חיפוש מיידי, ולא סורקים שוב – רק ספרים חדשים.\nכדאי לחבר לטעינה. אפשר לכבות את המסך – העבודה ממשיכה.", 15.5f, 0xEEFFFFFF.toInt()).apply { gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER })
        gateBar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; progress = 0 }
        gate.addView(gateBar, lp(MATCH, dp(18)).m(0, dp(24), 0, dp(10)))
        gateText = label("מתחיל…", 16f, Color.WHITE, true).apply { gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER }
        gate.addView(gateText, lp(MATCH, WRAP))
        gateSkip = label("להשתמש בינתיים בספרים שכבר נקראו ›", 14f, 0xCCFFFFFF.toInt()).apply {
            gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER; setPadding(0, dp(28), 0, 0)
            visibility = View.GONE
            setOnClickListener { gateSkipped = true; updateGate() }
        }
        gate.addView(gateSkip, lp(MATCH, WRAP))
        val frame = android.widget.FrameLayout(this)
        frame.addView(root, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
        frame.addView(gate, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(frame)
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
        searchChrome(false)
        crumbs.removeAllViews(); cats.removeAllViews()
        summary.text = ""
        homeBox.removeAllViews()
        // welcome card
        val hero = vbox().apply {
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = Col.gradient(Col.ACC, Col.ACC2, dp(18).toFloat())
        }
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val hello = when (hour) { in 4..11 -> "בוקר טוב!"; in 12..16 -> "צהריים טובים!"; in 17..20 -> "ערב טוב!"; else -> "לילה טוב!" }
        hebrewDate().takeIf { it.isNotEmpty() }?.let { hero.addView(label(it, 13.5f, 0xDDFFFFFF.toInt(), true)) }
        hero.addView(label("$hello מה נלמד היום?", 21f, android.graphics.Color.WHITE, true))
        val heroStats = label("טוען את נתוני המאגר…", 14f, 0xFFDDE6F2.toInt()).apply { setPadding(0, dp(4), 0, dp(12)) }
        hero.addView(heroStats)
        homeBox.addView(hero, lp(MATCH, WRAP).m(0, dp(6), 0, dp(6)))

        // every part of the app, one tap away (also in the side menu ☰)
        val grid = vbox()
        Nav.ITEMS.chunked(2).forEachIndexed { r, pair ->
            val row = hbox()
            pair.forEachIndexed { c, it ->
                val (c1, c2) = Col.TILES[(r * 2 + c) % Col.TILES.size]
                row.addView(vbox().apply {
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    background = Col.gradient(c1, c2, dp(16).toFloat())
                    if (!Col.isEink) elevation = dp(3).toFloat()
                    addView(label(it.icon, 24f, android.graphics.Color.WHITE))
                    addView(label(it.title, 16f, android.graphics.Color.WHITE, true))
                    addView(label(it.sub, 11.5f, 0xEEFFFFFF.toInt()).apply { maxLines = 2 })
                    setOnClickListener { _ -> it.go(this@MainActivity) }
                }, lp(0, WRAP, 1f).m(dp(4), dp(4), dp(4), dp(4)))
            }
            grid.addView(row, lp(MATCH, WRAP))
        }
        homeBox.addView(grid, lp(MATCH, WRAP).m(0, dp(2), 0, dp(4)))

        // continue where you stopped
        Library.recent().firstOrNull()?.let { k ->
            val p = k.substringBefore('|'); val pg = k.substringAfter('|').toIntOrNull() ?: 1
            if (File(p).exists()) homeBox.addView(hbox().apply {
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = pressBg(Col.CARD, Col.ACC_LIGHT, 16, dp(1.5f), Col.ACC)
                if (!Col.isEink) elevation = dp(2).toFloat()
                addView(label("📖", 28f, Col.ACC), lp(WRAP, WRAP))
                addView(vbox().apply {
                    setPadding(dp(12), 0, dp(6), 0)
                    addView(label("להמשיך מאיפה שעצרת", 12.5f, Col.ACC, true))
                    addView(label(File(p).nameWithoutExtension, 16f, Col.TEXT, true).apply { setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END })
                    addView(label("עמוד $pg", 12.5f, Col.SUB))
                }, lp(0, WRAP, 1f))
                addView(label("◂", 22f, Col.ACC, true))
                setOnClickListener { ReaderActivity.open(this@MainActivity, p, pg) }
            }, lp(MATCH, WRAP).m(0, dp(6), 0, dp(6)))
        }

        // the big microphone: say what you are looking for
        val mic = vbox().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(18), dp(16), dp(16))
            background = pressBg(Col.BG, Col.ACC_LIGHT, 22, dp(2), Col.ACC)
            setOnClickListener { openVoice() }
        }
        mic.addView(label("🎤", 46f, Col.ACC, true).apply {
            gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER
            background = roundBg(Col.ACC_LIGHT, dp(48).toFloat(), dp(3), Col.ACC)
            setPadding(dp(18), dp(10), dp(18), dp(14))
        }, lp(dp(96), dp(96)))
        mic.addView(label("לחצו ודברו", 19f, Col.TEXT, true).apply { gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER; setPadding(0, dp(10), 0, dp(2)) }, lp(MATCH, WRAP))
        mic.addView(label("אומרים מה מחפשים – והספר נפתח במקום. עובד בלי אינטרנט", 13.5f, Col.SUB).apply { gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER }, lp(MATCH, WRAP))
        val ex = FlowLayout(this).apply { setPadding(0, dp(12), 0, 0) }
        listOf("ברכות דף כ״ז ע״א", "לקוטי שיחות חלק ד עמוד 58", "לקוטי שיחות חלק ד נח", "תניא פרק ה", "מה מברכים על פרי העץ").forEach { e ->
            ex.addView(chip("“$e”", false) {
                startActivity(Intent(this, VoiceActivity::class.java).putExtra("q", e))
            })
        }
        mic.addView(ex, lp(MATCH, WRAP))
        homeBox.addView(mic, lp(MATCH, WRAP).m(0, dp(8), 0, dp(8)))
        exec.execute {
            val st = try { Db.get().stats() } catch (e: Exception) { null }
            ui {
                heroStats.text = when {
                    st == null || st.books == 0 -> "עוד אין ספרים במאגר – בחרו את תיקיית הספרים כדי להתחיל."
                    else -> "במאגר ${st.books} ספרים · ${st.textPages} עמודים שאפשר לחפש בהם" +
                        (if (st.pending > 0) "\nעוד ${st.pending} ספרים בתור לסריקה" else "") +
                        (if (Semantic.ready(this)) "\n💡 אינדקס המשמעות: ${st.embedded} מתוך ${st.done} ספרים" + (if (st.embedded >= st.done && st.done > 0) " ✓ מוכן לשאלות" else "") else "")
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
                "• לחיצה על תוצאה פותחת את הספר בדיוק בעמוד, והמילים מסומנות.\n" +
                "• 🎤 חיפוש קולי: לוחצים על המיקרופון ואומרים – \"ברכות דף כ״ז עמוד א\", \"ליקוטי שיחות חלק ג ויצא\", או שאלה. התוכנה פותחת ישר את המקום.", 14.5f, Col.SUB).apply { setLineSpacing(0f, 1.15f) })
        homeBox.addView(label("\nפותח על ידי שניאור וילהלם · מופץ לתועלת הרבים", 12.5f, Col.SUB).apply { gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER })
        homeBox.addView(label("יחי אדוננו מורנו ורבנו מלך המשיח לעולם ועד", 14f, Col.ACC, true).apply { gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER; setPadding(0, dp(6), 0, dp(10)) })
        if (!Col.isEink) for (i in 0 until minOf(homeBox.childCount, 8)) homeBox.getChildAt(i).apply {
            alpha = 0f; translationY = dp(18).toFloat()
            animate().alpha(1f).translationY(0f).setStartDelay(i * 60L).setDuration(380).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }
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

    private fun doSearch(autoOpen: Boolean = false) {
        val q = input.text.toString().trim()
        if (q.isEmpty()) return
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
        Library.addHistory(q)
        prefix = emptyList()
        expanded.clear()
        home.visibility = View.GONE
        searchChrome(true)
        list.visibility = View.VISIBLE
        summary.text = if (semantic) "מחפש לפי משמעות… (בפעם הראשונה זה לוקח כמה שניות)" else "מחפש…"
        crumbs.removeAllViews(); cats.removeAllViews()
        adapter.set(emptyList())
        val token = ++searchToken
        showJump(q, token, auto = autoOpen)
        val sem = semantic
        val m = Match.valueOf(Prefs.s("match", "PHRASE"))
        val pre = Prefs.b("prefixes", true)
        exec.execute {
            val r = if (sem) Search.runSemantic(this, q) { t -> ui { if (token == searchToken) summary.text = t } } else Search.run(q, m, pre)
            val e = if (sem) "" else Search.buildMatch(r.terms, m, pre)
            ui {
                if (token != searchToken) return@ui
                results = r; expr = e
                render()
            }
        }
    }

    /**
     * Android 11 keeps the reason the app last closed (also for crashes of native code and the system
     * killing it for memory, which no error handler can catch). Show it once, with a fix where there is one.
     */
    private fun checkLastExit() {
        val busy = Prefs.l("voiceBusy", 0L)
        Prefs.put("voiceBusy", 0L)
        // the meaning index was being built when the app closed: don't let it close the app again on every start
        val sem = Prefs.l("semBusy", 0L)
        Prefs.put("semBusy", 0L)
        if (sem > 0 && !Progress.running) {
            Prefs.put("semantic", false)
            Semantic.lastError = "בניית אינדקס המשמעות נעצרה כי המכשיר סגר את התוכנה. אפשר להפעיל שוב מההגדרות (מומלץ בטעינה, בלילה)."
            Sheet.confirm(this, "אינדקס המשמעות נעצר", "בזמן בניית אינדקס המשמעות המכשיר סגר את התוכנה (כנראה חוסר זיכרון). כדי שזה לא יחזור – הבנייה הופסקה.\n\nהחיפוש לפי משמעות עדיין עובד על העמודים שמכילים את מילות השאלה.", "לנסות שוב עכשיו") {
                Prefs.put("semantic", true); Indexer.start(this)
            }
            return
        }
        if (android.os.Build.VERSION.SDK_INT < 30) {
            if (busy > 0) voiceCrashSheet("")
            return
        }
        try {
            val am = getSystemService(android.app.ActivityManager::class.java)
            val e = am.getHistoricalProcessExitReasons(packageName, 0, 1).firstOrNull() ?: return
            if (e.timestamp <= Prefs.l("lastExitSeen", 0L)) return
            Prefs.put("lastExitSeen", e.timestamp)
            val r = e.reason
            val bad = r == android.app.ApplicationExitInfo.REASON_CRASH || r == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE ||
                r == android.app.ApplicationExitInfo.REASON_LOW_MEMORY || r == android.app.ApplicationExitInfo.REASON_ANR ||
                (r == android.app.ApplicationExitInfo.REASON_SIGNALED && busy > 0)
            if (!bad) return
            val why = when (r) {
                android.app.ApplicationExitInfo.REASON_LOW_MEMORY, android.app.ApplicationExitInfo.REASON_SIGNALED -> "המכשיר סגר את התוכנה כי נגמר הזיכרון"
                android.app.ApplicationExitInfo.REASON_ANR -> "התוכנה לא הגיבה זמן רב"
                else -> "שגיאה בתוכנה"
            }
            val details = "$why\n(${e.reason}/${e.status} ${e.description ?: ""}) גרסה ${packageManager.getPackageInfo(packageName, 0).versionName}"
            if (busy > 0 && e.timestamp >= busy) voiceCrashSheet(details)
            else Sheet.confirm(this, "התוכנה נסגרה בפעם הקודמת", details + "\n\nאפשר להעתיק את הפרטים ולשלוח למפתח.", "העתקת הפרטים") {
                (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("exit", details))
                toast("הועתק")
            }
        } catch (x: Throwable) {}
    }

    private fun voiceCrashSheet(details: String) {
        val cur = VoiceModels.spec()
        val lower = VoiceModels.SPECS.getOrNull(VoiceModels.SPECS.indexOf(cur) - 1)
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (lower != null) items.add("לעבור לזיהוי ${lower.title} (קל יותר למכשיר)" to {
            Prefs.put("whisperModel", lower.key)
            if (!VoiceModels.ready(this, lower.key)) VoiceSearch.downloadNow(this, lower.key) { toast("מוכן – נסו שוב את המיקרופון") } else toast("עבר לזיהוי ${lower.title} – נסו שוב")
        })
        if (VoiceSearch.systemAvailable(this)) items.add("להשתמש בשירות הדיבור של המכשיר (צריך אינטרנט)" to { Prefs.put("voiceEngine", "system"); toast("נסו שוב את המיקרופון") })
        items.add("להעתיק את הפרטים למפתח" to {
            (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("voice", "זיהוי דיבור נסגר: $details"))
            toast("הועתק")
        })
        items.add("להמשיך כרגיל" to {})
        Sheet.actions(this, "זיהוי הדיבור עצר את התוכנה בפעם הקודמת" + if (details.isNotEmpty()) "\n$details" else "", items)
    }

    // ---------------- voice and direct jumps ----------------

    private val questionWords = setOf("מה", "מהו", "מהי", "איך", "כיצד", "למה", "מדוע", "מתי", "מי", "האם", "היכן", "איפה", "מאיפה", "כמה")

    private fun onVoice(text: String) {
        input.setText(text)
        input.setSelection(input.text.length)
        val first = Heb.words(text).firstOrNull()
        if (RefParser.parse(text) == null && first != null && first in questionWords && Semantic.ready(this) && !semantic) {
            semantic = true; buildModes()
        }
        doSearch(autoOpen = true)
    }

    /** When the search is a reference (ברכות דף כז / ליקוטי שיחות חלק ג ויצא), offer to go straight there. */
    private fun showJump(q: String, token: Int, auto: Boolean) {
        jumpBar.visibility = View.GONE
        val ref = try { RefParser.parse(q) } catch (e: Throwable) { null } ?: return
        if (ref.kind == com.chdv.searchplus.core.Ref.Kind.GEMARA && ref.daf == null) {
            jumpBar.text = "⤶ ${ref.work}: לא הבנתי איזה דף. אמרו למשל \"${ref.work} דף כ״ז עמוד א\""
            jumpBar.visibility = View.VISIBLE
            jumpBar.setOnClickListener(null)
            return
        }
        jumpBar.text = "⤶ מחפש את ${ref.label()}…"
        jumpBar.visibility = View.VISIBLE
        jumpBar.setOnClickListener(null)
        exec.execute {
            val targets = try { Jump.resolve(ref, q) } catch (e: Throwable) { emptyList() }
            ui {
                if (token != searchToken) return@ui
                if (targets.isEmpty()) {
                    jumpBar.text = "⤶ ${ref.label()}: לא נמצא ספר כזה במאגר" + if (Progress.running) " (הסריקה עדיין רצה)" else ""
                    return@ui
                }
                jumpBar.text = if (targets.size == 1) "⤶ פתיחה: ${targets[0].book.title} – ${targets[0].note}"
                else "⤶ ${ref.label()} – נמצא ב-${targets.size} ספרים. לבחירה ◂"
                jumpBar.setOnClickListener { pickTarget(targets) }
                if (auto) pickTarget(targets)
            }
        }
    }

    private fun pickTarget(targets: List<Jump.Target>) {
        if (targets.size == 1) { openTarget(targets[0]); return }
        Sheet.actions(this, "איזה ספר לפתוח? (${targets[0].ref.label()})", targets.map { t ->
            val folder = Library.categoryOf(t.book.dir).joinToString(" › ")
            "📖 ${t.book.title}\n$folder · ${t.note}" to { openTarget(t) }
        })
    }

    private fun openTarget(t: Jump.Target) {
        ReaderActivity.open(this, t.book.path, t.page, refLabel = t.ref.label(), amudIndex = t.amudIndex ?: -1, exact = t.exact)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == VoiceSearch.REQ_MIC) VoiceSearch.onPermission(this, grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED)
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

    private fun openHit(h: BookHit, page: Int, phrase: String? = null) {
        val r = results ?: return
        val hitPages = h.pages.map { it.page }.distinct().sorted().toIntArray()
        ReaderActivity.open(this, h.book.path, page, r.terms, hitPages, phrase = phrase)
    }

    private fun bookMenu(h: BookHit) {
        val b = h.book
        Sheet.actions(this, b.title, listOf(
            "פתיחה בעמוד הראשון שנמצא" to { openHit(h, h.pages.firstOrNull()?.page ?: 1) },
            "פתיחה באפליקציה אחרת" to { Library.openExternal(this, b.path, h.pages.firstOrNull()?.page ?: 1, results?.terms ?: emptyList(), chooser = true) },
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
    /** Question & answer: the sentence that answers, per page – opened framed in the reader. */
    private val sentenceOf = java.util.concurrent.ConcurrentHashMap<Long, String>()

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
                        val q = results?.query ?: ""
                        snipExec.execute {
                            val s = (if (sem) (try { Semantic.sentenceFor(this@MainActivity, q, ph.pageId) } catch (x: Throwable) { null })
                                ?.also { sentenceOf[ph.pageId] = it }?.let { "\u0001$it\u0002" } ?: Search.semanticSnippet(ph.pageId, terms)
                            else Search.snippet(e, ph.pageId))?.replace('\n', ' ') ?: ""
                            val cs = marked(s)
                            ui {
                                snippetCache[ph.pageId] = cs
                                if (tv.tag == ph.pageId) tv.text = cs
                            }
                        }
                    }
                    v.setOnClickListener { openHit(row.hit, ph.page, sentenceOf[ph.pageId]) }
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
                        a.pageId < 0 || src == null -> "לא נמצא משפט שעונה במדויק. הקטעים הקרובים ביותר מופיעים למטה." +
                            if (!Semantic.Deep.ready(this@MainActivity)) "\n\n🧠 לחצו כאן להבנה מתקדמת יותר – מודל שקורא את המשפטים בהקשר ומוצא את התשובה." else ""
                        else -> "‟" + a.text + "”"
                    }
                    v.findViewById<TextView>(3).text = if (src != null && a != null && a.pageId >= 0) "📖 ${src.first.title}, עמוד ${src.second}  ·  לפתיחה ◂" else ""
                    v.setOnClickListener {
                        if ((a == null || a.pageId < 0 || src == null) && !Semantic.Deep.ready(this@MainActivity)) { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)); toast("הגדרות › 🧠 מודל הבנה עמוק"); return@setOnClickListener }
                        if (src != null) ReaderActivity.open(this@MainActivity, src.first.path, src.second, Semantic.keyWords(a!!.text).take(6), phrase = a.text)
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
