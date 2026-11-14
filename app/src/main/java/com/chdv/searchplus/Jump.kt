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

    fun resolve(ref: Ref, raw: String? = null): List<Target> {
        val db = Db.get()
        val books = db.books().values
        val scored = ArrayList<Pair<Book, Int>>()
        for (b in books) {
            val title = norm(b.title)
            val path = norm(b.dir.substringAfterLast('/')) + norm(b.dir)
            var score = 0
            when (ref.kind) {
                Ref.Kind.GEMARA -> {
                    if (!has(title, ref.work) && !has(norm(b.dir.substringAfterLast('/')), ref.work)) continue
                    // another masechet with a longer name that contains this one ("בבא" ...) is not this one
                    if (RefParser.MASECHTOT.any { it != ref.work && it.contains(ref.work) && has(title, it) }) continue
                    score += 10
                    if (has(path, "ירושלמי") || has(title, "ירושלמי") || has(title, "משניות") || has(title, "משנה")) score -= 8
                }
                Ref.Kind.SICHOS -> {
                    val all = title + path
                    if (!(all.contains("שיחות") && (all.contains("לקוטי") || all.contains("ליקוטי") || all.contains(" לקוש ")))) continue
                    score += 10
                    val v = PageFinder.volumeOf(b.title) ?: PageFinder.volumeOf(b.dir.substringAfterLast('/'))
                    if (ref.volume != null) {
                        if (v == ref.volume) score += 20 else if (v != null) continue
                    }
                }
                Ref.Kind.GENERIC -> {
                    val all = title + path
                    // known books: the file may carry any of the book's names (שו״ע הרב / שולחן ערוך אדמו״ר הזקן …)
                    val groups = com.chdv.searchplus.core.Works.ALIASES.keys.filter { c -> RefParser.normalize(ref.work).contains(RefParser.normalize(c)) }
                    var rest = RefParser.normalize(ref.work)
                    var g = 0
                    for (c in groups.sortedByDescending { it.length }) {
                        if (!rest.contains(RefParser.normalize(c))) continue
                        rest = rest.replace(RefParser.normalize(c), " ")
                        if (com.chdv.searchplus.core.Works.variants(c).any { v -> has(all, v) || v.split(' ').all { w -> has(all, w) } }) g++ else { g = -1; break }
                    }
                    if (g < 0) continue
                    val words = rest.split(' ').filter { it.length >= 2 }
                    val tk = all.split(' ').filter { it.length >= 2 }.map(::key).toSet()
                    val hit = words.count { has(title, it) || has(path, it) || (key(it).length >= 2 && key(it) in tk) }
                    if (g == 0 && hit < maxOf(1, (words.size + 1) / 2)) continue
                    score += g * 15 + hit * 5 + (if (words.isNotEmpty() && words.all { has(title, it) }) 10 else 0)
                }
            }
            for (e in ref.extra) if (has(title, e) || has(path, e)) score += 6
            scored.add(b to score)
        }
        // the library's own names (folders and files as they are on the device): always part of the search
        val request = raw ?: (listOf(ref.work) + ref.extra).joinToString(" ")
        val fromNames = try { Catalog.find(request, ref.volume, 20) } catch (e: Throwable) { emptyList() }
        val have = scored.map { it.first.id }.toHashSet()
        for (m in fromNames) {
            if (m.book.id in have) { val i = scored.indexOfFirst { it.first.id == m.book.id }; scored[i] = scored[i].first to (scored[i].second + (m.score * 3).toInt()) }
            else if (ref.kind == Ref.Kind.GENERIC || scored.isEmpty()) {
                // a Gemara / Sichos volume must still be the right one
                if (ref.kind == Ref.Kind.SICHOS && ref.volume != null) {
                    val v = PageFinder.volumeOf(m.book.title) ?: PageFinder.volumeOf(m.book.dir.substringAfterLast('/'))
                    if (v != null && v != ref.volume) continue
                }
                scored.add(m.book to (m.score * 3).toInt())
            }
        }
        val located = scored.sortedByDescending { it.second }.take(if (ref.siman != null || ref.chapter != null) 30 else 12).mapNotNull { (b, _) ->
            try { locate(b, ref) } catch (e: Throwable) { Target(b, 1, false, ref, null) }
        }
        // a siman / chapter: a work in several volumes – keep the volumes where it was really found
        if ((ref.siman != null || ref.chapter != null) && located.any { it.exact }) return located.filter { it.exact }.take(12)
        return located.take(12)
    }

    /** No such book: the closest names in the library ("did you mean…"). */
    fun suggest(text: String, max: Int = 5): List<Book> {
        val q = RefParser.normalize(text).split(' ').filter { it.length >= 2 && it !in setOf("דף", "עמוד", "חלק", "סימן", "פרק", "שיחה", "סעיף") && HebNum0(it) }
        if (q.isEmpty()) return emptyList()
        val qk = q.map(::key)
        return Db.get().books().values.map { b ->
            val tk = norm(b.title + " " + b.dir.substringAfterLast('/')).split(' ').filter { it.length >= 2 }.map(::key).toSet()
            b to qk.count { it in tk }
        }.filter { it.second > 0 }.sortedByDescending { it.second }.take(max).map { it.first }
    }

    private fun HebNum0(w: String) = com.chdv.searchplus.core.HebNum.gematria(w) == null || w.length > 3

    private fun locate(b: Book, ref: Ref): Target {
        val pages = Db.get().pageTexts(b.id)
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
                val words = RefParser.normalize(parsha).split(' ')
                val starts = pages.keys.sorted().filter { PageFinder.headerHas(pages[it]!!, words) }
                if (starts.isEmpty()) {
                    // search the whole page text for the parsha name as a heading word
                    val any = pages.keys.sorted().firstOrNull { p -> RefParser.normalize(pages[p]!!.take(600)).split(' ').containsAll(words) }
                    return Target(b, any ?: 1, any != null, ref, null)
                }
                val first = starts.first()
                val n = ref.sicha
                if (n != null && n > 1) {
                    val mark = com.chdv.searchplus.core.HebNum.toHeb(n)
                    val inRun = starts.filter { it - first < 120 }
                    inRun.firstOrNull { p -> Regex("(^|\\n)\\s*$mark\\s*[.)]").containsMatchIn(pages[p]!!.take(400)) }?.let { return Target(b, it, true, ref, null) }
                }
                return Target(b, first, true, ref, null)
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
                ref.parsha?.let { pr -> sorted.firstOrNull { PageFinder.headerHas(pages[it]!!, RefParser.normalize(pr).split(' ')) }?.let { return Target(b, it, true, ref, null) } }
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
