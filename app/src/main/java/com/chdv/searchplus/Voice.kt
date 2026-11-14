package com.chdv.searchplus

import ai.onnxruntime.OrtEnvironment
import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.chdv.searchplus.core.Whisper
import com.chdv.searchplus.core.WhisperTokenizer
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * Voice search. Two engines:
 *  • the device's own speech recognition service (Google), when it exists – fast and accurate, needs internet;
 *  • Whisper, an AI model that runs on the device without internet (downloaded once).
 * "Automatic" uses the device service when it is there and falls back to Whisper.
 */
object VoiceModels {
    class Spec(val key: String, val title: String, val repo: String, val sizeMb: Int, val note: String)

    val SPECS = listOf(
        Spec("small", "מדויק (מומלץ)", "Xenova/whisper-small", 250, "מבין עברית טוב. כ-10 שניות לזיהוי ב-BOOX"),
        Spec("base", "מהיר", "Xenova/whisper-base", 80, "מהיר פי 3, אבל טועה יותר בעברית"),
    )

    fun spec(key: String = Prefs.s("whisperModel", "small")) = SPECS.firstOrNull { it.key == key } ?: SPECS[0]

    /** Models go to the storage with the most free space (internal memory or the memory card). */
    fun dir(c: Context, key: String): File {
        val saved = Prefs.s("whisperDir", "")
        val base = if (saved.isNotEmpty() && File(saved).let { it.exists() && it.canWrite() }) File(saved) else {
            val best = (listOf(c.filesDir) + c.getExternalFilesDirs(null).filterNotNull()).filter { it.exists() || it.mkdirs() }
                .maxByOrNull { it.usableSpace } ?: c.filesDir
            Prefs.put("whisperDir", best.absolutePath)
            best
        }
        return File(base, "whisper-$key")
    }

    private fun files(c: Context, key: String) = dir(c, key).let {
        Triple(File(it, "encoder_model_quantized.onnx"), File(it, "decoder_model_merged_quantized.onnx"), File(it, "tokenizer.json"))
    }

    fun ready(c: Context, key: String = spec().key): Boolean {
        val (e, d, t) = files(c, key)
        return e.length() > 5_000_000 && d.length() > 5_000_000 && t.length() > 500_000
    }

    fun download(c: Context, key: String, progress: (String) -> Unit) {
        val s = spec(key)
        val d = dir(c, key).apply { mkdirs() }
        val base = "https://huggingface.co/${s.repo}/resolve/main/"
        val (e, dec, t) = files(c, key)
        if (t.length() < 500_000) Net.download(base + "tokenizer.json", t) { progress("מוריד מילון… $it%") }
        if (e.length() < 5_000_000) Net.download(base + "onnx/encoder_model_quantized.onnx", e) { progress("מוריד את המודל (חלק 1 מתוך 2)… $it%") }
        if (dec.length() < 5_000_000) Net.download(base + "onnx/decoder_model_merged_quantized.onnx", dec) { progress("מוריד את המודל (חלק 2 מתוך 2)… $it%") }
        if (!d.exists()) throw IllegalStateException("התיקייה לא נוצרה")
    }

    fun delete(c: Context, key: String) { Engine.release(); dir(c, key).deleteRecursively() }

    /** Keeps Whisper loaded for a few minutes after use (loading takes several seconds), then frees the memory. */
    object Engine {
        private var w: Whisper? = null
        private var key = ""
        private val releaser = Runnable { release() }

        @Synchronized
        fun get(c: Context): Whisper {
            val k = spec().key
            App.main.removeCallbacks(releaser)
            w?.let { if (key == k) return it }
            release()
            Semantic.close() // never hold two models at once on a 2GB device
            System.gc()
            val (e, d, t) = files(c, k)
            val threads = max(1, minOf(4, Runtime.getRuntime().availableProcessors()))
            return Whisper(OrtEnvironment.getEnvironment(), e.absolutePath, d.absolutePath, WhisperTokenizer(t), threads).also { w = it; key = k }
        }

        fun releaseLater() { App.main.removeCallbacks(releaser); App.main.postDelayed(releaser, 3 * 60 * 1000L) }

        @Synchronized
        fun release() { try { w?.close() } catch (e: Exception) {}; w = null }
    }

