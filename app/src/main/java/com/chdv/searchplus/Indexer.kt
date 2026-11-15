package com.chdv.searchplus

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import com.googlecode.tesseract.android.TessBaseAPI
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.FileOutputStream
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.URL

/** Live progress, read by the screens. */
/**
 * The scan's progress. The scan runs in its own process (":scan") – if a damaged book or lack of
 * memory brings it down, the screen stays open and the scan comes back by itself. The scan process
 * writes its state to a small file every second; the app's screens read it from there.
 */
object Progress {
    /** True inside the scan process (set in App.onCreate). */
    @Volatile var inScan = false

    @Volatile private var _running = false
    @Volatile private var _phaseStart = 0L
    @Volatile private var _phase = ""
    @Volatile private var _done = 0
    @Volatile private var _total = 0
    @Volatile private var _current = ""
    @Volatile private var _cancel = false
    @Volatile private var _lastFinished = 0L

    var running: Boolean get() = if (inScan) _running else snap().running; set(v) { _running = v }
    val phaseStart: Long get() = if (inScan) _phaseStart else snap().phaseStart
    var phase: String
        get() = if (inScan) _phase else snap().phase
        set(v) { if (v != _phase) _phaseStart = System.currentTimeMillis(); _phase = v }
    var done: Int get() = if (inScan) _done else snap().done; set(v) { _done = v }
    var total: Int get() = if (inScan) _total else snap().total; set(v) { _total = v }
    var current: String get() = if (inScan) _current else snap().current; set(v) { _current = v }
    var lastFinished: Long get() = if (inScan) _lastFinished else snap().lastFinished; set(v) { _lastFinished = v }
    /** Stop the scan. From a screen: a flag file the scan process picks up within a second. */
    var cancel: Boolean
        get() = _cancel
        set(v) {
            if (inScan) _cancel = v
            else if (v) try { File(App.ctx.filesDir, "scan.cancel").writeText("1") } catch (e: Throwable) {}
        }
    /** A single book the user asked to OCR now. */
    @Volatile var ocrFile: Long? = null
    /** Kept for the background work's pauses (nothing pauses it now). */
    @Volatile var paused = false

    fun waitWhilePaused() { var n = 0; while (paused && !cancel && n < 1200) { Thread.sleep(100); n++ } }

    fun text(): String = if (!running) "" else buildString {
        val total = total; val done = done
        append(phase)
        if (total > 0) append("  $done / $total")
        val el = System.currentTimeMillis() - phaseStart
        if (total > 0 && done > 0 && done < total && el > 60_000) {
            val left = el / done * (total - done) / 60_000
            append(if (left >= 60) "  · עוד כ-${left / 60} שעות ${left % 60} דק׳" else "  · עוד כ-${left.coerceAtLeast(1)} דק׳")
        }
        val cur = current
        if (cur.isNotEmpty()) append("\n").append(cur)
    }

    // ---- between the processes ----
    private class Snap(val running: Boolean, val phaseStart: Long, val phase: String, val done: Int, val total: Int, val current: String, val lastFinished: Long)
    @Volatile private var cached = Snap(false, 0, "", 0, 0, "", 0)
    @Volatile private var readAt = 0L
    private fun stateFile() = File(App.ctx.filesDir, "scan.state")

    private fun snap(): Snap {
        val now = System.currentTimeMillis()
        if (now - readAt < 700) return cached
        readAt = now
        cached = try {
            val f = stateFile()
            if (!f.exists()) Snap(false, 0, "", 0, 0, "", 0) else {
                val m = f.readText().lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
                // the scan writes every second: an old file = the scan process is gone
                val alive = now - (m["beat"]?.toLongOrNull() ?: 0L) < 6000
                Snap(alive && m["running"] == "1", m["phaseStart"]?.toLongOrNull() ?: 0L, m["phase"] ?: "", m["done"]?.toIntOrNull() ?: 0,
                    m["total"]?.toIntOrNull() ?: 0, (m["current"] ?: "").replace("\\n", "\n"), m["lastFinished"]?.toLongOrNull() ?: 0L)
            }
        } catch (e: Throwable) { cached }
        return cached
    }

