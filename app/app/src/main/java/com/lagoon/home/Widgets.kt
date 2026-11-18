package com.lagoon.home

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import androidx.core.animation.doOnEnd
import androidx.dynamicanimation.animation.DynamicAnimation
import kotlin.math.abs
import kotlin.math.max

/** Shared focus behaviour: spring lift, rim, light sweep, raised shadow. */
abstract class FocusView(ctx: Context, protected val radius: Float, private val lift: Float) : View(ctx) {
    protected var focus = 0f
    protected var sheen = -1f
    private var focusAnim: ValueAnimator? = null
    private var sheenAnim: ValueAnimator? = null
    private val sheenP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenM = Matrix()
    private val ringP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
    protected val rect = RectF()
    var raise = true
    private val shadowP = Paint(Paint.FILTER_BITMAP_FLAG)

    /** Soft lift shadow from a cached pre-blurred bitmap: one textured quad, no per-frame shadow tessellation. */
    private fun shadowFor(w: Int, h: Int): Bitmap = shadowCache.getOrPut((w shl 16) or h) {
        val pad = D.u(26f).toInt()
        val b = Bitmap.createBitmap(w + pad * 2, h + pad * 2, Bitmap.Config.ALPHA_8)
        Canvas(b).drawRoundRect(RectF(pad.toFloat(), pad.toFloat(), (pad + w).toFloat(), (pad + h).toFloat()), radius, radius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; maskFilter = BlurMaskFilter(D.u(13f), BlurMaskFilter.Blur.NORMAL) })
        b
    }

    protected fun drawLiftShadow(c: Canvas) {
        if (!raise || focus < 0.02f || width == 0) return
        val b = shadowFor(width, height); val pad = D.u(26f).toInt()
        shadowP.alpha = (focus * 150).toInt()
        c.drawBitmap(b, -pad.toFloat(), -pad + D.u(9f) * focus, shadowP)
    }

    companion object { private val shadowCache = HashMap<Int, Bitmap>() }

    fun setFocusState(f: Boolean) {
        springScale(if (f) lift else 1f)
        focusAnim?.cancel()
        focusAnim = ValueAnimator.ofFloat(focus, if (f) 1f else 0f).apply {
            duration = if (f) 160 else 220; interpolator = Ease.out
            addUpdateListener { focus = it.animatedValue as Float; invalidate() }
            start()
        }
        if (f && Motion.on) {
            sheenAnim?.cancel()
            sheenAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 820; startDelay = 70; interpolator = Ease.inOut
                addUpdateListener { sheen = it.animatedValue as Float; invalidate() }
                doOnEnd { sheen = -1f; invalidate() }
                start()
            }
        }
    }

    /** Quick press-in, then back: physical feedback before an app opens. */
    fun press() {
        springScale(lift * 0.93f, 900f, 0.7f)
        postDelayed({ springScale(if (focus > 0.5f) lift else 1f) }, 110)
    }

    protected fun drawFocusLayers(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (sheen in 0f..1f) {
            if (sheenP.shader == null) sheenP.shader = LinearGradient(0f, 0f, w * 0.55f, h * 0.55f,
                intArrayOf(0, argbA(0.32f, 0xFFFFFF), 0), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            val x = -w * 0.8f + sheen * w * 2.2f
            sheenM.setTranslate(x, x * h / w); sheenP.shader.setLocalMatrix(sheenM)
            rect.set(0f, 0f, w, h); c.drawRoundRect(rect, radius, radius, sheenP)
        }
        if (focus > 0.01f) {
            ringP.strokeWidth = D.u(2.4f); ringP.alpha = (focus * 255).toInt()
            val r = ringP.strokeWidth / 2f
            rect.set(r, r, w - r, h - r); c.drawRoundRect(rect, radius - r, radius - r, ringP)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); sheenP.shader = null }
}

