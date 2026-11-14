package com.chdv.searchplus.core

/**
 * Understanding a spoken or typed reference to a place in a book:
 * "מסכת ברכות דף כז עמוד א", "ברכות כ"ז ע"ב", "ליקוטי שיחות חלק ג שיחה ב לפרשת ויצא",
 * "תניא פרק ה", "דף כף זין" (letter names, as people say them aloud), "דף עשרים ושבע".
 */
object HebNum {
    private val VAL = mapOf(
        'א' to 1, 'ב' to 2, 'ג' to 3, 'ד' to 4, 'ה' to 5, 'ו' to 6, 'ז' to 7, 'ח' to 8, 'ט' to 9,
        'י' to 10, 'כ' to 20, 'ך' to 20, 'ל' to 30, 'מ' to 40, 'ם' to 40, 'נ' to 50, 'ן' to 50, 'ס' to 60, 'ע' to 70,
        'פ' to 80, 'ף' to 80, 'צ' to 90, 'ץ' to 90, 'ק' to 100, 'ר' to 200, 'ש' to 300, 'ת' to 400,
    )

    /** Names of the letters, as they are said aloud (and as speech recognition tends to write them). */
    val LETTER_NAMES: Map<String, Char> = buildMap {
        fun n(c: Char, vararg names: String) = names.forEach { put(it, c) }
        n('א', "אלף", "אלפ", "אלעף")
        n('ב', "בית", "בייט", "בת", "בס", "בעת", "בית'")
        n('ג', "גימל", "גמל", "גימעל")
        n('ד', "דלת", "דלד", "דלט", "דאלעד", "דאלת")
        n('ה', "הא", "הי", "היי", "הה", "האי")
        n('ו', "וו", "ואו", "וב", "וואו", "ווב")
        n('ז', "זין", "זיין", "זאין", "זן", "זיון")
        n('ח', "חית", "חת", "חס", "חיס", "חיט")
        n('ט', "טית", "טת", "טס", "טיס", "טט")
        n('י', "יוד", "יוּד", "יד", "יאד", "יודה")
        n('כ', "כף", "כאף", "קף", "חף", "כפ", "קאף", "כאפ", "חב", "כב", "חאף", "חאב", "כהף")
        n('ל', "למד", "לאמד", "למעד", "לאמעד", "למ")
        n('מ', "מם", "מים", "מעם", "מאם")
        n('נ', "נון", "נוּן", "נן")
        n('ס', "סמך", "סאמעך", "סמח", "סאמך")
        n('ע', "עין", "עיין", "איין", "אין")
        n('פ', "פא", "פה", "פי", "פיי", "פאי")
        n('צ', "צדי", "צדיק", "צאדי", "צדיי", "צדיה")
        n('ק', "קוף", "קופ", "קוּף")
        n('ר', "ריש", "רייש", "רש", "רעש")
        n('ש', "שין", "שיין", "שן", "סין")
        n('ת', "תו", "תיו", "תוו", "תף", "תאו", "סאף", "תאף")
    }

    private val UNITS = mapOf(
        "אחד" to 1, "אחת" to 1, "ראשון" to 1, "ראשונה" to 1,
        "שתיים" to 2, "שתים" to 2, "שניים" to 2, "שנים" to 2, "שני" to 2, "שנייה" to 2, "שניה" to 2, "שתי" to 2,
        "שלוש" to 3, "שלושה" to 3, "שלש" to 3, "שלשה" to 3, "שלישי" to 3, "שלישית" to 3,
        "ארבע" to 4, "ארבעה" to 4, "רביעי" to 4, "רביעית" to 4,
        "חמש" to 5, "חמישה" to 5, "חמשה" to 5, "חמישי" to 5, "חמישית" to 5,
        "שש" to 6, "שישה" to 6, "ששה" to 6, "שישי" to 6, "שישית" to 6,
        "שבע" to 7, "שבעה" to 7, "שביעי" to 7, "שביעית" to 7,
        "שמונה" to 8, "שמיני" to 8, "שמינית" to 8,
        "תשע" to 9, "תשעה" to 9, "תשיעי" to 9, "תשיעית" to 9,
        "עשר" to 10, "עשרה" to 10, "עשירי" to 10, "עשירית" to 10,
    )
    private val TENS = mapOf(
        "עשרים" to 20, "שלושים" to 30, "שלשים" to 30, "ארבעים" to 40, "חמישים" to 50, "חמשים" to 50,
        "שישים" to 60, "ששים" to 60, "שבעים" to 70, "שמונים" to 80, "תשעים" to 90,
        "מאה" to 100, "מאתיים" to 200, "מאתים" to 200,
    )

