package com.chdv.searchplus

import com.chdv.searchplus.core.HebNum
import com.chdv.searchplus.core.PageFinder
import com.chdv.searchplus.core.Ref
import com.chdv.searchplus.core.RefParser

/**
 * Going straight to a place: "ברכות דף כז ע״א" → every edition of Berachos in the library, each
 * opened at that daf. The page is found from the daf marks printed at the top of the pages (read
 * during the scan); where a book has no text, from the user's own correction, or an estimate.
 */
object Jump {
    class Target(val book: Book, val page: Int, val exact: Boolean, val ref: Ref, val amudIndex: Int?) {
        /** Words to mark on the opened page: the parsha / "סימן …" / "פרק …" heading that was looked for. */
        val marks: List<String> get() = when {
            ref.parsha != null && ref.page == null -> RefParser.normalize(ref.parsha!!).split(' ')
            ref.siman != null -> listOf("סימן", HebNum.toHeb(ref.siman!!))
            ref.chapter != null -> listOf("פרק", HebNum.toHeb(ref.chapter!!))
            else -> emptyList()
        }
        val note: String get() = if (exact) "עמוד $page" else "עמוד $page (משוער – אפשר לתקן בקורא)"
    }

    /** Names of files and folders, with their abbreviations written out (a folder "אדה״ז" = אדמו״ר הזקן). */
    private fun norm(s: String): String {
        val t = s.replace('_', ' ').replace('-', ' ').replace('.', ' ')
        val n = RefParser.normalize(t)
        val e = RefParser.normalize(com.chdv.searchplus.core.Abbr.expand(t))
        return " " + (if (e != n) "$n $e" else n) + " "
    }

    private fun has(hay: String, phrase: String): Boolean {
        val p = RefParser.normalize(phrase)
        return hay.contains(" $p ") || hay.contains(" ב$p ") || hay.contains(" ו$p ") || hay.contains(" ה$p ") || hay.contains(" מ$p ") || hay.contains(" ל$p ")
    }

    /** Letters without the vowel letters – so "ליקוטי" = "לקוטי", "שולחן" = "שלחן". */
    private fun key(w: String) = w.filter { it !in "אהויע" }.replace('ך', 'כ').replace('ם', 'מ').replace('ן', 'נ').replace('ף', 'פ').replace('ץ', 'צ')

    /**
     * The books for a request (by the names they have on the device), each opened at the place.
     * [stale] lets a newer request stop an older one at once.
     */
    /** [progress] gets a message and how far along it is (0–100). */
    fun resolve(ref: Ref, raw: String? = null, stale: () -> Boolean = { false }, progress: (String, Int) -> Unit = { _, _ -> },
                cancel: android.os.CancellationSignal? = null, deep: Boolean = true): List<Target> {
        val request = raw ?: (listOf(ref.work) + ref.extra).joinToString(" ")
        // "ליקוטי שיחות (חלק ד) נח": the page in each volume where that parsha's sicha starts
        if (ref.parsha != null && ref.page == null && (ref.kind == Ref.Kind.SICHOS || ref.kind == Ref.Kind.GENERIC)) {
            progress("🔎 מחפש בשמות ${Db.get().books().size} הספרים…", 15)
            val found = parshaPlaces(request, ref, stale, progress, cancel, deep)
            if (found.isNotEmpty() || stale()) return found
        }
        val manyVolumes = ref.siman != null || ref.chapter != null
        progress("🔎 מחפש בשמות ${Db.get().books().size} הספרים…", 15)
        val cands = Catalog.find(request, ref, if (manyVolumes) 10 else 6)
        if (cands.isNotEmpty()) progress("📚 נמצאו ${cands.size} ספרים מתאימים – מחפש בהם את המקום…", 35)
        val out = ArrayList<Target>()
        // books not scanned yet are read now – at most ~20 seconds for all of them
        val until = System.currentTimeMillis() + 20_000
        for ((k, m) in cands.withIndex()) {
            if (stale()) return out
            progress("📖 ${k + 1}/${cands.size} · מחפש את ${ref.label()} ב„${m.book.title}”…", 35 + 60 * k / maxOf(1, cands.size))
            out.add(try { locate(m.book, ref, deep && System.currentTimeMillis() < until) { stale() || System.currentTimeMillis() > until } } catch (e: Throwable) { Target(m.book, 1, false, ref, null) })
        }
        // where the place was really found in some books – only those (no "page 1, estimated")
        if (out.any { it.exact }) return out.filter { it.exact }
        return out
    }

