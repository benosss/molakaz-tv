package com.lagoon.home

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.widget.FrameLayout
import kotlin.math.max

/**
 * Every visible glass panel publishes its on-screen rounded rect here once per UI frame.
 * The GL backdrop reads the snapshot and draws the blurred video only inside those rects,
 * so the frosting follows panels while they fade, scale or slide.
 */
object Glass {
    const val STRIDE = 7   // x, y, w, h, radius, alpha, shadow
    val panels = ArrayList<GlassPanel>()
    @Volatile var snapshot = FloatArray(0)
        private set
    var onChanged: (() -> Unit)? = null
    private val loc = IntArray(2)
    private var buf = FloatArray(16 * STRIDE)

    fun update() {
        if (buf.size < panels.size * STRIDE) buf = FloatArray(panels.size * STRIDE)
        var n = 0
        for (p in panels) {
            if (!p.isShown || p.width == 0) continue
            val a = p.effectiveAlpha() * p.surface
            if (a < 0.01f) continue
            p.getLocationOnScreen(loc)
            buf[n++] = loc[0].toFloat(); buf[n++] = loc[1].toFloat()
            buf[n++] = p.width * p.scaleX; buf[n++] = p.height * p.scaleY
            buf[n++] = p.radius * p.scaleX; buf[n++] = a; buf[n++] = p.shadow * a
        }
        val cur = snapshot
        if (cur.size == n) {
            var same = true
            for (i in 0 until n) if (cur[i] != buf[i]) { same = false; break }
            if (same) return
        }
        snapshot = buf.copyOf(n)
        onChanged?.invoke()
    }
}

/** Liquid-glass panel: the GL layer supplies the blur; this view adds tint, rim light and specular. */
open class GlassPanel(ctx: Context, var radius: Float, val shadow: Float = 0.28f) : FrameLayout(ctx) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spec = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
    private val rect = RectF()
    /** 0 = tint follows theme; >0 = brighter (selected pill). */
    var emphasis = 0f
        set(v) { field = v; rebuild(); invalidate() }
    private var focus = 0f
    private var focusAnim: ValueAnimator? = null
    /** How much glass to draw (tint, rim and the GL frost), separate from the children: 0 = text straight on the video. */
    var surface = 1f
        set(v) { field = v; invalidate() }
    private var surfaceAnim: ValueAnimator? = null
    fun surfaceTo(v: Float, ms: Long = 240) {
        surfaceAnim?.cancel()
        if (!Motion.on) { surface = v; return }
        surfaceAnim = ValueAnimator.ofFloat(surface, v).apply {
            duration = ms; interpolator = Ease.out; addUpdateListener { surface = it.animatedValue as Float }; start()
        }
    }

    init {
        setWillNotDraw(false)
        clipChildren = false
        Theme.listen { rebuild(); invalidate() }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); Glass.panels += this }
    override fun onDetachedFromWindow() { Glass.panels -= this; super.onDetachedFromWindow() }
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); rebuild() }

    fun effectiveAlpha(): Float {
        if (visibility != VISIBLE) return 0f
        var a = alpha
        var v: Any? = parent
        while (v is View) { if (v.visibility != VISIBLE) return 0f; a *= v.alpha; v = v.parent }
        return a
    }

    private fun rebuild() {
        if (width == 0 || height == 0) return
        val t = Theme.t
        val e = emphasis
        val a0 = argbA(lerp(0.22f, 0.15f, t) + e * 0.18f, 0xFFFFFF)
        val a1 = argbA(lerp(0.07f, 0.035f, t) + e * 0.14f, 0xFFFFFF)
        val a2 = argbA(lerp(0.12f, 0.08f, t) + e * 0.16f, 0xFFFFFF)
        val w = width.toFloat(); val h = height.toFloat()
        fill.shader = LinearGradient(0f, 0f, w, h, intArrayOf(a0, a1, a2), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        spec.shader = RadialGradient(w * 0.18f, 0f, max(w, h) * 0.75f, argbA(lerp(0.24f, 0.10f, t), 0xFFFFFF), 0, Shader.TileMode.CLAMP)
        rim.strokeWidth = max(1.5f, D.u(1f))
        rim.shader = LinearGradient(0f, 0f, 0f, h, argbA(lerp(0.85f, 0.32f, t), 0xFFFFFF), argbA(lerp(0.35f, 0.12f, t), 0xFFFFFF), Shader.TileMode.CLAMP)
    }

    /** Focus = slight lift + white rim, both springy and interruptible. */
    fun setFocusState(f: Boolean, lift: Float = 1.035f) {
        springScale(if (f) lift else 1f, Spring.FOCUS_K, 0.75f)
        focusAnim?.cancel()
        focusAnim = ValueAnimator.ofFloat(focus, if (f) 1f else 0f).apply {
            duration = if (f) 180 else 240; interpolator = Ease.out
            addUpdateListener { focus = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        rect.set(0f, 0f, w, h)
        if (surface > 0.004f) {
            val a = (surface * 255).toInt(); fill.alpha = a; spec.alpha = a; rim.alpha = a
            c.drawRoundRect(rect, radius, radius, fill)
            c.drawRoundRect(rect, radius, radius, spec)
            val hw = rim.strokeWidth / 2f
            rect.inset(hw, hw)
            c.drawRoundRect(rect, radius - hw, radius - hw, rim)
        }
        if (focus > 0.01f) {
            ring.strokeWidth = D.u(2.2f); ring.alpha = (focus * 235).toInt()
            val r = ring.strokeWidth / 2f
            rect.set(r, r, w - r, h - r)
            c.drawRoundRect(rect, radius - r, radius - r, ring)
        }
        super.onDraw(c)
    }
}
