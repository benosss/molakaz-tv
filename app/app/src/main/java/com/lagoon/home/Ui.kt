package com.lagoon.home

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** Design units: everything is laid out on a 1280x720 grid and scaled to the real screen. */
object D {
    var s = 1.5f
    fun u(v: Float) = v * s
    fun ui(v: Float) = (v * s).roundToInt()
}

/** Apple-style curves. Springs drive anything the remote moves; curves drive fades. */
object Ease {
    val out = PathInterpolator(0.22f, 1f, 0.36f, 1f)     // decisive start, long soft landing
    val inOut = PathInterpolator(0.45f, 0f, 0.25f, 1f)
    val exit = PathInterpolator(0.4f, 0f, 1f, 1f)
}

object Motion {
    /** Honors Settings > Accessibility > Remove animations (animator duration scale = 0). */
    val on: Boolean get() = ValueAnimator.areAnimatorsEnabled()
}

/** Spring presets: response tuned for a 60 Hz TV, damping just under critical for a hint of life. */
object Spring {
    const val FOCUS_K = 520f; const val FOCUS_Z = 0.68f      // focus lift: quick, tiny overshoot
    const val GLIDE_K = 260f; const val GLIDE_Z = 0.86f      // rows and pills sliding
    const val SOFT_K = 180f; const val SOFT_Z = 0.92f        // entrances
}

fun argbA(a: Float, rgb: Int) = ((a.coerceIn(0f, 1f) * 255f).roundToInt() shl 24) or (rgb and 0xFFFFFF)

fun mix(a: Int, b: Int, t: Float): Int {
    val u = 1f - t
    return Color.argb(
        (Color.alpha(a) * u + Color.alpha(b) * t).roundToInt(),
        (Color.red(a) * u + Color.red(b) * t).roundToInt(),
        (Color.green(a) * u + Color.green(b) * t).roundToInt(),
        (Color.blue(a) * u + Color.blue(b) * t).roundToInt()
    )
}

fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/** Day (light glass) ↔ night (dark glass). t animates 0..1 across sunrise/sunset. */
object Theme {
    var t = 0f
        private set
    private val listeners = ArrayList<() -> Unit>()
    fun listen(f: () -> Unit) { listeners += f }
    fun set(v: Float) { t = v; listeners.forEach { it() } }
    val ink get() = mix(0xFF0B2230.toInt(), Color.WHITE, t)
    val ink2 get() = mix(argbA(0.66f, 0x0B2230), argbA(0.72f, 0xFFFFFF), t)
}

private val springs = WeakHashMap<View, HashMap<DynamicAnimation.ViewProperty, SpringAnimation>>()

/** Interruptible spring: retargets mid-flight and keeps its velocity, so held D-pad presses stay fluid. */
fun View.springTo(p: DynamicAnimation.ViewProperty, to: Float, k: Float = Spring.GLIDE_K, z: Float = Spring.GLIDE_Z) {
    val m = springs.getOrPut(this) { HashMap() }
    val a = m.getOrPut(p) { SpringAnimation(this, p).setSpring(SpringForce(to)) }
    a.spring.setStiffness(k).setDampingRatio(z)
    if (!Motion.on) { a.cancel(); p.setValue(this, to); return }
    a.animateToFinalPosition(to)
}

/** Where [p] is headed: a running spring's target, else where the view is now. */
fun View.springRest(p: DynamicAnimation.ViewProperty): Float =
    springs[this]?.get(p)?.takeIf { it.isRunning }?.spring?.finalPosition ?: p.getValue(this)

/** Jump straight to a value, stopping any spring that was heading somewhere else. */
fun View.springSet(p: DynamicAnimation.ViewProperty, v: Float) { springs[this]?.get(p)?.cancel(); p.setValue(this, v) }

fun View.springScale(to: Float, k: Float = Spring.FOCUS_K, z: Float = Spring.FOCUS_Z) {
    springTo(DynamicAnimation.SCALE_X, to, k, z); springTo(DynamicAnimation.SCALE_Y, to, k, z)
}