    fun freeMemoryMb(c: Context): Long {
        val mi = ActivityManager.MemoryInfo()
        (c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        return mi.availMem / (1024 * 1024)
    }
}

/** Records from the microphone until the speaker stops (about 1.3 seconds of silence) or 12 seconds pass. */
class Recorder(private val onLevel: (Float) -> Unit) {
    @Volatile var stop = false
    @Volatile var cancel = false

    fun record(): FloatArray? {
        val sr = 16000
        val min = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // some devices (E-ink readers among them) don't offer the speech-recognition source: fall back to the plain microphone
        var rec: AudioRecord? = null
        for (src in listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.DEFAULT)) {
            val r = try { AudioRecord(src, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, sr)) } catch (e: Exception) { null } ?: continue
            if (r.state == AudioRecord.STATE_INITIALIZED) { rec = r; break }
            r.release()
        }
        if (rec == null) return null
        val out = ShortArrayBuilder()
        val buf = ShortArray(sr / 10) // 100ms
        var noise = 300f
        var spoke = false
        var quiet = 0
        try {
            rec.startRecording()
            val t0 = System.currentTimeMillis()
            while (!stop && !cancel && System.currentTimeMillis() - t0 < 12_000) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                out.add(buf, n)
                var e = 0L
                for (i in 0 until n) e += abs(buf[i].toInt())
                val level = e.toFloat() / n
                onLevel((level / 3000f).coerceIn(0f, 1f))
                if (!spoke) {
                    if (level > noise * 2.2f && level > 150) spoke = true
                    else noise = noise * 0.9f + level * 0.1f
                } else {
                    if (level < noise * 1.8f) quiet++ else quiet = 0
                    if (quiet >= 13) break
                }
                if (!spoke && System.currentTimeMillis() - t0 > 6000) break // nobody spoke
            }
        } finally {
            try { rec.stop() } catch (e: Exception) {}
            rec.release()
        }
        if (cancel) return null
        val s = out.get()
        return FloatArray(s.size) { s[it] / 32768f }
    }

    private class ShortArrayBuilder {
        private var a = ShortArray(16000 * 4); private var n = 0
        fun add(b: ShortArray, len: Int) { if (n + len > a.size) a = a.copyOf(maxOf(a.size * 2, n + len)); System.arraycopy(b, 0, a, n, len); n += len }
        fun get() = a.copyOf(n)
    }
}

/** The voice search window: listen → recognize → give the text back. */
object VoiceSearch {
    const val REQ_MIC = 41
    private var pending: ((String) -> Unit)? = null

    fun systemAvailable(c: Context) = try { SpeechRecognizer.isRecognitionAvailable(c) } catch (e: Exception) { false }

    // default: on the device, no internet needed (the user asked for offline voice search)
    fun engine(c: Context): String = when (Prefs.s("voiceEngine", "whisper")) {
        "system" -> "system"
        "whisper" -> "whisper"
        else -> if (systemAvailable(c) && !Prefs.b("systemFailed", false)) "system" else "whisper"
    }

