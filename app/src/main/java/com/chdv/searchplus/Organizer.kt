package com.chdv.searchplus

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Tidying the library on the device itself:
 *  - finding duplicate books (identical file, or the same text saved in another file),
 *  - suggesting the right folder for each book by what is written in it, and moving the files.
 * Every move is written to a log so the last tidy-up can be undone.
 */
object Organizer {
    const val DUP_DIR = "_כפולים"
    @Volatile var buildTargetsCache: List<Target>? = null

    // ---------------- duplicates ----------------

    enum class DupKind(val title: String) { SAME_FILE("קובץ זהה לגמרי"), SAME_TEXT("אותו תוכן (גם אם שם הקובץ שונה)") }

    class DupGroup(val kind: DupKind, val books: List<Book>, val similarity: Int = 100)

    private fun quickHash(f: File): String? = try {
        val md = MessageDigest.getInstance("MD5")
        RandomAccessFile(f, "r").use { r ->
            val len = r.length()
            val buf = ByteArray(65536)
            var n = r.read(buf); if (n > 0) md.update(buf, 0, n)
            if (len > 131072) { r.seek(len / 2); n = r.read(buf); if (n > 0) md.update(buf, 0, n) }
            if (len > 65536) { r.seek(maxOf(0, len - 65536)); n = r.read(buf); if (n > 0) md.update(buf, 0, n) }
            md.update(len.toString().toByteArray())
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) { null }

    private const val SKETCH = 128

    /**
     * A fingerprint of what is written in the book (not of its name): the smallest hashes of all the
     * 5-word sequences in up to 12 pages. Two books with the same text have mostly the same smallest
     * hashes, even if one is a different scan, has a different cover page or a few OCR mistakes.
     */
    private fun sketch(b: Book): IntArray? {
        if (b.textPages < 2) return null
        val db = Db.get()
        val words = ArrayList<String>()
        var got = 0
        var p = 1
        while (got < 12 && p <= minOf(b.pages, 60)) {
            val t = db.pageText(b.id, p)
            if (t != null && Heb.letterCount(t) > 120) { words.addAll(Heb.words(t)); got++ }
            p++
        }
        if (words.size < 120) return null
        val hs = HashSet<Int>()
        for (i in 0..words.size - 5) {
            var h = 1125899907
            for (j in 0 until 5) h = h * 31 + words[i + j].hashCode()
            hs.add(h xor (h ushr 15))
        }
        return hs.sorted().take(SKETCH).toIntArray()
    }

    private fun jaccard(a: IntArray, b: IntArray): Float {
        // estimate from bottom-k sketches
        val union = (a.toList() + b.toList()).toSortedSet().take(SKETCH).toSet()
        val sa = a.toHashSet(); val sb = b.toHashSet()
        val both = union.count { it in sa && it in sb }
        return both.toFloat() / union.size.coerceAtLeast(1)
    }

    fun findDuplicates(progress: (String) -> Unit): List<DupGroup> {
        val books = Db.get().books().values.filter { File(it.path).exists() }
        val out = ArrayList<DupGroup>()
        val used = HashSet<Long>()
        // 1. identical files: same size, then same bytes
        progress("משווה קבצים…")
        books.groupBy { it.size }.values.filter { it.size > 1 }.forEach { same ->
            same.groupBy { quickHash(File(it.path)) }.forEach { (h, g) ->
                if (h != null && g.size > 1) { out.add(DupGroup(DupKind.SAME_FILE, g)); g.forEach { used.add(it.id) } }
            }
        }
        // 2. the same text inside different files
        val sk = HashMap<Long, IntArray>()
        var i = 0
        for (b in books) {
            if (++i % 25 == 0) progress("קורא את תוכן הספרים להשוואה… $i/${books.size}")
            sketch(b)?.let { sk[b.id] = it }
        }
        progress("מחפש ספרים עם אותו תוכן…")
        // candidates: books that share one of their 6 smallest hashes
        val buckets = HashMap<Int, MutableList<Long>>()
        for ((id, s) in sk) s.take(6).forEach { buckets.getOrPut(it) { ArrayList() }.add(id) }
        val parent = HashMap<Long, Long>()
        fun find(x: Long): Long { var r = x; while (parent[r] != null && parent[r] != r) r = parent[r]!!; return r }
        val sims = HashMap<Long, Float>()
        val checked = HashSet<Long>()
        for (ids in buckets.values) {
            if (ids.size < 2 || ids.size > 50) continue
            for (a in ids.indices) for (b in a + 1 until ids.size) {
                val x = ids[a]; val y = ids[b]
                val key = minOf(x, y) * 1_000_003L + maxOf(x, y)
                if (!checked.add(key)) continue
                val j = jaccard(sk[x]!!, sk[y]!!)
                if (j >= 0.5f) {
                    val rx = find(x); val ry = find(y)
                    if (rx != ry) parent[rx] = ry
                    sims[x] = maxOf(sims[x] ?: 0f, j); sims[y] = maxOf(sims[y] ?: 0f, j)
                }
            }
        }
        val groups = HashMap<Long, MutableList<Long>>()
        for (id in sims.keys) groups.getOrPut(find(id)) { ArrayList() }.add(id)
        val m = Db.get().books()
        for (g in groups.values) {
            if (g.size < 2) continue
            val bs = g.mapNotNull { m[it] }
            if (bs.all { it.id in used } && out.any { d -> d.books.map { it.id }.toSet() == bs.map { it.id }.toSet() }) continue
            out.add(DupGroup(DupKind.SAME_TEXT, bs, (g.minOf { sims[it] ?: 0f } * 100).toInt()))
        }
        return out
    }

    // ---------------- moving files (with undo) ----------------

    /** Move a file; works across volumes (copy + delete). Returns the new file or null. */
    fun move(src: File, destDir: File): File? {
        destDir.mkdirs()
        var dest = File(destDir, src.name)
        var k = 2
        while (dest.exists()) { dest = File(destDir, src.nameWithoutExtension + " ($k)." + src.extension); k++ }
        if (src.renameTo(dest)) return dest
        return try {
            FileInputStream(src).use { i -> FileOutputStream(dest).use { o -> i.copyTo(o, 1 shl 16) } }
            if (dest.length() == src.length() && src.delete()) dest else { dest.delete(); null }
        } catch (e: Exception) { dest.delete(); null }
    }

    /** Move a book and keep the index (no need to read it again). */
    fun moveBook(path: String, destDir: File): File? {
        val src = File(path)
        val dest = move(src, destDir) ?: return null
        val db = Db.get()
        db.bookByPath(path)?.let { b ->
            db.writableDatabase.execSQL("UPDATE files SET path=?, dir=?, name=?, mtime=? WHERE id=?",
                arrayOf(dest.absolutePath, dest.parent, dest.name, dest.lastModified(), b.id))
            db.invalidate()
        }
        val log = Prefs.list("moveLog")
        log.add(0, "M|${dest.absolutePath}|${src.parent}")
        Prefs.putList("moveLog", log.take(5000))
        return dest
    }

    fun beginBatch() { Prefs.put("moveLog", "") }
    fun undoCount() = Prefs.list("moveLog").size

    fun undo(): Int {
        var n = 0
        for (e in Prefs.list("moveLog")) {
            val parts = e.split('|')
            if (parts.size < 3) continue
            val f = File(parts[1])
            if (!f.exists()) continue
            val ok = if (parts[0] == "R") renameRaw(f, File(parts[2])) else moveBookNoLog(f, File(parts[2]))
            if (ok) n++
        }
        Prefs.put("moveLog", "")
        return n
    }

    private fun moveBookNoLog(src: File, destDir: File): Boolean {
        val path = src.absolutePath
        val dest = move(src, destDir) ?: return false
        val db = Db.get()
        db.bookByPath(path)?.let { b ->
            db.writableDatabase.execSQL("UPDATE files SET path=?, dir=?, name=?, mtime=? WHERE id=?",
                arrayOf(dest.absolutePath, dest.parent, dest.name, dest.lastModified(), b.id))
            db.invalidate()
        }
        return true
    }

    fun dupDir(): File = File(Library.roots().firstOrNull() ?: Library.storageRoot().absolutePath, DUP_DIR)

    // ---------------- smart sorting into folders ----------------

    private val STOP = hashSetOf(
        "של", "את", "על", "הוא", "זה", "לא", "כי", "אשר", "עם", "גם", "כל", "אם", "מה", "היא", "הנה", "וכו", "אל", "או", "יש",
        "אין", "רק", "כמו", "אבל", "זו", "היה", "הם", "בו", "לו", "כן", "עד", "מן", "שהוא", "ולא", "וזה", "דהנה", "והנה", "להיות",
        "שלא", "אלא", "בזה", "וגם", "לכן", "אך", "הרי", "מפני", "שם", "בין", "לפי", "וכן", "אחד", "אותו", "היינו", "שהם", "אותם",
        "יותר", "מאד", "הזה", "הזאת", "כאשר", "ואם", "וכל", "שזה", "ועל", "וכמו", "שיש", "שאין", "עוד", "ואין", "וגו", "ע\"ה", "the", "and", "of"
    )

    /** Word counts of a text, without very common words. */
    private fun bag(text: String, into: HashMap<String, Float>, weight: Float = 1f) {
        for (w in Heb.words(text)) {
            if (w.length < 3 || w in STOP || w.all { it.isDigit() }) continue
            into[w] = (into[w] ?: 0f) + weight
        }
    }

    /** The text we use to understand what a book is about: title + a few pages from its start and middle. */
    private fun bookBag(path: String, title: String): HashMap<String, Float> {
        val m = HashMap<String, Float>()
        bag(title, m, 6f)
        val db = Db.get()
        val b = db.bookByPath(path)
        if (b != null && b.textPages > 0) {
            val pages = listOf(2, 3, 4, 6, 8, 10, b.pages / 2, b.pages / 2 + 1).filter { it in 1..b.pages }.distinct()
            for (p in pages) db.pageText(b.id, p)?.let { bag(it, m) }
        } else {
            // not indexed yet (e.g. a book in the Download folder) - read a few pages now
            try {
                Indexer.loadDoc(File(path)).use { doc ->
                    val st = com.tom_roush.pdfbox.text.PDFTextStripper()
                    st.startPage = 1; st.endPage = minOf(doc.numberOfPages, 8)
                    Indexer.prepare(st.getText(doc))?.let { bag(it, m) }
                }
            } catch (e: Throwable) {}
        }
        return m
    }

    class Target(val dir: String, val cat: List<String>) {
        val bag = HashMap<String, Float>()
        var vec: Map<String, Float> = emptyMap()
        var norm = 1f
        var books = 0
    }

    class Suggestion(val path: String, val title: String, var dest: Target?, val score: Float, val currentCat: List<String>) {
        var selected = true
    }

    /** All folders (up to 3 levels) of the library, with a description of what is in them. */
    fun buildTargets(c: Context, progress: (String) -> Unit): List<Target> {
        val db = Db.get()
        val targets = LinkedHashMap<String, Target>()
        for (r in Library.roots()) {
            fun walk(d: File, depth: Int) {
                if (depth > 3) return
                d.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") && it.name != DUP_DIR }?.forEach { k ->
                    targets[k.absolutePath] = Target(k.absolutePath, Library.categoryOf(k.absolutePath))
                    walk(k, depth + 1)
                }
            }
            walk(File(r), 1)
        }
        val books = db.books().values.filter { it.textPages > 0 }
        // sample books of every folder
        val perFolder = HashMap<String, Int>()
        var i = 0
        for (b in books.shuffled(java.util.Random(7))) {
            val owners = targets.keys.filter { b.dir == it || b.dir.startsWith("$it/") }
            if (owners.isEmpty()) continue
            if (owners.all { (perFolder[it] ?: 0) >= 25 }) continue
            if (++i % 20 == 0) progress("לומד מה יש בכל תיקייה… $i")
            val bb = bookBag(b.path, b.title)
            for (o in owners) {
                if ((perFolder[o] ?: 0) >= 25) continue
                perFolder[o] = (perFolder[o] ?: 0) + 1
                val t = targets[o]!!
                t.books++
                for ((w, n) in bb) t.bag[w] = (t.bag[w] ?: 0f) + n
            }
        }
        // the folder name itself is a strong hint
        for (t in targets.values) bag(File(t.dir).name, t.bag, 25f)
        // tf-idf
        val df = HashMap<String, Int>()
        targets.values.forEach { t -> t.bag.keys.forEach { df[it] = (df[it] ?: 0) + 1 } }
        val nT = targets.size.coerceAtLeast(1)
        for (t in targets.values) {
            val top = t.bag.entries.sortedByDescending { it.value }.take(600)
            val v = HashMap<String, Float>()
            var s = 0f
            for ((w, n) in top) {
                val x = (1 + ln(n.toDouble())).toFloat() * ln(1.0 + nT.toDouble() / (df[w] ?: 1)).toFloat()
                v[w] = x; s += x * x
            }
            t.vec = v; t.norm = sqrt(s).coerceAtLeast(1e-6f)
            t.bag.clear()
        }
        return targets.values.toList()
    }

