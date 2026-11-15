package com.chdv.searchplus

import android.content.Context

/** How the words of a query must appear on a page. */
enum class Match(val key: String, val title: String) {
    PHRASE("phrase", "ביטוי מדויק"),
    ALL("all", "כל המילים בעמוד"),
    NEAR("near", "מילים קרובות"),
    ANY("any", "אחת המילים לפחות"),
}

/** One page that matched. */
data class PageHit(val pageId: Long, val page: Int, val score: Float = 0f)

/** One book with all its matching pages. */
class BookHit(val book: Book, val pages: MutableList<PageHit>, var nameMatch: Boolean = false) {
    val category: List<String> by lazy { Library.categoryOf(book.dir) }
    var score: Float = 0f
}

class Results(
    val query: String,
    val terms: List<String>,
    val books: List<BookHit>,
    val semantic: Boolean,
    val truncated: Boolean,
    val error: String? = null,
) {
    val pageCount: Int get() = books.sumOf { it.pages.size }
}

/** Full-text search over the index. */
object Search {
    private const val MAX_ROWS = 60000

    /** Query words in index form; keeps a trailing * (prefix search). */
    fun terms(q: String): List<String> {
        val out = ArrayList<String>()
        for (raw in q.trim().split(Regex("\\s+"))) {
            val star = raw.endsWith("*")
            val ws = Heb.words(raw)
            ws.forEachIndexed { i, w -> out.add(if (star && i == ws.size - 1) "$w*" else w) }
        }
        return out
    }

    /** Builds an FTS4 MATCH expression (standard syntax: implicit AND, OR binds tighter, "phrases", NEAR/n). */
    fun buildMatch(terms: List<String>, match: Match, prefixes: Boolean): String {
        if (terms.isEmpty()) return ""
        fun variants(w: String): List<String> =
            if (!prefixes || w.endsWith("*") || w.any { it in 'a'..'z' }) listOf(w)
            else listOf(w) + Heb.PREFIXES.map { it + w }

        if (terms.size == 1) return variants(terms[0]).joinToString(" OR ")
        return when (match) {
            Match.PHRASE -> {
                val rest = terms.drop(1).joinToString(" ")
                variants(terms[0]).joinToString(" OR ") { "\"$it $rest\"" }
            }
            Match.ALL -> terms.joinToString(" ") { t -> variants(t).joinToString(" OR ") }
            Match.ANY -> terms.flatMap { variants(it) }.joinToString(" OR ")
            Match.NEAR -> terms.joinToString(" NEAR/12 ")
        }
    }

    /**
     * [cancel] stops the query in the middle (a newer request came) – SQLite checks it while it works,
     * so a slow search never holds up the next one. [maxRows] keeps quick lookups quick.
     */
    fun run(q: String, match: Match, prefixes: Boolean, inFile: Long? = null,
            cancel: android.os.CancellationSignal? = null, maxRows: Int = MAX_ROWS): Results {
        val terms = terms(q)
        if (terms.isEmpty()) return Results(q, terms, emptyList(), false, false)
        val expr = buildMatch(terms, match, prefixes)
        val db = Db.get()
        val books = db.books()
        val byBook = LinkedHashMap<Long, BookHit>()
        var rows = 0
        try {
            val sql = "SELECT p.file, p.page, p.id FROM fts JOIN pages p ON p.id = fts.docid WHERE fts MATCH ?" +
                (if (inFile != null) " AND p.file = $inFile" else "") + " LIMIT $maxRows"
            db.readableDatabase.rawQuery(sql, arrayOf(expr), cancel).use { c ->
                while (c.moveToNext()) {
                    rows++
                    val fid = c.getLong(0)
                    val b = books[fid] ?: continue
                    byBook.getOrPut(fid) { BookHit(b, ArrayList()) }.pages.add(PageHit(c.getLong(2), c.getInt(1)))
                }
            }
        } catch (e: Exception) {
            return Results(q, terms, emptyList(), false, false, "החיפוש לא הצליח: " + (e.message ?: ""))
        }
        // books whose name matches
        if (inFile == null) {
            val plain = terms.map { it.removeSuffix("*") }
            for (b in books.values) {
                val name = Heb.clean(b.title)
                if (plain.all { name.contains(it) }) {
                    val h = byBook.getOrPut(b.id) { BookHit(b, ArrayList()) }
                    h.nameMatch = true
                }
            }
        }
        byBook.values.forEach { h ->
            h.pages.sortBy { it.page }
            h.score = h.pages.size.toFloat() + if (h.nameMatch) 10000f else 0f
        }
        return Results(q, terms, byBook.values.toList(), false, rows >= maxRows)
    }

    fun runSemantic(c: Context, q: String, progress: (String) -> Unit = {}): Results {
        val db = Db.get()
        val books = db.books()
        val hits = try { Semantic.search(c, q, progress) } catch (e: Throwable) {
            Semantic.lastError = e.message ?: e.javaClass.simpleName
            return Results(q, terms(q), emptyList(), true, false, "חיפוש לפי משמעות נכשל: " + (e.message ?: e.javaClass.simpleName))
        }
        val byBook = LinkedHashMap<Long, BookHit>()
        for (h in hits) {
            val ref = db.pageRef(h.pageId) ?: continue
            val b = books[ref.first] ?: continue
            val bh = byBook.getOrPut(b.id) { BookHit(b, ArrayList()) }
            bh.pages.add(PageHit(h.pageId, ref.second, h.score))
            bh.score = maxOf(bh.score, h.score)
        }
        return Results(q, Semantic.keyWords(q), byBook.values.toList(), true, false)
    }

    /** Snippet with the matching words wrapped in \u0001 ... \u0002. */
    fun snippet(expr: String, pageId: Long): String? = try {
        Db.get().readableDatabase.rawQuery(
            "SELECT snippet(fts, '\u0001', '\u0002', '…', -1, 30) FROM fts WHERE fts MATCH ? AND docid = ?",
            arrayOf(expr, pageId.toString())
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (e: Exception) { null }

    /** For meaning search: the passage of the page that is closest to the question words, marked. */
    fun semanticSnippet(pageId: Long, terms: List<String>): String? {
        val body = Db.get().pageTextById(pageId) ?: return null
        val words = body.split(' ', '\n').filter { it.isNotBlank() }
        if (words.isEmpty()) return null
        var bestI = 0; var bestS = -1
        val win = 34
        for (i in 0 until maxOf(1, words.size - win) step 6) {
            var s = 0
            for (j in i until minOf(words.size, i + win)) if (terms.any { t -> words[j].contains(t.removeSuffix("*")) }) s++
            if (s > bestS) { bestS = s; bestI = i }
        }
        return (if (bestI > 0) "…" else "") + words.subList(bestI, minOf(words.size, bestI + win)).joinToString(" ") { w ->
            if (terms.any { t -> t.length > 1 && w.contains(t.removeSuffix("*")) }) "\u0001$w\u0002" else w
        } + "…"
    }
}
