package com.chdv.searchplus.core

import ai.onnxruntime.OrtEnvironment
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Tests with the real models. The CI workflow downloads them into MODELS_DIR, together with
 * reference values made by the official Python libraries (tools/make_reference.py).
 */
class ModelTest {
    private val dir = File(System.getProperty("models") ?: "")
    private fun need(f: String): File { val x = File(dir, f); assumeTrue("missing $x", x.exists()); return x }

    private fun readWav(f: File): FloatArray {
        val b = f.readBytes()
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        var p = 12
        while (p + 8 <= b.size) {
            val id = String(b, p, 4, Charsets.US_ASCII); val size = bb.getInt(p + 4)
            if (id == "data") {
                val n = size / 2
                return FloatArray(n) { bb.getShort(p + 8 + it * 2) / 32768f }
            }
            p += 8 + size + (size and 1)
        }
        error("no data in $f")
    }

    @Test fun melMatchesReference() {
        val ref = need("mel_ref.f32")
        val wav = need("audio/0.wav")
        val mine = WhisperMel.logMel(readWav(wav), 80)
        val bb = ByteBuffer.wrap(ref.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        var maxDiff = 0f
        for (i in mine.indices) maxDiff = maxOf(maxDiff, kotlin.math.abs(mine[i] - bb.get(i)))
        println("mel max diff = $maxDiff")
        assertTrue("mel differs by $maxDiff", maxDiff < 2e-3f)
    }

    @Test fun whisperTokenizer() {
        val tok = WhisperTokenizer(need("whisper-base/tokenizer.json"))
        val expected = need("whisper_prompt_ids.txt").readText().trim().split(',').map { it.trim().toInt() }
        val mine = tok.encode(" " + Whisper.PROMPT)
        assertEquals(expected, mine)
        assertEquals(Whisper.PROMPT, tok.decode(mine).trim())
        assertEquals(50258, tok.id("<|startoftranscript|>"))
    }

    @Test fun whisperTranscribes() {
        val env = OrtEnvironment.getEnvironment()
        val cases = need("audio/cases.txt").readLines().filter { it.isNotBlank() }
        for (model in listOf("whisper-base", "whisper-small")) {
            val enc = File(dir, "$model/encoder_model_quantized.onnx")
            if (!enc.exists()) { println("skip $model"); continue }
            val w = Whisper(env, enc.path, File(dir, "$model/decoder_model_merged_quantized.onnx").path, WhisperTokenizer(File(dir, "$model/tokenizer.json")))
            w.use {
                var good = 0
                cases.forEachIndexed { i, said ->
                    val audio = readWav(File(dir, "audio/$i.wav"))
                    val t0 = System.currentTimeMillis()
                    val text = w.transcribe(audio, "he", Whisper.PROMPT)
                    val ms = System.currentTimeMillis() - t0
                    val want = RefParser.parse(said)
                    val got = RefParser.parse(text)
                    val ok = want == null || (got != null && got.work == want.work && got.daf == want.daf && got.volume == want.volume && got.parsha == want.parsha && got.chapter == want.chapter)
                    if (ok) good++
                    println("[$model] ${if (ok) "OK " else "BAD"} said=「$said」 heard=「$text」 ref=${got?.label()} (${ms}ms: mel ${w.lastMelMs} enc ${w.lastEncMs} dec ${w.lastDecMs})")
                    assertTrue("empty transcription for $said", text.isNotBlank())
                }
                println("[$model] understood $good / ${cases.size}")
                // a quiet microphone: the same sentence at 3% volume must still be understood
                val quiet = readWav(File(dir, "audio/0.wav")).map { it * 0.03f }.toFloatArray()
                println("[$model] quiet: 「${w.transcribe(quiet, "he", Whisper.PROMPT)}」")
                println("[$model] peak memory so far: " + File("/proc/self/status").readLines().filter { it.startsWith("VmHWM") || it.startsWith("VmRSS") })
            }
        }
    }

    @Test fun e5MatchesPython() {
        val bin = File(dir, "e5/vocab.bin")
        val t0 = System.currentTimeMillis()
        E5Tokenizer.compile(need("e5/tokenizer.json"), bin)
        println("compile vocab ${System.currentTimeMillis() - t0}ms, ${bin.length()} bytes")
        val tok = E5Tokenizer.load(bin)
        val lines = need("e5_ref.txt").readLines().filter { it.isNotBlank() }
        val env = OrtEnvironment.getEnvironment()
        E5Model(env, need("e5/model_quantized.onnx").path, tok).use { m ->
            for (l in lines) {
                val (text, ids, vec) = l.split('\t')
                val want = ids.split(',').map { it.toLong() }.toLongArray()
                val mine = tok.tokenize(text, 512)
                assertArrayEquals("tokens of 「$text」", want, mine)
                val ref = vec.split(',').map { it.toFloat() }
                val v = m.embed(text, 512)
                var dot = 0f
                for (d in v.indices) dot += v[d] * ref[d]
                println("e5 cos=$dot  $text")
                assertTrue("embedding differs ($dot) for $text", dot > 0.999f)
            }
            // meaning: the question is closer to the passage that answers it
            val q = m.embed("query: מה מברכים על פרי העץ")
            val good = m.embed("passage: על פירות האילן הוא אומר בורא פרי העץ, ועל פירות הארץ בורא פרי האדמה")
            val bad = m.embed("passage: מי שנתחייב מלקות במזיד והתרו בו לוקה ארבעים חסר אחת")
            fun dot(a: FloatArray, b: FloatArray) = a.indices.sumOf { (a[it] * b[it]).toDouble() }
            println("e5 good=${dot(q, good)} bad=${dot(q, bad)}")
            assertTrue(dot(q, good) > dot(q, bad))
        }
    }

    @Test fun bgeM3() {
        val bin = File(dir, "bge/vocab.bin")
        E5Tokenizer.compile(need("bge/tokenizer.json"), bin)
        val tok = E5Tokenizer.load(bin)
        val env = OrtEnvironment.getEnvironment()
        val t0 = System.currentTimeMillis()
        E5Model(env, need("bge/model_quantized.onnx").path, tok, cls = true, lowMemory = true).use { m ->
            println("bge load ${System.currentTimeMillis() - t0}ms")
            val vecs = ArrayList<FloatArray>()
            for (l in need("bge_ref.txt").readLines().filter { it.isNotBlank() }) {
                val (text, ids, vec) = l.split('\t')
                assertArrayEquals("tokens of 「$text」", ids.split(',').map { it.toLong() }.toLongArray(), tok.tokenize(text, 512))
                val ref = vec.split(',').map { it.toFloat() }
                val t1 = System.currentTimeMillis()
                val v = m.embed(text, 512)
                var dot = 0f
                for (d in v.indices) dot += v[d] * ref[d]
                println("bge cos=$dot (${System.currentTimeMillis() - t1}ms) $text")
                assertTrue(dot > 0.98f) // the quantized model differs slightly between ONNX Runtime versions
                vecs.add(v)
            }
            fun dot(a: FloatArray, b: FloatArray) = a.indices.sumOf { (a[it] * b[it]).toDouble() }
            println("bge good=${dot(vecs[0], vecs[1])} bad=${dot(vecs[0], vecs[2])}")
            assertTrue(dot(vecs[0], vecs[1]) > dot(vecs[0], vecs[2]))
            println("bge peak memory: " + File("/proc/self/status").readLines().filter { it.startsWith("VmHWM") })
        }
    }
}
