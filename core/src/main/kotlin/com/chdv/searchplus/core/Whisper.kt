package com.chdv.searchplus.core

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin

/** Log-Mel spectrogram exactly as Whisper expects it (16kHz, 25ms window, 10ms hop, 30 seconds). */
object WhisperMel {
    const val SR = 16000
    const val NFFT = 400
    const val HOP = 160
    const val FRAMES = 3000
    const val SAMPLES = SR * 30
    private const val BINS = NFFT / 2 + 1

    private fun hzToMel(f: Double): Double {
        val fsp = 200.0 / 3; val minLogHz = 1000.0; val minLogMel = minLogHz / fsp; val logstep = ln(6.4) / 27.0
        return if (f >= minLogHz) minLogMel + ln(f / minLogHz) / logstep else f / fsp
    }

    private fun melToHz(m: Double): Double {
        val fsp = 200.0 / 3; val minLogHz = 1000.0; val minLogMel = minLogHz / fsp; val logstep = ln(6.4) / 27.0
        return if (m >= minLogMel) minLogHz * kotlin.math.exp(logstep * (m - minLogMel)) else fsp * m
    }

    /** librosa.filters.mel(sr=16000, n_fft=400, n_mels=n, norm="slaney") */
    fun filters(nMels: Int): Array<FloatArray> {
        val fft = DoubleArray(BINS) { it * (SR / 2.0) / (BINS - 1) }
        val lo = hzToMel(0.0); val hi = hzToMel(SR / 2.0)
        val melF = DoubleArray(nMels + 2) { melToHz(lo + (hi - lo) * it / (nMels + 1)) }
        return Array(nMels) { i ->
            val enorm = 2.0 / (melF[i + 2] - melF[i])
            FloatArray(BINS) { k ->
                val lower = (fft[k] - melF[i]) / (melF[i + 1] - melF[i])
                val upper = (melF[i + 2] - fft[k]) / (melF[i + 2] - melF[i + 1])
                (max(0.0, minOf(lower, upper)) * enorm).toFloat()
            }
        }
    }

    private val cosT by lazy { Array(BINS) { k -> FloatArray(NFFT) { n -> cos(2 * PI * k * n / NFFT).toFloat() } } }
    private val sinT by lazy { Array(BINS) { k -> FloatArray(NFFT) { n -> sin(2 * PI * k * n / NFFT).toFloat() } } }
    private val window by lazy { FloatArray(NFFT) { (0.5 - 0.5 * cos(2 * PI * it / NFFT)).toFloat() } }
    private val melCache = HashMap<Int, Array<FloatArray>>()

    /** Returns [nMels * 3000] (row = mel band). Audio: mono 16kHz floats in -1..1, at most 30 seconds are used. */
    fun logMel(audio: FloatArray, nMels: Int = 80): FloatArray {
        val n = SAMPLES
        val len = minOf(audio.size, n)
        val pad = NFFT / 2
        // x = audio padded/trimmed to 30s, then reflect-padded by 200 on both sides (torch.stft center=True)
        fun x(i: Int): Float = if (i < len) audio[i] else 0f
        fun padded(p: Int): Float {
            val i = p - pad
            return when {
                i < 0 -> x(-i)
                i >= n -> x(2 * n - 2 - i)
                else -> x(i)
            }
        }
        val mel = synchronized(melCache) { melCache.getOrPut(nMels) { filters(nMels) } }
        val out = FloatArray(nMels * FRAMES)
        val frame = FloatArray(NFFT)
        val power = FloatArray(BINS)
        val zeroMel = log10(1e-10).toFloat()
        var mx = Float.NEGATIVE_INFINITY
        for (t in 0 until FRAMES) {
            var any = false
            val start = t * HOP
            for (k in 0 until NFFT) { val v = padded(start + k) * window[k]; frame[k] = v; if (v != 0f) any = true }
            if (!any) {
                for (m in 0 until nMels) out[m * FRAMES + t] = zeroMel
                mx = max(mx, zeroMel)
                continue
            }
            for (b in 0 until BINS) {
                val c = cosT[b]; val s = sinT[b]
                var re = 0f; var im = 0f
                for (k in 0 until NFFT) { val v = frame[k]; re += v * c[k]; im += v * s[k] }
                power[b] = re * re + im * im
            }
            for (m in 0 until nMels) {
                val f = mel[m]
                var e = 0f
                for (b in 0 until BINS) e += f[b] * power[b]
                val l = log10(max(e, 1e-10f))
                out[m * FRAMES + t] = l
                if (l > mx) mx = l
            }
        }
        val floor = mx - 8f
        for (i in out.indices) out[i] = (max(out[i], floor) + 4f) / 4f
        return out
    }
}

