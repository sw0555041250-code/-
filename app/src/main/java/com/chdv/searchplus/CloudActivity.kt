package com.chdv.searchplus

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * Books from Google Drive (or any cloud app on the phone). The books are brought once into a library
 * folder on the device; from then on they are scanned and searched like every other book – on the
 * device, without internet. The user chooses books (or a folder, where the app allows it) in the
 * system picker, where Google Drive appears after signing in to the Drive app.
 */
class CloudActivity : BaseActivity() {
    companion object {
        private const val PICK_FILES = 51
        private const val PICK_TREE = 52

        /** The library folder for books brought from the cloud: on the storage with the most free space. */
        fun cloudDir(a: Activity): File {
            val saved = Prefs.s("cloudDir", "")
            if (saved.isNotEmpty()) return File(saved)
            val vols = Library.volumes(a).filter { it.canWrite() }
            val base = vols.maxByOrNull { it.usableSpace } ?: a.getExternalFilesDir(null)!!
            return File(base, "חיפוש פלוס – ספרים מהענן").also { Prefs.put("cloudDir", it.absolutePath) }
        }
    }

    private lateinit var info: TextView
    private lateinit var log: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val box = vbox().apply { setPadding(dp(16), dp(10), dp(16), dp(30)) }
        val head = vbox().apply {
            background = Col.gradient(Col.TILES[2].first, Col.TILES[2].second, dp(20).toFloat())
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        head.addView(label("☁ ספרים מ-Google Drive", 21f, android.graphics.Color.WHITE, true))
        head.addView(label("מחפשים בספרים שב-Drive שלך ובתיקיות ששיתפו איתך – והם נשארים בענן. רק הטקסט נשמר במכשיר (קטן), וספר נפתח בהורדה לרגע.", 14.5f, 0xEEFFFFFF.toInt()).apply { setPadding(0, dp(6), 0, 0) })
        box.addView(head, lp(MATCH, WRAP).m(0, dp(4), 0, dp(14)))

        status = vbox()
        box.addView(status, lp(MATCH, WRAP))
        box.addView(label("מתחברים פעם אחת עם חשבון Google ומאשרים קריאה. התוכנה מוצאת את כל ספרי ה-PDF – ב-Drive שלך וגם מה ששיתפו איתך – אתם בוחרים תיקיות, והן נסרקות מהענן. רק קריאה: שום דבר ב-Drive לא משתנה, ושום ספר לא נשאר במכשיר.", 13.5f, Col.SUB).apply { setPadding(dp(4), dp(4), dp(4), dp(12)) })
        box.addView(label("או להביא ספרים למכשיר (בלי חיבור חשבון):", 14f, Col.ACC, true).apply { setPadding(0, dp(4), 0, dp(2)) })
        box.addView(pill("📄 בחירת ספרים (PDF או ZIP)", false) { pickFiles() }, lp(MATCH, WRAP).m(0, dp(4), 0, dp(4)))
        box.addView(label("⭐ תיקייה שלמה מ-Drive (גם תיקייה ששיתפו איתך): באפליקציית Drive לוחצים ⋮ ליד התיקייה › \"הורדה\" – היא יורדת כקובץ ZIP. ואז כאן \"בחירת ספרים\" ובוחרים את ה-ZIP (בתיקיית ההורדות) – כל הספרים נכנסים, עם התיקיות שלהם.\n" +
            "או: נכנסים לתיקייה בחלון הבחירה, לחיצה ארוכה על ספר, ⋮ › \"בחירת הכול\" › \"בחירה\".", 13.5f, Col.SUB).apply { setPadding(dp(4), 0, dp(4), dp(8)) })
        box.addView(pill("📁 בחירת תיקייה מהמכשיר או מהכרטיס", false) { pickTree() }, lp(MATCH, WRAP).m(0, dp(4), 0, dp(10)))
        box.addView(label("איך זה עובד", 15f, Col.ACC, true).apply { setPadding(0, dp(10), 0, dp(4)) })
        box.addView(label(
            "1. לוחצים \"בחירת ספרים\". בחלון שנפתח לוחצים ☰ ובוחרים Google Drive (צריך שאפליקציית Drive תהיה מותקנת ומחוברת לחשבון).\n" +
            "2. נכנסים לתיקייה. לספרים בודדים – לחיצה ארוכה על הראשון ואז על השאר. לכל התיקייה – לחיצה ארוכה על ספר אחד, ⋮ › \"בחירת הכול\". ולוחצים \"בחירה\".\n" +
            "3. הספרים יורדים לתיקייה במכשיר, נכנסים למאגר ונסרקים. זהו – מחפשים.\n\n" +
            "ספרים ששיתפו איתך (לא ב-Drive שלך): ב\"חיבור לחשבון\" הם מופיעים לבד, בתיקייה \"משותף איתי\". בבחירה מ-Drive – אם \"משותף איתי\" לא מופיע בחלון: באפליקציית Drive, על התיקייה ששיתפו ⋮ › \"הוספת קיצור דרך ל-Drive\", ואז היא מופיעה ב\"האחסון שלי\".\n\n" +
            "למה אין \"בחירת תיקייה\" מ-Drive? אנדרואיד לא מאפשר לבחור תיקייה שלמה מ-Google Drive – רק קבצים (וגם הרבה ביחד, כמו למעלה).\n\n" +
            "\"חיבור לחשבון\" יעבוד אחרי רישום חד-פעמי של התוכנה אצל Google.", 14.5f, Col.SUB).apply { setLineSpacing(0f, 1.2f) })
        info = label("", 14f, Col.TEXT, true).apply { setPadding(0, dp(14), 0, dp(4)) }
        box.addView(info)
        log = vbox()
        box.addView(log)
        screen("☁ ספרים מהענן", ScrollView(this).apply { addView(box) })
        refresh()
    }

