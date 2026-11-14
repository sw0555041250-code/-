package com.chdv.searchplus

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ResultReceiver
import com.chdv.searchplus.core.Whisper
import com.chdv.searchplus.core.WhisperTokenizer
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Speech recognition in its own process (":voice"). If the model is too heavy for the device and the
 * system kills it, or the native code crashes, only this process dies – the app stays open, notices,
 * and offers the lighter model or online recognition instead of closing.
 */
class VoiceService : Service() {
    companion object {
        const val ACT_LOAD = "load"
        const val ACT_RUN = "run"
        private var w: Whisper? = null
        private var wKey = ""
        private val exec = Executors.newSingleThreadExecutor()
    }

    private val main = Handler(Looper.getMainLooper())
    private val idle = Runnable {
        // nothing to do for a while: give all the memory back by ending this process
        try { w?.close() } catch (e: Exception) {}
        w = null
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    override fun onBind(intent: Intent?): IBinder = Binder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val i = intent ?: return START_NOT_STICKY
        main.removeCallbacks(idle)
        exec.execute {
            @Suppress("DEPRECATION") val rr: ResultReceiver? = i.getParcelableExtra("rr")
            try {
                val m = load(i)
                if (i.action == ACT_RUN) {
                    val audio = readAudio(File(i.getStringExtra("audio")!!))
                    val t0 = System.currentTimeMillis()
                    val text = m.transcribe(audio, "he", Whisper.PROMPT)
                    rr?.send(0, Bundle().apply {
                        putString("text", text); putLong("ms", System.currentTimeMillis() - t0); putLong("enc", m.lastEncMs)
                    })
                }
            } catch (e: Throwable) {
                try { w?.close() } catch (x: Exception) {}
                w = null
                rr?.send(1, Bundle().apply { putString("error", (if (e is OutOfMemoryError) "אין מספיק זיכרון" else e.message ?: e.javaClass.simpleName)) })
            }
            main.postDelayed(idle, 3 * 60 * 1000L)
        }
        return START_NOT_STICKY
    }

    private fun load(i: Intent): Whisper {
        val key = i.getStringExtra("key") ?: "small"
        w?.let { if (wKey == key) return it }
        try { w?.close() } catch (e: Exception) {}
        w = null
        System.gc()
        val threads = max(1, minOf(4, Runtime.getRuntime().availableProcessors()))
        return Whisper(OrtEnvironment.getEnvironment(), i.getStringExtra("enc")!!, i.getStringExtra("dec")!!,
            WhisperTokenizer(File(i.getStringExtra("tok")!!)), threads, OrtSession.SessionOptions.OptLevel.BASIC_OPT).also { w = it; wKey = key }
    }

    private fun readAudio(f: File): FloatArray = DataInputStream(f.inputStream().buffered()).use { d ->
        val n = d.readInt(); FloatArray(n) { d.readFloat() }
    }
}

/** The app's side: start the voice process, send it the recording, and notice if it dies. */
object VoiceRemote {
    class Result(val text: String?, val error: String?, val died: Boolean, val ms: Long = 0, val encMs: Long = 0)

    private fun base(c: Context, action: String): Intent {
        val (e, d, t) = VoiceModels.paths(c, VoiceModels.spec().key)
        return Intent(c, VoiceService::class.java).setAction(action)
            .putExtra("key", VoiceModels.spec().key).putExtra("enc", e.absolutePath).putExtra("dec", d.absolutePath).putExtra("tok", t.absolutePath)
    }

    /** Start loading the model while the user is still speaking. */
    fun warm(c: Context) { try { c.startService(base(c, VoiceService.ACT_LOAD)) } catch (e: Exception) {} }

    fun transcribe(c: Context, audio: FloatArray, done: (Result) -> Unit) {
        val f = File(c.cacheDir, "voice.f32")
        DataOutputStream(f.outputStream().buffered()).use { o -> o.writeInt(audio.size); for (x in audio) o.writeFloat(x) }
        var finished = false
        lateinit var conn: ServiceConnection
        fun finish(r: Result) {
            if (finished) return
            finished = true
            try { c.unbindService(conn) } catch (e: Exception) {}
            done(r)
        }
        conn = object : ServiceConnection {
            override fun onServiceConnected(n: ComponentName?, b: IBinder?) {}
            // the voice process died (killed for memory or crashed in native code)
            override fun onServiceDisconnected(n: ComponentName?) { ui { finish(Result(null, null, true)) } }
        }
        try { c.bindService(Intent(c, VoiceService::class.java), conn, Context.BIND_AUTO_CREATE) } catch (e: Exception) {}
        val rr = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(code: Int, data: Bundle?) {
                if (code == 0) finish(Result(data?.getString("text") ?: "", null, false, data?.getLong("ms") ?: 0, data?.getLong("enc") ?: 0))
                else finish(Result(null, data?.getString("error") ?: "שגיאה", false))
            }
        }
        try { c.startService(base(c, VoiceService.ACT_RUN).putExtra("audio", f.absolutePath).putExtra("rr", rr)) }
        catch (e: Exception) { finish(Result(null, e.message ?: "לא ניתן להפעיל את הזיהוי", false)); return }
        // a device far too slow for this model: stop waiting after 3 minutes
        App.main.postDelayed({ finish(Result(null, "הזיהוי לקח יותר מדי זמן במכשיר הזה", false)) }, 180_000)
    }
}
