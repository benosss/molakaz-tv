package com.lagoon.home

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * App icons as monochrome glass, so the bar reads as one family whatever the apps (the look of the dark "clear" icons
 * on recent iPhones): a dark glass tile with light on its top edge, and the app's mark in white fading to silver,
 * sitting on a soft shadow. The mark is found in the app's own icon: everything that stands out from the icon's
 * background (taken from its edges), so a red YouTube button on white becomes a white button with the play arrow cut
 * out. An icon that is a picture rather than a mark (a photo, a busy illustration) keeps its picture, in greys.
 *
 * Quality: the mark is read from the icon at 256 px or more, and the tile is drawn at twice its size and scaled down,
 * so edges stay smooth on a big screen.
 */
object GlassIcon {
    private const val A = 256   // analysis size

    /**
     * A glass tile for an app icon ([src] should be at least 256 px for clean edges). [tinted]: the glass takes the app's
     * own colour (white mark on red glass for YouTube), else it's dark glass for every app.
     */
    fun make(src: Bitmap, size: Int, tinted: Boolean = false): Bitmap {
        val a = if (src.width == A && src.height == A) src else Bitmap.createScaledBitmap(src, A, A, true)
        val px = IntArray(A * A); a.getPixels(px, 0, A, 0, 0, A, A)

        // The background: the median of a ring just inside the icon's edge (transparent if most of it is). Not the very
        // edge: many icons are a rounded tile with a rim on a white or clear square, and that rim isn't the app's mark.
        val ring = ArrayList<Int>()
        val inner = A * 9 / 100; val outer = A * 14 / 100
        for (y in 0 until A step 2) for (x in 0 until A step 2) {
            val e = minOf(x, y, A - 1 - x, A - 1 - y)
            if (e in inner..outer) ring += px[y * A + x]
        }
        val clearRing = ring.count { Color.alpha(it) < 60 } > ring.size / 2
        val bg = if (clearRing) 0 else medianColor(ring)
        val spread = if (clearRing) 0.0 else ring.filter { Color.alpha(it) >= 60 }.map { dist(it, bg) }.average()

        // The mark: how far each pixel stands from the background (or simply its opacity on a clear ring).
        val mark = FloatArray(A * A)
        var cover = 0f; var tr = 0f; var tg = 0f; var tb = 0f; var tw = 0f
        val edge = A * 8 / 100
        for (k in px.indices) {
            val x = k % A; val y = k / A
            if (minOf(x, y, A - 1 - x, A - 1 - y) < edge) continue      // frames and rims live out here
            val c = px[k]; val al = Color.alpha(c) / 255f
            // Faint shading (a gradient in a corner) stays out; anything that really stands out is in.
            val v = if (clearRing) al else ((dist(c, bg).toFloat() - 50f) / 70f).coerceIn(0f, 1f) * al
            mark[k] = v; cover += v
            if (v > 0.5f) { val w = v * (0.3f + saturation(c)); tr += Color.red(c) * w; tg += Color.green(c) * w; tb += Color.blue(c) * w; tw += w }
        }
        cover /= px.size
        // The app's colour: its mark's, or its background's when the mark is white (white letters on a blue tile).
        var tint = if (tw > 0f) Color.rgb((tr / tw).toInt(), (tg / tw).toInt(), (tb / tw).toInt()) else 0xFF3A3B3F.toInt()
        if (saturation(tint) < 0.2f && !clearRing && saturation(bg) > 0.25f) tint = bg
        val picture = spread > 30.0 || cover > 0.62f || cover < 0.015f
        return if (picture) pictureTile(src, size, if (tinted) null else 0f) else markTile(mark, A, size, tint = if (tinted && saturation(tint) > 0.15f) tint else null)
    }

    /** A glass tile for one of the app's own glyphs (Settings, All apps): the drawable's shape becomes the mark. */
    fun glyph(d: Drawable, size: Int): Bitmap {
        val n = A
        val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val ins = (n * 0.18f).toInt()
        d.setBounds(ins, ins, n - ins, n - ins); d.setTint(Color.WHITE); d.draw(Canvas(b))
        val px = IntArray(n * n); b.getPixels(px, 0, n, 0, 0, n, n)
        val mark = FloatArray(n * n) { Color.alpha(px[it]) / 255f }
        return markTile(mark, n, size, fill = 0.50f)
    }

