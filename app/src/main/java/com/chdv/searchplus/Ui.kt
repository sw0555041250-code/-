package com.chdv.searchplus

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ctx = this
        Prefs.init(this)
        // if the app ever closes because of an error, keep the details and show them next time
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = java.io.StringWriter(); e.printStackTrace(java.io.PrintWriter(sw))
                java.io.File(filesDir, "crash.txt").writeText("${java.util.Date()}  thread=${t.name}\n$sw".take(8000))
            } catch (x: Throwable) {}
            // a crash in a background thread (scan, AI) must not close the app
            if (t !== android.os.Looper.getMainLooper().thread) {
                Progress.running = false
                return@setDefaultUncaughtExceptionHandler
            }
            prev?.uncaughtException(t, e)
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // the system is short of memory: free the AI models (they load again when needed)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            Thread { try { Semantic.close() } catch (e: Throwable) {}; try { if (!Progress.paused) VoiceModels.Engine.release() } catch (e: Throwable) {} }.start()
        }
    }

    companion object {
        lateinit var ctx: App
        val main = Handler(Looper.getMainLooper())
    }
}

object Prefs {
    private lateinit var sp: SharedPreferences
    fun init(c: Context) { sp = c.getSharedPreferences("searchplus", Context.MODE_PRIVATE) }
    fun b(k: String, d: Boolean) = sp.getBoolean(k, d)
    fun s(k: String, d: String) = sp.getString(k, d) ?: d
    fun i(k: String, d: Int) = sp.getInt(k, d)
    fun l(k: String, d: Long) = sp.getLong(k, d)
    fun has(k: String) = sp.contains(k)
    fun put(k: String, v: Any?) {
        val e = sp.edit()
        when (v) {
            null -> e.remove(k)
            is Boolean -> e.putBoolean(k, v)
            is Int -> e.putInt(k, v)
            is Long -> e.putLong(k, v)
            else -> e.putString(k, v.toString())
        }
        e.apply()
    }
    fun list(k: String): MutableList<String> = s(k, "").split('\n').filter { it.isNotBlank() }.toMutableList()
    fun putList(k: String, l: List<String>) = put(k, l.joinToString("\n"))
}

/** Colours: calm, high contrast, works well on E-ink too. */
object Col {
    val BG = 0xFFFFFFFF.toInt()
    val TEXT = 0xFF111111.toInt()
    val SUB = 0xFF5A5A5A.toInt()
    val LINE = 0xFFD6D6D6.toInt()
    val CARD = 0xFFF3F4F6.toInt()
    val ACC = 0xFF1F3A5F.toInt()
    val ACC_LIGHT = 0xFFE3EAF3.toInt()
    val GOLD = 0xFFFFD65A.toInt()
    val HL = 0x77FFC800
    val RED = 0xFFB00020.toInt()
}

fun scale(): Float = when (Prefs.s("fontSize", "normal")) {
    "small" -> 0.9f; "large" -> 1.15f; "xlarge" -> 1.3f; else -> 1f
}

fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density + 0.5f).toInt()
fun Context.toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
fun ui(r: () -> Unit) = App.main.post(r)

fun roundBg(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color); cornerRadius = radius
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

fun Context.pressBg(color: Int, pressed: Int, radiusDp: Int, stroke: Int = 0, strokeColor: Int = 0): Drawable =
    StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), roundBg(pressed, dp(radiusDp).toFloat(), stroke, strokeColor))
        addState(intArrayOf(), roundBg(color, dp(radiusDp).toFloat(), stroke, strokeColor))
    }

fun Context.label(s: CharSequence, size: Float, color: Int = Col.TEXT, bold: Boolean = false): TextView =
    TextView(this).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size * scale())
        if (bold) typeface = Typeface.DEFAULT_BOLD
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG_RTL
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    }

