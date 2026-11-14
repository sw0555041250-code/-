package com.chdv.searchplus

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.JsonReader
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.nio.LongBuffer
import java.text.Normalizer
import java.util.PriorityQueue
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Search by meaning. Uses the multilingual-e5-small model (it understands Hebrew) running on the
 * device with ONNX Runtime. Every page is cut into passages; every passage gets a vector of 384
 * numbers that represents its meaning. A question is turned into a vector the same way and the
 * passages with the closest meaning are returned. Nothing leaves the device.
 */
object Semantic {
    private const val BASE = "https://huggingface.co/Xenova/multilingual-e5-small/resolve/main/"
    const val DIM = 384
    private const val REC = 4 + 4 + DIM // pageId, scale, int8 vector
    private const val WORDS_PER_CHUNK = 90
    private const val MAX_CHUNKS = 6

    fun dir(c: Context) = File(c.filesDir, "e5")
    private fun modelFile(c: Context) = File(dir(c), "model_quantized.onnx")
    private fun tokFile(c: Context) = File(dir(c), "tokenizer.json")
    private fun vocabFile(c: Context) = File(dir(c), "vocab.bin")
    fun vectorsFile(c: Context) = File(c.filesDir, "vectors.bin")

    fun ready(c: Context) = modelFile(c).length() > 50_000_000 && (vocabFile(c).exists() || tokFile(c).length() > 1_000_000)

    fun download(c: Context, progress: (String) -> Unit) {
        dir(c).mkdirs()
        Net.download(BASE + "tokenizer.json", tokFile(c)) { progress("מוריד מילון… $it%") }
        Net.download(BASE + "onnx/model_quantized.onnx", modelFile(c)) { progress("מוריד את מודל הבינה… $it%") }
        progress("מכין את המילון…")
        buildVocab(c)
    }

    fun deleteAll(c: Context) {
        close()
        dir(c).deleteRecursively()
        vectorsFile(c).delete()
        Db.get().resetEmbeddings()
    }

    // ---------- tokenizer (SentencePiece Unigram, as in XLM-RoBERTa) ----------

    private var vocab: HashMap<String, Int>? = null
    private var scores: FloatArray = FloatArray(0)
    private var maxPiece = 16
    private var unkId = 3
    private var bosId = 0
    private var eosId = 2
    private var unkScore = -20f