    /** Value of a Hebrew-letter number written as one word (כז, קנג, טו). Null when the word is not a number. */
    fun gematria(word: String): Int? {
        val w = word.filter { it != '"' && it != '\'' && it != '״' && it != '׳' }
        if (w.isEmpty() || w.length > 4) return null
        var sum = 0
        var last = Int.MAX_VALUE
        for ((i, ch) in w.withIndex()) {
            val v = VAL[ch] ?: return null
            // final letters only at the end, letters go from large to small (except טו / טז)
            if (ch in "ךםןףץ" && i != w.length - 1) return null
            if (v > last && !(w.endsWith("טו") || w.endsWith("טז"))) return null
            if (v == last && v != 400 && v != 100) return null
            last = v
            sum += v
        }
        return sum
    }

    fun toHeb(n: Int): String {
        if (n <= 0) return n.toString()
        val sb = StringBuilder()
        var x = n
        while (x >= 400) { sb.append('ת'); x -= 400 }
        for ((v, c) in listOf(300 to 'ש', 200 to 'ר', 100 to 'ק')) if (x >= v) { sb.append(c); x -= v }
        if (x == 15) return sb.append("טו").toString()
        if (x == 16) return sb.append("טז").toString()
        for ((v, c) in listOf(90 to 'צ', 80 to 'פ', 70 to 'ע', 60 to 'ס', 50 to 'נ', 40 to 'מ', 30 to 'ל', 20 to 'כ', 10 to 'י')) if (x >= v) { sb.append(c); x -= v; break }
        if (x > 0) sb.append("אבגדהוזחט"[x - 1])
        return sb.toString()
    }

    /**
     * Read a number that starts at tokens[i]. Accepts: digits (27), letters (כז / כ"ז), letter names
     * (כף זין), number words (עשרים ושבע, שבע ועשרים, שבעה עשר). Returns value and how many tokens were used.
     */
    fun read(tokens: List<String>, i: Int, allowPlainWord: Boolean = true): Pair<Int, Int>? {
        if (i >= tokens.size) return null
        val t = tokens[i]
        t.toIntOrNull()?.let { return it to 1 }
        Regex("^(\\d+)[אבab.:]?$").find(t)?.let { return it.groupValues[1].toInt() to 1 }
        // letter names: "כף זין", "קוף נון גימל"
        run {
            var j = i
            val sb = StringBuilder()
            while (j < tokens.size && sb.length < 4) {
                val c = LETTER_NAMES[stripVav(tokens[j], j > i)]
                if (c != null) { sb.append(c); j++; continue }
                // two letter names written as one word by the speech recognizer: "כפזין", "חבזין"
                val glued = glued(tokens[j]) ?: break
                sb.append(glued); j++
            }
            if (j - i == 1 && sb.length >= 2) gematria(sb.toString())?.let { return it to 1 }
            if (j - i >= 2 || (j - i == 1 && tokens[i].length >= 2 && tokens[i] !in UNITS && tokens[i] !in TENS && tokens[i] in STRONG_NAMES)) {
                gematria(sb.toString())?.let { return it to (j - i) }
            }
        }
        // number words
        run {
            var j = i
            var total = 0
            var used = false
            while (j < tokens.size) {
                val w = stripVav(tokens[j], used)
                val u = UNITS[w]; val tn = TENS[w]
                when {
                    tn != null -> { total += tn; used = true; j++ }
                    u != null -> {
                        // "שבעה עשר" = 17
                        if (j + 1 < tokens.size && (tokens[j + 1] == "עשר" || tokens[j + 1] == "עשרה") && u < 10) { total += u + 10; j += 2 }
                        else { total += u; j++ }
                        used = true
                    }
                    else -> break
                }
            }
            if (used && total > 0) return total to (j - i)
        }
        if (allowPlainWord) gematria(t)?.let { return it to 1 }
        return null
    }