    /** Scan process: write the state for the screens, and pick up a stop request. */
    fun publish(c: Context) {
        try {
            val cf = File(c.filesDir, "scan.cancel")
            if (cf.exists()) { cf.delete(); _cancel = true }
            val t = "running=${if (_running) 1 else 0}\nbeat=${System.currentTimeMillis()}\nphaseStart=$_phaseStart\nphase=$_phase\ndone=$_done\ntotal=$_total\n" +
                "current=${_current.replace("\n", "\\n")}\nlastFinished=$_lastFinished\n" +
                "firstDone=${if (Prefs.b("firstScanDone", false)) 1 else 0}\nsomeRead=${if (Prefs.b("someRead", false)) 1 else 0}\n"
            val tmp = File(c.filesDir, "scan.state.tmp")
            tmp.writeText(t)
            tmp.renameTo(File(c.filesDir, "scan.state"))
        } catch (e: Throwable) {}
    }
}

/** Reads every PDF page by page and stores the words in the index. */
object Indexer {
    private var pdfboxReady = false

    fun initPdfBox(c: Context) {
        if (!pdfboxReady) { PDFBoxResourceLoader.init(c.applicationContext); pdfboxReady = true }
    }

    /** Collects the text of each page in one pass over the document. */
    private class PageStripper(val n: Int) : PDFTextStripper() {
        val pages = arrayOfNulls<String>(n)
        val buf = StringWriter()
        override fun startPage(page: PDPage?) {
            buf.buffer.setLength(0)
            super.startPage(page)
        }
        override fun endPage(page: PDPage?) {
            super.endPage(page)
            val i = currentPageNo - 1
            if (i in 0 until n) pages[i] = buf.toString()
            buf.buffer.setLength(0)
        }
    }

    fun loadDoc(f: File): PDDocument = PDDocument.load(f, "", MemoryUsageSetting.setupTempFileOnly())

    /** Text of every page: PDFium first (fast), PDFBox when PDFium can't read the file. */
    fun extractFast(c: Context, f: File): List<String?> {
        val raw = PdfText.pages(c, f) { Progress.cancel }
        if (raw != null) return raw.map { r -> r?.let { prepare(it) } }
        if (f.length() > 80L * 1024 * 1024) throw IllegalStateException("קובץ גדול מדי לקריאה במנוע הגיבוי")
        return extract(f)
    }

    /** Raw text of every page (null where nothing could be read). */
    fun extract(f: File): List<String?> {
        loadDoc(f).use { doc ->
            val n = doc.numberOfPages
            val st = PageStripper(n)
            st.sortByPosition = Prefs.b("sortByPosition", false)
            // much faster on big books: skip the search for duplicated overlapping letters and article beads
            st.suppressDuplicateOverlappingText = false
            st.setShouldSeparateByBeads(false)
            st.lineSeparator = "\n"
            st.wordSeparator = " "
            st.writeText(doc, st.buf)
            return st.pages.map { raw -> raw?.let { prepare(it) } }
        }
    }

    /** Repair + clean the text of one page; null when there is no usable text. */
    fun prepare(raw: String): String? {
        if (raw.isBlank()) return null
        val fixed = Heb.repairPage(raw).text
        val t = Heb.clean(fixed)
        if (Heb.letterCount(t) < 12 || Heb.looksBroken(t)) return null
        return t
    }

    fun start(c: Context, ocrFile: Long = 0L) {
        if (!Library.hasAccess(c) || Library.roots().isEmpty()) return
        val i = Intent(c, IndexService::class.java)
        if (ocrFile > 0) i.putExtra("ocrFile", ocrFile)
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
    }