/** Byte-level BPE tokenizer of Whisper, read from the Hugging Face tokenizer.json. */
class WhisperTokenizer(tokenizerJson: File) {
    private val vocab = HashMap<String, Int>(60_000)
    private val idToTok = HashMap<Int, String>(60_000)
    private val ranks = HashMap<String, Int>(60_000)
    val special = HashMap<String, Int>()

    init {
        @Suppress("UNCHECKED_CAST")
        val root = tokenizerJson.bufferedReader(Charsets.UTF_8).use { Json.parse(it) } as Map<String, Any?>
        val model = root["model"] as Map<String, Any?>
        (model["vocab"] as Map<String, Any?>).forEach { (k, v) -> val id = (v as Number).toInt(); vocab[k] = id; idToTok[id] = k }
        (model["merges"] as List<Any?>).forEachIndexed { i, m ->
            val key = when (m) { is String -> m; is List<*> -> "${m[0]} ${m[1]}"; else -> return@forEachIndexed }
            ranks[key] = i
        }
        (root["added_tokens"] as? List<Any?>)?.forEach { t ->
            val o = t as Map<String, Any?>
            val id = (o["id"] as Number).toInt(); val c = o["content"] as String
            special[c] = id; idToTok[id] = c
        }
    }

    fun id(s: String): Int = special[s] ?: vocab[s] ?: throw IllegalStateException("no token $s")
    val eot: Int get() = id("<|endoftext|>")

    private val byteEnc: CharArray
    private val byteDec = HashMap<Char, Int>()
    init {
        val bs = ArrayList<Int>()
        bs.addAll('!'.code..'~'.code); bs.addAll('¡'.code..'¬'.code); bs.addAll('®'.code..'ÿ'.code)
        val cs = ArrayList(bs)
        var n = 0
        for (b in 0..255) if (b !in bs) { bs.add(b); cs.add(256 + n); n++ }
        byteEnc = CharArray(256)
        for (i in bs.indices) { byteEnc[bs[i]] = cs[i].toChar(); byteDec[cs[i].toChar()] = bs[i] }
    }