    private fun glued(w: String): String? {
        if (w.length < 4) return null
        for (k in 2..w.length - 2) {
            val a = LETTER_NAMES[w.substring(0, k)] ?: continue
            val b = LETTER_NAMES[w.substring(k)] ?: continue
            return "$a$b"
        }
        return null
    }

    /** Letter names that are clearly a number even when said alone ("דף זין"). */
    private val STRONG_NAMES = setOf("אלף", "בית", "גימל", "דלת", "זין", "חית", "טית", "יוד", "כף", "למד", "מם", "נון", "סמך", "עין", "צדי", "קוף", "ריש", "שין", "תו")

    private fun stripVav(w: String, allowed: Boolean): String =
        if (allowed && w.length > 2 && w[0] == 'ו' && w !in UNITS && w !in TENS && w !in LETTER_NAMES) w.substring(1) else w
}

data class Ref(
    val kind: Kind,
    /** Work name as it should appear in a book's file or folder name (ברכות, לקוטי שיחות, תניא...). */
    val work: String,
    /** Other words said, used to choose between books (edition names like שוטנשטיין, וילנא, עוז והדר). */
    val extra: List<String> = emptyList(),
    val volume: Int? = null,
    val daf: Int? = null,
    /** 1 = עמוד א, 2 = עמוד ב, null = not said. */
    val amud: Int? = null,
    val chapter: Int? = null,
    val siman: Int? = null,
    val sicha: Int? = null,
    val parsha: String? = null,
    /** A printed page number ("עמוד 45" in a book that is not Gemara). */
    val page: Int? = null,
) {
    enum class Kind { GEMARA, SICHOS, GENERIC }

    /** Short Hebrew description, e.g. "ברכות דף כז ע״א". */
    fun label(): String = buildString {
        append(work)
        volume?.let { append(" חלק ").append(HebNum.toHeb(it)) }
        parsha?.let { append(" – ").append(it) }
        sicha?.let { append(" שיחה ").append(HebNum.toHeb(it)) }
        daf?.let { append(" דף ").append(HebNum.toHeb(it)) }
        amud?.let { append(if (it == 1) " ע״א" else " ע״ב") }
        chapter?.let { append(" פרק ").append(HebNum.toHeb(it)) }
        siman?.let { append(" סימן ").append(HebNum.toHeb(it)) }
        page?.let { append(" עמוד ").append(it) }
    }
}

object RefParser {
    val MASECHTOT: List<String> = listOf(
        "ברכות", "שבת", "עירובין", "פסחים", "שקלים", "יומא", "סוכה", "ביצה", "ראש השנה", "תענית", "מגילה",
        "מועד קטן", "חגיגה", "יבמות", "כתובות", "נדרים", "נזיר", "סוטה", "גיטין", "קידושין", "בבא קמא",
        "בבא מציעא", "בבא בתרא", "סנהדרין", "מכות", "שבועות", "עבודה זרה", "הוריות", "זבחים", "מנחות",
        "חולין", "בכורות", "ערכין", "תמורה", "כריתות", "מעילה", "תמיד", "נדה",
    )

    /** Other spellings → the standard name. */
    private val MAS_ALIASES = mapOf(
        "ערובין" to "עירובין", "עירובן" to "עירובין", "פסחין" to "פסחים", "סוכא" to "סוכה", "ביצא" to "ביצה",
        "מגלה" to "מגילה", "כתובות" to "כתובות", "כתובת" to "כתובות", "קדושין" to "קידושין", "גטין" to "גיטין",
        "בק" to "בבא קמא", "במ" to "בבא מציעא", "בב" to "בבא בתרא", "בבא קמה" to "בבא קמא", "בבא מציאה" to "בבא מציעא",
        "עז" to "עבודה זרה", "עבודה זרא" to "עבודה זרה", "מק" to "מועד קטן", "רה" to "ראש השנה", "חלין" to "חולין",
        "נידה" to "נדה", "סנהדרן" to "סנהדרין", "שבועת" to "שבועות", "יבמת" to "יבמות", "זבחין" to "זבחים",
        "מנחת" to "מנחות", "בכורת" to "בכורות", "כריתת" to "כריתות", "הוריות" to "הוריות", "הוריוט" to "הוריות",
        "נזר" to "נזיר", "סוטא" to "סוטה", "יומה" to "יומא", "שקלין" to "שקלים", "תמורא" to "תמורה",
    )