    private lateinit var status: LinearLayout

    private fun refresh() {
        val d = cloudDir(this)
        val n = d.walkTopDown().count { it.isFile && it.name.endsWith(".pdf", true) }
        info.text = if (n == 0) "" else "📚 $n ספרים הובאו למכשיר · ב: ${d.absolutePath}"
        // connected to Drive: what is searched, and the ways to change it
        status.removeAllViews()
        if (!Cloud.connected()) {
            status.addView(pill("🔗 חיבור לחשבון Google Drive") { DriveSync.run(this) { refresh() } }, lp(MATCH, WRAP).m(0, dp(4), 0, dp(2)))
            return
        }
        val st = label("☁ מחובר ל-Google Drive", 16f, Col.TEXT, true)
        val sub = label("…", 13.5f, Col.SUB)
        status.addView(vbox().apply {
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundBg(Col.ACC_LIGHT, dp(14).toFloat(), dp(1), Col.ACC)
            addView(st); addView(sub)
        }, lp(MATCH, WRAP).m(0, dp(4), 0, dp(6)))
        Thread {
            val all = try { Db.get().books().values.filter { Cloud.isCloud(it.path) } } catch (e: Throwable) { emptyList() }
            val done = all.count { it.status == St.DONE }
            val cache = Cloud.cacheSize(this) / 1048576
            ui {
                sub.text = "תיקיות: " + Cloud.chosen().joinToString(" · ").ifEmpty { "הכול" } +
                    "\n${all.size} ספרים מ-Drive · נסרקו $done" + (if (all.size > done) " (הסריקה ממשיכה ברקע)" else " ✓") +
                    "\nספרים שנפתחו לאחרונה שמורים זמנית: ${cache}MB (עד 300MB, נמחקים לבד)"
            }
        }.start()
        val row = hbox()
        row.addView(pill("📁 בחירת תיקיות", false) { DriveSync.run(this) { refresh() } }, lp(0, WRAP, 1f).m(0, 0, dp(4), 0))
        row.addView(pill("ניתוק", false) {
            Sheet.confirm(this, "ניתוק מ-Google Drive", "הספרים מ-Drive יוסרו מהחיפוש (ב-Drive עצמו לא משתנה דבר).", "ניתוק") {
                Prefs.put("driveOn", false); Prefs.put("driveSel", "")
                Thread {
                    try { Db.get().books().values.filter { Cloud.isCloud(it.path) }.forEach { Db.get().removeFile(it.id) } } catch (e: Throwable) {}
                    Cloud.clearCache(this)
                    ui { refresh() }
                }.start()
            }
        }, lp(0, WRAP, 1f).m(dp(4), 0, 0, 0))
        status.addView(row, lp(MATCH, WRAP).m(0, 0, 0, dp(4)))
    }

    private fun pickFiles() {
        // PDF books, or a ZIP of a whole folder (Google Drive downloads a folder as ZIP)
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/pdf", "application/zip", "application/x-zip-compressed", "application/x-zip"))
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        if (!safeStartForResult(i, PICK_FILES)) toast("לא נמצא בוחר קבצים במכשיר")
    }