    fun decode(ids: List<Int>): String {
        val bytes = java.io.ByteArrayOutputStream()
        for (id in ids) {
            if (id >= eot) continue
            val t = idToTok[id] ?: continue
            for (c in t) { val b = byteDec[c]; if (b != null) bytes.write(b) }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    private val pat = Regex("'s|'t|'re|'ve|'m|'ll|'d| ?\\p{L}+| ?\\p{N}+| ?[^\\s\\p{L}\\p{N}]+|\\s+(?!\\S)|\\s+")

    fun encode(text: String): List<Int> {
        val out = ArrayList<Int>()
        for (m in pat.findAll(text)) {
            val word = m.value.toByteArray(Charsets.UTF_8).map { byteEnc[it.toInt() and 0xFF].toString() }.toMutableList()
            while (word.size > 1) {
                var best = -1; var bestRank = Int.MAX_VALUE
                for (i in 0 until word.size - 1) {
                    val r = ranks[word[i] + " " + word[i + 1]] ?: continue
                    if (r < bestRank) { bestRank = r; best = i }
                }
                if (best < 0) break
                word[best] = word[best] + word[best + 1]
                word.removeAt(best + 1)
            }
            for (p in word) out.add(vocab[p] ?: continue)
        }
        return out
    }
}

/**
 * Whisper speech recognition with ONNX Runtime: encoder + merged decoder (with key/value cache).
 * Works with the Xenova / onnx-community exports (encoder_model*.onnx, decoder_model_merged*.onnx).
 */
class Whisper(
    private val env: OrtEnvironment,
    encoderPath: String,
    decoderPath: String,
    val tok: WhisperTokenizer,
    threads: Int = 4,
    /** BASIC uses less memory while loading (weak devices); EXTENDED runs a little faster. */
    optLevel: OrtSession.SessionOptions.OptLevel = OrtSession.SessionOptions.OptLevel.EXTENDED_OPT,
) : AutoCloseable {
    private val enc: OrtSession
    private val dec: OrtSession
    val nMels: Int

    init {
        fun opts() = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setOptimizationLevel(optLevel)
        }
        enc = env.createSession(encoderPath, opts())
        dec = try { env.createSession(decoderPath, opts()) } catch (e: Throwable) { enc.close(); throw e }
        val shape = (enc.inputInfo.values.first().info as TensorInfo).shape
        nMels = if (shape.size == 3 && shape[1] > 0) shape[1].toInt() else 80
    }

    /** Timings of the last run, for tests and the status line. */
    var lastMelMs = 0L; var lastEncMs = 0L; var lastDecMs = 0L

    fun transcribe(audio: FloatArray, language: String = "he", prompt: String? = null, maxTokens: Int = 64, cancelled: () -> Boolean = { false }): String {
        var t0 = System.currentTimeMillis()
        val mel = WhisperMel.logMel(normalize(audio), nMels)
        lastMelMs = System.currentTimeMillis() - t0
        if (cancelled()) return ""

        t0 = System.currentTimeMillis()
        val encIn = OnnxTensor.createTensor(env, FloatBuffer.wrap(mel), longArrayOf(1, nMels.toLong(), WhisperMel.FRAMES.toLong()))
        val encRes = try { enc.run(mapOf(enc.inputNames.first() to encIn)) } finally { encIn.close() }
        lastEncMs = System.currentTimeMillis() - t0
        t0 = System.currentTimeMillis()
        try {
            val hidden = encRes.get(0) as OnnxTensor
            val prefix = ArrayList<Int>()
            if (!prompt.isNullOrBlank()) {
                prefix.add(tok.id("<|startofprev|>"))
                prefix.addAll(tok.encode(" " + prompt.trim()).takeLast(120))
            }
            prefix.add(tok.id("<|startoftranscript|>"))
            prefix.add(tok.id("<|$language|>"))
            prefix.add(tok.id("<|transcribe|>"))
            prefix.add(tok.id("<|notimestamps|>"))
            val out = decode(hidden, prefix, maxTokens, cancelled)
            return tok.decode(out).trim()
        } finally {
            encRes.close()
            lastDecMs = System.currentTimeMillis() - t0
        }
    }

    private fun decode(hidden: OnnxTensor, prefix: List<Int>, maxTokens: Int, cancelled: () -> Boolean): List<Int> {
        val eot = tok.eot
        val inputs = dec.inputNames
        val pastNames = inputs.filter { it.startsWith("past_key_values.") }
        val empties = ArrayList<OnnxTensor>()
        val emptyPast = HashMap<String, OnnxTensor>()
        for (name in pastNames) {
            val s = (dec.inputInfo[name]!!.info as TensorInfo).shape
            val heads = if (s.size == 4 && s[1] > 0) s[1] else 8
            val dim = if (s.size == 4 && s[3] > 0) s[3] else 64
            OnnxTensor.createTensor(env, FloatBuffer.allocate(0), longArrayOf(1, heads, 0, dim)).also { empties.add(it); emptyPast[name] = it }
        }
        val generated = ArrayList<Int>()
        var first: OrtSession.Result? = null
        var prev: OrtSession.Result? = null
        val small = ArrayList<OnnxTensor>()
        try {
            var ids: List<Int> = prefix
            for (step in 0 until maxTokens) {
                if (cancelled()) break
                val feed = HashMap<String, OnnxTensor>()
                val idT = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size) { ids[it].toLong() }), longArrayOf(1, ids.size.toLong()))
                small.add(idT)
                feed["input_ids"] = idT
                if ("encoder_hidden_states" in inputs) feed["encoder_hidden_states"] = hidden
                if ("use_cache_branch" in inputs) OnnxTensor.createTensor(env, booleanArrayOf(step > 0)).also { small.add(it); feed["use_cache_branch"] = it }
                for (name in pastNames) {
                    feed[name] = if (step == 0) emptyPast[name]!! else {
                        val pres = name.replace("past_key_values", "present")
                        // the cross-attention (encoder) cache comes from the first step; self-attention from the last one
                        val src = if (".encoder." in name) first!! else prev!!
                        (src.get(pres).orElse(null) as? OnnxTensor) ?: emptyPast[name]!!
                    }
                }
                val res = dec.run(feed)
                val logits = res.get("logits").orElse(res.get(0)) as OnnxTensor
                val shape = logits.info.shape
                val vocab = shape[2].toInt()
                val buf = logits.floatBuffer
                val base = (shape[1].toInt() - 1) * vocab
                var best = -1; var bestV = Float.NEGATIVE_INFINITY
                for (v in 0 until minOf(vocab, eot + 1)) {
                    if (step == 0 && (v == eot || v == 220)) continue
                    val x = buf.get(base + v)
                    if (x > bestV) { bestV = x; best = v }
                }
                if (step == 0) first = res else { if (prev !== first) prev?.close() }
                prev = res
                small.forEach { it.close() }; small.clear()
                if (best < 0 || best == eot) break
                generated.add(best)
                // stop on a loop of the same token
                if (generated.size >= 6 && generated.takeLast(6).toSet().size == 1) break
                ids = listOf(best)
            }
        } finally {
            small.forEach { it.close() }
            if (prev !== first) prev?.close()
            first?.close()
            empties.forEach { it.close() }
        }
        return generated
    }

    override fun close() {
        try { enc.close() } catch (e: Exception) {}
        try { dec.close() } catch (e: Exception) {}
    }

    companion object {
        /** A quiet microphone (common on E-ink readers) is brought to a normal level; silence stays silence. */
        fun normalize(audio: FloatArray): FloatArray {
            var peak = 0f
            for (x in audio) { val v = kotlin.math.abs(x); if (v > peak) peak = v }
            // every recording is brought to the same level (tests: clearer numbers like כ״ז at this level)
            if (peak < 0.003f) return audio
            val g = minOf(0.6f / peak, 40f)
            return FloatArray(audio.size) { audio[it] * g }
        }

        /** A hint that teaches the recognizer how the names of books are written. */
        const val PROMPT = "מסכת ברכות דף כ״ז עמוד א׳. ליקוטי שיחות חלק ג׳ פרשת ויצא. תניא פרק ה׳. רמב״ם. שולחן ערוך. גמרא שוטנשטיין."
    }
}