/** Square rail tile: an app icon (full bleed) or a glyph on glass. */
class Tile(ctx: Context, radius: Float) : FocusView(ctx, radius, 1.16f) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val m = Matrix()
    var glyph: Drawable? = null
    var bitmap: Bitmap? = null
        set(v) { field = v; p.shader = null; invalidate() }
    var bg = Color.BLACK

    init { Theme.listen { invalidate() } }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        drawLiftShadow(c)
        rect.set(0f, 0f, w, h)
        val b = bitmap
        if (b != null) {
            if (p.shader == null) {
                val sh = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                val s = max(w / b.width, h / b.height)
                m.setScale(s, s); m.postTranslate((w - b.width * s) / 2f, (h - b.height * s) / 2f); sh.setLocalMatrix(m)
                p.shader = sh
            }
            c.drawRoundRect(rect, radius, radius, p)
        } else {
            p.shader = null
            // White glyphs on a smoky plate in both themes (the dark-on-white day version was hard to read).
            p.color = if (glyph != null) mix(argbA(0.30f, 0x0B2230), argbA(0.18f, 0xFFFFFF), Theme.t) else bg
            c.drawRoundRect(rect, radius, radius, p)
            glyph?.let { g ->
                val ins = (w * 0.28f).toInt()
                g.setBounds(ins, ins, width - ins, height - ins); g.setTint(Color.WHITE); g.draw(c)
            }
        }
        drawFocusLayers(c)
    }
}

