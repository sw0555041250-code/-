package com.chdv.searchplus

import java.text.Normalizer

/**
 * Hebrew text repair and normalisation.
 *
 * Old PDFs often give back broken text: Hebrew stored in "visual" (reversed) order, or Hebrew letters
 * hidden behind old Windows-1255 / DOS-862 code pages so they come out as Latin gibberish
 * ("ùîòåï" instead of "שמעון"). [repairPage] tries every repair and keeps the one that looks most like
 * real Hebrew. [clean] turns text into the form that is stored in the search index.
 */
object Heb {
    private const val FINALS = "ךםןףץ"
    private val COMMON = hashSetOf(
        "של", "את", "על", "הוא", "זה", "לא", "כי", "אשר", "עם", "גם", "כל", "אם", "מה", "היא", "הנה",
        "וכו", "אל", "או", "יש", "אין", "רק", "כמו", "אבל", "זו", "היה", "הם", "בו", "לו", "כן", "עד",
        "מן", "שהוא", "ולא", "וזה", "דהנה", "והנה", "להיות", "שלא", "אלא", "בזה", "ע\"ה", "כמ\"ש", "וגם",
        "ה", "ב", "לכן", "אך", "הרי", "מפני", "כמבואר", "שם", "בין", "לפי", "וכן", "אחד", "אותו", "היינו"
    )

    /** Prefix letters that may be glued to the front of a Hebrew word. */
    val PREFIXES = listOf(
        "ו", "ה", "ב", "כ", "ל", "מ", "ש", "ד", "וה", "וב", "ול", "ומ", "וש", "וכ", "וד",
        "שה", "שב", "של", "שמ", "מה", "כש", "לה", "בה", "דה", "כה", "וכש", "ושה", "מש"
    )

    fun isHebLetter(c: Char) = c in 'א'..'ת'

    // ---------- encoding / direction repair ----------

    private const val CP437_HEB = "ÇüéâäàåçêëèïîìÄÅÉæÆôöòûùÿÖÜ" // DOS-862 bytes 0x80..0x9A shown as CP437

    private fun fromCp1255Latin(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                in 'à'..'ú' -> sb.append('א' + (c - 'à'))
                in 'À'..'Ò' -> {} // cp1255 nikud bytes
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun fromCp862Controls(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(if (c in '\u0080'..'\u009A') 'א' + (c - '\u0080') else c)
        return sb.toString()
    }

    private fun fromCp862Cp437(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            val i = CP437_HEB.indexOf(c)
            sb.append(if (i >= 0) 'א' + i else c)
        }
        return sb.toString()
    }

    private fun mirror(c: Char): Char = when (c) {
        '(' -> ')'; ')' -> '('; '[' -> ']'; ']' -> '['; '{' -> '}'; '}' -> '{'; '<' -> '>'; '>' -> '<'
        else -> c
    }

    /** Reverse every line (fixes text stored in visual order). */
    fun reverseLines(s: String): String = s.split('\n').joinToString("\n") { line ->
        val sb = StringBuilder(line.length)
        for (i in line.length - 1 downTo 0) sb.append(mirror(line[i]))
        sb.toString()
    }

    /** How much does this look like correct, logically-ordered Hebrew? */
    fun score(s: String): Double {
        var score = 0.0
        var heb = 0
        val word = StringBuilder()
        fun flush() {
            if (word.isEmpty()) return
            val w = word.toString()
            if (w in COMMON) score += 3.0
            for (i in w.indices) {
                if (FINALS.indexOf(w[i]) >= 0) {
                    if (i == w.length - 1) score += 1.0 else score -= 2.0
                }
            }
            word.setLength(0)
        }
        for (c in s) {
            if (isHebLetter(c) || c == '"') {
                if (c != '"') heb++
                word.append(c)
            } else flush()
        }
        flush()
        return score + heb * 0.02
    }

    data class Repair(val text: String, val method: String)