fun Context.vbox(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
fun Context.hbox(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
}

fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
fun LinearLayout.LayoutParams.m(l: Int, t: Int, r: Int, b: Int) = apply { setMargins(l, t, r, b) }

/** Rounded pill button. */
fun Context.pill(text: String, filled: Boolean = true, onClick: () -> Unit): TextView = label(text, 15f, if (filled) Color.WHITE else Col.ACC, true).apply {
    gravity = Gravity.CENTER
    textAlignment = View.TEXT_ALIGNMENT_CENTER
    setPadding(dp(16), dp(10), dp(16), dp(10))
    background = if (filled) pressBg(Col.ACC, 0xFF0F2540.toInt(), 22) else pressBg(Col.BG, Col.ACC_LIGHT, 22, dp(1.5f), Col.ACC)
    isClickable = true
    setOnClickListener { onClick() }
}

/** Small selectable chip. */
fun Context.chip(text: String, selected: Boolean, onClick: () -> Unit): TextView = label(text, 14f, if (selected) Color.WHITE else Col.TEXT, selected).apply {
    gravity = Gravity.CENTER
    textAlignment = View.TEXT_ALIGNMENT_CENTER
    setPadding(dp(12), dp(7), dp(12), dp(7))
    background = if (selected) pressBg(Col.ACC, 0xFF0F2540.toInt(), 16) else pressBg(Col.CARD, Col.ACC_LIGHT, 16, dp(1), Col.LINE)
    isClickable = true
    setOnClickListener { onClick() }
}

fun Context.divider(): View = View(this).apply {
    setBackgroundColor(Col.LINE)
    layoutParams = lp(MATCH, maxOf(1, dp(0.7f)))
}

/** A simple wrapping row layout (chips flow to the next line). Lays out right-to-left. */
class FlowLayout(c: Context) : ViewGroup(c) {
    var gap = c.dp(6)
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val ch = getChildAt(i)
            if (ch.visibility == GONE) continue
            measureChild(ch, MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), heightMeasureSpec)
            if (x > 0 && x + ch.measuredWidth > maxW) { x = 0; y += rowH + gap; rowH = 0 }
            x += ch.measuredWidth + gap
            rowH = maxOf(rowH, ch.measuredHeight)
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), y + rowH + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        var x = 0; var y = paddingTop; var rowH = 0
        for (i in 0 until childCount) {
            val ch = getChildAt(i)
            if (ch.visibility == GONE) continue
            if (x > 0 && x + ch.measuredWidth > maxW) { x = 0; y += rowH + gap; rowH = 0 }
            val left = if (rtl) (r - l) - paddingRight - x - ch.measuredWidth else paddingLeft + x
            ch.layout(left, y, left + ch.measuredWidth, y + ch.measuredHeight)
            x += ch.measuredWidth + gap
            rowH = maxOf(rowH, ch.measuredHeight)
        }
    }
}

open class BaseActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
    }

    /** Standard screen: header with back + title + optional actions, and a body. */
    fun screen(title: String, body: View, actions: List<View> = emptyList(), back: Boolean = true): LinearLayout {
        val root = vbox().apply { setBackgroundColor(Col.BG); layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val head = hbox().apply { setPadding(dp(8), dp(8), dp(8), dp(8)) }
        if (back) head.addView(label("→", 24f, Col.ACC, true).apply {
            setPadding(dp(10), dp(2), dp(12), dp(2))
            setOnClickListener { finish() }
        })
        head.addView(label(title, 21f, Col.TEXT, true).apply { setSingleLine() }, lp(0, WRAP, 1f))
        actions.forEach { head.addView(it) }
        root.addView(head, lp(MATCH, WRAP))
        root.addView(divider())
        root.addView(body, lp(MATCH, 0, 1f))
        setContentView(root)
        return root
    }

    fun headerButton(text: String, onClick: () -> Unit): TextView = label(text, 15f, Col.ACC, true).apply {
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = pressBg(Color.TRANSPARENT, Col.ACC_LIGHT, 14)
        setOnClickListener { onClick() }
    }
}