/** Shelf card: thumbnail, legibility gradient, badge/title/sub, optional progress. Drawn, not inflated. */
class Card(ctx: Context, radius: Float) : FocusView(ctx, radius, 1.08f) {
    private val img = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val m = Matrix()
    private val tBadge = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); letterSpacing = 0.09f }
    private val tTitle = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.create("sans-serif", Typeface.BOLD) }
    private val tSub = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = argbA(0.86f, 0xFFFFFF); typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
    var badge = ""; var title = ""
    var sub = ""
        set(v) { if (field != v) { field = v; invalidate() } }
    /** Progress line (0..1; -1 = none). One that appears later (a preview started) slides the text up to make room. */
    var progress = -1f
        set(v) {
            val was = field; field = v
            if (v >= 0f && was < 0f && isAttachedToWindow && Motion.on) animBar(1f)
            else if (v < 0f && was >= 0f) animBar(0f)
            else if (barAnim == null) barA = if (v >= 0f) 1f else 0f
            if (abs((v - was) * width) >= 0.5f || (v < 0f) != (was < 0f)) invalidate()
        }
    private var barA = 0f
    private var barAnim: ValueAnimator? = null
    private fun animBar(to: Float) {
        barAnim?.cancel()
        barAnim = ValueAnimator.ofFloat(barA, to).apply {
            duration = 420; interpolator = Ease.out
            addUpdateListener { barA = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() { override fun onAnimationEnd(a: android.animation.Animator) { if (barAnim === a) barAnim = null } })
            start()
        }
    }
    /** A preview of this card is playing: the progress line gets a playhead (and [edge] shows, for trailers). */
    var live = false
        set(v) {
            if (field == v) return
            field = v
            liveAnim?.cancel()
            liveAnim = ValueAnimator.ofFloat(liveA, if (v) 1f else 0f).apply {
                duration = if (v) 500 else 300; interpolator = Ease.out
                addUpdateListener { liveA = it.animatedValue as Float; invalidate() }
                start()
            }
        }
    private var liveA = 0f
    private var liveAnim: ValueAnimator? = null
    /** How far a trailer preview has played (0..1; -1 = none): a thin line along the card's bottom edge while it plays. */
    var edge = -1f
        set(v) { val was = field; field = v; if (abs((v - was) * width) >= 0.5f) invalidate() }
    var fallback = intArrayOf(0xFF0E7490.toInt(), 0xFF062A3A.toInt())
    var bitmap: Bitmap? = null
        set(v) { field = v; img.shader = null; invalidate() }

    /** A preview is on its way: a small ring turns in the top-right corner until it fades in behind Home. */
    var loading = false
        set(v) {
            if (field == v) return
            field = v
            spinAnim?.cancel()
            spinAnim = ValueAnimator.ofFloat(spinA, if (v) 1f else 0f).apply {
                duration = if (v) 280 else 240; interpolator = Ease.out
                addUpdateListener { spinA = it.animatedValue as Float; invalidate() }
                start()
            }
        }
    private var spinA = 0f
    private var spinAnim: ValueAnimator? = null
    private val ringP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val discP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val playP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val playPath = android.graphics.Path()
    private val arc = RectF()

    private fun drawSpinner(c: Canvas, w: Float) {
        val r = D.u(15f); val cx = w - D.u(11f) - r; val cy = D.u(11f) + r
        discP.color = argbA(0.46f * spinA, 0x05121A)
        c.drawCircle(cx, cy, r, discP)
        val rr = r - D.u(4.5f)
        ringP.strokeWidth = D.u(2f)
        ringP.color = argbA(0.22f * spinA, 0xFFFFFF)
        c.drawCircle(cx, cy, rr, ringP)
        // Material-style: the arc turns and breathes.
        val t = (android.os.SystemClock.uptimeMillis() % 1400L) / 1400f
        val sweep = 40f + 190f * (0.5f - 0.5f * kotlin.math.cos(t * 2f * Math.PI.toFloat()))
        val start = t * 360f * 2f - 90f
        ringP.color = argbA(0.95f * spinA, 0xFFFFFF)
        arc.set(cx - rr, cy - rr, cx + rr, cy + rr)
        c.drawArc(arc, start, sweep, false, ringP)
        // A small play mark in the middle: what's loading is a preview.
        val s = D.u(4.6f)
        playPath.reset(); playPath.moveTo(cx - s * 0.62f, cy - s); playPath.lineTo(cx + s * 0.95f, cy); playPath.lineTo(cx - s * 0.62f, cy + s); playPath.close()
        playP.color = argbA(0.92f * spinA, 0xFFFFFF)
        c.drawPath(playPath, playP)
        postInvalidateOnAnimation()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        drawLiftShadow(c)
        rect.set(0f, 0f, w, h)
        val b = bitmap
        if (img.shader == null) {
            img.shader = if (b != null) BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also {
                val s = max(w / b.width, h / b.height)
                m.setScale(s, s); m.postTranslate((w - b.width * s) / 2f, (h - b.height * s) / 2f); it.setLocalMatrix(m)
            } else LinearGradient(0f, 0f, w, h, fallback[0], fallback[1], Shader.TileMode.CLAMP)
        }
        c.drawRoundRect(rect, radius, radius, img)
        if (title.isEmpty() && badge.isEmpty()) { drawFocusLayers(c); return }
        if (shade.shader == null) shade.shader = LinearGradient(0f, h * 0.28f, 0f, h, 0, argbA(0.8f, 0x000000), Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, radius, radius, shade)
        val pad = D.u(13f); val avail = w - pad * 2
        var y = h - pad
        if (barA > 0.002f) {
            val bh = D.u(3.5f); val p = progress.coerceIn(0f, 1f)
            bar.color = argbA(0.32f * barA, 0xFFFFFF); rect.set(pad, y - bh, w - pad, y); c.drawRoundRect(rect, bh, bh, bar)
            bar.color = argbA(barA, 0xFFFFFF); rect.set(pad, y - bh, pad + avail * p, y); c.drawRoundRect(rect, bh, bh, bar)
            // Live: a playhead with a soft halo at the end of the line.
            if (liveA > 0.01f && edge < 0f) {
                val hx = pad + avail * p; val hy = y - bh / 2
                bar.color = argbA(0.22f * liveA * barA, 0xFFFFFF); c.drawCircle(hx, hy, D.u(8f) * liveA, bar)
                bar.color = argbA(liveA * barA, 0xFFFFFF); c.drawCircle(hx, hy, D.u(4.6f) * (0.6f + 0.4f * liveA), bar)
            }
            y -= (bh + D.u(8f)) * barA
        }
        tSub.textSize = D.u(12.5f); tTitle.textSize = D.u(16.5f); tBadge.textSize = D.u(10.5f)
        c.drawText(TextUtils.ellipsize(sub, tSub, avail, TextUtils.TruncateAt.END).toString(), pad, y - tSub.descent(), tSub)
        y -= tSub.textSize + D.u(3f)
        c.drawText(TextUtils.ellipsize(title, tTitle, avail, TextUtils.TruncateAt.END).toString(), pad, y - tTitle.descent(), tTitle)
        y -= tTitle.textSize + D.u(4f)
        tBadge.alpha = 225
        c.drawText(TextUtils.ellipsize(badge, tBadge, avail, TextUtils.TruncateAt.END).toString(), pad, y - tBadge.descent(), tBadge)
        if (spinA > 0.01f) drawSpinner(c, w)
        // A trailer playing: its timeline runs along the bottom of the artwork, just inside the focus rim.
        if (edge >= 0f && liveA > 0.01f) {
            val eh = D.u(2.5f); val x0 = radius; val x1 = w - radius; val y1 = h - D.u(4.5f)
            bar.color = argbA(0.30f * liveA, 0xFFFFFF); rect.set(x0, y1 - eh, x1, y1); c.drawRoundRect(rect, eh, eh, bar)
            bar.color = argbA(0.95f * liveA, 0xFFFFFF); rect.set(x0, y1 - eh, x0 + (x1 - x0) * edge.coerceIn(0f, 1f), y1); c.drawRoundRect(rect, eh, eh, bar)
        }
        drawFocusLayers(c)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); img.shader = null; shade.shader = null }
}

