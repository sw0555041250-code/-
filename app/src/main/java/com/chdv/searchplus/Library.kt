package com.chdv.searchplus

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/** Where the books are, how folders become categories, and file access permission. */
object Library {

    fun storageRoot(): File = Environment.getExternalStorageDirectory()

    /** All storage volumes (internal + SD card). */
    fun volumes(c: Context): List<File> {
        val out = ArrayList<File>()
        out.add(storageRoot())
        c.getExternalFilesDirs(null).filterNotNull().forEach { f ->
            val p = f.absolutePath
            val i = p.indexOf("/Android/")
            if (i > 0) {
                val v = File(p.substring(0, i))
                if (out.none { it.absolutePath == v.absolutePath }) out.add(v)
            }
        }
        return out
    }

    /** Library folders chosen by the user. Empty list = not chosen yet. */
    fun roots(): List<String> = Prefs.list("roots")

    fun setRoots(l: List<String>) = Prefs.putList("roots", l.distinct())

    fun rootOf(path: String): String? = roots().filter { path == it || path.startsWith("$it/") }.maxByOrNull { it.length }

    fun rootName(root: String): String = if (root == storageRoot().absolutePath) "זיכרון פנימי" else File(root).name

    /**
     * Category path of a folder: the folders under the library root. With one library folder the
     * first level is its sub folders (הלכה, חסידות חב"ד ...). With several library folders the
     * first level is the library folder itself.
     */
    fun categoryOf(dir: String): List<String> {
        val root = rootOf(dir) ?: return listOf(File(dir).name)
        val rel = dir.removePrefix(root).trim('/')
        val parts = if (rel.isEmpty()) emptyList() else rel.split('/')
        return if (roots().size > 1) listOf(rootName(root)) + parts else parts
    }

    fun hasAccess(c: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(c, android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun askAccess(a: Activity) {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                a.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + a.packageName)))
            } catch (e: Exception) {
                a.safeStart(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            ActivityCompat.requestPermissions(a, arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 7)
        }
    }

    private val SKIP = setOf("Android", "LOST.DIR", ".thumbnails", "\$RECYCLE.BIN", "System Volume Information")

    /** Every PDF under the library folders. */
    fun walk(onFile: (File) -> Unit, cancelled: () -> Boolean) {
        val seen = HashSet<String>()
        for (r in roots()) {
            val stack = ArrayDeque<File>()
            stack.add(File(r))
            while (stack.isNotEmpty()) {
                if (cancelled()) return
                val d = stack.removeLast()
                val canon = try { d.canonicalPath } catch (e: Exception) { d.absolutePath }
                if (!seen.add(canon)) continue
                val kids = d.listFiles() ?: continue
                for (k in kids) {
                    if (k.name.startsWith(".")) continue
                    if (k.isDirectory) {
                        if (k.name !in SKIP) stack.add(k)
                    } else if (k.name.endsWith(".pdf", true) && k.length() > 0) onFile(k)
                }
            }
        }
    }

    fun uriFor(c: Context, f: File): Uri = FileProvider.getUriForFile(c, c.packageName + ".files", f)

    /** Open a PDF in another app (the device's default reader). */
    fun openExternal(a: Activity, path: String, page: Int) {
        val f = File(path)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriFor(a, f), "application/pdf")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .putExtra("page", page)
            .putExtra("PAGE", page)
            .putExtra("pageNumber", page)
        val chooser = Intent.createChooser(i, "פתיחה באמצעות")
        if (!a.safeStart(chooser)) a.toast("לא נמצאה אפליקציה לפתיחת PDF")
    }

    // ---------- favourites, history, recent books ----------

    fun favorites(): List<String> = Prefs.list("favs")
    fun isFavorite(key: String) = key in favorites()
    fun toggleFavorite(key: String): Boolean {
        val l = Prefs.list("favs")
        val on = if (key in l) { l.remove(key); false } else { l.add(0, key); true }
        Prefs.putList("favs", l)
        return on
    }

    fun history(): List<String> = Prefs.list("history")
    fun addHistory(q: String) {
        if (q.isBlank()) return
        val l = Prefs.list("history"); l.remove(q); l.add(0, q)
        Prefs.putList("history", l.take(40))
    }

    fun recent(): List<String> = Prefs.list("recent")
    fun addRecent(path: String, page: Int) {
        val l = Prefs.list("recent").filterNot { it.substringBefore('|') == path }.toMutableList()
        l.add(0, "$path|$page")
        Prefs.putList("recent", l.take(30))
    }
    fun lastPage(path: String): Int = recent().firstOrNull { it.substringBefore('|') == path }?.substringAfter('|')?.toIntOrNull() ?: 1
}