/** Dialog helpers (bottom sheets in the app's style). */
object Sheet {
    fun make(ctx: Context, content: View): Dialog {
        val d = Dialog(ctx)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val wrap = FrameLayout(ctx).apply { setPadding(ctx.dp(12), ctx.dp(12), ctx.dp(12), ctx.dp(12)) }
        content.layoutDirection = View.LAYOUT_DIRECTION_RTL
        content.background = roundBg(Col.BG, ctx.dp(16).toFloat(), ctx.dp(1), Col.LINE)
        wrap.addView(content)
        d.setContentView(wrap)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
        }
        return d
    }

    private fun box(ctx: Context, title: String?): LinearLayout = ctx.vbox().apply {
        setPadding(ctx.dp(18), ctx.dp(16), ctx.dp(18), ctx.dp(14))
        if (title != null) addView(ctx.label(title, 19f, Col.TEXT, true), lp(MATCH, WRAP).m(0, 0, 0, ctx.dp(10)))
    }

    private fun scrollable(ctx: Context, inner: View, maxDp: Int = 420): View = ScrollView(ctx).apply {
        addView(inner)
        isVerticalScrollBarEnabled = true
        viewTreeObserver.addOnGlobalLayoutListener {
            val max = ctx.dp(maxDp)
            if (height > max) layoutParams = layoutParams.apply { height = max }
        }
    }

    fun info(ctx: Context, title: String, msg: String) {
        val b = box(ctx, title)
        b.addView(scrollable(ctx, ctx.label(msg, 15f, Col.SUB)), lp(MATCH, WRAP))
        lateinit var d: Dialog
        b.addView(ctx.pill("אישור") { d.dismiss() }, lp(MATCH, WRAP).m(0, ctx.dp(14), 0, 0))
        d = make(ctx, b); d.show()
    }

    fun confirm(ctx: Context, title: String, msg: String, ok: String, onOk: () -> Unit) {
        val b = box(ctx, title)
        b.addView(scrollable(ctx, ctx.label(msg, 15f, Col.SUB)), lp(MATCH, WRAP))
        lateinit var d: Dialog
        val row = ctx.hbox()
        row.addView(ctx.pill(ok) { d.dismiss(); onOk() }, lp(0, WRAP, 1f).m(ctx.dp(4), 0, ctx.dp(4), 0))
        row.addView(ctx.pill("ביטול", false) { d.dismiss() }, lp(0, WRAP, 1f).m(ctx.dp(4), 0, ctx.dp(4), 0))
        b.addView(row, lp(MATCH, WRAP).m(0, ctx.dp(14), 0, 0))
        d = make(ctx, b); d.show()
    }

    fun choice(ctx: Context, title: String, options: List<Pair<String, String>>, current: String?, onPick: (String) -> Unit) {
        val b = box(ctx, title)
        val list = ctx.vbox()
        lateinit var d: Dialog
        options.forEach { (key, text) ->
            val sel = key == current
            list.addView(ctx.label((if (sel) "● " else "○ ") + text, 16f, if (sel) Col.ACC else Col.TEXT, sel).apply {
                setPadding(ctx.dp(6), ctx.dp(12), ctx.dp(6), ctx.dp(12))
                background = ctx.pressBg(Color.TRANSPARENT, Col.ACC_LIGHT, 10)
                setOnClickListener { d.dismiss(); onPick(key) }
            }, lp(MATCH, WRAP))
        }
        b.addView(scrollable(ctx, list), lp(MATCH, WRAP))
        d = make(ctx, b); d.show()
    }

    fun actions(ctx: Context, title: String?, items: List<Pair<String, () -> Unit>>) =
        choice(ctx, title ?: "", items.mapIndexed { i, p -> i.toString() to p.first }, null) { k -> items[k.toInt()].second() }

    fun input(ctx: Context, title: String, initial: String, hint: String, numeric: Boolean = false, onOk: (String) -> Unit) {
        val b = box(ctx, title)
        val e = EditText(ctx).apply {
            setText(initial); this.hint = hint
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG_RTL
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
            setSelection(text.length)
        }
        b.addView(e, lp(MATCH, WRAP))
        lateinit var d: Dialog
        b.addView(ctx.pill("אישור") { d.dismiss(); onOk(e.text.toString()) }, lp(MATCH, WRAP).m(0, ctx.dp(12), 0, 0))
        d = make(ctx, b)
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
        e.requestFocus()
    }
}

fun Activity.safeStart(i: Intent): Boolean = try { startActivity(i); true } catch (e: Exception) { false }

fun human(size: Long): String = when {
    size >= 1L shl 30 -> String.format("%.1f GB", size / (1L shl 30).toDouble())
    size >= 1L shl 20 -> String.format("%.1f MB", size / (1L shl 20).toDouble())
    size >= 1L shl 10 -> String.format("%d KB", size shr 10)
    else -> "$size B"
}