/** Rounded artwork that cross-dissolves when the track changes. */
class ArtView(ctx: Context, private val radius: Float) : View(ctx) {
    private val pNew = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val pOld = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val pBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var cur: Bitmap? = null
    private var old: Bitmap? = null
    private var t = 1f
    var placeholder: Drawable? = null
    /** Whisper style: a soft glow in the cover's own colour, as if the art lights the video behind it. */
    var glow = false
    private var glowNew: Bitmap? = null; private var glowOld: Bitmap? = null
    private val pGlowNew = Paint(Paint.FILTER_BITMAP_FLAG); private val pGlowOld = Paint(Paint.FILTER_BITMAP_FLAG)
    private val glowPad get() = D.u(22f)

    private fun glowFor(b: Bitmap, into: Paint): Bitmap? {
        if (width == 0) return null
        val px = Bitmap.createScaledBitmap(b, 1, 1, true).getPixel(0, 0)
        val hsv = FloatArray(3); Color.colorToHSV(px, hsv)
        hsv[1] = max(hsv[1], 0.55f); hsv[2] = max(hsv[2], 0.8f)
        into.color = Color.HSVToColor(hsv)
        val pad = glowPad; val g = Bitmap.createBitmap((width + pad * 2).toInt(), (height + pad * 2).toInt(), Bitmap.Config.ALPHA_8)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = BlurMaskFilter(D.u(13f), BlurMaskFilter.Blur.NORMAL) }
        Canvas(g).drawRoundRect(RectF(pad, pad, pad + width, pad + height), radius, radius, p)
        return g
    }

    fun set(b: Bitmap?) {
        if (b === cur) return
        if (glow) { glowOld = glowNew; pGlowOld.color = pGlowNew.color; glowNew = b?.let { glowFor(it, pGlowNew) } }
        old = cur; cur = b
        pOld.shader = pNew.shader; pNew.shader = b?.let { shaderFor(it) }
        if (!Motion.on) { t = 1f; invalidate(); return }
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 450; interpolator = Ease.inOut
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun shaderFor(b: Bitmap): Shader {
        val sh = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val w = width.toFloat().coerceAtLeast(1f); val h = height.toFloat().coerceAtLeast(1f)
        val s = max(w / b.width, h / b.height)
        sh.setLocalMatrix(Matrix().apply { setScale(s, s); postTranslate((w - b.width * s) / 2f, (h - b.height * s) / 2f) })
        return sh
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh); pNew.shader = cur?.let { shaderFor(it) }; pOld.shader = old?.let { shaderFor(it) }
    }

    override fun onDraw(c: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        if (cur == null && (old == null || t >= 1f)) {
            pBg.color = argbA(0.22f, 0xFFFFFF); c.drawRoundRect(rect, radius, radius, pBg)
            placeholder?.let { val i = (width * 0.3f).toInt(); it.setBounds(i, i, width - i, height - i); it.setTint(Theme.ink); it.draw(c) }
            return
        }
        if (glow) {
            val dy = D.u(6f)
            glowOld?.let { if (t < 1f) { pGlowOld.alpha = ((1f - t) * 140).toInt(); c.drawBitmap(it, -glowPad, -glowPad + dy, pGlowOld) } }
            glowNew?.let { pGlowNew.alpha = (t * 140).toInt(); c.drawBitmap(it, -glowPad, -glowPad + dy, pGlowNew) }
        }
        if (old != null && t < 1f) { pOld.alpha = 255; c.drawRoundRect(rect, radius, radius, pOld) }
        if (cur != null) { pNew.alpha = (t * 255).toInt(); c.drawRoundRect(rect, radius, radius, pNew) }
    }
}