    val PARSHIYOS: List<String> = listOf(
        "בראשית", "נח", "לך לך", "וירא", "חיי שרה", "תולדות", "ויצא", "וישלח", "וישב", "מקץ", "ויגש", "ויחי",
        "שמות", "וארא", "בא", "בשלח", "יתרו", "משפטים", "תרומה", "תצוה", "כי תשא", "ויקהל", "פקודי",
        "ויקרא", "צו", "שמיני", "תזריע", "מצורע", "אחרי מות", "אחרי", "קדושים", "אמור", "בהר", "בחוקותי", "בחקתי",
        "במדבר", "נשא", "בהעלותך", "שלח", "קרח", "חוקת", "חקת", "בלק", "פנחס", "מטות", "מסעי",
        "דברים", "ואתחנן", "עקב", "ראה", "שופטים", "כי תצא", "כי תבוא", "נצבים", "וילך", "האזינו", "וזאת הברכה",
        // festivals and special days that have sichos
        "ראש השנה", "יום כיפור", "סוכות", "שמיני עצרת", "שמחת תורה", "חנוכה", "פורים", "פסח", "שבועות",
        "ל״ג בעומר", "לג בעומר", "תשעה באב", "ט״ו באב", "חמשה עשר באב", "י״ט כסלו", "יט כסלו", "י״ב תמוז", "יב תמוז",
    )
    private val PARSHA_ALIASES = mapOf("תצווה" to "תצוה", "בחוקתי" to "בחוקותי", "פינחס" to "פנחס", "מטת" to "מטות",
        "חיי שרא" to "חיי שרה", "נוח" to "נח", "שמיני עצרת" to "שמיני עצרת", "בחקותי" to "בחוקותי", "ויצה" to "ויצא", "וישלך" to "וישלח", "שלח לך" to "שלח", "קורח" to "קרח")

    private val SICHOS_NAMES = listOf("ליקוטי שיחות", "לקוטי שיחות", "ליקוטי שיחת", "לקוטי שיחת", "לקוש", "ליקוש", "לקש")
    private val GEMARA_WORDS = setOf("גמרא", "גמרה", "גמ", "תלמוד", "בבלי", "מסכת", "מסכתא", "מסכתות", "שס", "ש\"ס")

    /** Spoken filler words that carry no meaning for finding the place. */
    private val FILLER = setOf(
        "תפתח", "תפתחי", "תח", "תפתחו", "פתיחת", "ספר", "בספר", "הספר", "פתח", "פתחי", "תביא", "תמצא", "מצא", "חפש", "תחפש", "תחפשי", "לי", "את", "של", "במהדורת",
        "מהדורת", "מהדורה", "בבקשה", "אני", "רוצה", "ללמוד", "לפתוח", "תראה", "הראה", "על", "לפרשת", "בפרשת", "פרשת", "פרשה",
        "פ", "ה", "או", "עם", "ב", "ל", "דף", "עמוד", "עמ", "חלק", "כרך", "פרק", "סימן", "שיחה", "שיחת", "שיחות",
    )

    fun normalize(s: String): String {
        var t = s.replace(Regex("[\\u0591-\\u05C7]"), "") // nikud & teamim
        t = t.replace('״', '"').replace('׳', '\'').replace('“', '"').replace('”', '"').replace('’', '\'').replace('`', '\'')
        t = t.replace(Regex("(?<=[א-ת])['\"](?=[.:?!]?(\\s|$))"), "") // ה׳. → ה.
        // amud marks: "כז." = amud a, "כז:" = amud b → keep as words
        t = t.replace(Regex("(?<=[א-ת\\d])\\.(?=\\s|$)"), " ע\"א ").replace(Regex("(?<=[א-ת\\d]):(?=\\s|$)"), " ע\"ב ")
        t = t.replace(Regex("[,;!?()\\[\\]{}–—\\-/]"), " ").replace(Regex("[.:](?=\\s|$)"), " ")
        t = t.replace("\"", "").replace("'", "")
        return t.replace(Regex("\\s+"), " ").trim()
    }