    fun start(a: Activity, onText: (String) -> Unit) {
        if (a.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pending = onText
            a.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        if (engine(a) == "system") listenSystem(a, onText) else listenWhisper(a, onText)
    }

    /** Call from onRequestPermissionsResult. */
    fun onPermission(a: Activity, granted: Boolean) {
        val p = pending; pending = null
        if (granted && p != null) start(a, p)
        else if (!granted) Sheet.info(a, "אין גישה למיקרופון", "כדי לחפש בקול צריך לאשר לחיפוש פלוס להשתמש במיקרופון. אפשר לאשר בהגדרות המכשיר › אפליקציות › חיפוש פלוס › הרשאות.")
    }

    private class Ui(val d: Dialog, val title: TextView, val text: TextView, val bar: View, val btn: TextView)

    private fun window(a: Activity, onButton: () -> Unit, onClose: () -> Unit): Ui {
        val box = a.vbox().apply { setPadding(a.dp(20), a.dp(20), a.dp(20), a.dp(18)) }
        val title = a.label("🎤 מקשיב… דברו עכשיו", 20f, Col.TEXT, true)
        box.addView(title)
        val hint = a.label("לדוגמה: \"ברכות דף כ״ז עמוד א\" · \"ליקוטי שיחות חלק ג ויצא\" · \"מה מברכים על פרי העץ\"", 13.5f, Col.SUB)
        box.addView(hint, lp(MATCH, WRAP).m(0, a.dp(4), 0, a.dp(12)))
        val track = LinearLayout(a).apply { background = roundBg(Col.CARD, a.dp(6).toFloat(), a.dp(1), Col.LINE) }
        val bar = View(a).apply { background = roundBg(Col.ACC, a.dp(6).toFloat()) }
        track.addView(bar, LinearLayout.LayoutParams(a.dp(4), a.dp(14)))
        box.addView(track, lp(MATCH, a.dp(14)))
        val text = a.label("", 17f, Col.ACC, true)
        box.addView(text, lp(MATCH, WRAP).m(0, a.dp(12), 0, 0))
        val row = a.hbox()
        val btn = a.pill("סיימתי לדבר") { onButton() }
        row.addView(btn, lp(0, WRAP, 1f).m(a.dp(4), 0, a.dp(4), 0))
        lateinit var d: Dialog
        row.addView(a.pill("ביטול", false) { d.dismiss() }, lp(0, WRAP, 1f).m(a.dp(4), 0, a.dp(4), 0))
        box.addView(row, lp(MATCH, WRAP).m(0, a.dp(16), 0, 0))
        d = Sheet.make(a, box)
        d.setOnDismissListener { onClose() }
        d.show()
        return Ui(d, title, text, bar, btn)
    }

    private fun level(u: Ui, l: Float) {
        val w = (u.bar.parent as View).width
        u.bar.layoutParams = u.bar.layoutParams.apply { width = max(u.bar.context.dp(4), (w * l).toInt()) }
        u.bar.requestLayout()
    }

    // ---------- the device's speech service ----------

    private fun listenSystem(a: Activity, onText: (String) -> Unit) {
        val sr = try { SpeechRecognizer.createSpeechRecognizer(a) } catch (e: Exception) { null }
        if (sr == null) { Prefs.put("systemFailed", true); listenWhisper(a, onText); return }
        var done = false
        lateinit var u: Ui
        u = window(a, { sr.stopListening() }, { if (!done) { done = true; try { sr.cancel() } catch (e: Exception) {} }; try { sr.destroy() } catch (e: Exception) {} })
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rms: Float) { level(u, ((rms + 2f) / 12f).coerceIn(0f, 1f)) }
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() { u.title.text = "⏳ מזהה…" }
            override fun onPartialResults(r: Bundle?) {
                r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { u.text.text = it }
            }
            override fun onEvent(t: Int, p: Bundle?) {}
            override fun onResults(r: Bundle?) {
                if (done) return
                done = true
                val t = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                u.d.dismiss()
                if (t.isNotBlank()) onText(t) else a.toast("לא נקלט דיבור. נסו שוב")
            }
            override fun onError(err: Int) {
                if (done) return
                done = true
                u.d.dismiss()
                when (err) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> a.toast("לא נקלט דיבור. נסו שוב")
                    else -> {
                        // no internet / service missing: offer the on-device engine
                        if (Prefs.s("voiceEngine", "whisper") == "auto") {
                            if (err == SpeechRecognizer.ERROR_CLIENT || err == SpeechRecognizer.ERROR_SERVER || err == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) Prefs.put("systemFailed", true)
                            listenWhisper(a, onText)
                        } else Sheet.info(a, "זיהוי הדיבור נכשל", "שירות הזיהוי של המכשיר לא הצליח (קוד $err). ייתכן שאין אינטרנט. אפשר לבחור בהגדרות זיהוי בתוך המכשיר (Whisper), שעובד בלי אינטרנט.")
                    }
                }
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "he-IL")
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "he-IL")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        try { sr.startListening(i) } catch (e: Exception) { done = true; u.d.dismiss(); Prefs.put("systemFailed", true); listenWhisper(a, onText) }
    }

    // ---------- Whisper on the device ----------

    fun offerDownload(a: Activity, then: () -> Unit) {
        val items = VoiceModels.SPECS.map { s -> "${s.title} – כ-${s.sizeMb}MB\n${s.note}" to { Prefs.put("whisperModel", s.key); downloadNow(a, s.key, then) } }
        Sheet.actions(a, "זיהוי דיבור בתוך המכשיר – הורדה חד-פעמית", items)
    }

    fun downloadNow(a: Activity, key: String, then: () -> Unit) {
        val s = a.label("מתחיל להוריד…", 15f, Col.SUB)
        val d = Sheet.make(a, a.vbox().apply { setPadding(a.dp(18), a.dp(18), a.dp(18), a.dp(18)); addView(a.label("מוריד את מודל זיהוי הדיבור", 18f, Col.TEXT, true)); addView(s) })
        d.setCancelable(false); d.show()
        Thread {
            try {
                VoiceModels.download(a, key) { t -> ui { s.text = t } }
                ui { d.dismiss(); a.toast("הותקן ✓"); then() }
            } catch (e: Throwable) {
                ui { d.dismiss(); Sheet.info(a, "ההורדה נכשלה", "בדקו את החיבור לאינטרנט ואת המקום הפנוי ונסו שוב. מה שכבר ירד נשמר.\n" + (e.message ?: "")) }
            }
        }.start()
    }

    private fun listenWhisper(a: Activity, onText: (String) -> Unit) {
        if (!VoiceModels.ready(a)) { offerDownload(a) { listenWhisper(a, onText) }; return }
        val rec = Recorder { l -> ui { if (cur != null) level(cur!!, l) } }
        var closed = false
        val u = window(a, { rec.stop = true }, { closed = true; rec.cancel = true; Progress.paused = false })
        cur = u
        Progress.paused = true
        Thread {
            // load the model while the user speaks
            var w: Whisper? = null
            var loadErr: Throwable? = null
            val loader = Thread { try { w = VoiceModels.Engine.get(a) } catch (e: Throwable) { loadErr = e } }.apply { start() }
            val audio = try { rec.record() } catch (e: Throwable) { null }
            if (closed) { loader.join(); cur = null; VoiceModels.Engine.releaseLater(); return@Thread }
            if (audio == null || audio.size < 16000 / 3) {
                ui { u.d.dismiss(); a.toast(if (audio == null) "לא ניתן להשתמש במיקרופון" else "לא נקלט דיבור. נסו שוב") }
                loader.join(); cur = null; VoiceModels.Engine.releaseLater(); return@Thread
            }
            ui { u.title.text = "⏳ מזהה את הדיבור…"; u.btn.visibility = View.GONE; level(u, 0f) }
            val t0 = System.currentTimeMillis()
            val ticker = object : Runnable { override fun run() {
                if (closed || cur == null) return
                u.title.text = "⏳ מזהה את הדיבור… ${(System.currentTimeMillis() - t0) / 1000} שנ׳"
                App.main.postDelayed(this, 1000)
            } }
            App.main.post(ticker)
            Prefs.put("voiceBusy", System.currentTimeMillis())
            loader.join()
            val text = try {
                val m = w ?: throw (loadErr ?: IllegalStateException("המודל לא נטען"))
                m.transcribe(audio, "he", Whisper.PROMPT) { closed }.also {
                    Prefs.put("voiceLast", "${(System.currentTimeMillis() - t0) / 1000} שנ׳ · \"$it\"")
                }
            } catch (e: OutOfMemoryError) {
                VoiceModels.Engine.release(); System.gc()
                ui { u.d.dismiss(); Sheet.info(a, "אין מספיק זיכרון", "המכשיר עמוס כרגע. סגרו אפליקציות אחרות ונסו שוב, או בחרו בהגדרות את מודל הדיבור המהיר (קטן יותר).") }
                null
            } catch (e: Throwable) {
                VoiceModels.Engine.release()
                ui { u.d.dismiss(); Sheet.info(a, "זיהוי הדיבור נכשל", (e.message ?: e.javaClass.simpleName) + "\n\nאפשר לנסות שוב, או להוריד מחדש את המודל בהגדרות.") }
                null
            }
            cur = null
            Prefs.put("voiceBusy", 0L)
            App.main.removeCallbacks(ticker)
            Progress.paused = false
            VoiceModels.Engine.releaseLater()
            if (text != null && !closed) ui {
                u.d.dismiss()
                val clean = text.trim().trimEnd('.', '!', '?')
                if (clean.isBlank()) a.toast("לא נקלט דיבור. נסו שוב") else onText(clean)
            }
        }.start()
    }

    @Volatile private var cur: Ui? = null
}
