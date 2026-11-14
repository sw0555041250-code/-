package com.chdv.searchplus

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * The side menu (opens from the right): every part of the app as its own screen –
 * voice search, question & answer, word search, browsing, organizing, settings.
 */
object Nav {
    class Item(val icon: String, val title: String, val sub: String, val go: (Activity) -> Unit)

    val ITEMS: List<Item> = listOf(
        Item("🎤", "חיפוש קולי", "אומרים – והספר נפתח במקום", { a -> a.startActivity(Intent(a, VoiceActivity::class.java).putExtra("listen", true)) }),
        Item("💡", "שאלה ותשובה", "חיפוש לפי משמעות", { a -> a.startActivity(Intent(a, MainActivity::class.java).putExtra("screen", "meaning").addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)) }),
        Item("🔤", "חיפוש מילים", "מילה או ביטוי בתוך כל הספרים", { a -> a.startActivity(Intent(a, MainActivity::class.java).putExtra("screen", "words").addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)) }),
        Item("📚", "עיון בספרים", "תיקיות וקטגוריות", { a -> a.startActivity(Intent(a, BrowseActivity::class.java)) }),
        Item("🗂", "סידור המאגר", "כפולים · סידור תיקיות · שמות חכמים", { a -> a.startActivity(Intent(a, OrganizeActivity::class.java)) }),
        Item("☁", "ספרים מ-Google Drive", "מביאים את המאגר מהענן ומחפשים בו", { a -> a.startActivity(Intent(a, CloudActivity::class.java)) }),
        Item("⚙", "הגדרות", "מודלים · פתיחת ספרים · עדכונים", { a -> a.startActivity(Intent(a, SettingsActivity::class.java)) }),
    )

    fun open(a: Activity) {
        val d = Dialog(a)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val panel = a.vbox().apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(Col.BG)
            setPadding(a.dp(14), a.dp(18), a.dp(14), a.dp(14))
        }
        // header
        val head = a.vbox().apply {
            background = Col.gradient(Col.ACC, Col.ACC2, a.dp(18).toFloat())
            setPadding(a.dp(16), a.dp(16), a.dp(16), a.dp(16))
        }
        head.addView(a.label("חיפוש פלוס", 24f, Color.WHITE, true))
        head.addView(a.label("כל הספרים שלך – במילה, בשאלה ובקול", 13.5f, 0xFFE2E6FF.toInt()))
        panel.addView(head, lp(MATCH, WRAP).m(0, 0, 0, a.dp(12)))

        val list = a.vbox()
        fun row(icon: String, title: String, sub: String, i: Int, go: () -> Unit) {
            val r = a.hbox().apply {
                setPadding(a.dp(10), a.dp(10), a.dp(10), a.dp(10))
                background = a.pressBg(Color.TRANSPARENT, Col.ACC_LIGHT, 14)
                setOnClickListener { d.dismiss(); go() }
            }
            val (c1, c2) = Col.TILES[i % Col.TILES.size]
            r.addView(a.label(icon, 20f, Color.WHITE).apply {
                gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER
                background = Col.gradient(c1, c2, a.dp(12).toFloat())
            }, lp(a.dp(44), a.dp(44)))
            r.addView(a.vbox().apply {
                setPadding(a.dp(12), 0, a.dp(4), 0)
                addView(a.label(title, 16.5f, Col.TEXT, true))
                addView(a.label(sub, 12.5f, Col.SUB))
            }, lp(0, WRAP, 1f))
            list.addView(r, lp(MATCH, WRAP).m(0, a.dp(2), 0, a.dp(2)))
        }
        row("🏠", "מסך הבית", "חיפוש, היסטוריה, מועדפים", 0) {
            a.startActivity(Intent(a, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("screen", "home"))
        }
        ITEMS.forEachIndexed { i, it -> row(it.icon, it.title, it.sub, i) { it.go(a) } }
        list.addView(a.divider(), lp(MATCH, 1).m(0, a.dp(8), 0, a.dp(8)))
        row("⟳", "בדיקת עדכונים", "גרסה " + (try { a.packageManager.getPackageInfo(a.packageName, 0).versionName } catch (e: Exception) { "" }), 5) { Updater.check(a, false) }
        row("❓", "עזרה ואודות", "המדריך המלא, הסרטון והאתר", 1) { help(a) }
        panel.addView(ScrollView(a).apply { addView(list) }, lp(MATCH, 0, 1f))
        panel.addView(a.label("פותח על ידי שניאור וילהלם · לתועלת הרבים", 11.5f, Col.SUB).apply {
            gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER; setPadding(0, a.dp(8), 0, 0)
        }, lp(MATCH, WRAP))

        d.setContentView(panel)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.WHITE))
            val w = (a.resources.displayMetrics.widthPixels * 0.84f).toInt().coerceAtMost(a.dp(420))
            setLayout(w, WindowManager.LayoutParams.MATCH_PARENT)
            setGravity(Gravity.RIGHT or Gravity.TOP)
            setWindowAnimations(android.R.style.Animation_Dialog)
        }
        d.show()
    }

    const val SITE = "https://sw0555041250-code.github.io/Search-Plus-/"
    const val GUIDE = "https://github.com/sw0555041250-code/Search-Plus-/releases/download/media/SearchPlus-Guide.pdf"
    const val VIDEO = "https://github.com/sw0555041250-code/Search-Plus-/releases/download/media/SearchPlus-Video.mp4"

    fun help(a: Activity) {
        fun web(u: String) { if (!a.safeStart(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(u)))) a.toast(u) }
        Sheet.actions(a, "עזרה ואודות", listOf(
            "📄 המדריך המלא (PDF)" to { web(GUIDE) },
            "🎬 סרטון הסבר" to { web(VIDEO) },
            "🌐 האתר של חיפוש פלוס" to { web(SITE) },
            "✉ יצירת קשר עם המפתח" to { web("mailto:sw0555041250@gmail.com?subject=" + android.net.Uri.encode("חיפוש פלוס")) },
        ))
    }

    /** The "☰" button for screen headers. */
    fun button(a: Activity) = a.label("☰", 22f, Col.ACC, true).apply {
        setPadding(a.dp(10), a.dp(4), a.dp(10), a.dp(4))
        background = a.pressBg(Color.TRANSPARENT, Col.ACC_LIGHT, 14)
        contentDescription = "תפריט"
        setOnClickListener { open(a) }
    }
}