/** Fade + rise, used for entrances and label swaps. Hardware layer only while animating. */
fun View.fadeTo(alpha: Float, dy: Float = 0f, ms: Long = 420, delay: Long = 0, end: (() -> Unit)? = null) {
    animate().cancel()
    if (!Motion.on) { this.alpha = alpha; translationY = dy; end?.invoke(); return }
    animate().alpha(alpha).translationY(dy).setDuration(ms).setStartDelay(delay)
        .setInterpolator(if (alpha > this.alpha) Ease.out else Ease.exit).withLayer()
        .withEndAction { end?.invoke() }.start()
}

/**
 * A TextView whose shadow is a real Gaussian blur of its own text, rendered once per change with room to fade to zero.
 * Android's built-in text shadow on this GPU is cut to a tight texture with 8-bit steps, which shows as a faint
 * blocky rectangle over bright scenery.
 */
class SoftText(ctx: Context) : TextView(ctx) {
    private var sr = 0f; private var sdx = 0f; private var sdy = 0f; private var sc = 0
    private var cache: android.graphics.Bitmap? = null
    private var key = ""
    private var pad = 0
    private val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
    private var building = false

    /**
     * The shadow reaches past the text's own box, so the views around it must not clip it (that clipping is what drew
     * hard dark rectangles). Stops at a view tagged clip_edge (Settings needs its clipping to scroll).
     */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (sr <= 0.5f) return
        var v = parent
        while (v is android.view.ViewGroup && v.getTag(R.id.clip_edge) == null) { v.clipChildren = false; v.clipToPadding = false; v = v.parent }
    }

    override fun setShadowLayer(radius: Float, dx: Float, dy: Float, color: Int) {
        sr = radius; sdx = dx; sdy = dy; sc = color
        super.setShadowLayer(0f, 0f, 0f, 0)
        cache = null; invalidate()
    }

    override fun onDraw(c: android.graphics.Canvas) {
        if (!building && sr > 0.5f && android.graphics.Color.alpha(sc) > 0 && width > 0 && height > 0) {
            val k = "$text|$width|$height|$currentTextColor|$textSize|$gravity"
            if (cache == null || k != key) { cache?.recycle(); cache = build(); key = k }
            cache?.let { paint.color = sc; c.drawBitmap(it, -pad + off[0] + sdx, -pad + off[1] + sdy, paint) }
        }
        super.onDraw(c)
    }

    /** The text exactly as drawn (gravity, ellipsis), then its glyph shapes blurred (extractAlpha applies the blur to
     *  the alpha itself; drawBitmap with a mask filter blurs the bitmap's rectangle, which is what looked blocky). */
    private var off = IntArray(2)
    private fun build(): android.graphics.Bitmap? = runCatching {
        pad = (sr * 2.4f).toInt() + 2
        val src = android.graphics.Bitmap.createBitmap(width + pad * 2, height + pad * 2, android.graphics.Bitmap.Config.ARGB_8888)
        building = true
        try { android.graphics.Canvas(src).apply { translate(pad.toFloat(), pad.toFloat()); super.onDraw(this) } } finally { building = false }
        val o = IntArray(2)
        val out = src.extractAlpha(android.graphics.Paint().apply {
            maskFilter = android.graphics.BlurMaskFilter(sr, android.graphics.BlurMaskFilter.Blur.NORMAL) }, o)
        src.recycle()
        off = o
        out
    }.getOrNull()
}

fun Context.label(sizeU: Float, family: String = "sans-serif", bold: Boolean = false, color: Int = Color.WHITE, shadow: Boolean = false) =
    SoftText(this).apply {
        typeface = Typeface.create(family, if (bold) Typeface.BOLD else Typeface.NORMAL)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, D.u(sizeU))
        setTextColor(color)
        includeFontPadding = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        if (shadow) setShadowLayer(D.u(9f), 0f, D.u(1.5f), argbA(0.6f, 0x00142A))
    }

/** Absolute placement in design units. Pass the sides you want to pin. */
fun place(w: Int, h: Int, l: Float? = null, t: Float? = null, r: Float? = null, b: Float? = null) =
    FrameLayout.LayoutParams(w, h).apply {
        var g = 0
        if (l != null) { leftMargin = D.ui(l); g = g or Gravity.LEFT }
        if (r != null) { rightMargin = D.ui(r); g = g or Gravity.RIGHT }
        if (t != null) { topMargin = D.ui(t); g = g or Gravity.TOP }
        if (b != null) { bottomMargin = D.ui(b); g = g or Gravity.BOTTOM }
        gravity = g
    }

const val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT
const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