    fun parse(input: String): Ref? {
        val norm = normalize(input)
        if (norm.isEmpty()) return null
        val tokens = norm.split(' ').toMutableList()
        val text = " $norm "

        // ---- which work ----
        var kind = Ref.Kind.GENERIC
        var work: String? = null
        val used = HashSet<Int>()

        fun findPhrase(p: String): Int {
            val ws = p.split(' ')
            outer@ for (i in 0..tokens.size - ws.size) {
                for (k in ws.indices) {
                    val t = tokens[i + k]
                    if (t != ws[k] && !(k == 0 && (t == "ב" + ws[k] || t == "ל" + ws[k] || t == "מ" + ws[k] || t == "ו" + ws[k]))) continue@outer
                }
                return i
            }
            return -1
        }

        for (n in SICHOS_NAMES) {
            val i = findPhrase(n)
            if (i >= 0) { kind = Ref.Kind.SICHOS; work = "לקוטי שיחות"; repeat(n.split(' ').size) { used.add(i + it) }; break }
        }
        if (work == null) {
            val all = MASECHTOT.map { it to it } + MAS_ALIASES.entries.map { it.key to it.value }
            for ((spelling, name) in all.sortedByDescending { it.first.length }) {
                if (spelling.length <= 2 && !(text.contains(" דף ") || tokens.any { it in GEMARA_WORDS })) continue
                val i = findPhrase(spelling)
                if (i >= 0) {
                    // "שבת" / "ראש השנה" / "שבועות" can also be a festival or a word: needs a Gemara word, or a daf
                    val needsCtx = name in setOf("שבת", "ראש השנה", "שבועות", "תמיד", "ביצה", "סוכה", "מכות", "נזיר", "תענית")
                    if (needsCtx && !(tokens.any { it in GEMARA_WORDS } || tokens.contains("דף") || looksLikeDafAfter(tokens, i + spelling.split(' ').size))) continue
                    kind = Ref.Kind.GEMARA; work = name; repeat(spelling.split(' ').size) { used.add(i + it) }; break
                }
            }
        }

        // speech recognition often spells a masechet name wrongly ("בבה מיציה"): compare without the vowel letters
        if (work == null && (tokens.contains("דף") || tokens.any { it in GEMARA_WORDS })) {
            fun key(w: String) = w.filter { it !in "אהעיו" }.replace('ך', 'כ').replace('ם', 'מ').replace('ן', 'נ').replace('ף', 'פ').replace('ץ', 'צ')
            loop@ for (name in MASECHTOT.sortedByDescending { it.length }) {
                val ws = name.split(' ')
                val nk = ws.map(::key)
                if (nk.any { it.length < 2 }) continue
                for (k in 0..tokens.size - ws.size) {
                    if ((0 until ws.size).all { key(tokens[k + it]) == nk[it] && tokens[k + it] != "דף" }) {
                        kind = Ref.Kind.GEMARA; work = name; repeat(ws.size) { used.add(k + it) }; break@loop
                    }
                }
            }
        }

        // ---- numbers by their keyword ----
        var volume: Int? = null; var daf: Int? = null; var amud: Int? = null; var chapter: Int? = null
        var siman: Int? = null; var sicha: Int? = null; var page: Int? = null
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            fun num(): Int? {
                val r = HebNum.read(tokens, i + 1) ?: return null
                for (k in 0..r.second) used.add(i + k)
                i += r.second
                return r.first
            }
            when (t) {
                "דף", "בדף", "לדף" -> { num()?.let { daf = it } }
                "חלק", "בחלק", "כרך", "בכרך" -> {
                    // "חלק ח' ד'" = חלק ד (the ח stands for חלק)
                    if (tokens.getOrNull(i + 1) == "ח" && HebNum.read(tokens, i + 2, allowPlainWord = true)?.first?.let { it in 1..39 } == true) { used.add(i); i++ }
                    num()?.let { volume = it }
                }
                "פרק", "בפרק", "לפרק" -> { num()?.let { chapter = it } }
                "סימן", "בסימן", "סעיף" -> { num()?.let { siman = it } }
                "שיחה", "בשיחה", "שיחת" -> { num()?.let { sicha = it } }
                "עמוד", "בעמוד", "עמ", "צד", "צפחה" -> {
                    // after a daf: amud a/b. Otherwise: a page number.
                    val nx = tokens.getOrNull(i + 1)
                    val ab = amudOf(nx)
                    if ((daf != null || kind == Ref.Kind.GEMARA) && ab != null) { amud = ab; used.add(i); used.add(i + 1); i++ }
                    else num()?.let { if (daf != null && it in 1..2) amud = it else page = it }
                }
                "עא", "עב" -> { if (kind == Ref.Kind.GEMARA) amud = if (t == "עא") 1 else 2; used.add(i) }
            }
            i++
        }
        // "ברכות כז" / "ברכות כז ע"א" without the word דף
        if (kind == Ref.Kind.GEMARA && daf == null) {
            for (k in tokens.indices) {
                if (k in used) continue
                val r = HebNum.read(tokens, k) ?: continue
                if (r.first in 2..200) { daf = r.first; for (x in 0 until r.second) used.add(k + x); break }
            }
        }
        if (kind == Ref.Kind.GEMARA && daf != null && amud == null) {
            // "דף כז א" / "דף כז ב"
            for (k in tokens.indices) if (k !in used && (tokens[k] == "א" || tokens[k] == "ב" || tokens[k] == "אלף" || tokens[k] == "בית")) {
                amud = if (tokens[k].startsWith("א")) 1 else 2; used.add(k); break
            }
        }
        // "חלק ח' ד'" – people sometimes say the letter ח (for חלק) before the number
        if (volume == null) {
            for (k in 0 until tokens.size - 1) if (k !in used && tokens[k] == "ח") {
                val r = HebNum.read(tokens, k + 1) ?: continue
                if (r.first in 1..39) { volume = r.first; used.add(k); for (x in 0 until r.second) used.add(k + 1 + x); break }
            }
        }
        // ליקוטי שיחות: "חלק ג" may come as "ח"ג" → "חג"
        if (kind == Ref.Kind.SICHOS && volume == null) {
            for (k in tokens.indices) {
                val w = tokens[k]
                if (k !in used && w.length in 2..3 && w[0] == 'ח') HebNum.gematria(w.substring(1))?.let { if (it in 1..39) { volume = it; used.add(k) } }
                if (volume != null) break
            }
            if (volume == null) for (k in tokens.indices) {
                if (k in used) continue
                val r = HebNum.read(tokens, k, allowPlainWord = false) ?: tokens[k].toIntOrNull()?.let { it to 1 } ?: continue
                if (r.first in 1..39) { volume = r.first; for (x in 0 until r.second) used.add(k + x); break }
            }
        }

