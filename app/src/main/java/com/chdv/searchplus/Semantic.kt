package com.chdv.searchplus

import ai.onnxruntime.OrtEnvironment
import android.content.Context
import com.chdv.searchplus.core.E5Model
import com.chdv.searchplus.core.E5Tokenizer
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.PriorityQueue
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Search by meaning. Uses the multilingual-e5-small model (it understands Hebrew) running on the
 * device with ONNX Runtime. Every page is cut into passages; every passage gets a vector of 384
 * numbers that represents its meaning. A question is turned into a vector the same way and the
 * passages with the closest meaning are returned. Nothing leaves the device.
 *
 * Two sources are combined: the prepared index (vectors.bin, built in the background) and, at
 * question time, the pages that contain the question's words, read and compared on the spot – so
 * meaning search gives answers even before the index is complete.
 */
object Semantic {
    private const val BASE = "https://huggingface.co/Xenova/multilingual-e5-small/resolve/main/"
    const val DIM = 384
    private const val REC = 4 + 4 + DIM // pageId, scale, int8 vector
    private const val WORDS_PER_CHUNK = 90
    private const val MAX_CHUNKS = 4

    fun dir(c: Context) = File(c.filesDir, "e5")
    private fun modelFile(c: Context) = File(dir(c), "model_quantized.onnx")
    private fun tokFile(c: Context) = File(dir(c), "tokenizer.json")
    private fun vocabFile(c: Context) = File(dir(c), "vocab.bin")
    fun vectorsFile(c: Context) = File(c.filesDir, "vectors.bin")

    fun ready(c: Context) = modelFile(c).length() > 50_000_000 && vocabFile(c).length() > 1_000_000

    /** Last problem, shown in the settings (so a failure is never silent). */
    var lastError: String
        get() = Prefs.s("semError", "")
        set(v) = Prefs.put("semError", v.take(400))

    fun download(c: Context, progress: (String) -> Unit) {
        dir(c).mkdirs()
        if (vocabFile(c).length() < 1_000_000) {
            Net.download(BASE + "tokenizer.json", tokFile(c)) { progress("מוריד מילון… $it%") }
            progress("מכין את המילון…")
            E5Tokenizer.compile(tokFile(c), vocabFile(c))
            tokFile(c).delete()
        }
        if (modelFile(c).length() < 50_000_000) Net.download(BASE + "onnx/model_quantized.onnx", modelFile(c)) { progress("מוריד את מודל הבינה… $it%") }
        progress("בודק את המודל…")
        // a real test run: if this works, search by meaning works
        val v = embed(c, "query: בדיקה")
        if (v.size != DIM) throw IllegalStateException("המודל החזיר ${v.size} מספרים במקום $DIM")
        lastError = ""
    }

    fun deleteAll(c: Context) {
        close()
        dir(c).deleteRecursively()
        vectorsFile(c).delete()
        Db.get().resetEmbeddings()
    }

    // ---------- model ----------

    private var model: E5Model? = null

    @Synchronized
    private fun open(c: Context): E5Model {
        model?.let { return it }
        if (!vocabFile(c).exists() && tokFile(c).exists()) E5Tokenizer.compile(tokFile(c), vocabFile(c))
        val tok = E5Tokenizer.load(vocabFile(c))
        val threads = max(1, min(4, Runtime.getRuntime().availableProcessors()))
        return E5Model(OrtEnvironment.getEnvironment(), modelFile(c).absolutePath, tok, threads).also { model = it }
    }

    @Synchronized
    fun close() {
        try { model?.close() } catch (e: Exception) {}
        model = null
    }

    @Synchronized
    fun embed(c: Context, text: String, maxLen: Int = 192): FloatArray = open(c).embed(text, maxLen)

    private fun dot(a: FloatArray, b: FloatArray): Float { var d = 0f; for (i in a.indices) d += a[i] * b[i]; return d }

    // ---------- passages ----------

    /** Split the text of a page into passages of ~90 words. */
    fun chunks(body: String): List<String> {
        val words = body.split(' ', '\n').filter { it.isNotBlank() }
        if (words.size < 8) return emptyList()
        val out = ArrayList<String>()
        var i = 0
        while (i < words.size && out.size < MAX_CHUNKS) {
            val end = min(words.size, i + WORDS_PER_CHUNK)
            out.add(words.subList(i, end).joinToString(" "))
            if (end == words.size) break
            i = end - 10 // small overlap
        }
        return out
    }