    /** The whole job: scan folders, read new books, OCR, meaning index. */
    fun runAll(c: Context) {
        val db = Db.get()
        initPdfBox(c)
        Progress.cancel = false
        // 1. find files
        Progress.phase = "סורק את התיקיות…"; Progress.done = 0; Progress.total = 0; Progress.current = ""
        val known = HashMap<String, Book>()
        db.books().values.forEach { known[it.path] = it }
        val found = HashSet<String>()
        Library.walk({ f ->
            val p = f.absolutePath
            found.add(p)
            val b = known[p]
            if (b == null) db.addFile(f)
            else if (b.size != f.length() || b.mtime != f.lastModified()) db.markChanged(b.id, f)
            Progress.done = found.size
        }, { Progress.cancel })
        if (Progress.cancel) return
        // books that were deleted or are outside the library folders now
        known.values.filter { it.path !in found }.forEach { db.removeFile(it.id) }

        // a book that was being read when the device closed the app (out of memory): skip it, so
        // the scan is not stopped by the same book again and again (it can be read later from its menu)
        Prefs.l("scanFile", 0L).takeIf { it > 0 }?.let { bad ->
            db.markError(bad, "כבד מדי למכשיר – המכשיר סגר את התוכנה בזמן הקריאה שלו")
            Prefs.commit("scanFile", 0L)
        }

        // 2. read text
        // small books first: within minutes most of the library is searchable; big ones follow
        val pending = db.pendingFiles().sortedBy { (_, p) -> File(p).length() }
        Progress.phase = "קורא ספרים"; Progress.total = pending.size; Progress.done = 0
        for ((id, path) in pending) {
            if (Progress.cancel) return
            Progress.waitWhilePaused()
            Progress.current = File(path).name
            // written to disk at once: if the app is killed now, the next run knows which book did it
            Prefs.commit("scanFile", id)
            try {
                val texts = extractFast(c, File(path))
                db.saveBook(id, texts)
                if (Progress.done == 20) Prefs.put("someRead", true)
            } catch (e: OutOfMemoryError) {
                System.gc()
                db.markError(id, "הקובץ גדול מדי לזיכרון המכשיר")
            } catch (e: Throwable) {
                db.markError(id, e.javaClass.simpleName + ": " + (e.message ?: ""))
            }
            Prefs.commit("scanFile", 0L)
            Progress.done++
            // memory back to the system between books (a 2GB device has little to spare)
            if (Progress.done % 25 == 0) System.gc()
        }

        // the library has been read once – from now on only new or changed books are read
        if (!Progress.cancel) Prefs.put("firstScanDone", true)

        if (Prefs.l("semBusy", 0L) > 0) {
            Prefs.put("semBusy", 0L); Prefs.put("semantic", false)
            Semantic.lastError = "בניית אינדקס המשמעות נעצרה כי המכשיר סגר אותה (כנראה חוסר זיכרון). אפשר להפעיל שוב מההגדרות."
        }
        // 3. meaning index (before OCR: OCR of a big library can take days, and must not hold this back)
        if (Prefs.b("semantic", false) && Semantic.ready(c)) {
            try { Semantic.indexPending(c) } catch (e: Throwable) { Semantic.lastError = e.message ?: e.javaClass.simpleName }
        }
        if (Progress.cancel) return

        // 4. OCR of scanned pages
        if (Prefs.b("ocrAuto", true) && Ocr.ready(c)) Ocr.runPending(c, null)
        if (Progress.cancel) return

        // 5. pages that OCR just made readable also go into the meaning index
        if (Prefs.b("semantic", false) && Semantic.ready(c)) {
            try { Semantic.indexPending(c) } catch (e: Throwable) { Semantic.lastError = e.message ?: e.javaClass.simpleName }
        }
    }

    /** Re-read one book now (from the book menu). */
    fun reindex(c: Context, b: Book) {
        val f = File(b.path)
        Db.get().markChanged(b.id, f)
        start(c)
    }
}