/** Thin progress track whose fill eases to each new value instead of jumping. */
class Bar(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var shown = 0f
    private var anim: ValueAnimator? = null
    /** On the video (Whisper): white fill on a 30% white hairline, whatever the theme. */
    var onVideo = false
    var value = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            anim?.cancel()
            anim = ValueAnimator.ofFloat(shown, field).apply { duration = 900; interpolator = Ease.inOut; addUpdateListener { shown = it.animatedValue as Float; invalidate() }; start() }
        }

    init { Theme.listen { invalidate() } }

    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        p.color = if (onVideo) argbA(0.3f, 0xFFFFFF) else mix(argbA(0.16f, 0x0B2230), argbA(0.25f, 0xFFFFFF), Theme.t)
        rect.set(0f, 0f, width.toFloat(), h); c.drawRoundRect(rect, h, h, p)
        p.color = if (onVideo) Color.WHITE else Theme.ink
        rect.set(0f, 0f, width * shown, h); c.drawRoundRect(rect, h, h, p)
    }
}

/** A phone showing the remote (a screenshot of the web remote), drawn as a simple modern handset with a lift shadow. */
class PhoneFrame(ctx: Context) : View(ctx) {
    private val shot: Bitmap? = runCatching { android.graphics.BitmapFactory.decodeResource(ctx.resources, R.drawable.remote_preview) }.getOrNull()
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val screen = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val island = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = argbA(0.45f, 0x000000); maskFilter = BlurMaskFilter(D.u(18f), BlurMaskFilter.Blur.NORMAL) }
    private val r = RectF()

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }   // blur mask for the shadow

    override fun onDraw(c: Canvas) {
        val pad = D.u(14f)
        val w = width - pad * 2; val h = height - pad * 2
        val rad = w * 0.16f
        r.set(pad, pad + D.u(10f), pad + w, pad + h + D.u(6f)); c.drawRoundRect(r, rad, rad, shadow)
        r.set(pad, pad, pad + w, pad + h)
        body.shader = LinearGradient(0f, pad, 0f, pad + h, 0xFF2A2F36.toInt(), 0xFF0E1115.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, rad, rad, body)
        rim.strokeWidth = D.u(1.5f); rim.color = argbA(0.35f, 0xFFFFFF); c.drawRoundRect(r, rad, rad, rim)
        val inset = D.u(6.5f)
        r.inset(inset, inset)
        val b = shot
        if (b != null) {
            if (screen.shader == null) {
                val sh = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                val s = max(r.width() / b.width, r.height() / b.height)
                sh.setLocalMatrix(Matrix().apply { setScale(s, s); postTranslate(r.left + (r.width() - b.width * s) / 2f, r.top) })
                screen.shader = sh
            }
            c.drawRoundRect(r, rad - inset, rad - inset, screen)
        } else { screen.color = 0xFF06121C.toInt(); c.drawRoundRect(r, rad - inset, rad - inset, screen) }
        val iw = r.width() * 0.32f; val ih = D.u(9f); val top = r.top + D.u(7f)
        c.drawRoundRect(RectF(r.centerX() - iw / 2, top, r.centerX() + iw / 2, top + ih), ih / 2, ih / 2, island)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); screen.shader = null }
}