    private fun cosine(b: Map<String, Float>, bn: Float, t: Target): Float {
        var dot = 0f
        for ((w, x) in b) { val y = t.vec[w] ?: continue; dot += x * y }
        return dot / (bn * t.norm)
    }

    /** Best folder for a book: first the best top folder, then go down while the match stays good. */
    fun classify(path: String, title: String, targets: List<Target>): Pair<Target?, Float> {
        val raw = bookBag(path, title)
        if (raw.isEmpty()) return null to 0f
        val v = HashMap<String, Float>()
        var s = 0f
        for ((w, n) in raw) { val x = (1 + ln(n.toDouble())).toFloat(); v[w] = x; s += x * x }
        val bn = sqrt(s).coerceAtLeast(1e-6f)
        var level = targets.filter { it.cat.size == targets.minOf { t -> t.cat.size } }
        var best: Target? = null
        var bestScore = 0f
        while (level.isNotEmpty()) {
            val scored = level.map { it to cosine(v, bn, it) }.sortedByDescending { it.second }
            val top = scored.first()
            val second = scored.getOrNull(1)?.second ?: 0f
            if (top.second < 0.04f || (best != null && top.second < bestScore * 0.6f)) break
            if (best != null && top.second - second < 0.005f) break
            best = top.first; bestScore = top.second
            level = targets.filter { it.cat.size == best.cat.size + 1 && it.dir.startsWith(best.dir + "/") }
        }
        return best to bestScore
    }

