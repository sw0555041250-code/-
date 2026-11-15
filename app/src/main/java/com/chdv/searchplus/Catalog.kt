package com.chdv.searchplus

import com.chdv.searchplus.core.Names
import com.chdv.searchplus.core.Ref

/**
 * The library's own names: every file and folder name found by the scan (see core/Names). Built once
 * in memory (no page reading – about a second for 12,000 books) and rebuilt after the library changes,
 * so a renamed or new folder is understood after the next scan.
 */
object Catalog {
    class Match(val book: Book, val score: Float)

    @Volatile private var names: Names? = null
    @Volatile private var books: Map<Long, Book> = emptyMap()

    @Volatile private var dirty = false
    @Volatile private var builtAt = 0L
    @Volatile private var rebuilding = false

    /**
     * The library changed (the scan saves books all the time). The current catalog keeps serving
     * requests; a new one is built in the background, at most once a minute – never in the way of a search.
     */
    fun invalidate() {
        dirty = true
        if (names != null && !rebuilding && System.currentTimeMillis() - builtAt > 60_000) {
            rebuilding = true
            Thread { try { synchronized(this) { names = null; build() } } catch (e: Throwable) {} finally { rebuilding = false } }.start()
        }
    }

    @Synchronized
    private fun get(): Names = names ?: build()

    /** Frees the names (built again on the next request). */
    fun drop() { if (!rebuilding) { names = null; books = emptyMap() } }

    private fun build(): Names {
        val all = Db.get().books()
        val roots = Library.roots()
        val items = all.values.map { b ->
            val root = roots.filter { b.dir == it || b.dir.startsWith("$it/") }.maxByOrNull { it.length } ?: ""
            Names.Item(b.id, b.title, b.dir.removePrefix(root).trim('/').split('/').filter { it.isNotBlank() })
        }
        val n = Names(items)
        books = all; names = n; dirty = false; builtAt = System.currentTimeMillis()
        return n
    }

    /** Build in the background, so the first request is instant. */
    fun warm() { Thread { try { get() } catch (e: Throwable) {} }.start() }

    fun find(request: String, ref: Ref? = null, max: Int = 10): List<Match> {
        val n = get()
        return n.find(request, ref, max).mapNotNull { h -> books[h.id]?.let { Match(it, h.score) } }
    }
}