    /** "ליקוטי שיחות", "לקו״ש" in a book's name or folders. */
    private fun isSichos(b: Book): Boolean {
        val t = norm(b.title + " " + b.dir.substringAfterLast('/') + " " + b.dir.substringBeforeLast('/').substringAfterLast('/'))
        val k = t.split(' ').joinToString(" ") { key(it) }
        return k.contains(" לקט שחת ") || t.contains(" לקוש ") || t.contains(" ליקוש ") || k.contains(" לקש ")
    }

    /**
     * The sicha of a parsha in every fitting book, each opened at its own page: one quick query over
     * the scanned pages that name the parsha, each page scored (heading at the top, "לפרשת נח"…),
     * the start of the strongest run of pages in each book. Books not scanned yet: their page headers
     * are read now. Only books where the place was really found are returned.
     */
    private fun parshaPlaces(request: String, ref: Ref, stale: () -> Boolean, progress: (String, Int) -> Unit,
                             cancel: android.os.CancellationSignal?, deep: Boolean): List<Target> {
        val parsha = ref.parsha!!
        var cands = Catalog.find(request, ref, if (ref.volume != null) 20 else 80).map { it.book }
        // real Likkutei Sichos volumes first – not weekly bulletins that quote them
        if (ref.kind == Ref.Kind.SICHOS) cands.filter { isSichos(it) }.let { if (it.isNotEmpty()) cands = it }
        if (cands.isEmpty() || stale()) return emptyList()
        progress("📚 מחפש את פרשת $parsha בתוך ${cands.size} ספרים…", 35)
        val texts = HashMap<Long, HashMap<Int, String>>()
        try {
            val expr = Search.buildMatch(Search.terms(parsha), Match.PHRASE, true)
            Db.get().readableDatabase.rawQuery(
                "SELECT p.file, p.page, f.body FROM fts f JOIN pages p ON p.id = f.docid WHERE f MATCH ? AND p.file IN (${cands.joinToString(",") { it.id.toString() }})",
                arrayOf(expr), cancel).use { c ->
                while (c.moveToNext()) texts.getOrPut(c.getLong(0)) { HashMap() }[c.getInt(1)] = c.getString(2)
            }
        } catch (e: Exception) { if (stale()) return emptyList() }
        val out = ArrayList<Pair<Target, Int>>()
        for (b in cands) {
            val pages = texts[b.id] ?: continue
            val (pg, score) = PageFinder.parshaPage(pages, parsha, ref.sicha) ?: continue
            out.add(Target(b, pg, true, ref, null) to score)
        }
        // books the scan has not read yet: their headers, now (a few, the best named ones)
        val unread = cands.filter { (it.status != St.DONE || it.textPages == 0) && !Cloud.isCloud(it.path) }.take(if (!deep) 0 else if (ref.volume != null) 4 else 3)
        // reading books now takes time: at most ~15 seconds for all of them
        val until = System.currentTimeMillis() + 15_000
        for ((k, b) in unread.withIndex()) {
            if (stale()) return emptyList()
            if (System.currentTimeMillis() > until) break
            progress("📖 קורא את „${b.title}” (עוד לא נסרק)…", 60 + 30 * k / maxOf(1, unread.size))
            val heads = try { PdfText.heads(App.ctx, java.io.File(b.path)) { stale() || System.currentTimeMillis() > until } } catch (e: Throwable) { emptyMap() }
            if (stale()) return emptyList()
            val (pg, score) = PageFinder.parshaPage(heads, parsha, ref.sicha) ?: continue
            out.add(Target(b, pg, true, ref, null) to score)
        }
        // the volume's order (חלק א, ב, ג…); translations and weak matches after
        return out.sortedWith(compareBy<Pair<Target, Int>> { if (it.second < 10) 1 else 0 }
            .thenBy { if (it.first.book.dir.contains("מתורגמ") || it.first.book.title.contains("מתורגמ")) 1 else 0 }
            .thenBy { PageFinder.volumeOf(it.first.book.title) ?: PageFinder.volumeOf(it.first.book.dir.substringAfterLast('/')) ?: 999 })
            .map { it.first }.take(30)
    }

    /** No such book: the closest names in the library ("did you mean…"). */
    fun suggest(text: String, max: Int = 5): List<Book> = Catalog.find(text, null, max).map { it.book }