    /** Suggestions for the books in [sourceDir] (or loose books in the library roots when null). */
    fun suggest(c: Context, sourceDir: String?, checkAll: Boolean, progress: (String) -> Unit): List<Suggestion> {
        val targets = buildTargets(c, progress)
        buildTargetsCache = targets
        if (targets.isEmpty()) return emptyList()
        val files: List<Pair<String, String>> = when {
            sourceDir != null -> (File(sourceDir).listFiles()?.filter { it.isFile && it.name.endsWith(".pdf", true) } ?: emptyList())
                .map { it.absolutePath to it.nameWithoutExtension }
            checkAll -> Db.get().books().values.map { it.path to it.title }
            else -> Db.get().books().values.filter { Library.categoryOf(it.dir).let { cat -> cat.isEmpty() || (Library.roots().size > 1 && cat.size == 1) } }
                .map { it.path to it.title }
        }
        val out = ArrayList<Suggestion>()
        files.forEachIndexed { i, (p, t) ->
            progress("מנתח ספרים… ${i + 1}/${files.size}")
            val (dest, score) = classify(p, t, targets)
            val cur = Library.categoryOf(File(p).parent ?: "")
            if (dest == null) return@forEachIndexed
            if (checkAll) {
                // only books that look clearly misplaced: the suggested top folder differs from the current one
                if (cur.isEmpty() || dest.cat.firstOrNull() == cur.firstOrNull() || score < 0.12f) return@forEachIndexed
            }
            if (File(p).parent == dest.dir) return@forEachIndexed
            out.add(Suggestion(p, t, dest, score, cur).apply { selected = score >= 0.08f })
        }
        return out.sortedByDescending { it.score }
    }