    /** Pick the most Hebrew-looking interpretation of the raw text of one page. */
    fun repairPage(raw: String): Repair {
        val cands = ArrayList<Pair<String, String>>()
        cands.add(raw to "plain")
        var latin = 0; var ctrl = 0; var cp437 = 0
        for (c in raw) {
            if (c in 'à'..'ú') latin++
            if (c in '\u0080'..'\u009A') ctrl++
            if (CP437_HEB.indexOf(c) >= 0) cp437++
        }
        val n = raw.length.coerceAtLeast(1)
        if (latin * 20 > n) cands.add(fromCp1255Latin(raw) to "cp1255")
        if (ctrl * 20 > n) cands.add(fromCp862Controls(raw) to "cp862")
        if (cp437 * 20 > n) cands.add(fromCp862Cp437(raw) to "cp862b")
        var best = raw; var bestM = "plain"; var bestS = Double.NEGATIVE_INFINITY
        for ((t, m) in cands) {
            val s1 = score(t)
            if (s1 > bestS) { bestS = s1; best = t; bestM = m }
            val r = reverseLines(t)
            val s2 = score(r)
            if (s2 > bestS + 0.5) { bestS = s2; best = r; bestM = "$m+rev" }
        }
        return Repair(best, bestM)
    }

    /** Apply a repair chosen by [repairPage] (for the whole page) to a piece of the same page. */
    fun applyMethod(s: String, method: String): String {
        val base = method.substringBefore('+')
        var t = when (base) {
            "cp1255" -> fromCp1255Latin(s)
            "cp862" -> fromCp862Controls(s)
            "cp862b" -> fromCp862Cp437(s)
            else -> s
        }
        if (method.endsWith("+rev")) t = reverseLines(t)
        return t
    }

    // ---------- normalisation ----------

    private fun needsNfkd(s: String): Boolean {
        for (c in s) if (c.code >= 0xFB1D && c.code <= 0xFB4F || c.code in 0x00C0..0x024F || c.code in 0xFB00..0xFB06) return true
        return false
    }

    /**
     * The form stored in the index: no nikud / cantillation, Hebrew punctuation turned into ASCII
     * punctuation (which the index treats as word breaks), lower-case latin, single spaces.
     */
    fun clean(input: String): String {
        val s = if (needsNfkd(input)) Normalizer.normalize(input, Normalizer.Form.NFKD) else input
        val sb = StringBuilder(s.length)
        var lastSpace = true
        fun sp() { if (!lastSpace) { sb.append(' '); lastSpace = true } }
        for (c in s) {
            when {
                c == '\n' || c == '\r' -> {
                    while (sb.isNotEmpty() && sb[sb.length - 1] == ' ') sb.setLength(sb.length - 1)
                    if (sb.isNotEmpty() && sb[sb.length - 1] != '\n') sb.append('\n')
                    lastSpace = true
                }
                c == '־' || c == '‐' || c == '‑' || c == '–' || c == '—' -> { sb.append('-'); lastSpace = false }
                c == '׃' -> { sb.append(':'); lastSpace = false }
                c == '׳' || c == '‘' || c == '’' || c == '‚' || c == '`' || c == '´' -> { sb.append('\''); lastSpace = false }
                c == '״' || c == '“' || c == '”' || c == '„' -> { sb.append('"'); lastSpace = false }
                c in '֑'..'ׇ' -> {} // nikud, cantillation marks
                c in '​'..'‏' || c in '‪'..'‮' || c in '⁦'..'⁩' || c == '﻿' -> {}
                Character.getType(c) == Character.NON_SPACING_MARK.toInt() -> {}
                c.isLetterOrDigit() -> { sb.append(c.lowercaseChar()); lastSpace = false }
                c.isWhitespace() || c.code < 32 -> sp()
                c.code < 128 -> { sb.append(c); lastSpace = false }
                else -> sp()
            }
        }
        return sb.toString().trim()
    }

    /** Words (letters/digits only) of a piece of text, in the same form as the index. */
    fun words(s: String): List<String> {
        val out = ArrayList<String>()
        val w = StringBuilder()
        for (c in clean(s)) {
            if (c.isLetterOrDigit() || c.code >= 128) w.append(c)
            else if (w.isNotEmpty()) { out.add(w.toString()); w.setLength(0) }
        }
        if (w.isNotEmpty()) out.add(w.toString())
        return out
    }

    /** Count of real letters in a page; used to decide whether a page has a usable text layer. */
    fun letterCount(s: String): Int {
        var n = 0
        for (c in s) if (isHebLetter(c) || c in 'a'..'z' || c in 'A'..'Z') n++
        return n
    }

    /** Does extracted text look like garbage (wrong font encoding)? */
    fun looksBroken(s: String): Boolean {
        var letters = 0; var good = 0
        for (c in s) {
            if (c.isLetter()) {
                letters++
                if (isHebLetter(c) || c in 'a'..'z' || c in 'A'..'Z') good++
            }
        }
        return letters >= 40 && good * 2 < letters
    }
}
