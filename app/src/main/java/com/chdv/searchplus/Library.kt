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

    private val SKIP = setOf("Android", "LOST.DIR", ".thumbnails", "\$RECYCLE.BIN", "System Volume Information", "_כפולים")

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

    /** Apps on the device that can open a PDF. */
    /**
     * Every app on the device that can open a PDF in any way: readers (ReadEra, NeoReader…), note apps,
     * file managers. Apps register differently (PDF type, any document, any file, by file name), so all
     * the ways are asked and merged.
     */
    fun pdfApps(c: Context): List<android.content.pm.ResolveInfo> {
        val pm = c.packageManager
        val asks = ArrayList<Intent>()
        for (scheme in listOf("content://x/y.pdf", "file:///sdcard/y.pdf")) {
            for (type in listOf("application/pdf", "application/*", "*/*", null)) {
                val u = Uri.parse(scheme)
                asks.add(Intent(Intent.ACTION_VIEW).apply { if (type == null) data = u else setDataAndType(u, type) })
            }
        }
        val seen = LinkedHashMap<String, android.content.pm.ResolveInfo>()
        for (i in asks) {
            val found = try { pm.queryIntentActivities(i, android.content.pm.PackageManager.MATCH_ALL) } catch (e: Exception) { emptyList() }
            for (r in found) {
                val a = r.activityInfo ?: continue
                if (a.packageName == c.packageName) continue
                val key = a.packageName + "/" + a.name
                if (key !in seen) seen[key] = r
            }
        }
        // one entry per app (the first, most specific activity found)
        return seen.values.distinctBy { it.activityInfo.packageName }.sortedBy { it.loadLabel(pm).toString() }
    }

    fun appName(c: Context, pkg: String): String = try {
        c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }

    /**
     * The first time a book is opened: show every app that can open it (and the built-in reader),
     * and remember the choice as the default. Can be changed later in the settings.
     */
    fun chooseDefault(a: Activity, then: () -> Unit) {
        val pm = a.packageManager
        val items = ArrayList<Pair<String, () -> Unit>>()
        items.add("📖 הקורא של חיפוש פלוס – עמוד מדויק + סימון המילים" to {
            Prefs.put("openWith", "internal"); Prefs.put("readerChosen", true); then()
        })
        for (r in pdfApps(a)) {
            items.add(r.loadLabel(pm).toString() to {
                Prefs.put("openWith", "external"); Prefs.put("readerPkg", r.activityInfo.packageName); Prefs.put("readerCls", r.activityInfo.name)
                Prefs.put("readerChosen", true)
                a.toast("מעכשיו ספרים ייפתחו ב-${r.loadLabel(pm)}. אפשר לשנות בהגדרות")
                then()
            })
        }
        Sheet.actions(a, "באיזו אפליקציה לפתוח ספרים? (הבחירה תישמר)", items)
    }

    /**
     * Open a PDF in another app. With a default reader chosen in the settings it opens there directly
     * (no "open with" question). The page number is sent in the ways readers know; the searched words
     * are copied, so they can be pasted into the reader's own search to mark them.
     */
    fun openExternal(a: Activity, path: String, page: Int, terms: List<String> = emptyList(), chooser: Boolean = false) {
        val f = File(path)
        val uri = uriFor(a, f)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/pdf")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .putExtra("page", page).putExtra("PAGE", page).putExtra("pageNumber", page).putExtra("page_number", page)
            .putExtra("pageIndex", page - 1).putExtra("start_page", page).putExtra("android.intent.extra.PAGE", page)
            .putExtra("com.onyx.intent.extra.PAGE", page - 1)
        if (terms.isNotEmpty() && Prefs.b("copyTerms", true)) {
            val words = terms.joinToString(" ") { it.removeSuffix("*") }
            (a.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .setPrimaryClip(android.content.ClipData.newPlainText("search", words))
            a.toast("עמוד $page · המילים \"$words\" הועתקו – אפשר להדביק בחיפוש של הקורא")
        }
        val pkg = Prefs.s("readerPkg", ""); val cls = Prefs.s("readerCls", "")
        if (!chooser && pkg.isNotEmpty()) {
            val direct = Intent(i).setClassName(pkg, cls)
            a.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (a.safeStart(direct)) return
            // the chosen app was removed: ask again
            Prefs.put("readerPkg", ""); Prefs.put("readerCls", "")
        }
        val apps = pdfApps(a)
        if (apps.isEmpty()) { if (!a.safeStart(Intent.createChooser(i, "פתיחה באמצעות"))) a.toast("לא נמצאה אפליקציה לפתיחת PDF"); return }
        val pm = a.packageManager
        Sheet.actions(a, if (chooser) "פתיחה באמצעות" else "באיזו אפליקציה לפתוח? (הבחירה תישמר – אפשר לשנות בהגדרות)", apps.map { r ->
            r.loadLabel(pm).toString() to {
                if (!chooser) {
                    Prefs.put("readerPkg", r.activityInfo.packageName); Prefs.put("readerCls", r.activityInfo.name)
                    a.toast("מעכשיו ספרים ייפתחו ב-${r.loadLabel(pm)}. אפשר לשנות בהגדרות")
                }
                a.grantUriPermission(r.activityInfo.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (!a.safeStart(Intent(i).setClassName(r.activityInfo.packageName, r.activityInfo.name))) a.toast("לא ניתן לפתוח")
            }
        } + listOf("עוד אפליקציות… (הרשימה של המכשיר)" to {
            if (!a.safeStart(Intent.createChooser(i, "פתיחה באמצעות"))) a.toast("לא נמצאה אפליקציה")
        }))
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