        // ---- parsha ----
        var parsha: String? = null
        val pList = PARSHIYOS.map { it to it } + PARSHA_ALIASES.entries.map { it.key to it.value }
        for ((sp, name) in pList.sortedByDescending { it.first.length }) {
            if (kind == Ref.Kind.GEMARA) break
            val n = normalize(sp)
            val k = findPhrase(n)
            if (k >= 0 && (0 until n.split(' ').size).none { (k + it) in used }) {
                // short parsha names that are also common words need the word פרשה / a Sichos context
                if (n in setOf("בא", "צו", "ראה", "נח", "שלח", "עקב", "אחרי") && kind != Ref.Kind.SICHOS && !text.contains("פרש")) continue
                parsha = name.replace("״", ""); repeat(n.split(' ').size) { used.add(k + it) }; break
            }
        }

        if (kind == Ref.Kind.GENERIC) {
            val anyNumber = chapter != null || siman != null || page != null || daf != null || volume != null || sicha != null || parsha != null
            if (!anyNumber) return null
        }

        val extra = tokens.filterIndexed { k, w -> k !in used && w !in FILLER && w !in GEMARA_WORDS && w.length >= 2 }
        if (kind == Ref.Kind.GENERIC) {
            if (extra.isEmpty()) return null
            work = extra.joinToString(" ")
        }
        return Ref(kind, work!!, if (kind == Ref.Kind.GENERIC) emptyList() else extra,
            volume, daf, amud, chapter, siman, sicha, parsha, page)
    }

    private fun looksLikeDafAfter(tokens: List<String>, i: Int): Boolean {
        val r = HebNum.read(tokens, i) ?: return false
        return r.first in 2..180
    }

    private fun amudOf(w: String?): Int? = when (w) {
        null -> null
        "א", "אלף", "ראשון", "1", "a" -> 1
        "ב", "בית", "שני", "2", "b" -> 2
        else -> null
    }
}