/** SentencePiece (Unigram) tokenizer of multilingual-e5, as in XLM-RoBERTa. */
class E5Tokenizer private constructor(private val vocab: HashMap<String, Int>, private val scores: FloatArray, unk: Int) {
    private val unkId = unk
    private val bosId = vocab["<s>"] ?: 0
    private val eosId = vocab["</s>"] ?: 2
    private val maxPiece: Int
    private val unkScore: Float

    init {
        var mx = 1; var mn = 0f
        for ((p, id) in vocab) { if (p.length > mx) mx = p.length; if (scores[id] < mn) mn = scores[id] }
        maxPiece = minOf(mx, 24); unkScore = mn - 10f
    }

    fun tokenize(text: String, maxLen: Int): LongArray {
        val norm = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC).replace(Regex("\\s+"), " ").trim()
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
                for (len in 1..minOf(maxPiece, n - i)) {
                    val id = vocab[w.substring(i, i + len)] ?: continue
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

    companion object {
        /** Turn the big tokenizer.json into a compact binary file (read with [load]). */
        fun compile(tokenizerJson: File, out: File) {
            val pieces = ArrayList<String>(260_000)
            val sc = ArrayList<Float>(260_000)
            tokenizerJson.bufferedReader(Charsets.UTF_8).use { r -> Json.streamUnigramVocab(r) { p, s -> pieces.add(p); sc.add(s) } }
            if (pieces.size < 1000) throw IllegalStateException("tokenizer.json: vocabulary too small (${pieces.size})")
            val tmp = File(out.parentFile, out.name + ".tmp")
            java.io.DataOutputStream(java.io.BufferedOutputStream(java.io.FileOutputStream(tmp), 1 shl 16)).use { o ->
                o.writeInt(pieces.size); o.writeInt(pieces.indexOf("<unk>").let { if (it < 0) 3 else it })
                for (i in pieces.indices) { o.writeUTF(pieces[i]); o.writeFloat(sc[i]) }
            }
            if (out.exists()) out.delete()
            if (!tmp.renameTo(out)) throw java.io.IOException("rename failed")
        }

        fun load(bin: File): E5Tokenizer =
            java.io.DataInputStream(java.io.BufferedInputStream(java.io.FileInputStream(bin), 1 shl 16)).use { i ->
                val n = i.readInt(); val unk = i.readInt()
                val m = HashMap<String, Int>(n * 2)
                val s = FloatArray(n)
                for (k in 0 until n) { m[i.readUTF()] = k; s[k] = i.readFloat() }
                E5Tokenizer(m, s, unk)
            }
    }
}

/** Runs the e5 model on a piece of text and returns a normalized 384-number vector. */
class E5Model(private val env: OrtEnvironment, modelPath: String, val tok: E5Tokenizer, threads: Int = 4,
              /** true for models that use the first token as the sentence vector (bge-m3); false = average (e5). */
              private val cls: Boolean = false, lowMemory: Boolean = false) : AutoCloseable {
    private val s: OrtSession = env.createSession(modelPath, OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.EXTENDED_OPT)
        if (lowMemory) { setCPUArenaAllocator(false); setMemoryPatternOptimization(false) }
    })
    private val needsTypes = s.inputNames.contains("token_type_ids")

    fun embed(text: String, maxLen: Int = 192): FloatArray {
        val ids = tok.tokenize(text, maxLen)
        val n = ids.size
        val shape = longArrayOf(1, n.toLong())
        val tensors = ArrayList<OnnxValue>()
        try {
            val inputs = HashMap<String, OnnxTensor>()
            OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape).also { tensors.add(it); inputs["input_ids"] = it }
            OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(n) { 1L }), shape).also { tensors.add(it); inputs["attention_mask"] = it }
            if (needsTypes) OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(n)), shape).also { tensors.add(it); inputs["token_type_ids"] = it }
            s.run(inputs).use { r ->
                val out = r.get(0) as OnnxTensor
                val sh = out.info.shape
                val fb = out.floatBuffer
                val dim = sh[sh.size - 1].toInt()
                val v = FloatArray(dim)
                if (sh.size == 2) { for (d in 0 until dim) v[d] = fb.get(d) } // already pooled
                else if (cls) { for (d in 0 until dim) v[d] = fb.get(d) } // first token
                else {
                    val rows = sh[1].toInt()
                    for (row in 0 until rows) for (d in 0 until dim) v[d] += fb.get(row * dim + d)
                    for (d in 0 until dim) v[d] /= rows
                }
                var norm = 0f
                for (d in 0 until dim) norm += v[d] * v[d]
                norm = kotlin.math.sqrt(norm).coerceAtLeast(1e-9f)
                for (d in 0 until dim) v[d] /= norm
                return v
            }
        } finally { tensors.forEach { it.close() } }
    }

    override fun close() { try { s.close() } catch (e: Exception) {} }
}
