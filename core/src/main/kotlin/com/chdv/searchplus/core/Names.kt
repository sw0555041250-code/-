package com.chdv.searchplus.core

/**
 * Finding books by the names they have on the device – file names and folder names, with
 * abbreviations written out and spelling variants allowed. Pure Kotlin (tested without Android).
 * Built once from the book list (fast: no page reading), then every request is a quick scan of it.
 */
class Names(books: List<Item>) {
    class Item(val id: Long, val title: String, val folders: List<String>)
    class Hit(val id: Long, val score: Float)

    private class Entry(val item: Item, val title: Set<String>, val folders: Set<String>, val keys: Set<String>,
                        val titleText: String, val allText: String, val volume: Int?)

    // many books share the same folders: each folder name is read once
    private val folderWords = HashMap<String, Set<String>>()

    private val entries: List<Entry> = books.map { b ->
        val tw = words(b.title)
        val fw = b.folders.flatMap { f -> folderWords.getOrPut(f) { words(f) } }.toSet()
        val volume = PageFinder.volumeOf(b.title) ?: b.folders.lastOrNull()?.let { PageFinder.volumeOf(it) }
        Entry(b, tw, fw, (tw + fw).map(::key).filter { it.length >= 2 }.toSet(),
            " " + tw.joinToString(" ") + " ", " " + (tw + fw).joinToString(" ") + " ", volume)
    }

    val size: Int get() = entries.size

    companion object {
        /** Words that say where in a book (handled by the page finder), or are just talk. */
        val PLACE = setOf("דף", "עמוד", "עמ", "חלק", "כרך", "פרק", "סימן", "סעיף", "שיחה", "שיחת", "פרשת", "פרשה", "לפרשת", "בפרשת",
            "עא", "עב", "תפתח", "פתח", "תביא", "חפש", "תחפש", "ספר", "את", "של", "לי", "מתוך", "בספר", "במהדורת", "מהדורת", "מסכת", "גמרא")

        fun key(w: String) = w.filter { it !in "אהויע" }.replace('ך', 'כ').replace('ם', 'מ').replace('ן', 'נ').replace('ף', 'פ').replace('ץ', 'צ')

        /** Normalized words of a name, also with its abbreviations written out. */
        fun words(name: String): Set<String> {
            val t = name.replace('_', ' ').replace('-', ' ').replace('.', ' ').replace(Regex("(?i)\\bpdf\\b"), " ")
            val out = LinkedHashSet<String>()
            for (s in listOf(t, Abbr.expand(t))) RefParser.normalize(s).split(' ').filter { it.length >= 2 }.forEach { out.add(it) }
            return out
        }

        private fun isNumber(w: String) = w.toIntOrNull() != null || (w.length <= 3 && HebNum.gematria(w) != null)
    }

    private fun hit(w: String, e: Entry): Float {
        if (w in e.title) return 3f
        if (w in e.folders) return 2.2f
        if (w.length > 3 && w[0] in "בהוכלמש" && w.substring(1).let { it in e.title || it in e.folders }) return 2f
        val k = key(w)
        // spelling variants (ליקוטי / לקוטי, שולחן / שלחן) count almost as much as the same spelling
        if (k.length >= 2 && k in e.keys) return 2.6f
        if (w.length >= 3 && (e.title.any { it.startsWith(w) } || e.folders.any { it.startsWith(w) })) return 1f
        return 0f
    }

    /**
     * Books for a request. [ref] (if the request was understood as a place) narrows it:
     * Gemara must carry the masechet's name, Likkutei Sichos the right volume.
     */
    fun find(request: String, ref: Ref? = null, max: Int = 10): List<Hit> {
        val base = LinkedHashSet<String>()
        base.addAll(words(request))
        // the understood work name and its other names count too ("שו״ע אדה״ז" → שולחן ערוך הרב)
        ref?.let { r -> Works.variants(r.work).forEach { v -> base.addAll(words(v)) }; base.addAll(words(r.work)) }
        val ws = base.filter { it !in PLACE && !isNumber(it) && (ref?.parsha == null || it != RefParser.normalize(ref.parsha)) }
        val reqWords = words(request).filter { it !in PLACE && !isNumber(it) }
        val out = ArrayList<Hit>()
        for (e in entries) {
            when (ref?.kind) {
                Ref.Kind.GEMARA -> {
                    val m = RefParser.normalize(ref.work)
                    if (!e.allText.contains(" $m ") && !e.allText.contains(" ב$m ")) continue
                    // a longer masechet name containing this one is another masechet
                    if (RefParser.MASECHTOT.any { it != ref.work && it.contains(ref.work) && e.allText.contains(" " + RefParser.normalize(it) + " ") }) continue
                }
                Ref.Kind.SICHOS -> if (!(e.allText.contains("שיחות") || e.allText.contains("לקוש") || e.allText.contains("ליקוש"))) continue
                else -> {}
            }
            var score = 0f; var covered = 0
            for (w in reqWords) { val h = hit(w, e); if (h > 0) { score += h; covered++ } }
            var extra = 0f
            for (w in ws) if (w !in reqWords) { val h = hit(w, e); if (h > 0) extra += h * 0.6f }
            if (ref?.kind != Ref.Kind.GEMARA && ref?.kind != Ref.Kind.SICHOS) {
                // a book by name: most of what was asked must be in its names
                val need = if (reqWords.isEmpty()) 1 else maxOf(1, (reqWords.size * 6 + 9) / 10)
                if (covered < need && extra < 2f) continue
            }
            score += extra
            if (ref?.kind == Ref.Kind.GEMARA) {
                score += 10f
                if (e.allText.contains(" ירושלמי ") || e.allText.contains(" משניות ") || e.titleText.contains(" משנה ")) score -= 8f
            }
            if (ref?.volume != null) {
                if (e.volume == ref.volume) score += 12f else if (e.volume != null) { if (ref.kind == Ref.Kind.SICHOS) continue else score -= 6f }
            }
            if (score <= 0f) continue
            out.add(Hit(e.item.id, score))
        }
        // equal scores: the plainer (shorter) name first
        val len = entries.associate { it.item.id to it.item.title.length }
        return out.sortedWith(compareByDescending<Hit> { Math.round(it.score * 10) }.thenBy { len[it.id] ?: 0 }).take(max)
    }
}

/**
 * Folder names that mean the same folder: "ליקוטי שיחות" = "לקוטי שיחות" = "לקו״ש", "תניא" = "תניא (2)"
 * = "תניא - עותק" = "תניא חדש". Used to suggest merging folders.
 */
object FolderNames {
    /** Words that don't make a folder different ("עותק", "חדש", "(2)", "new"…). */
    private val NOISE = setOf("עותק", "העתק", "חדש", "חדשה", "נוסף", "נוספים", "ישן", "copy", "new", "old", "pdf", "קבצים", "files", "הורדות", "download", "downloads")

    fun key(name: String): String {
        var t = name.lowercase()
            .replace(Regex("\\(\\d+\\)"), " ")           // "(2)"
            .replace(Regex("[_.\\-–—]+"), " ")
        t = Abbr.expand(t)
        val words = RefParser.normalize(t).split(' ').filter { it.isNotBlank() && it !in NOISE }
        return words.map { Names.key(it).ifEmpty { it } }.joinToString(" ")
    }

    fun same(a: String, b: String): Boolean {
        val ka = key(a); val kb = key(b)
        return ka.isNotEmpty() && ka == kb
    }
}
