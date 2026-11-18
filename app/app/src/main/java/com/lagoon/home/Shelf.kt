package com.lagoon.home

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.max
import kotlin.math.min

/** [subFor]: the card's second line for a position and duration in seconds (it follows a playing preview). */
class ShelfItem(val badge: String, val title: String, val sub: String, val progress: Float, val bitmap: Bitmap?,
                val fallback: IntArray, val preview: PreviewSrc? = null, val subFor: ((Float, Float) -> String)? = null,
                val action: (View) -> Unit)

/**
 * Tabs (glass pill slides between them) over a horizontally spring-scrolled card row.
 *
 * The view is bigger than what it shows by [PAD_X] on each side, [PAD_TOP] above and [PAD_BOTTOM] below: a focused
 * card grows 8% and casts a shadow past its slot, and whenever the shelf is drawn through a layer (a fade, a preview
 * caching Home) anything outside the view's own bounds is cut off. With the margin inside the view, nothing is.
 */
class Shelf(ctx: Context, private val provider: (Int) -> List<ShelfItem>, private val emptyText: (Int) -> String) : FrameLayout(ctx) {
    private val names = arrayOf("Continue watching", "Fresh picks")
    private val tabs: List<TextView> = names.map { ctx.label(17f, "sans-serif-medium", bold = true, shadow = true).apply { text = it } }
    val pill = GlassPanel(ctx, D.u(17f), shadow = 0.14f)
    private val row = FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false }
    private val empty = ctx.label(16f, shadow = true).apply { maxLines = 2; alpha = 0f }
    private var cards: List<Card> = emptyList()
    private var items: List<ShelfItem> = emptyList()
    var tab = 0
        private set
    var index = 0
        private set
    val count get() = cards.size
    /** Called with the row's items whenever it (re)loads. */
    var onItems: ((List<ShelfItem>) -> Unit)? = null
    private val cardW = D.ui(250f); private val cardH = D.ui(152f); private val gap = D.ui(18f)
    private val tabH = D.ui(34f); private val padX = D.u(18f); private val tabGap = D.u(6f)
    private val tabX = FloatArray(2); private val tabW = FloatArray(2)
    private val ix = D.ui(PAD_X); private val iy = D.ui(PAD_TOP)
    companion object { const val PAD_X = 40f; const val PAD_TOP = 12f; const val PAD_BOTTOM = 34f }
    private val pillW = FloatValueHolder(0f)
    private val pillWAnim = SpringAnimation(pillW).apply {
        spring = SpringForce().setStiffness(Spring.GLIDE_K).setDampingRatio(Spring.GLIDE_Z)
        addUpdateListener { _, v, _ -> pill.layoutParams.width = v.toInt(); pill.requestLayout() }
    }

    init {
        clipChildren = false; clipToPadding = false
        addView(pill, LayoutParams(0, tabH).apply { leftMargin = ix; topMargin = iy })
        var x = 0f
        tabs.forEachIndexed { i, t ->
            val w = t.paint.measureText(names[i]) + padX * 2
            tabX[i] = x; tabW[i] = w
            addView(t, LayoutParams(WRAP, tabH).apply { leftMargin = ix + (x + padX).toInt(); topMargin = iy })
            t.gravity = android.view.Gravity.CENTER_VERTICAL
            x += w + tabGap
        }
        addView(row, LayoutParams(MATCH, cardH).apply { leftMargin = ix; rightMargin = ix; topMargin = iy + tabH + D.ui(14f) })
        addView(empty, LayoutParams(MATCH, WRAP).apply { leftMargin = ix; rightMargin = ix; topMargin = iy + tabH + D.ui(14f) + cardH / 3 })
        pill.layoutParams.width = tabW[0].toInt(); pillW.value = tabW[0]
        Theme.listen { tint() }
        tint()
    }

    private fun tint() = tabs.forEachIndexed { i, t ->
        t.setTextColor(Color.WHITE)
        t.setShadowLayer(D.u(if (i == tab) 6f else 9f), 0f, D.u(1f), argbA(if (i == tab) 0.35f else 0.6f, 0x00142A))
    }

    fun reload(animate: Boolean) {
        items = provider(tab)
        cards.forEach { row.removeView(it) }
        cards = items.mapIndexed { i, it ->
            Card(context, D.u(16f)).apply {
                badge = it.badge; title = it.title; sub = it.sub; progress = it.progress; bitmap = it.bitmap; fallback = it.fallback
                row.addView(this, LayoutParams(cardW, cardH).apply { leftMargin = i * (cardW + gap) })
                if (animate && Motion.on) { alpha = 0f; translationX = D.u(28f); fadeIn(this, i) }
            }
        }
        empty.text = emptyText(tab)
        empty.fadeTo(if (items.isEmpty()) 1f else 0f, 0f, 300)
        index = index.coerceIn(0, max(0, cards.size - 1))
        row.translationX = 0f; rowTarget = 0f
        onItems?.invoke(items)
    }

    private fun fadeIn(v: View, i: Int) {
        v.animate().alpha(1f).setStartDelay(40L * i).setDuration(420).setInterpolator(Ease.out).withLayer().start()
        v.postDelayed({ v.springTo(DynamicAnimation.TRANSLATION_X, 0f, Spring.SOFT_K, Spring.SOFT_Z) }, 40L * i)
    }

    fun selectTab(i: Int) {
        if (i == tab) return
        val dir = if (i > tab) 1f else -1f
        tab = i; tint()
        pill.springTo(DynamicAnimation.TRANSLATION_X, tabX[i])
        pillWAnim.animateToFinalPosition(tabW[i])
        val out = cards
        out.forEach { c -> c.animate().alpha(0f).translationXBy(-dir * D.u(30f)).setDuration(160).setInterpolator(Ease.exit).withLayer().start() }
        postDelayed({ out.forEach { row.removeView(it) }; cards = emptyList(); reloadKeepTab(dir) }, 150)
    }

    private fun reloadKeepTab(dir: Float) {
        items = provider(tab)
        index = 0
        cards = items.mapIndexed { i, it ->
            Card(context, D.u(16f)).apply {
                badge = it.badge; title = it.title; sub = it.sub; progress = it.progress; bitmap = it.bitmap; fallback = it.fallback
                row.addView(this, LayoutParams(cardW, cardH).apply { leftMargin = i * (cardW + gap) })
                alpha = 0f; translationX = dir * D.u(30f); fadeIn(this, i)
            }
        }
        row.translationX = 0f; rowTarget = 0f
        empty.text = emptyText(tab)
        empty.fadeTo(if (items.isEmpty()) 1f else 0f, 0f, 300)
        onItems?.invoke(items)
    }

    /**
     * A preview of card [i] is at [t] of [dur] seconds. A YouTube card's own progress line follows it (it's the same
     * video, and opening the card carries on from there); a trailer gets the thin line along the card's edge.
     */
    fun previewAt(i: Int, t: Float, dur: Float) {
        val c = cards.getOrNull(i) ?: return; val it = items.getOrNull(i) ?: return
        val f = (t / dur).coerceIn(0f, 1f)
        if (it.preview?.trailerOf != null || it.preview?.track != null) c.edge = f
        else { c.progress = f; it.subFor?.let { s -> c.sub = s(t, dur) } }
    }

    /** Card [i]'s preview is showing (the progress line gets its playhead), and no other card's. */
    fun previewLive(i: Int, on: Boolean) = cards.forEachIndexed { j, c -> c.live = on && j == i }

    /** The loading ring on card [i] (and on no other). */
    fun setLoading(i: Int, on: Boolean) = cards.forEachIndexed { j, c -> c.loading = on && j == i }

    // Cards scrolled past the start of the row fade out before they reach the rail, instead of sliding over it.
    private var rowTarget = 0f
    private val fadeP = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    override fun dispatchDraw(c: android.graphics.Canvas) {
        if (rowTarget > -1f && row.translationX > -1f) { super.dispatchDraw(c); return }
        val ext = D.u(70f); val left = ix - D.u(150f)
        val sc = c.saveLayer(left, -ext, width + ext, height + ext, null)
        super.dispatchDraw(c)
        // From the rail's right edge (32 dp left of the row) to 12 dp before the first card's slot: a focused card there
        // reaches ~10 dp past its slot (8% lift), and its rim must stay whole.
        val x0 = ix - D.u(32f); val x1 = ix - D.u(12f)
        if (fadeP.shader == null) fadeP.shader = LinearGradient(x0, 0f, x1, 0f, Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(left, -ext, x1, height + ext, fadeP)
        c.restoreToCount(sc)
    }

    fun focusTabs(on: Boolean) = pill.setFocusState(on)

    fun focusCard(i: Int, on: Boolean) {
        if (i !in cards.indices) return
        index = i
        cards[i].setFocusState(on)
        if (on) {
            val left = i * (cardW + gap).toFloat()
            val view = row.width.toFloat()
            var tx = row.translationX
            if (left + tx < 0f) tx = -left
            if (left + cardW + tx > view) tx = view - left - cardW
            rowTarget = min(0f, tx)
            row.springTo(DynamicAnimation.TRANSLATION_X, rowTarget)
            invalidate()
        }
    }

    fun itemAt(i: Int): ShelfItem? = items.getOrNull(i)

    fun activate(i: Int) { if (i in cards.indices) { cards[i].press(); val c = cards[i]; postDelayed({ items[i].action(c) }, 90) } }
}

