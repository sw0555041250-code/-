package com.chdv.searchplus

import android.app.Activity
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import java.io.File

/**
 * Books that stay in Google Drive (the user's own, and folders shared with them). Nothing is kept
 * on the device: to scan a book it is downloaded for a moment, its text goes into the index, and the
 * file is deleted. To read a book it is downloaded when opened; the last few stay in a small cache
 * that cleans itself (at most [CACHE_MB]).
 *
 * In the database such a book has the path "drive:<fileId>/<file name>" and the folder
 * "☁ Google Drive/<its folders in Drive>", so search, categories and names work as for any book.
 */
object Cloud {
    private const val PREFIX = "drive:"
    const val ROOT = "☁ Google Drive"
    private const val CACHE_MB = 300L
    private const val SCOPE = "https://www.googleapis.com/auth/drive.readonly"

    fun isCloud(path: String) = path.startsWith(PREFIX)
    fun idOf(path: String) = path.removePrefix(PREFIX).substringBefore('/')
    fun pathOf(id: String, name: String) = PREFIX + id + "/" + name.replace('/', '_')

    /** Connected = the user chose Drive folders to search in. */
    fun connected() = Prefs.b("driveOn", false)
    fun chosen(): List<String> = Prefs.list("driveSel")

    // ---------- the key to Drive ----------

    @Volatile private var token: String? = null
    @Volatile private var tokenAt = 0L

    /** Remember a key the user just gave (after Google's own permission screen). */
    fun keep(t: String) { token = t; tokenAt = System.currentTimeMillis() }

    /**
     * A key to read Drive, without asking the user again (they allowed it once). Null when Google
     * wants the user to allow it again – then the next visit to the Drive screen asks.
     * Not on the screen's thread.
     */
    fun token(c: Context): String? {
        token?.let { if (System.currentTimeMillis() - tokenAt < 40 * 60_000L) return it }
        return try {
            val req = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()
            val r = com.google.android.gms.tasks.Tasks.await(Identity.getAuthorizationClient(c).authorize(req), 30, java.util.concurrent.TimeUnit.SECONDS)
            if (r.hasResolution()) null else r.accessToken?.also { keep(it) }
        } catch (e: Throwable) { null }
    }

    private fun freshToken(c: Context): String? { token = null; return token(c) }

    // ---------- the list of books ----------

    /**
     * Scan process: bring the database in line with Drive – new books in the chosen folders are added
     * (to be scanned), changed ones are scanned again, books no longer there or no longer chosen leave.
     * Returns false when Drive could not be read (no key / no internet) – then nothing is removed.
     */
    fun sync(c: Context, db: Db): Boolean {
        val t = token(c) ?: return false
        val items = try { DriveSync.listAll(t) { Progress.current = it } } catch (e: Throwable) { return false }
        val sel = chosen().toSet()
        val want = items.filter { sel.isEmpty() || DriveSync.groupOf(it) in sel }
        val known = db.books().values.filter { isCloud(it.path) }.associateBy { idOf(it.path) }
        val keep = HashSet<String>()
        for (it in want) {
            keep.add(it.id)
            val mtime = try { java.time.Instant.parse(it.modified).toEpochMilli() } catch (e: Exception) { 0L }
            val dir = if (it.folder.isEmpty()) ROOT else "$ROOT/${it.folder}"
            val b = known[it.id]
            if (b == null) db.addCloud(pathOf(it.id, it.name), dir, it.name, it.size, mtime)
            else if (b.mtime != mtime || b.dir != dir) db.updateCloud(b.id, dir, it.size, mtime, b.mtime != mtime)
        }
        known.filter { it.key !in keep }.values.forEach { db.removeFile(it.id) }
        return true
    }

    // ---------- the files themselves ----------

    private fun cacheDir(c: Context) = File(c.cacheDir, "drive").apply { mkdirs() }