/** OCR (text recognition) for scanned pages, with Tesseract and its Hebrew model. */
object Ocr {
    private const val URL_HEB = "https://github.com/tesseract-ocr/tessdata_fast/raw/main/heb.traineddata"
    private const val URL_HEB_BEST = "https://github.com/tesseract-ocr/tessdata_best/raw/main/heb.traineddata"

    fun dir(c: Context) = File(c.filesDir, "tess")
    private fun model(c: Context) = File(dir(c), "tessdata/heb.traineddata")
    /** The Hebrew model ships inside the APK (assets/tessdata); copy it out on first use. */
    fun ready(c: Context): Boolean {
        if (model(c).length() > 100_000) return true
        return try {
            model(c).parentFile?.mkdirs()
            c.assets.open("tessdata/heb.traineddata").use { i -> FileOutputStream(model(c)).use { o -> i.copyTo(o) } }
            model(c).length() > 100_000
        } catch (e: Exception) { model(c).delete(); false }
    }

    fun download(c: Context, best: Boolean, progress: (Int) -> Unit) {
        val f = model(c)
        f.parentFile?.mkdirs()
        Net.download(if (best) URL_HEB_BEST else URL_HEB, f, progress)
    }

    fun delete(c: Context) { dir(c).deleteRecursively() }

    /** OCR all pages that have no text (or only those of one book). */
    fun runPending(c: Context, onlyFile: Long?) {
        val db = Db.get()
        val api = TessBaseAPI()
        if (!api.init(dir(c).absolutePath, "heb")) { api.recycle(); return }
        try {
            Progress.phase = "מזהה טקסט בעמודים סרוקים (OCR)"
            Progress.total = db.emptyPages(1_000_000, onlyFile).size
            Progress.done = 0
            while (!Progress.cancel) {
                val batch = db.emptyPages(50, onlyFile)
                if (batch.isEmpty()) break
                var lastFile = -1L
                var renderer: PdfRenderer? = null
                var pfd: ParcelFileDescriptor? = null
                try {
                    for ((pageId, fileId, page) in batch) {
                        if (Progress.cancel) break
                        Progress.waitWhilePaused()
                        val b = db.book(fileId)
                        if (b == null) { db.saveOcr(pageId, fileId, null); continue }
                        Progress.current = "${b.title} – עמוד $page"
                        // a page that closed the scan before (damaged picture, lack of memory): skip it
                        if (Prefs.l("ocrPage", 0L) == pageId) { db.saveOcr(pageId, fileId, null); Prefs.commit("ocrPage", 0L); continue }
                        Prefs.commit("ocrPage", pageId)
                        if (fileId != lastFile) {
                            renderer?.close(); pfd?.close()
                            pfd = ParcelFileDescriptor.open(File(b.path), ParcelFileDescriptor.MODE_READ_ONLY)
                            renderer = PdfRenderer(pfd)
                            lastFile = fileId
                        }
                        val text = try { ocrPage(api, renderer!!, page - 1) } catch (e: Throwable) { null }
                        db.saveOcr(pageId, fileId, text?.let { Indexer.prepare(it) })
                        Prefs.commit("ocrPage", 0L)
                        Progress.done++
                    }
                } finally {
                    try { renderer?.close() } catch (e: Exception) {}
                    try { pfd?.close() } catch (e: Exception) {}
                }
            }
        } finally {
            api.recycle()
        }
    }

