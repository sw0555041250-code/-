package com.chdv.searchplus.core

import java.io.Reader

/** A very small JSON reader (objects → Map, arrays → List, numbers → Double). Enough for tokenizer files. */
class Json private constructor(private val r: Reader) {
    private var ch = -2

    private fun peek(): Int { if (ch == -2) ch = r.read(); return ch }
    private fun next(): Int { val c = peek(); ch = -2; return c }
    private fun ws() { while (true) { val c = peek(); if (c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code) next() else return } }
    private fun expect(c: Char) { ws(); val x = next(); if (x != c.code) throw IllegalStateException("JSON: expected $c got ${if (x < 0) "EOF" else x.toChar().toString()}") }

    fun value(): Any? {
        ws()
        return when (val c = peek()) {
            '{'.code -> obj()
            '['.code -> arr()
            '"'.code -> str()
            't'.code -> { lit("true"); true }
            'f'.code -> { lit("false"); false }
            'n'.code -> { lit("null"); null }
            else -> if (c == '-'.code || (c >= '0'.code && c <= '9'.code)) num() else throw IllegalStateException("JSON: bad char ${c.toChar()}")
        }
    }

    private fun lit(s: String) { for (c in s) if (next() != c.code) throw IllegalStateException("JSON: bad literal") }

    private fun num(): Double {
        val sb = StringBuilder()
        while (true) {
            val c = peek()
            if (c < 0 || !(c.toChar().isDigit() || c == '-'.code || c == '+'.code || c == '.'.code || c == 'e'.code || c == 'E'.code)) break
            sb.append(next().toChar())
        }
        return sb.toString().toDouble()
    }

    fun str(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            val c = next()
            if (c < 0) throw IllegalStateException("JSON: EOF in string")
            if (c == '"'.code) return sb.toString()
            if (c == '\\'.code) {
                when (val e = next().toChar()) {
                    'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r'); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                    'u' -> { val h = CharArray(4) { next().toChar() }; sb.append(String(h).toInt(16).toChar()) }
                    else -> sb.append(e)
                }
            } else sb.append(c.toChar())
        }
    }

    private fun obj(): Map<String, Any?> {
        expect('{')
        val m = LinkedHashMap<String, Any?>()
        ws(); if (peek() == '}'.code) { next(); return m }
        while (true) {
            ws(); val k = str(); expect(':'); m[k] = value(); ws()
            when (next()) { ','.code -> continue; '}'.code -> return m; else -> throw IllegalStateException("JSON: bad object") }
        }
    }

    private fun arr(): List<Any?> {
        expect('[')
        val l = ArrayList<Any?>()
        ws(); if (peek() == ']'.code) { next(); return l }
        while (true) {
            l.add(value()); ws()
            when (next()) { ','.code -> continue; ']'.code -> return l; else -> throw IllegalStateException("JSON: bad array") }
        }
    }

    /** Skip to the value of the first key named [key] (at any depth), reading as little as possible. */
    private fun seek(key: String): Boolean {
        val target = "\"$key\""
        val win = StringBuilder()
        var inStr = false; var esc = false
        while (true) {
            val c = next(); if (c < 0) return false
            val x = c.toChar()
            if (inStr) {
                if (esc) esc = false else if (x == '\\') esc = true else if (x == '"') inStr = false
            } else if (x == '"') inStr = true
            win.append(x); if (win.length > target.length + 4) win.deleteCharAt(0)
            if (!inStr && win.endsWith(target)) { ws(); if (peek() == ':'.code) { next(); return true } }
        }
    }

    companion object {
        fun parse(r: Reader): Any? = Json(r).value()

        /**
         * Stream the "vocab" array of a SentencePiece tokenizer.json ([[piece, score], ...]) without
         * loading the whole 17MB file into memory. Also reports "unk_id" if it comes first.
         */
        fun streamUnigramVocab(r: Reader, onPiece: (String, Float) -> Unit): Int {
            val j = Json(r)
            if (!j.seek("vocab")) throw IllegalStateException("tokenizer.json: no vocab")
            j.expect('[')
            j.ws()
            if (j.peek() == ']'.code) return 0
            var n = 0
            while (true) {
                j.expect('['); j.ws()
                val p = j.str(); j.expect(',')
                val s = (j.value() as Number).toFloat()
                j.ws(); while (j.peek() == ','.code) { j.next(); j.value(); j.ws() }
                j.expect(']')
                onPiece(p, s); n++
                j.ws()
                when (j.next()) { ','.code -> continue; ']'.code -> return n; else -> throw IllegalStateException("JSON: bad vocab") }
            }
        }
    }
}