    // ---------------- smart names ----------------

    private val BAD = Regex("(?i)^(scan|scanned|img|image|doc|document|file|untitled|new|hebrewbooks|hebrewbooks_org|book|pdf|download|copy|cam|dsc)?[ _\\-.]*(org)?[ _\\-.]*\\d*[ _\\-.()0-9a-f]*$")

    /** Is this name meaningless (scan0012, 12345, HebrewBooks_org_4521, New folder…)? */
    fun meaningless(name: String): Boolean {
        val n = name.substringBeforeLast('.').trim()
        if (n.isEmpty()) return true
        if (n.any { Heb.isHebLetter(it) }) return n.startsWith("תיקייה חדשה") || n.startsWith("עותק של")
        if (n.startsWith("New folder", true) || n.startsWith("Untitled", true)) return true
        return BAD.matches(n) || n.count { it.isDigit() } * 2 >= n.length
    }

    /** Characters that are not allowed in file names on memory cards. */
    fun safeName(s: String): String = s.replace('"', '\u05F4').replace('\'', '\u05F3')
        .replace(Regex("[\\\\/:*?<>|\\n\\r\\t]"), " ").replace(Regex("\\s+"), " ").trim().take(90)

    /** A title for a book from what is written on its title page (the biggest letters), or from the PDF metadata. */
    fun suggestBookName(path: String): String? {
        return try {
            Indexer.loadDoc(File(path)).use { doc ->
                val meta = doc.documentInformation?.title?.trim()
                if (meta != null && meta.count { Heb.isHebLetter(it) } >= 3 && meta.length in 3..90) return safeName(meta)
                for (pg in 1..minOf(3, doc.numberOfPages)) {
                    val plain = com.tom_roush.pdfbox.text.PDFTextStripper().apply { startPage = pg; endPage = pg }
                    val method = Heb.repairPage(plain.getText(doc)).method
                    data class W(val text: String, val size: Float, val y: Float)
                    val ws = ArrayList<W>()
                    val st = object : com.tom_roush.pdfbox.text.PDFTextStripper() {
                        override fun writeString(text: String?, textPositions: MutableList<com.tom_roush.pdfbox.text.TextPosition>?) {
                            val tps = textPositions ?: return
                            if (tps.isEmpty()) return
                            val raw = tps.joinToString("") { it.unicode ?: "" }
                            val t = Heb.clean(Heb.applyMethod(raw, method))
                            if (t.isBlank()) return
                            ws.add(W(t, tps.map { it.fontSizeInPt * it.textMatrix.scalingFactorY }.average().toFloat().coerceAtLeast(tps[0].heightDir), tps[0].yDirAdj))
                        }
                    }
                    st.startPage = pg; st.endPage = pg
                    st.getText(doc)
                    val words = ws.filter { w -> w.text.count { Heb.isHebLetter(it) || it.isLetter() } >= 2 }
                    if (words.isEmpty()) continue
                    val maxSize = words.maxOf { it.size }
                    val big = words.filter { it.size >= maxSize * 0.85f }
                    // the lines of the biggest letters, top to bottom
                    val lines = big.groupBy { (it.y / (maxSize * 0.6f)).toInt() }.toSortedMap().values.take(2)
                    val title = lines.joinToString(" ") { line -> line.joinToString(" ") { it.text } }
                    val clean = safeName(title)
                    if (clean.count { Heb.isHebLetter(it) } >= 3 && clean.length >= 3) return clean
                }
                null
            }
        } catch (e: Throwable) { null }
    }