    private fun pickTree() {
        if (!safeStartForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PICK_TREE)) toast("המכשיר לא מאפשר בחירת תיקייה")
    }

    private fun safeStartForResult(i: Intent, code: Int) = try { @Suppress("DEPRECATION") startActivityForResult(i, code); true } catch (e: Exception) { false }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == DriveSync.REQ) { if (resultCode == RESULT_OK) DriveSync.onResult(this, requestCode, data); return }
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            PICK_FILES -> {
                val uris = ArrayList<Uri>()
                data.clipData?.let { cd -> for (k in 0 until cd.itemCount) uris.add(cd.getItemAt(k).uri) }
                data.data?.let { if (it !in uris) uris.add(it) }
                bring(uris.map { it to "" })
            }
            PICK_TREE -> data.data?.let { tree -> Thread { val l = listTree(tree); ui { if (l.isEmpty()) Sheet.info(this, "לא נמצאו ספרים",
                "בתיקייה שנבחרה לא נמצאו קבצי PDF.\n\nאם זו תיקייה מ-Google Drive: אנדרואיד לא מראה את התוכן של תיקיות Drive בבחירת תיקייה. " +
                    "במקום זה: באפליקציית Drive (או באתר) – על התיקייה ⋮ › \"הורדה\". היא יורדת כקובץ ZIP. אחר כך כאן: \"בחירת ספרים\" ← בוחרים את קובץ ה-ZIP – וכל הספרים שבו נכנסים, עם התיקיות.") else bring(l) } }.start() }
        }
    }

    /** All PDFs under a chosen folder, with their sub-folder path (kept as categories). */
    private fun listTree(tree: Uri): List<Pair<Uri, String>> {
        val out = ArrayList<Pair<Uri, String>>()
        val root = DocumentsContract.getTreeDocumentId(tree)
        val stack = ArrayDeque<Pair<String, String>>().apply { add(root to "") }
        while (stack.isNotEmpty() && out.size < 20000) {
            val (id, rel) = stack.removeLast()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
            try {
                contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val cid = c.getString(0); val name = c.getString(1) ?: continue; val mime = c.getString(2) ?: ""
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) stack.add(cid to (if (rel.isEmpty()) name else "$rel/$name"))
                        else if (name.endsWith(".pdf", true) || mime == "application/pdf" || name.endsWith(".zip", true)) out.add(DocumentsContract.buildDocumentUriUsingTree(tree, cid) to rel)
                    }
                }
            } catch (e: Exception) {}
        }
        return out
    }

    private fun nameOf(u: Uri): String = try {
        contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (e: Exception) { null } ?: (u.lastPathSegment?.substringAfterLast('/') ?: "ספר")

    /** Download the chosen books into the cloud library folder, then add it to the library and scan. */
    private fun bring(items: List<Pair<Uri, String>>) {
        if (items.isEmpty()) return
        val st = label("מתחיל…", 15f, Col.SUB)
        val d = Sheet.make(this, vbox().apply { setPadding(dp(18), dp(18), dp(18), dp(18)); addView(label("מביא ספרים מהענן", 18f, Col.TEXT, true)); addView(st) })
        d.setCancelable(false); d.show()
        val dir = cloudDir(this)
        Thread {
            var ok = 0; var skip = 0; var fail = 0
            items.forEachIndexed { k, (u, rel) ->
                // a ZIP (a whole folder downloaded from Drive): every PDF in it, in its folders
                if (nameOf(u).endsWith(".zip", true) || (contentResolver.getType(u) ?: "").contains("zip")) {
                    val base = File(if (rel.isEmpty()) dir else File(dir, rel), nameOf(u).removeSuffix(".zip").removeSuffix(".ZIP").replace('/', '_'))
                    try {
                        java.util.zip.ZipInputStream(contentResolver.openInputStream(u)!!.buffered(1 shl 16)).use { z ->
                            while (true) {
                                val e = z.nextEntry ?: break
                                if (e.isDirectory || !e.name.endsWith(".pdf", true)) continue
                                val parts = e.name.split('/').filter { it.isNotBlank() && it != ".." }
                                val dest = File(base, parts.joinToString("/"))
                                ui { st.text = "${k + 1} / ${items.size} · ZIP\n${dest.name}" }
                                if (dest.exists() && dest.length() > 0) { skip++; continue }
                                dest.parentFile?.mkdirs()
                                val tmp = File(dest.parentFile, dest.name + ".part")
                                tmp.outputStream().use { o -> z.copyTo(o, 1 shl 16) }
                                if (tmp.renameTo(dest)) ok++ else fail++
                            }
                        }
                    } catch (e: Exception) { fail++ }
                    return@forEachIndexed
                }
                val name = nameOf(u).let { if (it.endsWith(".pdf", true)) it else "$it.pdf" }.replace('/', '_')
                val dest = File(if (rel.isEmpty()) dir else File(dir, rel), name)
                ui { st.text = "${k + 1} / ${items.size}\n$name" }
                try {
                    dest.parentFile?.mkdirs()
                    if (dest.exists() && dest.length() > 0) { skip++; return@forEachIndexed }
                    val tmp = File(dest.parentFile, dest.name + ".part")
                    contentResolver.openInputStream(u)!!.use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
                    if (!tmp.renameTo(dest)) throw java.io.IOException("rename")
                    ok++
                } catch (e: Exception) { fail++ }
            }
            ui {
                d.dismiss()
                if (Library.roots().none { dir.absolutePath == it || dir.absolutePath.startsWith("$it/") }) Library.setRoots(Library.roots() + dir.absolutePath)
                Indexer.start(this, user = true)
                refresh()
                Sheet.info(this, "הספרים הובאו ✓", "הובאו $ok ספרים" + (if (skip > 0) " · $skip כבר היו" else "") + (if (fail > 0) " · $fail לא ירדו (בדקו אינטרנט)" else "") +
                    ".\n\nהם נכנסו למאגר ונסרקים עכשיו ברקע – אפשר לחפש בהם בעוד רגע.")
            }
        }.start()
    }
}