    /**
     * Where each word is on a scanned page (no text layer): the page is read with OCR and every
     * word's box returned, in the coordinates of an image [width] pixels wide – so the reader can
     * mark the searched words even in old scanned books.
     */
    fun wordBoxes(c: Context, path: String, index: Int, width: Int): List<Pair<String, android.graphics.RectF>> {
        if (!ready(c)) return emptyList()
        val out = ArrayList<Pair<String, android.graphics.RectF>>()
        val api = TessBaseAPI()
        if (!api.init(dir(c).absolutePath, "heb")) { api.recycle(); return out }
        try {
            ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { r ->
                    if (index < 0 || index >= r.pageCount) return out
                    r.openPage(index).use { p ->
                        val w = 1700
                        val h = (w.toLong() * p.height / p.width.coerceAtLeast(1)).toInt().coerceIn(100, 3000)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        api.setImage(bmp)
                        api.getUTF8Text()
                        val k = width.toFloat() / w
                        val it = api.resultIterator
                        if (it != null) {
                            it.begin()
                            do {
                                val t = it.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD) ?: continue
                                val b = it.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                                val word = Heb.clean(t).filter { ch -> ch.isLetterOrDigit() }
                                if (word.isNotEmpty()) out.add(word to android.graphics.RectF(b.left * k - 3, b.top * k - 3, b.right * k + 3, b.bottom * k + 3))
                            } while (it.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
                            it.delete()
                        }
                        api.clear()
                        bmp.recycle()
                    }
                }
            }
        } catch (e: Throwable) {
        } finally { api.recycle() }
        return out
    }

    private fun ocrPage(api: TessBaseAPI, r: PdfRenderer, index: Int): String? {
        if (index < 0 || index >= r.pageCount) return null
        r.openPage(index).use { p ->
            val w = 1700
            val h = (w.toLong() * p.height / p.width.coerceAtLeast(1)).toInt().coerceIn(100, 3000)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            api.setImage(bmp)
            val t = api.getUTF8Text()
            api.clear()
            bmp.recycle()
            return t
        }
    }
}

/** Small download helper that follows redirects across hosts. */
object Net {
    fun open(url: String): HttpURLConnection {
        var u = url
        repeat(8) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 20000
            c.readTimeout = 60000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "SearchPlus-Android")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc == null) throw java.io.IOException("redirect without location")
                u = URL(URL(u), loc).toString()
            } else {
                if (code >= 400) { c.disconnect(); throw java.io.IOException("HTTP $code") }
                return c
            }
        }
        throw java.io.IOException("too many redirects")
    }

    fun text(url: String): String {
        val c = open(url)
        return try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
    }

    /** A copy of every model file in the app's own GitHub release ("models"). */
    private const val MIRROR = "https://github.com/sw0555041250-code/Search-Plus-/releases/download/models/"

    /** "https://huggingface.co/Xenova/bge-m3/resolve/main/onnx/model_quantized.onnx" → its name in the release. */
    fun mirrorOf(url: String): String? {
        val m = Regex("^https://huggingface\\.co/[^/]+/([^/]+)/resolve/main/(.+)$").find(url) ?: return null
        return MIRROR + m.groupValues[1] + "__" + m.groupValues[2].replace('/', '_')
    }

    /**
     * Downloads a model file. First from the app's own release on GitHub (the device trusts it – some
     * devices refuse the certificate of Hugging Face's file servers: "Trust anchor … not found"), then
     * from Hugging Face. A broken connection continues from where it stopped (nothing is downloaded twice).
     */
    fun download(url: String, out: File, progress: (Int) -> Unit) {
        val sources = listOfNotNull(mirrorOf(url), url)
        var last: Exception? = null
        for (src in sources) for (attempt in 0 until 4) {
            try { fetch(src, out, progress); return } catch (e: java.io.FileNotFoundException) { last = e; break } catch (e: Exception) {
                last = e
                if (e.message == "HTTP 416") { File(out.parentFile, out.name + ".part").delete(); continue }
                if (e.message?.startsWith("HTTP 4") == true) break
                Thread.sleep(2000L * (attempt + 1))
            }
        }
        throw last ?: java.io.IOException("download failed")
    }

    private fun fetch(url: String, out: File, progress: (Int) -> Unit) {
        val tmp = File(out.parentFile, out.name + ".part")
        // continue a broken download: ask only for the rest of the file
        var have = if (tmp.exists()) tmp.length() else 0L
        val c = openFrom(url, have)
        try {
            val partial = c.responseCode == 206
            if (!partial) have = 0L
            val total = if (c.contentLength >= 0) c.contentLength.toLong() + have else -1L
            var done = have
            var lastPct = -1
            c.inputStream.use { input ->
                FileOutputStream(tmp, partial).use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) { lastPct = pct; progress(pct) }
                        }
                    }
                }
            }
            if (total > 0 && done < total) throw java.io.IOException("החיבור נקטע ($done/$total)")
        } finally { c.disconnect() }
        if (out.exists()) out.delete()
        if (!tmp.renameTo(out)) throw java.io.IOException("rename failed")
    }

    private fun openFrom(url: String, from: Long): HttpURLConnection {
        var u = url
        repeat(8) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 20000
            c.readTimeout = 60000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "SearchPlus-Android")
            if (from > 0) c.setRequestProperty("Range", "bytes=$from-")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc == null) throw java.io.IOException("redirect without location")
                u = URL(URL(u), loc).toString()
            } else {
                if (code == 404) { c.disconnect(); throw java.io.FileNotFoundException(u) }
                if (code == 416) { c.disconnect(); throw java.io.IOException("HTTP 416") }
                if (code >= 400) { c.disconnect(); throw java.io.IOException("HTTP $code") }
                return c
            }
        }
        throw java.io.IOException("too many redirects")
    }
}