    /** A name for a folder from the titles of the books in it (what they have in common). */
    fun suggestFolderName(dir: File): String? {
        val titles = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".pdf", true) }
            ?.map { it.nameWithoutExtension }?.filter { !meaningless(it) } ?: return null
        if (titles.size < 2) return null
        val tw = titles.map { t -> t.split(Regex("[\\s_\\-]+")).filter { it.isNotBlank() } }
        // longest beginning that most titles share ("לקוטי שיחות חלק א", "לקוטי שיחות חלק ב" -> "לקוטי שיחות")
        for (len in 4 downTo 1) {
            val counts = HashMap<String, Int>()
            tw.filter { it.size >= len }.forEach { counts[it.take(len).joinToString(" ")] = (counts[it.take(len).joinToString(" ")] ?: 0) + 1 }
            val best = counts.maxByOrNull { it.value } ?: continue
            if (best.value >= titles.size * 0.6 && best.key.count { Heb.isHebLetter(it) } >= 3 && Heb.words(best.key).none { it in setOf("חלק", "כרך", "ספר") && len == 1 })
                return safeName(best.key)
        }
        // otherwise the most common pair of words
        val pairs = HashMap<String, Int>()
        tw.forEach { ws -> ws.windowed(2).map { it.joinToString(" ") }.toSet().forEach { pairs[it] = (pairs[it] ?: 0) + 1 } }
        val best = pairs.maxByOrNull { it.value } ?: return null
        return if (best.value >= titles.size * 0.5 && best.key.count { Heb.isHebLetter(it) } >= 3) safeName(best.key) else null
    }

    class Rename(val file: File, val isDir: Boolean, var newName: String) { var selected = true }

    fun suggestNames(roots: List<String>, all: Boolean, progress: (String) -> Unit): List<Rename> {
        val out = ArrayList<Rename>()
        var n = 0
        for (r in roots) {
            val stack = ArrayDeque<File>(); stack.add(File(r))
            while (stack.isNotEmpty()) {
                val d = stack.removeLast()
                val kids = d.listFiles() ?: continue
                for (k in kids) {
                    if (k.name.startsWith(".") || k.name == DUP_DIR) continue
                    if (k.isDirectory) {
                        stack.add(k)
                        if (all || meaningless(k.name)) {
                            val s = suggestFolderName(k)
                            if (s != null && s != k.name) out.add(Rename(k, true, s))
                        }
                    } else if (k.name.endsWith(".pdf", true) && (all || meaningless(k.nameWithoutExtension))) {
                        if (++n % 5 == 0) progress("קורא דפי שער… $n")
                        val s = suggestBookName(k.absolutePath)
                        if (s != null && s != k.nameWithoutExtension) out.add(Rename(k, false, s))
                    }
                }
            }
        }
        return out
    }

    private fun renameRaw(src: File, dest: File): Boolean {
        if (dest.exists() || !src.renameTo(dest)) return false
        val db = Db.get()
        val old = src.absolutePath; val nw = dest.absolutePath
        db.writableDatabase.execSQL("UPDATE files SET path=?, dir=?, name=? WHERE path=?", arrayOf(nw, dest.parent, dest.name, old))
        db.writableDatabase.execSQL("UPDATE files SET path=? || substr(path, ?), dir=? || substr(dir, ?) WHERE path LIKE ? ESCAPE '\\'",
            arrayOf(nw, old.length + 1, nw, old.length + 1, old.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "/%"))
        db.invalidate()
        val roots = Library.roots().map { if (it == old) nw else if (it.startsWith("$old/")) nw + it.substring(old.length) else it }
        Library.setRoots(roots)
        return true
    }

    /** Rename a book file or a folder, keep the index, write to the undo log. */
    fun rename(r: Rename): Boolean {
        val ext = if (r.isDir) "" else "." + r.file.extension
        var dest = File(r.file.parentFile, safeName(r.newName) + ext)
        var k = 2
        while (dest.exists()) { dest = File(r.file.parentFile, safeName(r.newName) + " ($k)" + ext); k++ }
        val was = r.file.absolutePath
        if (!renameRaw(r.file, dest)) return false
        val log = Prefs.list("moveLog"); log.add(0, "R|${dest.absolutePath}|$was"); Prefs.putList("moveLog", log.take(5000))
        return true
    }
}