/** Finding where a daf / chapter / parsha starts inside a book, from the text of its pages. */
object PageFinder {
    /** Amud number counted from 2a: 2a=0, 2b=1, 3a=2 ... */
    fun amudIndex(daf: Int, amud: Int) = (daf - 2) * 2 + (amud - 1)

    /**
     * Daf marks in the top (or bottom) lines of a page: "ברכות כז.", "דף כז ע"א", "27a", "כז:" etc.
     * Returns the amud indexes found.
     */
    fun dafMarks(pageText: String, masechet: String?): List<Int> {
        val out = ArrayList<Int>()
        val head = edge(pageText)
        // Latin "27a" / "27b" (Schottenstein / Artscroll)
        Regex("\\b(\\d{1,3})\\s?([ab])\\b").findAll(head).forEach { m ->
            val d = m.groupValues[1].toInt(); if (d in 2..180) out.add(amudIndex(d, if (m.groupValues[2] == "a") 1 else 2))
        }
        val t = RefParser.normalize(head)
        val ws = t.split(' ')
        for (k in ws.indices) {
            val w = ws[k]
            val afterMas = masechet != null && k > 0 && (ws[k - 1] == masechet.substringAfterLast(' '))
            if (w == "דף" || afterMas) {
                val idx = if (w == "דף") k + 1 else k
                val word = ws.getOrNull(idx) ?: continue
                val d = HebNum.gematria(word) ?: word.toIntOrNull() ?: continue
                if (d !in 2..180) continue
                val next = ws.getOrNull(idx + 1)
                val a = when (next) { "עא" -> 1; "עב" -> 2; else -> 0 }
                if (a == 0) { out.add(amudIndex(d, 1)); out.add(amudIndex(d, 2)) } else out.add(amudIndex(d, a))
            }
        }
        return out
    }

    private fun edge(s: String): String = if (s.length <= 360) s else s.substring(0, 180) + "\n" + s.substring(s.length - 180)

    /**
     * Given anchors (pdf page → amud index) found in a book, estimate the page of the wanted amud.
     * Keeps only anchors that agree with each other (pages and amudim must both go forward).
     */
    fun estimate(anchors: List<Pair<Int, Int>>, target: Int, pageCount: Int): Int? {
        if (anchors.isEmpty()) return null
        // longest chain where page and amud both increase
        val a = anchors.sortedWith(compareBy({ it.first }, { it.second }))
        val n = a.size
        val len = IntArray(n) { 1 }; val prev = IntArray(n) { -1 }
        for (i in 0 until n) for (j in 0 until i) {
            if (a[j].first < a[i].first && a[j].second < a[i].second && (a[i].second - a[j].second) <= (a[i].first - a[j].first) * 2 + 2 && len[j] + 1 > len[i]) {
                len[i] = len[j] + 1; prev[i] = j
            }
        }
        var best = (0 until n).maxByOrNull { len[it] } ?: return null
        val chain = ArrayList<Pair<Int, Int>>()
        while (best >= 0) { chain.add(a[best]); best = prev[best] }
        chain.reverse()
        if (chain.size < 2) {
            val (p, x) = chain[0]
            return (p + (target - x)).coerceIn(1, pageCount)
        }
        // exact hit
        chain.firstOrNull { it.second == target }?.let { return it.first }
        val lo = chain.lastOrNull { it.second < target }
        val hi = chain.firstOrNull { it.second > target }
        val (p0, x0, p1, x1) = when {
            lo != null && hi != null -> listOf(lo.first, lo.second, hi.first, hi.second)
            hi == null -> { val l = chain.takeLast(2); listOf(l[0].first, l[0].second, l[1].first, l[1].second) }
            else -> { val l = chain.take(2); listOf(l[0].first, l[0].second, l[1].first, l[1].second) }
        }
        val rate = (p1 - p0).toDouble() / (x1 - x0).coerceAtLeast(1)
        return (p0 + Math.round((target - x0) * rate).toInt()).coerceIn(1, pageCount)
    }