/** Runs the indexing in the background with a notification, so it continues when the screen is off. */
class IndexService : Service() {
    @Volatile private var again = false
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    private fun note(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel("idx") == null) {
            nm.createNotificationChannel(NotificationChannel("idx", "סריקת ספרים", NotificationManager.IMPORTANCE_LOW))
        }
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, "idx")
        return b.setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("חיפוש פלוס – סורק ספרים")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, note("מתחיל…"))
        Prefs.init(this) // the latest settings from the screens (folders, options)
        intent?.getLongExtra("ocrFile", 0L)?.takeIf { it > 0 }?.let { Progress.ocrFile = it }
        if (Progress.running) { again = intent != null; return START_STICKY }
        Progress.running = true
        try { java.io.File(filesDir, "scan.cancel").delete() } catch (e: Throwable) {}
        // the screens learn the state from a small file, every second
        Thread {
            while (Progress.running) { Progress.publish(this); try { Thread.sleep(1000) } catch (e: InterruptedException) { break } }
            Progress.publish(this)
        }.apply { isDaemon = true }.start()
        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "searchplus:index").apply {
            setReferenceCounted(false); acquire(16 * 3600 * 1000L)
        }
        val nm = getSystemService(NotificationManager::class.java)
        val ticker = object : Runnable {
            override fun run() {
                if (!Progress.running) return
                nm.notify(1, note(Progress.phase + if (Progress.total > 0) "  ${Progress.done}/${Progress.total}" else ""))
                App.main.postDelayed(this, 5000)
            }
        }
        App.main.postDelayed(ticker, 3000)
        Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            try {
                do { again = false; Indexer.runAll(this) } while (again)
                val one = Progress.ocrFile
                if (one != null) { Progress.ocrFile = null; if (Ocr.ready(this)) Ocr.runPending(this, one) }
            } catch (e: Throwable) {
                // never crash the app because of one bad file
            } finally {
                Progress.running = false
                Progress.phase = ""; Progress.current = ""; Progress.total = 0; Progress.done = 0
                Progress.lastFinished = System.currentTimeMillis()
                Progress.publish(this@IndexService)
                try { wake?.release() } catch (e: Exception) {}
                ui { stopForeground(true); stopSelf() }
            }
        }.start()
        // if the system closes the app (memory), it brings the scan back by itself
        return if (Prefs.b("firstScanDone", false)) START_NOT_STICKY else START_STICKY
    }
}