    /** Index every book that is not in the meaning index yet. Limited to the folders chosen in settings. */
    fun indexPending(c: Context) {
        val db = Db.get()
        val only = Prefs.list("semanticRoots")
        val books = db.filesForEmbedding().filter { b -> only.isEmpty() || only.any { b.path.startsWith("$it/") } }
        if (books.isEmpty()) return
        Progress.phase = "בונה אינדקס לפי משמעות"
        Progress.total = books.size; Progress.done = 0
        Prefs.put("semBusy", System.currentTimeMillis())
        try {
            open(c)
        } catch (e: Throwable) {
            lastError = "המודל לא נטען: " + (e.message ?: e.javaClass.simpleName)
            return
        }
        try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(vectorsFile(c), true), 1 shl 16)).use { out ->
                for (b in books) {
                    if (Progress.cancel) return
                    Progress.waitWhilePaused()
                    Progress.current = b.title
                    try {
                        val recs = ArrayList<Pair<Int, ByteArray>>()
                        val scales = ArrayList<Float>()
                        for ((pageId, body) in db.pagesOf(b.id)) {
                            if (Progress.cancel) return
                            Progress.waitWhilePaused()
                            for (ch in chunks(body)) {
                                val v = embed(c, "passage: $ch")
                                var mx = 0f
                                for (x in v) mx = max(mx, kotlin.math.abs(x))
                                val sc = if (mx == 0f) 1f else mx / 127f
                                recs.add(pageId.toInt() to ByteArray(DIM) { (v[it] / sc).roundToInt().coerceIn(-127, 127).toByte() })
                                scales.add(sc)
                            }
                        }
                        // the whole book is written at once, so a stop in the middle never leaves half a book
                        for (k in recs.indices) { out.writeInt(recs[k].first); out.writeFloat(scales[k]); out.write(recs[k].second) }
                        out.flush()
                        db.markEmbedded(b.id)
                    } catch (e: OutOfMemoryError) {
                        System.gc(); db.markEmbedded(b.id, 2)
                        lastError = "הספר ${b.title} גדול מדי לזיכרון – דילגתי עליו"
                    } catch (e: Throwable) {
                        db.markEmbedded(b.id, 2)
                        lastError = "${b.title}: " + (e.message ?: e.javaClass.simpleName)
                    }
                    Progress.done++
                }
            }
        } finally {
            close()
            Prefs.put("semBusy", 0L)
        }
    }

    data class Hit(val pageId: Long, val score: Float)

    /** The pages whose meaning is closest to the question, from the prepared index. */
    fun searchIndex(c: Context, q: FloatArray, k: Int = 300): List<Hit> {
        val f = vectorsFile(c)
        if (!f.exists() || f.length() < REC) return emptyList()
        val best = HashMap<Int, Float>() // best score per page
        val heap = PriorityQueue<Pair<Int, Float>>(k + 1, compareBy { it.second })
        val buf = ByteArray(DIM)
        DataInputStream(BufferedInputStream(FileInputStream(f), 1 shl 18)).use { i ->
            val n = f.length() / REC
            for (r in 0 until n) {
                val pid = i.readInt()
                val sc = i.readFloat()
                i.readFully(buf)
                var d = 0f
                for (x in 0 until DIM) d += q[x] * buf[x]
                d *= sc
                val prev = best[pid]
                if (prev != null && prev >= d) continue
                if (heap.size >= k && d <= heap.peek()!!.second) continue
                best[pid] = d
                heap.add(pid to d)
                if (heap.size > k) heap.poll()
            }
        }
        val m = HashMap<Int, Float>()
        heap.forEach { (p, s) -> if ((m[p] ?: -9f) < s) m[p] = s }
        return m.entries.sortedByDescending { it.value }.map { Hit(it.key.toLong(), it.value) }
    }

    /** Small words that say nothing about the subject of a question. */
    private val STOP = setOf("מה", "מהו", "מהי", "איך", "כיצד", "למה", "מדוע", "מתי", "מי", "האם", "איפה", "היכן", "של", "על", "את", "אל",
        "עם", "הוא", "היא", "הם", "זה", "זו", "זאת", "אם", "כי", "יש", "אין", "לא", "כל", "גם", "או", "אבל", "רק", "כמו", "אחרי", "לפני",
        "צריך", "צריכים", "אפשר", "מותר", "אסור", "דין", "הדין", "לגבי", "בענין", "בעניין", "אומרים", "אומר", "כתוב", "נאמר", "עושים")

    fun keyWords(q: String): List<String> = Heb.words(q).filter { it.length >= 2 && it !in STOP }

    /**
     * Meaning search = prepared index + on-the-spot reading of the pages that contain the question's
     * words. [progress] reports what is happening (it can take a few seconds on an E-ink device).
     */
    fun search(c: Context, query: String, progress: (String) -> Unit = {}): List<Hit> {
        val q = embed(c, "query: $query", 96)
        val scores = HashMap<Long, Float>()
        searchIndex(c, q).forEach { scores[it.pageId] = it.score }

        // pages with the most question words, compared now
        val kw = keyWords(query)
        if (kw.isNotEmpty()) {
            progress("קורא את העמודים שמזכירים את המילים…")
            val expr = Search.buildMatch(kw, Match.ANY, true)
            val cand = ArrayList<Pair<Long, Int>>()
            try {
                Db.get().readableDatabase.rawQuery("SELECT docid, body FROM fts WHERE fts MATCH ? LIMIT 400", arrayOf(expr)).use { cur ->
                    while (cur.moveToNext()) {
                        val body = cur.getString(1)
                        val n = kw.count { w -> body.contains(w) }
                        cand.add(cur.getLong(0) to n)
                    }
                }
            } catch (e: Exception) {}
            val top = cand.sortedByDescending { it.second }.take(16).filter { it.first !in scores }
            top.forEachIndexed { i, (pid, _) ->
                progress("משווה משמעות ${i + 1}/${top.size}…")
                val body = Db.get().pageTextById(pid) ?: return@forEachIndexed
                val chunk = chunks(body).maxByOrNull { ch -> kw.count { ch.contains(it) } } ?: return@forEachIndexed
                scores[pid] = dot(q, embed(c, "passage: $chunk"))
            }
        }
        return scores.entries.sortedByDescending { it.value }.take(300).map { Hit(it.key, it.value) }
    }

    private var qText = ""
    private var qVec: FloatArray? = null

    @Synchronized
    private fun queryVec(c: Context, q: String): FloatArray {
        if (q == qText) qVec?.let { return it }
        return embed(c, "query: $q", 96).also { qText = q; qVec = it }
    }

    /** Sentences of a page (or ~30-word pieces where the text has no punctuation). */
    fun sentences(body: String): List<String> {
        val out = ArrayList<String>()
        for (part in body.replace('\n', ' ').split(Regex("(?<=[.:;?!])\\s+"))) {
            val ws = part.split(' ').filter { it.isNotBlank() }
            if (ws.size < 5) continue
            var i = 0
            while (i < ws.size) { out.add(ws.subList(i, minOf(ws.size, i + 35)).joinToString(" ")); i += 30 }
        }
        return out
    }

    /**
     * The sentence of a page whose meaning is closest to the question – shown in the results and
     * marked in the reader, instead of scattered words.
     */
    fun sentenceFor(c: Context, question: String, pageId: Long): String? {
        val body = Db.get().pageTextById(pageId) ?: return null
        val kw = keyWords(question)
        val cand = sentences(body).sortedByDescending { s -> kw.count { s.contains(it) } }.take(6)
        if (cand.isEmpty()) return null
        val q = queryVec(c, question)
        return cand.maxByOrNull { dot(q, embed(c, "passage: $it", 80)) }
    }

    /**
     * The deep understanding model (bge-m3, ~570MB, optional): reads the candidate sentences in their
     * context and picks the one that really answers. Slower, much more accurate; used only for the
     * final choice of the answer, so it stays fast enough on the device.
     */
    object Deep {
        private const val REPO = "https://huggingface.co/Xenova/bge-m3/resolve/main/"
        fun dir(c: Context): File = File(VoiceModels.dir(c, "x").parentFile, "bge-m3")
        private fun model(c: Context) = File(dir(c), "model_quantized.onnx")
        private fun tok(c: Context) = File(dir(c), "tokenizer.json")
        private fun vocab(c: Context) = File(dir(c), "vocab.bin")
        fun ready(c: Context) = model(c).length() > 300_000_000 && vocab(c).length() > 1_000_000
        fun on(c: Context) = ready(c) && Prefs.b("deep", true)

        fun download(c: Context, progress: (String) -> Unit) {
            dir(c).mkdirs()
            if (vocab(c).length() < 1_000_000) {
                Net.download(REPO + "tokenizer.json", tok(c)) { progress("מוריד מילון… $it%") }
                E5Tokenizer.compile(tok(c), vocab(c)); tok(c).delete()
            }
            if (model(c).length() < 300_000_000) Net.download(REPO + "onnx/model_quantized.onnx", model(c)) { progress("מוריד את מודל ההבנה… $it%") }
            progress("בודק את המודל…")
            close()
            use(c) { it.embed("בדיקה") }
            close()
        }

        fun delete(c: Context) { close(); dir(c).deleteRecursively() }

        private var m: E5Model? = null

        @Synchronized
        fun <T> use(c: Context, f: (E5Model) -> T): T {
            val mm = m ?: run {
                Semantic.close(); VoiceModels.Engine.release(); System.gc()
                if (VoiceModels.freeMemoryMb(c) < 700) throw IllegalStateException("אין מספיק זיכרון פנוי למודל ההבנה")
                E5Model(OrtEnvironment.getEnvironment(), model(c).absolutePath, E5Tokenizer.load(vocab(c)), max(1, min(4, Runtime.getRuntime().availableProcessors())), cls = true, lowMemory = true)
            }.also { m = it }
            return f(mm)
        }

        @Synchronized
        fun close() { try { m?.close() } catch (e: Exception) {}; m = null }

        /** Best of the sentences for the question, with its score (cosine). */
        fun best(c: Context, question: String, sentences: List<String>): Pair<String, Float>? = use(c) { mm ->
            val q = mm.embed(question, 96)
            sentences.map { sn -> val v = mm.embed(sn, 128); sn to q.indices.fold(0f) { acc, i -> acc + q[i] * v[i] } }.maxByOrNull { it.second }
        }
    }

    fun vectorCount(c: Context): Long = vectorsFile(c).length() / REC

    class Answer(val text: String, val pageId: Long, val score: Float)

    /** Extractive answer: the sentence (from the closest pages) whose meaning is closest to the question. */
    fun answer(c: Context, question: String, pageIds: List<Long>): Answer? {
        if (pageIds.isEmpty()) return null
        val q = embed(c, "query: $question", 96)
        val kw = keyWords(question)
        val db = Db.get()
        val sents = ArrayList<Pair<String, Long>>()
        for (pid in pageIds) {
            val body = db.pageTextById(pid) ?: continue
            for (part in body.replace('\n', ' ').split(Regex("(?<=[.:;?!])\\s+"))) {
                val ws = part.split(' ').filter { it.isNotBlank() }
                if (ws.size < 5) continue
                var i = 0
                while (i < ws.size) { sents.add(ws.subList(i, minOf(ws.size, i + 35)).joinToString(" ") to pid); i += 30 }
            }
        }
        // compare only the sentences most likely to answer (each comparison costs time on the device)
        val pick = sents.sortedByDescending { (s, _) -> kw.count { s.contains(it) } }.take(20)
        if (Deep.on(c)) try {
            // the deep model reads each candidate sentence together with its neighbour, for context
            val withCtx = pick.map { (sn, _) -> sn }
            val r = Deep.best(c, question, withCtx)
            Deep.close()
            if (r != null) {
                val pid = pick.first { it.first == r.first }.second
                return Answer(r.first, pid, r.second).takeIf { it.score > 0.45f }
            }
        } catch (e: Throwable) { Deep.close(); lastError = "מודל ההבנה: " + (e.message ?: e.javaClass.simpleName) }
        var best: Answer? = null
        for ((sn, pid) in pick) {
            val d = dot(q, embed(c, "passage: $sn", 80))
            if (best == null || d > best.score) best = Answer(sn, pid, d)
        }
        return best?.takeIf { it.score > 0.8f }
    }
}
