package com.chdv.searchplus

import com.chdv.searchplus.core.Abbr
import com.chdv.searchplus.core.HebNum
import com.chdv.searchplus.core.PageFinder
import com.chdv.searchplus.core.RefParser

/**
 * The library's own names: every folder and file name found by the scan, learned as they are on the
 * device. A request ("שולחן ערוך אדמו״ר הזקן סימן ב", "לקו״ש כ״ד", "אגרות קודש הרבי") is matched
 * against these names – not against a fixed list – so a renamed or new folder is understood after the
 * next scan. Abbreviations, spelling variants (ליקוטי/לקוטי) and prefixes (ב, ל, ו…) are taken into account.
 */
object Catalog {
    class Entry(val book: Book, val title: Set<String>, val folders: Set<String>, val cover: Set<String>, val keys: Set<String>, val volume: Int?)
    class Match(val book: Book, val score: Float, val covered: Int, val of: Int)

    @Volatile private var entries: List<Entry> = emptyList()
    @Volatile private var builtFor = -1

    /** Words that only say *where* in a book, not *which* book. */
    private val PLACE = setOf("דף", "עמוד", "עמ", "חלק", "כרך", "פרק", "סימן", "סעיף", "שיחה", "שיחת", "פרשת", "פרשה", "לפרשת", "עא", "עב",
        "תפתח", "פתח", "תביא", "חפש", "תחפש", "ספר", "את", "של", "לי", "מתוך", "בספר", "במהדורת", "מהדורת")

    private fun key(w: String) = w.filter { it !in "אהויע" }.replace('ך', 'כ').replace('ם', 'מ').replace('ן', 'נ').replace('ף', 'פ').replace('ץ', 'צ')

    /** Normalized words of a name, with its abbreviations also written out. */
    fun words(name: String): Set<String> {
        val t = name.replace('_', ' ').replace('-', ' ').replace('.', ' ')
        val out = HashSet<String>()
        for (s in listOf(t, Abbr.expand(t))) RefParser.normalize(s).split(' ').filter { it.length >= 2 }.forEach { out.add(it) }
        return out
    }

    private fun build() {
        val db = Db.get()
        val books = db.books().values
        if (builtFor == books.size && entries.isNotEmpty()) return
        val roots = Library.roots()
        entries = books.map { b ->
            val root = roots.filter { b.dir == it || b.dir.startsWith("$it/") }.maxByOrNull { it.length } ?: ""
            val rel = b.dir.removePrefix(root).trim('/')
            val title = words(b.title)
            val folders = rel.split('/').filter { it.isNotBlank() }.flatMap { words(it) }.toSet()
            // the title page as printed in the book – for files whose names say nothing ("scan_0042.pdf")
            val cover = try {
                (1..2).mapNotNull { db.pageText(b.id, it)?.take(160) }.flatMap { words(it) }.filter { it.length >= 3 }.toSet()
            } catch (e: Exception) { emptySet() }
            Entry(b, title, folders, cover, (title + folders).map(::key).filter { it.length >= 2 }.toSet(),
                PageFinder.volumeOf(b.title) ?: rel.split('/').lastOrNull()?.let { PageFinder.volumeOf(it) })
        }
        builtFor = books.size
    }

    fun invalidate() { builtFor = -1 }

    /** How well one request word matches a book's names (0 = not at all). */
    private fun hit(w: String, e: Entry): Float {
        if (w in e.title) return 3f
        if (w in e.folders) return 2.2f
        if (w in e.cover) return 1.6f
        // with a prefix letter (בליקוטי, לרבי…) or without one
        if (w.length > 3 && w[0] in "בהוכלמש" && (w.substring(1) in e.title || w.substring(1) in e.folders)) return 2f
        if (e.title.any { it.length > 3 && it[0] in "בהוכלמש" && it.substring(1) == w }) return 2f
        // spelling variants: ליקוטי/לקוטי, שולחן/שלחן
        val k = key(w)
        if (k.length >= 2 && k in e.keys) return 1.5f
        // the start of a long word ("שיח" → שיחות)
        if (w.length >= 3 && (e.title.any { it.startsWith(w) } || e.folders.any { it.startsWith(w) })) return 1f
        return 0f
    }

    /**
     * Books whose file or folder names match the request, best first. Numbers and place words
     * (דף, עמוד, חלק…) are left for finding the page; a volume number ("חלק כ״ד") prefers that volume.
     */
    fun find(request: String, volume: Int? = null, max: Int = 12): List<Match> {
        build()
        val ws = words(request).filter { it !in PLACE && !(it.length <= 3 && HebNum.gematria(it) != null) && it.toIntOrNull() == null }
        if (ws.isEmpty()) return emptyList()
        val out = ArrayList<Match>()
        for (e in entries) {
            var score = 0f; var covered = 0
            for (w in ws) { val h = hit(w, e); if (h > 0) { score += h; covered++ } }
            if (covered == 0) continue
            // most of the request must be in the names
            if (covered * 10 < ws.size * 6) continue
            if (volume != null && e.volume != null) { if (e.volume == volume) score += 4f else score -= 6f }
            out.add(Match(e.book, score, covered, ws.size))
        }
        return out.sortedWith(compareByDescending<Match> { it.score }.thenBy { it.book.title.length }).take(max)
    }
}
