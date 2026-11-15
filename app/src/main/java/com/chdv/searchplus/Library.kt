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
        if (dir.startsWith(Cloud.ROOT)) return dir.split('/').filter { it.isNotEmpty() }
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
    /** Known PDF readers, in the order they are offered (ReadEra first). */
    private val READERS = listOf("org.readera", "org.readera.premium", "com.onyx.kreader", "com.onyx.neoreader", "com.flyersoft.moonreaderp",
        "com.flyersoft.moonreader", "com.adobe.reader", "com.xodo.pdf.reader", "com.foobnix.pdf.reader", "com.google.android.apps.docs",
        "com.microsoft.office.officehubrow", "com.mobisystems.office", "com.ebooks.ebookreader")

    fun readEraInstalled(c: Context): Boolean = listOf("org.readera", "org.readera.premium").any {
        try { c.packageManager.getPackageInfo(it, 0); true } catch (e: Exception) { false }
    }

    /** ReadEra's page in the app store (or its site, where there is no store). */
    fun installReadEra(a: Activity) {
        if (!a.safeStart(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=org.readera"))))
            a.safeStart(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=org.readera")))
    }

    /**
     * The apps that really open PDF files: those that declare PDF, plus known readers that are installed
     * (ReadEra first). Apps that only take "any file" (gallery, installer, messages…) only when nothing else exists.
     */
    fun pdfApps(c: Context): List<android.content.pm.ResolveInfo> {
        val pm = c.packageManager
        fun query(i: Intent) = try { pm.queryIntentActivities(i, android.content.pm.PackageManager.MATCH_ALL) } catch (e: Exception) { emptyList() }
        val uris = listOf(Uri.parse("content://x/y.pdf"), Uri.parse("file:///sdcard/y.pdf"))
        val seen = LinkedHashMap<String, android.content.pm.ResolveInfo>()
        fun add(list: List<android.content.pm.ResolveInfo>) {
            for (r in list) {
                val a = r.activityInfo ?: continue
                if (a.packageName == c.packageName) continue
                if (a.packageName !in seen) seen[a.packageName] = r
            }
        }
        for (u in uris) add(query(Intent(Intent.ACTION_VIEW).setDataAndType(u, "application/pdf")))
        // known readers that are installed but declare PDF differently (by file name, any type…)
        for (pkg in READERS) if (pkg !in seen) {
            val r = uris.flatMap { u -> listOf(Intent(Intent.ACTION_VIEW).setDataAndType(u, "*/*"), Intent(Intent.ACTION_VIEW).setData(u),
                Intent(Intent.ACTION_VIEW).setDataAndType(u, "application/*")).flatMap { query(it.setPackage(pkg)) } }
            add(r)
        }
        if (seen.isEmpty()) for (u in uris) for (t in listOf("application/*", "*/*", null))
            add(query(Intent(Intent.ACTION_VIEW).apply { if (t == null) data = u else setDataAndType(u, t) }))
        val rank = { r: android.content.pm.ResolveInfo -> READERS.indexOf(r.activityInfo.packageName).let { if (it < 0) 99 else it } }
        return seen.values.sortedWith(compareBy<android.content.pm.ResolveInfo> { rank(it) }.thenBy { it.loadLabel(pm).toString() })
    }

    /** A reader to open books with. [cls] empty = the system picks the activity inside the app. */
    class Reader(val pkg: String, val cls: String, val label: String)

    /**
     * Every app that can open the books, ReadEra first. Some readers (ReadEra on BOOX) don't answer
     * the system's question "who opens PDF?" – they are added when they are installed.
     */
    fun readers(c: Context): List<Reader> {
        val pm = c.packageManager
        val out = LinkedHashMap<String, Reader>()
        val installed = try { pm.getInstalledApplications(0) } catch (e: Exception) { emptyList() }
        // ReadEra in any edition (by its package or its name)
        installed.filter { it.packageName.startsWith("org.readera") || pm.getApplicationLabel(it).toString().contains("ReadEra", true) }
            .forEach { out[it.packageName] = Reader(it.packageName, "", pm.getApplicationLabel(it).toString()) }
        for (r in pdfApps(c)) {
            val p = r.activityInfo.packageName
            out[p] = Reader(p, r.activityInfo.name, r.loadLabel(pm).toString())
        }
        val have = installed.map { it.packageName }.toSet()
        for (p in READERS) if (p in have && p !in out) out[p] = Reader(p, "", appName(c, p))
        val rank = { r: Reader -> if (r.pkg.startsWith("org.readera")) -1 else READERS.indexOf(r.pkg).let { if (it < 0) 99 else it } }
        return out.values.sortedWith(compareBy<Reader> { rank(it) }.thenBy { it.label })
    }

    /** Opens [i] (a VIEW of a book) in that app: its activity, or the app's own choice, or its main screen. */
    fun startIn(a: Activity, i: Intent, pkg: String, cls: String): Boolean {
        i.data?.let { a.grantUriPermission(pkg, it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        if (cls.isNotEmpty() && a.safeStart(Intent(i).setClassName(pkg, cls))) return true
        if (a.safeStart(Intent(i).setPackage(pkg))) return true
        val launch = a.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return a.safeStart(Intent(i).setComponent(launch.component))
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
        if (!readEraInstalled(a)) items.add("⬇ ReadEra – לא מותקן במכשיר. להתקנה" to { installReadEra(a) })
        for (r in readers(a)) {
            items.add(r.label to {
                Prefs.put("openWith", "external"); Prefs.put("readerPkg", r.pkg); Prefs.put("readerCls", r.cls)
                Prefs.put("readerChosen", true)
                a.toast("מעכשיו ספרים ייפתחו ב-${r.label}. אפשר לשנות בהגדרות")
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
    fun openExternal(a: Activity, path: String, page: Int, terms: List<String> = emptyList(), chooser: Boolean = false, phrase: String? = null) {
        // a book in Google Drive: the other app gets the downloaded copy
        if (Cloud.isCloud(path)) { Cloud.open(a, path) { f -> openExternal(a, f.absolutePath, page, terms, chooser, phrase) }; return }
        // the app can't be told the page and the words: give it the found page, marked, as a small PDF
        // also for a place without search words (ליקוטי שיחות חלק כד עמוד 358): the app gets that page first
        if ((terms.isNotEmpty() || phrase != null || page > 1) && Prefs.b("excerpt", true)) {
            a.toast("מכין את העמוד המסומן…")
            Thread {
                val ex = Excerpt.make(a, path, page, terms, phrase)
                ui {
                    if (ex != null) openExternal(a, ex.absolutePath, 1, emptyList(), chooser)
                    else openExternal(a, path, page, emptyList(), chooser)
                }
            }.start()
            return
        }
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
            if (startIn(a, i, pkg, cls)) return
            // the chosen app was removed: ask again
            Prefs.put("readerPkg", ""); Prefs.put("readerCls", "")
        }
        val apps = readers(a)
        if (apps.isEmpty()) { if (!a.safeStart(Intent.createChooser(i, "פתיחה באמצעות"))) a.toast("לא נמצאה אפליקציה לפתיחת PDF"); return }
        Sheet.actions(a, if (chooser) "פתיחה באמצעות" else "באיזו אפליקציה לפתוח? (הבחירה תישמר – אפשר לשנות בהגדרות)", apps.map { r ->
            r.label to {
                if (!chooser) {
                    Prefs.put("readerPkg", r.pkg); Prefs.put("readerCls", r.cls)
                    a.toast("מעכשיו ספרים ייפתחו ב-${r.label}. אפשר לשנות בהגדרות")
                }
                if (!startIn(a, i, r.pkg, r.cls)) a.toast("לא ניתן לפתוח")
            }
        } + (if (readEraInstalled(a)) emptyList() else listOf("⬇ ReadEra – לא מותקן במכשיר. להתקנה" to { installReadEra(a) })) +
            listOf("עוד אפליקציות… (הרשימה של המכשיר)" to {
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