/** Every launchable app as a banner grid on full-screen glass. */
class AllApps(ctx: Context, private val launch: (String, View) -> Unit) : GlassPanel(ctx, 0f, 0f) {
    private val title = ctx.label(30f, "sans-serif-medium", bold = true).apply { text = "All apps" }
    private val grid = FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false }
    private var tiles: List<Card> = emptyList()
    private var pkgs: List<String> = emptyList()
    private var index = 0
    private val cols = 5
    private val tw = D.ui(196f); private val th = D.ui(110f); private val gx = D.ui(26f); private val gy = D.ui(30f)
    val isOpen get() = visibility == VISIBLE && alpha > 0f

    init {
        visibility = GONE
        val totalW = cols * tw + (cols - 1) * gx
        addView(title, LayoutParams(WRAP, WRAP).apply { leftMargin = (D.ui(1280f) - totalW) / 2; topMargin = D.ui(70f) })
        addView(grid, LayoutParams(totalW, MATCH).apply { leftMargin = (D.ui(1280f) - totalW) / 2; topMargin = D.ui(140f) })
        Theme.listen { title.setTextColor(Theme.ink) }
        title.setTextColor(Theme.ink)
    }

    private fun load() {
        val pm = context.packageManager
        val seen = LinkedHashMap<String, android.content.pm.ResolveInfo>()
        for (cat in arrayOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER))
            for (ri in pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0))
                if (ri.activityInfo.packageName != context.packageName && !Apps.hidden(ri.activityInfo.packageName)) seen.putIfAbsent(ri.activityInfo.packageName, ri)
        val apps = seen.values.sortedBy { it.loadLabel(pm).toString().lowercase() }
        grid.removeAllViews()
        pkgs = apps.map { it.activityInfo.packageName }
        tiles = apps.mapIndexed { i, ri ->
            val banner = ri.activityInfo.loadBanner(pm) ?: ri.activityInfo.applicationInfo.loadBanner(pm)
            Card(context, D.u(14f)).apply {
                if (banner != null) bitmap = render(banner, tw, th, null)
                else { bitmap = render(ri.loadIcon(pm), tw, th, intArrayOf(0xFF1E3A4C.toInt(), 0xFF0C1B26.toInt())); title = ri.loadLabel(pm).toString() }
                grid.addView(this, LayoutParams(tw, th).apply { leftMargin = (i % cols) * (tw + gx); topMargin = (i / cols) * (th + gy) })
            }
        }
    }

    private fun render(d: Drawable, w: Int, h: Int, bg: IntArray?): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        if (bg != null) {
            c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), bg[0], bg[1], Shader.TileMode.CLAMP) })
            val s = (h * 0.46f).toInt(); d.setBounds((w - s) / 2, (h - s) / 2 - h / 10, (w + s) / 2, (h + s) / 2 - h / 10)
        } else d.setBounds(0, 0, w, h)
        d.draw(c); return b
    }

    fun open() {
        load(); index = 0
        visibility = VISIBLE; alpha = 0f
        animate().alpha(1f).setDuration(320).setInterpolator(Ease.out).start()
        grid.scaleX = 0.94f; grid.scaleY = 0.94f; grid.pivotY = 0f
        grid.springScale(1f, Spring.SOFT_K, Spring.SOFT_Z)
        tiles.forEachIndexed { i, t -> t.alpha = 0f; t.animate().alpha(1f).setStartDelay(18L * i).setDuration(360).setInterpolator(Ease.out).start() }
        postDelayed({ tiles.getOrNull(0)?.setFocusState(true) }, 120)
    }

    fun close(done: () -> Unit) {
        grid.springScale(0.97f, Spring.SOFT_K, Spring.SOFT_Z)
        animate().alpha(0f).setDuration(220).setInterpolator(Ease.exit).withEndAction { visibility = GONE; tiles.forEach { it.setFocusState(false) }; done() }.start()
    }

    fun key(code: Int): Boolean {
        val n = tiles.size; if (n == 0) return true
        val next = when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (index % cols > 0) index - 1 else index
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (index % cols < cols - 1 && index + 1 < n) index + 1 else index
            KeyEvent.KEYCODE_DPAD_UP -> if (index - cols >= 0) index - cols else index
            KeyEvent.KEYCODE_DPAD_DOWN -> if (index + cols < n) index + cols else index
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                val t = tiles[index]; t.press(); postDelayed({ launch(pkgs[index], t) }, 90); return true
            }
            else -> return false
        }
        if (next != index) {
            tiles[index].setFocusState(false); index = next; tiles[index].setFocusState(true)
            val rowTop = (index / cols) * (th + gy).toFloat()
            val visible = height - D.u(180f)
            grid.springTo(DynamicAnimation.TRANSLATION_Y, if (rowTop + th > visible) visible - rowTop - th else 0f)
        }
        return true
    }
}