    /** The book on the device if it is in the cache (no download). */
    fun cached(c: Context, path: String): File? = File(cacheDir(c), idOf(path) + ".pdf").takeIf { it.length() > 0 }

    /**
     * The book as a file to open: from the cache, or downloaded now (not on the screen's thread).
     * [progress] gets the percent.
     */
    fun fetch(c: Context, path: String, progress: (Int) -> Unit = {}): File {
        cached(c, path)?.let { it.setLastModified(System.currentTimeMillis()); return it }
        val dest = File(cacheDir(c), idOf(path) + ".pdf")
        download(c, idOf(path), dest, progress)
        trim(c)
        return dest
    }

    /** Scan process: the book for a moment – [use] it, and it is deleted (unless it was in the cache). */
    fun <T> withTemp(c: Context, path: String, use: (File) -> T): T {
        cached(c, path)?.let { return use(it) }
        val tmp = File(File(c.cacheDir, "drive-scan").apply { mkdirs() }, idOf(path) + ".pdf")
        try {
            download(c, idOf(path), tmp) {}
            return use(tmp)
        } finally { tmp.delete(); File(tmp.parentFile, tmp.name + ".part").delete() }
    }

    /** Scan process (OCR): the book on the device until [dropTemp]. */
    fun tempCopy(c: Context, path: String): File {
        cached(c, path)?.let { return it }
        val tmp = File(File(c.cacheDir, "drive-scan").apply { mkdirs() }, idOf(path) + ".pdf")
        if (tmp.length() <= 0) download(c, idOf(path), tmp) {}
        return tmp
    }

    fun dropTemp(f: File?) { if (f != null && f.parentFile?.name == "drive-scan") f.delete() }

    private fun download(c: Context, id: String, dest: File, progress: (Int) -> Unit) {
        val t = token(c) ?: throw IllegalStateException("צריך לאשר שוב את החיבור ל-Google Drive (מסך „ספרים מ-Google Drive”)")
        try { DriveSync.downloadId(t, id, dest, progress) }
        catch (e: java.io.IOException) {
            // the key ran out: one more try with a new one
            if (e.message?.contains("401") == true) DriveSync.downloadId(freshToken(c) ?: throw e, id, dest, progress) else throw e
        }
    }

    /** The cache never grows beyond [CACHE_MB]: the books opened longest ago go first. */
    private fun trim(c: Context) {
        val files = cacheDir(c).listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: return
        var sum = 0L
        for (f in files) { sum += f.length(); if (sum > CACHE_MB * 1024 * 1024) f.delete() }
    }

    fun cacheSize(c: Context): Long = cacheDir(c).listFiles()?.sumOf { it.length() } ?: 0L
    fun clearCache(c: Context) { cacheDir(c).listFiles()?.forEach { it.delete() } }

    /**
     * Screens: open a Drive book – downloads it first (with a small progress window), then [then]
     * gets the file on the device.
     */
    fun open(a: Activity, path: String, then: (File) -> Unit) {
        cached(a, path)?.let { then(it); return }
        val st = a.label("מוריד את הספר מ-Google Drive…", 15f, Col.SUB)
        val d = Sheet.make(a, a.vbox().apply { setPadding(a.dp(18), a.dp(18), a.dp(18), a.dp(18)); addView(a.label("☁ " + File(path).nameWithoutExtension, 17f, Col.TEXT, true)); addView(st) })
        d.show()
        Thread {
            val f = try { fetch(a, path) { p -> ui { st.text = "מוריד את הספר מ-Google Drive… $p%" } } } catch (e: Throwable) {
                ui { try { d.dismiss() } catch (x: Throwable) {}; Sheet.info(a, "לא ניתן להוריד את הספר", (e.message ?: e.javaClass.simpleName) + "\n\nבדקו שיש אינטרנט.") }
                return@Thread
            }
            ui { try { d.dismiss() } catch (x: Throwable) {}; if (!a.isFinishing) then(f) }
        }.start()
    }
}