    private fun markTile(mark: FloatArray, n: Int, size: Int, fill: Float = 0.62f, tint: Int? = null): Bitmap {
        val big = size * 2
        val out = Bitmap.createBitmap(big, big, Bitmap.Config.ARGB_8888); val c = Canvas(out)
        val s = big.toFloat(); val rr = s * 0.27f
        tile(c, s, rr, tint)

        // The mark, cut to its bounds and fitted into the middle of the tile.
        var x0 = n; var y0 = n; var x1 = -1; var y1 = -1
        for (y in 0 until n) for (x in 0 until n) if (mark[y * n + x] > 0.3f) { x0 = min(x0, x); y0 = min(y0, y); x1 = max(x1, x); y1 = max(y1, y) }
        if (x1 >= x0 && y1 >= y0) {
            val w = x1 - x0 + 1; val h = y1 - y0 + 1
            val alpha = ByteArray(w * h) { i -> (mark[(y0 + i / w) * n + (x0 + i % w)] * 255f).toInt().coerceIn(0, 255).toByte() }
            val m = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
            m.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(alpha))
            val box = s * fill; val k = min(box / w, box / h)
            val dw = w * k; val dh = h * k
            val dst = RectF((s - dw) / 2, (s - dh) / 2, (s + dw) / 2, (s + dh) / 2)
            val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            // A soft shadow under it, so the mark sits on the glass…
            p.color = argbA(0.55f, 0x000000); p.maskFilter = BlurMaskFilter(s * 0.03f, BlurMaskFilter.Blur.NORMAL)
            c.drawBitmap(m, null, RectF(dst).apply { offset(0f, s * 0.025f) }, p)
            p.maskFilter = null
            // …then the mark itself, white at the top fading to silver at the bottom.
            p.color = Color.WHITE
            p.shader = LinearGradient(0f, dst.top, 0f, dst.bottom, Color.WHITE, if (tint == null) 0xFFBCC1C7.toInt() else 0xFFE8EAED.toInt(), Shader.TileMode.CLAMP)
            c.drawBitmap(m, null, dst, p)
        }
        rim(c, s, rr)
        return Bitmap.createScaledBitmap(out, size, size, true)
    }

    /** A picture icon keeps its picture ([saturation] 0: in greys), under the same glass light so it still belongs to the set. */
    private fun pictureTile(src: Bitmap, size: Int, saturation: Float?): Bitmap {
        val big = size * 2
        val out = Bitmap.createBitmap(big, big, Bitmap.Config.ARGB_8888); val c = Canvas(out)
        val s = big.toFloat(); val rr = s * 0.27f
        c.drawRoundRect(RectF(0f, 0f, s, s), rr, rr, Paint(Paint.ANTI_ALIAS_FLAG))
        val grey = ColorMatrix().apply { setSaturation(saturation ?: 1f) }
        grey.postConcat(ColorMatrix(floatArrayOf(0.82f, 0f, 0f, 0f, 0f, 0f, 0.82f, 0f, 0f, 0f, 0f, 0f, 0.82f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
        c.drawBitmap(src, null, RectF(0f, 0f, s, s), Paint(Paint.FILTER_BITMAP_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN); colorFilter = ColorMatrixColorFilter(grey)
        })
        c.drawRoundRect(RectF(0f, 0f, s, s), rr, rr, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, 0f, s * 0.5f, argbA(0.18f, 0xFFFFFF), argbA(0f, 0xFFFFFF), Shader.TileMode.CLAMP)
        })
        rim(c, s, rr)
        return Bitmap.createScaledBitmap(out, size, size, true)
    }

    /**
     * Dark glass, or glass in the app's colour ([tint]): a little lighter at the top, deep at the bottom, slightly
     * see-through so the bar's glass glows behind.
     */
    private fun tile(c: Canvas, s: Float, rr: Float, tint: Int? = null) {
        val r = RectF(0f, 0f, s, s)
        val top = if (tint == null) argbA(0.80f, 0x3A3B3F) else argbA(0.78f, mix(tint, Color.WHITE, 0.12f) and 0xFFFFFF)
        val bottom = if (tint == null) argbA(0.90f, 0x111214) else argbA(0.88f, mix(tint, Color.BLACK, 0.38f) and 0xFFFFFF)
        c.drawRoundRect(r, rr, rr, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, 0f, s, top, bottom, Shader.TileMode.CLAMP)
        })
        // Light caught on the upper half.
        c.drawRoundRect(r, rr, rr, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, 0f, s * 0.48f, argbA(0.16f, 0xFFFFFF), argbA(0f, 0xFFFFFF), Shader.TileMode.CLAMP)
        })
    }

    /** A thin bright edge, strongest at the top, like light on the rim of glass, and a faint dark line under it. */
    private fun rim(c: Canvas, s: Float, rr: Float) {
        val sw = s * 0.018f
        val r = RectF(sw / 2, sw / 2, s - sw / 2, s - sw / 2)
        c.drawRoundRect(r, rr - sw / 2, rr - sw / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = sw
            shader = LinearGradient(0f, 0f, 0f, s, argbA(0.50f, 0xFFFFFF), argbA(0.06f, 0xFFFFFF), Shader.TileMode.CLAMP)
        })
    }

    private fun saturation(c: Int): Float { val hsv = FloatArray(3); Color.colorToHSV(c, hsv); return hsv[1] * min(1f, hsv[2] * 1.4f) }

    private fun dist(a: Int, b: Int): Double {
        val dr = Color.red(a) - Color.red(b); val dg = Color.green(a) - Color.green(b); val db = Color.blue(a) - Color.blue(b)
        return sqrt((dr * dr + dg * dg + db * db).toDouble())
    }

    private fun medianColor(list: List<Int>): Int {
        val ok = list.filter { Color.alpha(it) >= 60 }.ifEmpty { list }
        fun med(f: (Int) -> Int) = ok.map(f).sorted()[ok.size / 2]
        return Color.rgb(med(Color::red), med(Color::green), med(Color::blue))
    }
}