    fun inside(books: List<Book>, words: List<String>, cancel: android.os.CancellationSignal? = null): List<Triple<Book, Int, List<String>>> {
        if (books.isEmpty() || words.isEmpty()) return emptyList()
        val out = ArrayList<Triple<Book, Int, List<String>>>()
        for (b in books.take(6)) {
            val r = try { Search.run(words.joinToString(" "), Match.ALL, true, b.id, cancel, 2000) } catch (e: Exception) { null } ?: continue
            val pages = r.books.firstOrNull()?.pages ?: continue
            if (pages.isNotEmpty()) out.add(Triple(b, pages.first().page, r.terms))
        }
        return out
    }

    private fun needsPages(ref: Ref) = ref.parsha != null || ref.page != null || ref.siman != null || ref.chapter != null || ref.daf != null

    private fun locate(b: Book, ref: Ref, deep: Boolean = true, stop: () -> Boolean = { false }): Target {
        // a book the scan has not reached yet: read its headers now (fast native reader)
        val pages = Db.get().pageTexts(b.id).ifEmpty { if (deep && needsPages(ref) && !Cloud.isCloud(b.path)) PdfText.heads(App.ctx, java.io.File(b.path), stop) else emptyMap() }
        val count = b.pages.coerceAtLeast(1)
        when (ref.kind) {
            Ref.Kind.GEMARA -> {
                val daf = ref.daf ?: return Target(b, 1, false, ref, null)
                val target = PageFinder.amudIndex(daf, ref.amud ?: 1)
                val anchors = ArrayList<Pair<Int, Int>>()
                for ((p, t) in pages) for (x in PageFinder.dafMarks(t, ref.work)) anchors.add(p to x)
                // the user's own corrections are the most reliable anchors
                calibrations(b.path).forEach { (p, x) -> repeat(3) { anchors.add(p to x) } }
                val exactPage = anchors.filter { it.second == target }.map { it.first }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                if (exactPage != null) return Target(b, exactPage, true, ref, target)
                val est = PageFinder.estimate(anchors, target, count)
                // a book with no daf marks at all: Vilna layout, one amud per page after a few title pages
                return Target(b, est ?: (target + 3).coerceIn(1, count), est != null && anchors.size >= 4, ref, target)
            }
            Ref.Kind.SICHOS -> {
                ref.page?.let { pr -> val (pg, ok) = PageFinder.pageOfPrinted(pages, pr, count); return Target(b, pg, ok, ref, null) }
                val parsha = ref.parsha ?: return Target(b, 1, true, ref, null)
                val at = PageFinder.parshaPage(pages, parsha, ref.sicha)
                return Target(b, at?.first ?: 1, at != null, ref, null)
            }
            Ref.Kind.GENERIC -> {
                val sorted = pages.keys.sorted()
                ref.chapter?.let { c -> sorted.firstOrNull { PageFinder.hasNumbered(pages[it]!!, "פרק", c) }?.let { return Target(b, it, true, ref, null) } }
                ref.siman?.let { s ->
                    sorted.firstOrNull { PageFinder.hasNumbered(pages[it]!!, "סימן", s) }?.let { return Target(b, it, true, ref, null) }
                    // the siman starts in the middle of a page
                    val mark = Regex("(^|\\s)סימן\\s+${HebNum.toHeb(s)}(\\s|$)")
                    sorted.firstOrNull { mark.containsMatchIn(RefParser.normalize(pages[it]!!)) }?.let { return Target(b, it, true, ref, null) }
                }
                ref.parsha?.let { pr -> PageFinder.parshaPage(pages, pr, ref.sicha)?.let { return Target(b, it.first, true, ref, null) } }
                ref.page?.let { pr -> val (pg, ok) = PageFinder.pageOfPrinted(pages, pr, count); return Target(b, pg, ok, ref, null) }
                return Target(b, 1, false, ref, null)
            }
        }
    }

    // ---------- the user's corrections ("this is daf X") ----------

    private fun calibrations(path: String): List<Pair<Int, Int>> =
        Prefs.list("dafCal").filter { it.substringBefore('|') == path }.mapNotNull {
            val p = it.split('|'); if (p.size == 3) (p[1].toIntOrNull() ?: return@mapNotNull null) to (p[2].toIntOrNull() ?: return@mapNotNull null) else null
        }

    fun calibrate(path: String, page: Int, amudIndex: Int) {
        val l = Prefs.list("dafCal").filterNot { it.startsWith("$path|") && it.split('|').getOrNull(2) == amudIndex.toString() }.toMutableList()
        l.add(0, "$path|$page|$amudIndex")
        Prefs.putList("dafCal", l.take(400))
    }
}
