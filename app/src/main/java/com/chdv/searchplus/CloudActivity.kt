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
        head.addView(label("יש לכם מאגר ספרים בדרייב? מביאים אותו פעם אחת למכשיר – ומאז מחפשים בו כמו בכל ספר, גם בלי אינטרנט.", 14.5f, 0xEEFFFFFF.toInt()).apply { setPadding(0, dp(6), 0, 0) })
        box.addView(head, lp(MATCH, WRAP).m(0, dp(4), 0, dp(14)))

        box.addView(pill("🔗 חיבור לחשבון Google Drive") { DriveSync.run(this, cloudDir(this)) { refresh() } }, lp(MATCH, WRAP).m(0, dp(4), 0, dp(2)))
        box.addView(label("מתחברים פעם אחת עם חשבון Google ומאשרים קריאה. התוכנה מוצאת את כל ספרי ה-PDF ב-Drive, אתם בוחרים תיקיות – והם יורדים למכשיר ונסרקים. רק קריאה: שום דבר ב-Drive לא משתנה.", 13.5f, Col.SUB).apply { setPadding(dp(4), 0, dp(4), dp(12)) })
        box.addView(label("או בלי חיבור חשבון (עובד תמיד):", 14f, Col.ACC, true).apply { setPadding(0, dp(4), 0, dp(2)) })
        box.addView(pill("📄 בחירת ספרים מ-Drive", false) { pickFiles() }, lp(MATCH, WRAP).m(0, dp(4), 0, dp(4)))
        box.addView(label("גם תיקייה שלמה: נכנסים לתיקייה, לוחצים לחיצה ארוכה על ספר אחד, ואז ⋮ › \"בחירת הכול\" › \"בחירה\".", 13.5f, Col.SUB).apply { setPadding(dp(4), 0, dp(4), dp(8)) })
        box.addView(pill("📁 בחירת תיקייה מהמכשיר או מהכרטיס", false) { pickTree() }, lp(MATCH, WRAP).m(0, dp(4), 0, dp(10)))
        box.addView(label("איך זה עובד", 15f, Col.ACC, true).apply { setPadding(0, dp(10), 0, dp(4)) })
        box.addView(label(
            "1. לוחצים \"בחירת ספרים מ-Drive\". בחלון שנפתח לוחצים ☰ ובוחרים Google Drive (צריך שאפליקציית Drive תהיה מותקנת ומחוברת לחשבון).\n" +
            "2. נכנסים לתיקייה. לספרים בודדים – לחיצה ארוכה על הראשון ואז על השאר. לכל התיקייה – לחיצה ארוכה על ספר אחד, ⋮ › \"בחירת הכול\". ולוחצים \"בחירה\".\n" +
            "3. הספרים יורדים לתיקייה במכשיר, נכנסים למאגר ונסרקים. זהו – מחפשים.\n\n" +
            "למה אין \"בחירת תיקייה\" מ-Drive? אנדרואיד לא מאפשר לבחור תיקייה שלמה מ-Google Drive – רק קבצים (וגם הרבה ביחד, כמו למעלה).\n\n" +
            "\"חיבור לחשבון\" יעבוד אחרי רישום חד-פעמי של התוכנה אצל Google.", 14.5f, Col.SUB).apply { setLineSpacing(0f, 1.2f) })
        info = label("", 14f, Col.TEXT, true).apply { setPadding(0, dp(14), 0, dp(4)) }
        box.addView(info)
        log = vbox()
        box.addView(log)
        screen("☁ ספרים מהענן", ScrollView(this).apply { addView(box) })
        refresh()
    }

    private fun refresh() {
        val d = cloudDir(this)
        val n = d.walkTopDown().count { it.isFile && it.name.endsWith(".pdf", true) }
        info.text = if (n == 0) "עוד לא הובאו ספרים." else "📚 $n ספרים הובאו מהענן · נשמרים ב: ${d.absolutePath}"
    }

    private fun pickFiles() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/pdf")
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
            PICK_TREE -> data.data?.let { tree -> Thread { val l = listTree(tree); ui { if (l.isEmpty()) Sheet.info(this, "לא נמצאו ספרים", "בתיקייה שנבחרה אין קבצי PDF.") else bring(l) } }.start() }
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
                        else if (name.endsWith(".pdf", true) || mime == "application/pdf") out.add(DocumentsContract.buildDocumentUriUsingTree(tree, cid) to rel)
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