    /** Is this page the start of a section called [name] (parsha, chapter...)? Looks at the top lines. */
    fun headerHas(pageText: String, words: List<String>): Boolean {
        val head = RefParser.normalize(pageText.take(220))
        val hw = head.split(' ').toSet()
        return words.all { w -> w in hw || hw.any { it.length > 2 && (it == "ב$w" || it == "ל$w" || it == "ו$w") } }
    }

    /** "פרק ה" / "סימן קכג" / "שיחה ב" at the top of a page. */
    fun hasNumbered(pageText: String, keyword: String, n: Int): Boolean {
        val head = RefParser.normalize(pageText.take(260)).split(' ')
        for (k in 0 until head.size - 1) if (head[k] == keyword || head[k] == "ה$keyword") {
            val v = HebNum.gematria(head[k + 1]) ?: head[k + 1].toIntOrNull()
            if (v == n) return true
        }
        return false
    }

    /**
     * The printed page number of a page, from its first or last line ("58", "נח", "- 58 -").
     * Returns all candidate numbers (the caller keeps the ones that agree with neighbouring pages).
     */
    fun printedNumbers(pageText: String): List<Int> {
        val out = ArrayList<Int>()
        val lines = pageText.trim().split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return out
        for (line in listOf(lines.first(), lines.last()) + (if (lines.size > 1) listOf(lines[1], lines[lines.size - 2]) else emptyList())) {
            val ws = RefParser.normalize(line).split(' ').filter { it.isNotEmpty() }
            for (w in listOf(ws.firstOrNull(), ws.lastOrNull())) {
                if (w == null) continue
                w.toIntOrNull()?.let { if (it in 1..2000) out.add(it) }
                if (w.length in 1..4 && line.length < 80) HebNum.gematria(w)?.let { if (it in 1..999) out.add(it) }
            }
        }
        return out.distinct()
    }

    /** PDF page of a printed page number, from the numbers printed on the pages of the book. */
    fun pageOfPrinted(pages: Map<Int, String>, printed: Int, pageCount: Int): Pair<Int, Boolean> {
        val anchors = ArrayList<Pair<Int, Int>>()
        for ((p, t) in pages) for (n in printedNumbers(t)) {
            // keep numbers that fit "printed ≈ pdf page - offset" for some plausible offset
            if (n <= p + 40 && n >= p - 1500) anchors.add(p to n)
        }
        // the most common offset (pdf page − printed page) is the book's numbering
        val offsets = anchors.groupingBy { it.first - it.second }.eachCount()
        val best = offsets.maxByOrNull { it.value }
        if (best != null && best.value >= 3) {
            val exact = anchors.firstOrNull { it.second == printed && it.first - it.second == best.key }
            if (exact != null) return exact.first to true
            // offsets can change (plates, two numberings): take the anchors near the wanted page
            val near = anchors.filter { (pp, n) -> kotlin.math.abs(n - printed) < 60 && offsets[pp - n]!! >= 2 }
            val local = near.groupingBy { it.first - it.second }.eachCount().maxByOrNull { it.value }?.key ?: best.key
            return (printed + local).coerceIn(1, pageCount) to true
        }
        return printed.coerceIn(1, pageCount) to false
    }

    /** How well a book file matches a wanted volume (חלק ג / 3 / ג׳ in the name). */
    fun volumeOf(name: String): Int? {
        val n = RefParser.normalize(name.replace('_', ' ').replace('.', ' '))
        Regex("(?:^|\\s)(?:חלק|כרך|ח)\\s*([א-ת]{1,2}|\\d{1,2})(?:\\s|$)").findAll(n).forEach { m ->
            val g = m.groupValues[1]; (g.toIntOrNull() ?: HebNum.gematria(g))?.let { return it }
        }
        Regex("(?:^|\\s)(\\d{1,2})(?:\\s|$)").find(n)?.let { return it.groupValues[1].toInt() }
        // "לקוטי שיחות ג" – a lone letter number at the end
        Regex("\\s([א-ת]{1,2})$").find(n)?.let { m -> HebNum.gematria(m.groupValues[1])?.let { if (it <= 39) return it } }
        return null
    }
}
