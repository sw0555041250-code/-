package com.chdv.searchplus

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

class SettingsActivity : BaseActivity() {
    private lateinit var box: LinearLayout
    private val bg = java.util.concurrent.Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        box = vbox().apply { setPadding(dp(14), dp(6), dp(14), dp(30)) }
        screen("הגדרות", ScrollView(this).apply { addView(box) })
        render()
    }

    override fun onResume() { super.onResume(); render() }

    private fun section(t: String) = box.addView(label(t, 15f, Col.ACC, true).apply { setPadding(dp(4), dp(18), dp(4), dp(6)) })

    private fun item(title: String, sub: String = "", onClick: (() -> Unit)?): TextView {
        val v = vbox().apply {
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = pressBg(Col.CARD, Col.ACC_LIGHT, 12)
        }
        v.addView(label(title, 16f, Col.TEXT, true))
        val s = label(sub, 13.5f, Col.SUB)
        if (sub.isNotEmpty()) v.addView(s)
        if (onClick != null) v.setOnClickListener { onClick() }
        box.addView(v, lp(MATCH, WRAP).m(0, dp(3), 0, dp(3)))
        return s
    }

    private fun toggle(title: String, sub: String, key: String, def: Boolean, after: (Boolean) -> Unit = {}) {
        val on = Prefs.b(key, def)
        item((if (on) "☑ " else "☐ ") + title, sub) { Prefs.put(key, !on); after(!on); render() }
    }

    private fun render() {
        box.removeAllViews()

        section("מאגר הספרים")
        val roots = Library.roots()
        item("תיקיות הספרים", if (roots.isEmpty()) "עוד לא נבחרה תיקייה" else roots.joinToString("\n")) { rootsMenu() }
        val statsLabel = item("מצב הסריקה", "טוען…") { statsSheet() }
        bg.execute {
            val st = Db.get().stats()
            ui {
                statsLabel.text = "${st.books} ספרים · נסרקו ${st.done}" + (if (st.pending > 0) " · ממתינים ${st.pending}" else "") +
                    (if (st.errors > 0) " · שגיאות ${st.errors}" else "") + "\n${st.textPages} עמודים עם טקסט · ${st.emptyPages} עמודים סרוקים בלי טקסט" +
                    (if (Progress.running) "\n⟳ " + Progress.text() else "")
            }
        }
        item("סריקה עכשיו", "מחפש ספרים חדשים, שהשתנו או שנמחקו") { Indexer.start(this); toast("הסריקה התחילה") }
        if (Progress.running) item("עצירת הסריקה", "") { Progress.cancel = true; toast("עוצר…") }
        toggle("סריקה אוטומטית בפתיחת התוכנה", "בכל פתיחה בודק אם נוספו ספרים", "autoScan", true)
        item("סריקה מחדש של כל הספרים", "מוחק את האינדקס וקורא הכול מההתחלה") {
            Sheet.confirm(this, "סריקה מחדש של הכול", "האינדקס יימחק וכל הספרים ייקראו מחדש. זה יכול לקחת זמן רב.", "כן, לסרוק מחדש") {
                Progress.cancel = true
                bg.execute { Db.get().resetAll(); ui { Indexer.start(this) } }
            }
        }
        toggle("סדר טקסט לפי מיקום בעמוד", "לספרים שהטקסט שלהם יוצא מבולגן. משפיע על סריקה חדשה", "sortByPosition", false)

        section("סידור המאגר")
        item("ספרים כפולים", "מציאת אותו ספר שנשמר כמה פעמים – לפי התוכן, לא לפי השם") { startActivity(android.content.Intent(this, OrganizeActivity::class.java).putExtra("tab", 0)) }
        item("סידור חכם לתיקיות", "הצעה לאיזו תיקייה כל ספר שייך, לפי מה שכתוב בו") { startActivity(android.content.Intent(this, OrganizeActivity::class.java).putExtra("tab", 1)) }
        item("שמות חכמים", "שמות לקבצים ולתיקיות לפי דף השער והתוכן – עם אישור על כל שינוי") { startActivity(android.content.Intent(this, OrganizeActivity::class.java).putExtra("tab", 2)) }

        section("זיהוי טקסט בעמודים סרוקים (OCR)")
        val ocrReady = Ocr.ready(this)
        item(if (ocrReady) "רכיב ה-OCR העברי מותקן ✓" else "התקנת רכיב OCR עברי",
            if (ocrReady) "לחיצה להחלפה לגרסה המדויקת (איטית יותר) או לחזרה לגרסה המהירה"
            else "ספרים ישנים סרוקים (תמונה בלי טקסט) – התוכנה תזהה את האותיות ותוכל לחפש בהם") { ocrMenu() }
        toggle("זיהוי אוטומטי של כל העמודים הסרוקים", "רץ ברקע אחרי קריאת הספרים. במכשיר חלש זה איטי (כמה שניות לעמוד)", "ocrAuto", true)

        section("חיפוש קולי 🎤")
        val eng = Prefs.s("voiceEngine", "whisper")
        val sysOk = VoiceSearch.systemAvailable(this)
        item("מנוע זיהוי הדיבור", when (eng) {
            "system" -> "שירות הדיבור של המכשיר (צריך אינטרנט)"
            "whisper" -> "בתוך המכשיר – Whisper (בלי אינטרנט)"
            else -> "אוטומטי: " + if (sysOk && !Prefs.b("systemFailed", false)) "שירות המכשיר, ואם הוא לא עובד – בתוך המכשיר" else "בתוך המכשיר (לא נמצא שירות דיבור במכשיר)"
        }) {
            Sheet.choice(this, "איך לזהות את הדיבור?", listOf(
                "whisper" to "בתוך המכשיר – Whisper, בלי אינטרנט (מומלץ)",
                "auto" to "אוטומטי: שירות המכשיר כשיש אינטרנט, אחרת בתוך המכשיר",
                "system" to "שירות הדיבור של המכשיר" + if (sysOk) "" else " (לא נמצא במכשיר הזה)",
            ), eng) { Prefs.put("voiceEngine", it); Prefs.put("systemFailed", false); render() }
        }
        val wm = VoiceModels.spec()
        val wReady = VoiceModels.ready(this)
        item(if (wReady) "מודל הדיבור במכשיר: ${wm.title} ✓" else "הורדת מודל דיבור לעבודה בלי אינטרנט",
            if (wReady) "נשמר ב: ${VoiceModels.dir(this, wm.key).parent}\nלחיצה להחלפה או למחיקה"
            else "הורדה חד-פעמית (80–250MB). נשמר במכשיר או בכרטיס – איפה שיש יותר מקום") {
            val items = ArrayList<Pair<String, () -> Unit>>()
            VoiceModels.SPECS.forEach { sp ->
                val have = VoiceModels.ready(this, sp.key)
                items.add((if (sp.key == wm.key && have) "● " else "○ ") + "${sp.title} – כ-${sp.sizeMb}MB" + (if (have) " (מותקן)" else "") + "\n${sp.note}" to {
                    Prefs.put("whisperModel", sp.key); VoiceModels.Engine.release()
                    if (!have) VoiceSearch.downloadNow(this, sp.key) { render() } else render()
                })
            }
            if (wReady) items.add("מחיקת מודל הדיבור (${wm.title})" to { VoiceModels.delete(this, wm.key); render() })
            Sheet.actions(this, "מודל זיהוי הדיבור", items)
        }
        item("בדיקת החיפוש הקולי", "לחיצה, ודברו – תראו מה התוכנה שמעה") {
            VoiceSearch.start(this) { t -> Sheet.info(this, "זה מה ששמעתי", t + (com.chdv.searchplus.core.RefParser.parse(t)?.let { "\n\nהבנתי: " + it.label() } ?: "")) }
        }

        section("חיפוש לפי משמעות (בינה מלאכותית)")
        val semReady = Semantic.ready(this)
        item(if (semReady) "מודל המשמעות מותקן ✓" else "הורדת מודל המשמעות (כ-120MB)",
            if (semReady) "וקטורים באינדקס: ${Semantic.vectorCount(this)} · לחיצה לניהול" + (Semantic.lastError.takeIf { it.isNotEmpty() }?.let { "\n⚠ $it" } ?: "")
            else "מאפשר לכתוב שאלה או רעיון ולמצוא את המקומות שעוסקים בהם. עובד בתוך המכשיר, בלי אינטרנט") { semanticMenu() }
        if (semReady) item("🌙 בניית אינדקס המשמעות המלא (מומלץ בלילה)", "כל הספרים נקראים ומובנים פעם אחת. השאירו את המכשיר בטעינה עם התוכנה פתוחה או ברקע – בבוקר אפשר לשאול שאלות בלי אינטרנט" +
            (if (Progress.running) "\n⟳ " + Progress.text() else "")) {
            Prefs.put("semantic", true)
            Sheet.info(this, "בונים את אינדקס המשמעות", "הבנייה רצה ברקע ומציגה כמה זמן נשאר.\n\n• חברו את המכשיר לטעינה.\n• ב-BOOX: הגדרות › אפליקציות › ניהול אפליקציות › חיפוש פלוס – כבו \"הקפאה\" / אפשרו ריצה ברקע, כדי שהמכשיר לא יעצור את העבודה בלילה.\n• אפשר להמשיך לחפש בזמן הבנייה.")
            Indexer.start(this)
        }
        toggle("בניית אינדקס משמעות ברקע", "כבוי = החיפוש לפי משמעות מבוטל ולא נבנה אינדקס", "semantic", false) { on ->
            if (on && Semantic.ready(this)) Indexer.start(this)
        }
        val semRoots = Prefs.list("semanticRoots")
        item("תיקיות לאינדקס המשמעות", if (semRoots.isEmpty()) "כל הספרים (מומלץ לבחור תיקיות חשובות – כך זה מהיר יותר)" else semRoots.joinToString("\n")) {
            Sheet.actions(this, "תיקיות לאינדקס המשמעות", listOf(
                "הוספת תיקייה" to { FolderPicker.pick(this) { d -> Prefs.putList("semanticRoots", (Prefs.list("semanticRoots") + d).distinct()); render() } },
                "כל הספרים" to { Prefs.put("semanticRoots", ""); render() },
            ))
        }

        section("פתיחת ספרים ותצוגה")
        val ow = Prefs.s("openWith", "internal")
        val rp = Prefs.s("readerPkg", "")
        item("באיזו אפליקציה לפתוח ספרים", if (ow == "internal") "הקורא המובנה – בעמוד הנכון עם סימון המילים"
            else (if (rp.isEmpty()) "אפליקציה אחרת – תישאלו פעם אחת באיזו" else Library.appName(this, rp)) + " · נפתח בעמוד, והמילים מועתקות לחיפוש") {
            val apps = Library.pdfApps(this)
            val opts = ArrayList<Pair<String, String>>()
            opts.add("internal" to "קורא מובנה – עמוד מדויק + סימון המילים (מומלץ)")
            apps.forEach { r -> opts.add("app:${r.activityInfo.packageName}/${r.activityInfo.name}" to r.loadLabel(packageManager).toString()) }
            val cur = if (ow == "internal") "internal" else "app:$rp/${Prefs.s("readerCls", "")}"
            Sheet.choice(this, "ברירת מחדל לפתיחת ספרים", opts, cur) { k ->
                Prefs.put("readerChosen", true)
                if (k == "internal") Prefs.put("openWith", "internal") else {
                    val (pkg, cls) = k.removePrefix("app:").split('/', limit = 2)
                    Prefs.put("openWith", "external"); Prefs.put("readerPkg", pkg); Prefs.put("readerCls", cls)
                }
                render()
            }
        }
        if (ow == "external") toggle("העתקת מילות החיפוש בפתיחה", "כדי להדביק אותן בחיפוש של הקורא ולסמן אותן שם", "copyTerms", true)
        val fs = Prefs.s("fontSize", "normal")
        item("גודל הכתב", mapOf("small" to "קטן", "normal" to "רגיל", "large" to "גדול", "xlarge" to "גדול מאוד")[fs] ?: "רגיל") {
            Sheet.choice(this, "גודל הכתב", listOf("small" to "קטן", "normal" to "רגיל", "large" to "גדול", "xlarge" to "גדול מאוד"), fs) {
                Prefs.put("fontSize", it); recreate()
            }
        }
        item("ניקוי היסטוריה", "חיפושים אחרונים וספרים שנפתחו לאחרונה") {
            Prefs.put("history", ""); Prefs.put("recent", ""); toast("נוקה")
        }

        section("עדכונים")
        toggle("בדיקת עדכונים אוטומטית", "פעם ביום, כשיש אינטרנט", "autoUpdate", true)
        item("בדיקת עדכונים עכשיו", "גרסה נוכחית: " + version()) { Updater.check(this, false) }

        section("אודות")
        item("חיפוש פלוס", "גרסה ${version()}\nפותח על ידי שניאור וילהלם\nמופץ בחינם לתועלת הרבים\n\nקוד פתוח: github.com/sw0555041250-code/Search-Plus-", null)
    }

    private fun version() = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (e: Exception) { "" }

    private fun rootsMenu() {
        val roots = Library.roots()
        val items = ArrayList<Pair<String, () -> Unit>>()
        items.add("הוספת תיקייה" to { FolderPicker.pick(this) { d -> Library.setRoots(Library.roots() + d); render(); Indexer.start(this) } })
        roots.forEach { r -> items.add("הסרה: $r" to {
            Sheet.confirm(this, "הסרת תיקייה", "הספרים שבתיקייה $r יוסרו מהאינדקס (הקבצים עצמם לא יימחקו).", "הסרה") {
                Library.setRoots(Library.roots() - r); render(); Indexer.start(this)
            }
        }) }
        Sheet.actions(this, "תיקיות הספרים", items)
    }

    private fun statsSheet() {
        bg.execute {
            val st = Db.get().stats()
            val errs = Db.get().errors()
            ui {
                Sheet.info(this, "מצב הסריקה",
                    "ספרים: ${st.books}\nנסרקו: ${st.done}\nממתינים: ${st.pending}\nשגיאות: ${st.errors}\nעמודים: ${st.pages}\n" +
                        "עמודים עם טקסט: ${st.textPages}\nעמודים סרוקים שממתינים ל-OCR: ${st.emptyPages}\nספרים באינדקס המשמעות: ${st.embedded}" +
                        (if (Progress.running) "\n\nעכשיו: " + Progress.text() else "") +
                        (if (errs.isNotEmpty()) "\n\nקבצים שלא נקראו:\n" + errs.joinToString("\n") { "• ${it.first}" } else ""))
            }
        }
    }

    private fun ocrMenu() {
        fun dl(best: Boolean) {
            val s = label("מוריד…", 15f, Col.SUB)
            val d = Sheet.make(this, vbox().apply { setPadding(dp(18), dp(18), dp(18), dp(18)); addView(s) })
            d.setCancelable(false); d.show()
            bg.execute {
                try {
                    Ocr.download(this, best) { p -> ui { s.text = "מוריד את רכיב ה-OCR… $p%" } }
                    ui { d.dismiss(); toast("הותקן ✓"); render(); Indexer.start(this) }
                } catch (e: Exception) {
                    ui { d.dismiss(); Sheet.info(this, "ההורדה נכשלה", "בדקו את החיבור לאינטרנט ונסו שוב.\n" + (e.message ?: "")) }
                }
            }
        }
        Sheet.actions(this, "רכיב OCR עברי", listOf(
            "גרסה מהירה (מומלץ ל-BOOX)" to { dl(false) },
            "גרסה מדויקת (איטית יותר, לסריקות קשות)" to { dl(true) },
            "הסרת הרכיב" to { Ocr.delete(this); render() },
        ))
    }

    private fun semanticMenu() {
        if (!Semantic.ready(this)) {
            Sheet.confirm(this, "הורדת מודל המשמעות",
                "יורדים כ-120MB (מומלץ ב-Wi‑Fi). אחר כך התוכנה בונה ברקע אינדקס משמעות לספרים.\n\nבמכשיר כמו BOOX Go 6 בניית האינדקס איטית (כשנייה לעמוד), ולכן כדאי לבחור תיקיות חשובות בהגדרה \"תיקיות לאינדקס המשמעות\".",
                "הורדה") {
                val s = label("מוריד…", 15f, Col.SUB)
                val d = Sheet.make(this, vbox().apply { setPadding(dp(18), dp(18), dp(18), dp(18)); addView(s) })
                d.setCancelable(false); d.show()
                bg.execute {
                    try {
                        Semantic.download(this) { t -> ui { s.text = t } }
                        Prefs.put("semantic", true)
                        ui { d.dismiss(); toast("הותקן ✓"); render(); Indexer.start(this) }
                    } catch (e: Throwable) {
                        ui { d.dismiss(); Sheet.info(this, "ההורדה נכשלה", "בדקו את החיבור לאינטרנט ואת המקום הפנוי ונסו שוב.\n" + (e.message ?: "")) }
                    }
                }
            }
            return
        }
        Sheet.actions(this, "מודל המשמעות", listOf(
            "בניית האינדקס מחדש" to {
                Semantic.vectorsFile(this).delete(); Db.get().resetEmbeddings(); Indexer.start(this); render()
            },
            "מחיקת המודל והאינדקס (ביטול החיפוש לפי משמעות)" to {
                Sheet.confirm(this, "מחיקה", "המודל והאינדקס יימחקו. אפשר להוריד שוב בכל עת.", "מחיקה") {
                    Semantic.deleteAll(this); Prefs.put("semantic", false); render()
                }
            },
        ))
    }

    override fun onDestroy() { super.onDestroy(); bg.shutdown() }
}
