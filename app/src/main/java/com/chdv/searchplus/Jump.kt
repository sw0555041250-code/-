package com.chdv.searchplus

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

    private fun norm(s: String) = " " + RefParser.normalize(s.replace('_', ' ').replace('-', ' ').replace('.', ' ')) + " "

    private fun has(hay: String, phrase: String): Boolean {
        val p = RefParser.normalize(phrase)
        return hay.contains(" $p ") || hay.contains(" ב$p ") || hay.contains(" ו$p ") || hay.contains(" ה$p ") || hay.contains(" מ$p ") || hay.contains(" ל$p ")
    }

    /** Letters without the vowel letters – so "ליקוטי" = "לקוטי", "שולחן" = "שלחן". */
    private fun key(w: String) = w.filter { it !in "אהויע" }.replace('ך', 'כ').replace('ם', 'מ').replace('ן', 'נ').replace('ף', 'פ').replace('ץ', 'צ')

    fun resolve(ref: Ref): List<Target> {
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
                    val words = RefParser.normalize(ref.work).split(' ').filter { it.length >= 2 }
                    val tk = (title + path).split(' ').filter { it.length >= 2 }.map(::key).toSet()
                    val hit = words.count { has(title, it) || has(path, it) || (key(it).length >= 2 && key(it) in tk) }
                    if (hit < maxOf(1, (words.size + 1) / 2)) continue
                    score += hit * 5 + (if (words.all { has(title, it) }) 10 else 0)
                }
            }
            for (e in ref.extra) if (has(title, e) || has(path, e)) score += 6
            scored.add(b to score)
        }
        return scored.sortedByDescending { it.second }.take(12).mapNotNull { (b, _) ->
            try { locate(b, ref) } catch (e: Throwable) { Target(b, 1, false, ref, null) }
        }
    }

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
                ref.siman?.let { s -> sorted.firstOrNull { PageFinder.hasNumbered(pages[it]!!, "סימן", s) }?.let { return Target(b, it, true, ref, null) } }
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
