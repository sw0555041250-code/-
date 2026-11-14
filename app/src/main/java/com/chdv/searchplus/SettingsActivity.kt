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

        section("זיהוי טקסט בעמודים סרוקים (OCR)")
        val ocrReady = Ocr.ready(this)
        item(if (ocrReady) "רכיב ה-OCR העברי מותקן ✓" else "התקנת רכיב OCR עברי",
            if (ocrReady) "לחיצה להחלפה לגרסה המדויקת (איטית יותר) או לחזרה לגרסה המהירה"
            else "ספרים ישנים סרוקים (תמונה בלי טקסט) – התוכנה תזהה את האותיות ותוכל לחפש בהם") { ocrMenu() }
        toggle("זיהוי אוטומטי של כל העמודים הסרוקים", "רץ ברקע אחרי קריאת הספרים. במכשיר חלש זה איטי (כמה שניות לעמוד)", "ocrAuto", true)

        section("חיפוש לפי משמעות (בינה מלאכותית)")
        val semReady = Semantic.ready(this)
        item(if (semReady) "מודל המשמעות מותקן ✓" else "הורדת מודל המשמעות (כ-120MB)",
            if (semReady) "וקטורים באינדקס: ${Semantic.vectorCount(this)} · לחיצה לניהול"
            else "מאפשר לכתוב שאלה או רעיון ולמצוא את המקומות שעוסקים בהם. עובד בתוך המכשיר, בלי אינטרנט") { semanticMenu() }
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
        item("פתיחת תוצאה", if (ow == "internal") "בקורא המובנה – בעמוד הנכון עם סימון המילים" else "באפליקציה אחרת (קורא ברירת המחדל)") {
            Sheet.choice(this, "איך לפתוח ספר?", listOf(
                "internal" to "קורא מובנה – בעמוד המדויק עם סימון (מומלץ)",
                "external" to "אפליקציה אחרת של המכשיר",
            ), ow) { Prefs.put("openWith", it); render() }
        }
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
        item("חיפוש פלוס", "גרסה ${version()}\nפותח על ידי שניאור וילהלם\nמופץ בחינם לתועלת הרבים\n\nקוד פתוח: github.com/chdv770-ctrl/Search-Plus", null)
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