    /** Read the vocabulary out of tokenizer.json (17MB) once, and keep a compact copy. */
    private fun buildVocab(c: Context) {
        val pieces = ArrayList<String>(260_000)
        val sc = ArrayList<Float>(260_000)
        var unk = 3
        JsonReader(InputStreamReader(BufferedInputStream(FileInputStream(tokFile(c)), 1 shl 16), Charsets.UTF_8)).use { r ->
            r.beginObject()
            while (r.hasNext()) {
                if (r.nextName() != "model") { r.skipValue(); continue }
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "unk_id" -> unk = r.nextInt()
                        "vocab" -> {
                            r.beginArray()
                            while (r.hasNext()) {
                                r.beginArray()
                                pieces.add(r.nextString())
                                sc.add(r.nextDouble().toFloat())
                                while (r.hasNext()) r.skipValue()
                                r.endArray()
                            }
                            r.endArray()
                        }
                        else -> r.skipValue()
                    }
                }
                r.endObject()
            }
            r.endObject()
        }
        DataOutputStream(BufferedOutputStream(FileOutputStream(vocabFile(c)), 1 shl 16)).use { o ->
            o.writeInt(pieces.size); o.writeInt(unk)
            for (i in pieces.indices) { o.writeUTF(pieces[i]); o.writeFloat(sc[i]) }
        }
        tokFile(c).delete()
    }

    private fun loadVocab(c: Context) {
        if (vocab != null) return
        if (!vocabFile(c).exists()) buildVocab(c)
        DataInputStream(BufferedInputStream(FileInputStream(vocabFile(c)), 1 shl 16)).use { i ->
            val n = i.readInt()
            unkId = i.readInt()
            val m = HashMap<String, Int>(n * 2)
            val s = FloatArray(n)
            var mx = 1
            var minScore = 0f
            for (k in 0 until n) {
                val p = i.readUTF()
                s[k] = i.readFloat()
                m[p] = k
                if (p.length > mx) mx = p.length
                if (s[k] < minScore) minScore = s[k]
            }
            vocab = m; scores = s; maxPiece = min(mx, 24)
            unkScore = minScore - 10f
            bosId = m["<s>"] ?: 0
            eosId = m["</s>"] ?: 2
        }
    }

    private fun tokenize(text: String, maxLen: Int): LongArray {
        val v = vocab!!
        val norm = Normalizer.normalize(text, Normalizer.Form.NFKC).replace(Regex("\\s+"), " ").trim()
        val ids = ArrayList<Int>()
        ids.add(bosId)
        for (word in norm.split(' ')) {
            if (word.isEmpty()) continue
            val w = "▁$word"
            val n = w.length
            val best = FloatArray(n + 1) { Float.NEGATIVE_INFINITY }
            val from = IntArray(n + 1)
            val tok = IntArray(n + 1)
            best[0] = 0f
            for (i in 0 until n) {
                if (best[i] == Float.NEGATIVE_INFINITY) continue
                var single = false
                val lim = min(maxPiece, n - i)
                for (len in 1..lim) {
                    val id = v[w.substring(i, i + len)] ?: continue
                    if (len == 1) single = true
                    val s = best[i] + scores[id]
                    if (s > best[i + len]) { best[i + len] = s; from[i + len] = i; tok[i + len] = id }
                }
                if (!single) {
                    val s = best[i] + unkScore
                    if (s > best[i + 1]) { best[i + 1] = s; from[i + 1] = i; tok[i + 1] = unkId }
                }
            }
            val wordIds = ArrayList<Int>()
            var k = n
            while (k > 0) { wordIds.add(tok[k]); k = from[k] }
            for (j in wordIds.indices.reversed()) {
                val id = wordIds[j]
                if (id == unkId && ids.isNotEmpty() && ids.last() == unkId) continue
                ids.add(id)
            }
            if (ids.size >= maxLen - 1) break
        }
        while (ids.size > maxLen - 1) ids.removeAt(ids.size - 1)
        ids.add(eosId)
        return LongArray(ids.size) { ids[it].toLong() }
    }

    // ---------- model ----------

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null

    @Synchronized
    private fun open(c: Context) {
        if (session != null) return
        loadVocab(c)
        val e = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions()
        opts.setIntraOpNumThreads(max(1, min(4, Runtime.getRuntime().availableProcessors())))
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        env = e
        session = e.createSession(modelFile(c).absolutePath, opts)
    }

    @Synchronized
    fun close() {
        try { session?.close() } catch (e: Exception) {}
        session = null
        vocab = null
    }

    @Synchronized
    fun embed(c: Context, text: String, maxLen: Int = 192): FloatArray {
        open(c)
        val s = session!!
        val e = env!!
        val ids = tokenize(text, maxLen)
        val n = ids.size
        val shape = longArrayOf(1, n.toLong())
        val tensors = ArrayList<OnnxTensor>()
        try {
            val inputs = HashMap<String, OnnxTensor>()
            OnnxTensor.createTensor(e, LongBuffer.wrap(ids), shape).also { tensors.add(it); inputs["input_ids"] = it }
            OnnxTensor.createTensor(e, LongBuffer.wrap(LongArray(n) { 1L }), shape).also { tensors.add(it); inputs["attention_mask"] = it }
            if (s.inputNames.contains("token_type_ids")) {
                OnnxTensor.createTensor(e, LongBuffer.wrap(LongArray(n)), shape).also { tensors.add(it); inputs["token_type_ids"] = it }
            }
            s.run(inputs).use { r ->
                @Suppress("UNCHECKED_CAST")
                val out = (r.get(0).value as Array<Array<FloatArray>>)[0]
                val v = FloatArray(DIM)
                for (row in out) for (d in 0 until DIM) v[d] += row[d]
                var norm = 0f
                for (d in 0 until DIM) { v[d] /= out.size; norm += v[d] * v[d] }
                norm = sqrt(norm).coerceAtLeast(1e-9f)
                for (d in 0 until DIM) v[d] /= norm
                return v
            }
        } finally {
            tensors.forEach { it.close() }
        }
    }

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
        try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(vectorsFile(c), true), 1 shl 16)).use { out ->
                for (b in books) {
                    if (Progress.cancel) return
                    Progress.current = b.title
                    val pages = db.pagesOf(b.id)
                    for ((pageId, body) in pages) {
                        if (Progress.cancel) return
                        for (ch in chunks(body)) {
                            val v = embed(c, "passage: $ch")
                            var mx = 0f
                            for (x in v) mx = max(mx, kotlin.math.abs(x))
                            val sc = if (mx == 0f) 1f else mx / 127f
                            out.writeInt(pageId.toInt())
                            out.writeFloat(sc)
                            val bytes = ByteArray(DIM) { (v[it] / sc).roundToInt().coerceIn(-127, 127).toByte() }
                            out.write(bytes)
                        }
                    }
                    out.flush()
                    db.markEmbedded(b.id)
                    Progress.done++
                }
            }
        } finally {
            close()
        }
    }

    data class Hit(val pageId: Long, val score: Float)

    /** The pages whose meaning is closest to the question. */
    fun search(c: Context, query: String, k: Int = 400): List<Hit> {
        val f = vectorsFile(c)
        if (!f.exists() || f.length() < REC) return emptyList()
        val q = embed(c, "query: $query", 96)
        val best = HashMap<Int, Float>() // best score per page
        val heap = PriorityQueue<Pair<Int, Float>>(k + 1, compareBy { it.second })
        val buf = ByteArray(DIM)
        DataInputStream(BufferedInputStream(FileInputStream(f), 1 shl 18)).use { i ->
            val n = f.length() / REC
            for (r in 0 until n) {
                val pid = i.readInt()
                val sc = i.readFloat()
                i.readFully(buf)
                var dot = 0f
                for (d in 0 until DIM) dot += q[d] * buf[d]
                dot *= sc
                val prev = best[pid]
                if (prev != null && prev >= dot) continue
                if (heap.size >= k && dot <= heap.peek()!!.second) continue
                best[pid] = dot
                heap.add(pid to dot)
                if (heap.size > k) heap.poll()
            }
        }
        // the heap can contain the same page twice; keep its best score
        val m = HashMap<Int, Float>()
        heap.forEach { (p, s) -> if ((m[p] ?: -9f) < s) m[p] = s }
        return m.entries.sortedByDescending { it.value }.map { Hit(it.key.toLong(), it.value) }
    }

    fun vectorCount(c: Context): Long = vectorsFile(c).length() / REC
}
