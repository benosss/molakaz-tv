package com.lagoon.home

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation

/** Settings text is white in both themes (dark text on the pale day glass was hard to read). */
private val INK = Color.WHITE
private val INK2 = argbA(0.80f, 0xFFFFFF)

/** A soft drop shadow on every text in a subtree, so white stays legible over bright scenes. */
private fun soften(v: View) {
    if (v is TextView) v.setShadowLayer(D.u(6f), 0f, D.u(1f), argbA(0.45f, 0x000000))
    if (v is android.view.ViewGroup) for (i in 0 until v.childCount) soften(v.getChildAt(i))
}

/** A focusable settings row: soft glass plate that brightens, lifts and gains a rim when focused. */
class SRow(ctx: Context, private val r: Float = D.u(18f)) : FrameLayout(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
    private val rect = RectF()
    private var f = 0f
    var lifted = false
        set(v) { field = v; springScale(if (v) 1.045f else if (isFocused) 1.02f else 1f); invalidate() }
    /** Solid colour instead of glass (e.g. a destructive action); brightens when focused. */
    var fill: Int? = null
        set(v) { field = v; invalidate() }

    init {
        isFocusable = true; isClickable = true; setWillNotDraw(false); clipChildren = false
        setOnFocusChangeListener { _, has ->
            springScale(if (has) (if (lifted) 1.045f else 1.02f) else 1f)
            ValueAnimator.ofFloat(f, if (has) 1f else 0f).apply { duration = 180; interpolator = Ease.out; addUpdateListener { f = it.animatedValue as Float; invalidate() }; start() }
        }
        Theme.listen { invalidate() }
    }

    override fun onDraw(c: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        val base = lerp(0.14f, 0.10f, Theme.t); val hi = lerp(0.28f, 0.24f, Theme.t)
        p.color = fill?.let { mix(argbA(0.78f, it and 0xFFFFFF), it, f) } ?: argbA(lerp(base, hi, f) + if (lifted) 0.12f else 0f, 0xFFFFFF)
        c.drawRoundRect(rect, r, r, p)
        if (f > 0.01f) { ring.strokeWidth = D.u(2f); ring.alpha = (f * 230).toInt(); val h = ring.strokeWidth / 2; rect.inset(h, h); c.drawRoundRect(rect, r - h, r - h, ring) }
        super.onDraw(c)
    }
}

/** Home settings: quick-access apps (reorder, add, remove), location, and a door to Android settings. */
class HomeSettings(ctx: Context, private val onRail: () -> Unit, private val onPlace: () -> Unit,
                   private val onPreviews: () -> Unit) : GlassPanel(ctx, 0f, 0f) {
    private enum class Screen { MAIN, RAIL, ADD, PLACE, REMOTE, SAVER, ABOUT, GOODNIGHT }
    private var screen = Screen.MAIN
    private val title = ctx.label(30f, "sans-serif-medium", bold = true)
    private val hint = ctx.label(16f).apply { maxLines = 2 }
    private val body = FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false }
    /** Clips the list below the title when it scrolls; the margin leaves room for the focus lift and rim. */
    private val viewport = FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false }
    private val vpPad = D.ui(22f)
    private val rowH = D.ui(76f); private val gap = D.ui(12f); private val step = rowH + gap
    private val colW = D.ui(700f)
    val isOpen get() = visibility == VISIBLE

    // rail editing state
    private var rail = mutableListOf<String>()
    private val railRows = ArrayList<SRow>()
    private var moving = -1

    init {
        visibility = GONE
        clipChildren = true   // a child is clipped to its own bounds: this is what keeps the list inside the viewport
        setTag(R.id.clip_edge, true)
        val left = D.ui(140f)
        addView(title, LayoutParams(WRAP, WRAP).apply { leftMargin = left; topMargin = D.ui(64f) })
        addView(hint, LayoutParams(colW, WRAP).apply { leftMargin = left; topMargin = D.ui(110f) })
        addView(viewport, LayoutParams(colW + vpPad * 2, MATCH).apply { leftMargin = left - vpPad; topMargin = D.ui(160f) - vpPad; bottomMargin = D.ui(12f) })
        viewport.addView(body, LayoutParams(colW, MATCH).apply { leftMargin = vpPad; topMargin = vpPad })
        Theme.listen { tint() }; tint()
        // More rows than fit: slide the column so the focused row stays on screen.
        body.viewTreeObserver.addOnGlobalFocusChangeListener { _, now -> if (now != null && isOpen) keepVisible(now) }
    }

    private fun keepVisible(v: View) {
        var top = 0; var x: View? = v
        while (x != null && x !== body) { top += x.top; x = x.parent as? View }
        if (x !== body) return
        // Before the first layout the viewport has no height yet: use the screen's.
        val vh = if (viewport.height > 0) viewport.height else resources.displayMetrics.heightPixels - D.ui(160f) - D.ui(12f) + vpPad
        val visible = vh - vpPad * 2
        val cur = -body.translationY
        val target = when { top - cur < 0 -> top.toFloat(); top + v.height - cur > visible -> (top + v.height - visible).toFloat(); else -> cur }
        if (target != cur) body.springTo(DynamicAnimation.TRANSLATION_Y, -target.coerceAtLeast(0f))
    }

    private fun tint() { title.setTextColor(INK); hint.setTextColor(INK2); soften(title); soften(hint); invalidate() }

    private val veil = Paint()
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        // Darken the frosted glass a little (more by day, when it is pale) so the white text reads.
        veil.color = argbA(lerp(0.30f, 0.14f, Theme.t), 0x000000)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), veil)
    }

    fun open(page: String? = null) {
        visibility = VISIBLE; alpha = 0f
        animate().alpha(1f).setDuration(300).setInterpolator(Ease.out).start()
        // A page by name (diagnostics and screenshots), else the main grid.
        show(Screen.values().firstOrNull { it.name.equals(page, true) } ?: Screen.MAIN)
    }

    fun close(done: () -> Unit) {
        animate().alpha(0f).setDuration(220).setInterpolator(Ease.exit).withEndAction { visibility = GONE; body.removeAllViews(); done() }.start()
    }

    /** Returns true when the key was consumed (move mode, back navigation). Otherwise Android focus handles it. */
    fun key(e: KeyEvent, closeAll: () -> Unit): Boolean {
        val code = e.keyCode
        if (moving >= 0) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> { if (moving > 0) swap(moving, moving - 1) else bump(); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { if (moving < rail.size - 1) swap(moving, moving + 1) else bump(); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK -> { drop(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> return true
            }
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            when (screen) { Screen.MAIN -> closeAll(); Screen.ADD -> show(Screen.RAIL); else -> show(Screen.MAIN) }
            return true
        }
        return false
    }

    private fun show(s: Screen) {
        screen = s; moving = -1
        body.removeAllViews(); railRows.clear()
        // The credits page uses the full width of the screen; everything else is one 700-wide column.
        (body.layoutParams as LayoutParams).let { lp -> val w = when (s) { Screen.ABOUT -> D.ui(1020f); Screen.MAIN, Screen.REMOTE -> D.ui(1000f); else -> colW }; if (lp.width != w) { lp.width = w; body.layoutParams = lp
            (viewport.layoutParams as LayoutParams).let { v -> v.width = w + vpPad * 2; viewport.layoutParams = v } } }
        body.alpha = 0f; body.translationX = D.u(24f); body.springSet(DynamicAnimation.TRANSLATION_Y, 0f)
        body.animate().alpha(1f).setDuration(320).setInterpolator(Ease.out).start()
        body.springTo(DynamicAnimation.TRANSLATION_X, 0f, Spring.SOFT_K, Spring.SOFT_Z)
        when (s) {
            Screen.MAIN -> main()
            Screen.RAIL -> railScreen()
            Screen.ADD -> addScreen()
            Screen.PLACE -> placeScreen()
            Screen.REMOTE -> remoteScreen()
            Screen.SAVER -> saverScreen()
            Screen.ABOUT -> aboutScreen()
            Screen.GOODNIGHT -> goodnightScreen()
        }
        soften(body)
    }

    private fun row(i: Int, top: String, sub: String?, icon: android.graphics.drawable.Drawable? = null, click: () -> Unit): SRow {
        val r = SRow(context)
        val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(D.ui(22f), 0, D.ui(22f), 0) }
        if (icon != null) line.addView(ImageView(context).apply { setImageDrawable(icon) }, LinearLayout.LayoutParams(D.ui(44f), D.ui(44f)).apply { rightMargin = D.ui(16f) })
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        col.addView(context.label(19f, "sans-serif-medium", bold = true, color = INK).apply { text = top })
        if (sub != null) col.addView(context.label(15f, color = INK2).apply { text = sub }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = D.ui(3f) })
        line.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        r.addView(line, LayoutParams(MATCH, MATCH))
        r.setOnClickListener { click() }
        body.addView(r, LayoutParams(MATCH, rowH).apply { topMargin = i * step })
        return r
    }

    /** Main page: nine tiles in a 3 x 3 grid, so everything fits on one screen with no scrolling. */
    private fun tile(i: Int, top: String, sub: String, icon: android.graphics.drawable.Drawable, click: () -> Unit): Pair<SRow, android.widget.TextView> {
        val gap = D.ui(16f); val w = (D.ui(1000f) - gap * 2) / 3; val h = D.ui(146f)
        val r = SRow(context, D.u(22f))
        r.addView(ImageView(context).apply { setImageDrawable(icon) }, LayoutParams(D.ui(44f), D.ui(44f)).apply { leftMargin = D.ui(22f); topMargin = D.ui(22f) })
        r.addView(context.label(19f, "sans-serif-medium", bold = true, color = INK).apply { text = top }, LayoutParams(w - D.ui(44f), WRAP).apply { leftMargin = D.ui(22f); topMargin = D.ui(80f) })
        val subV = context.label(14.5f, color = INK2).apply { text = sub; maxLines = 1 }
        r.addView(subV, LayoutParams(w - D.ui(44f), WRAP).apply { leftMargin = D.ui(22f); topMargin = D.ui(108f) })
        r.setOnClickListener { click() }
        body.addView(r, LayoutParams(w, h).apply { leftMargin = (i % 3) * (w + gap); topMargin = (i / 3) * (h + gap) })
        return r to subV
    }

    private fun main() {
        title.text = "Settings"; hint.text = ""
        val p = Prefs.place
        tile(0, "Quick access apps", "${Prefs.rail.size} apps on the left", chip(R.drawable.ic_grid, 0xFF2F80ED.toInt())) { show(Screen.RAIL) }.first.requestFocus()
        val live = LiveTv.find(context)
        tile(1, "Live TV", liveSub(live), chip(R.drawable.ic_play, 0xFFE5484D.toInt())) {
            val pkg = LiveTv.find(context) ?: return@tile
            if (pkg !in Prefs.rail) { Prefs.rail = listOf(pkg) + Prefs.rail.filter { it != pkg }; onRail() } else LiveTv.open(context)
            show(Screen.MAIN)
        }
        tile(2, "Goodnight", goodnightShort(), chip(R.drawable.ic_moon, 0xFF5856D6.toInt())) { show(Screen.GOODNIGHT) }
        tile(3, "Location", "${p.name}, ${p.country}", chip(R.drawable.ic_pin, 0xFF30B47C.toInt())) { show(Screen.PLACE) }
        tile(4, "Phone remote", Remote.url()?.removePrefix("http://") ?: "Connect to Wi-Fi first", chip(R.drawable.ic_phone, 0xFF7B61FF.toInt())) { show(Screen.REMOTE) }
        tile(5, "Background & sound", "Saver " + saverLabel(saverMs()).replace("Never", "off").replace(" minutes", " min").replace(" minute", " min") +
            " · previews " + (if (Prefs.previews) "on" else "off"), chip(R.drawable.ic_cloud, 0xFF0A84FF.toInt())) { show(Screen.SAVER) }
        if (OriginalHome.exists(context)) {
            fun sub() = if (OriginalHome.isOff(context)) "Off · select to bring it back" else "On · select to switch it off"
            lateinit var t: Pair<SRow, android.widget.TextView>
            t = tile(6, "Original home screen", sub(), chip(R.drawable.ic_grid, 0xFF636366.toInt())) {
                val off = !OriginalHome.isOff(context)
                t.second.text = if (off) "Switching it off…" else "Bringing it back…"
                OriginalHome.set(context, off) { ok -> t.second.text = if (ok) sub() else "Allow USB debugging on the TV, then retry" }
            }
        }
        tile(7, "Android settings", "Wi-Fi, display, sound", chip(R.drawable.ic_gear, 0xFF8E8E93.toInt())) {
            context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        tile(8, "About & credits", "Films from Pexels", chip(R.drawable.ic_info, 0xFF05A081.toInt())) { show(Screen.ABOUT) }
    }

    private fun liveSub(pkg: String?) = when {
        pkg == null -> "No my.t TV app on this box"
        pkg in Prefs.rail -> "${LiveTv.label(context, pkg)} · on the TV button"
        else -> "${LiveTv.label(context, pkg)} · select to add"
    }

    // ---------- goodnight ----------

    private fun clockText(minuteOfDay: Int) = android.text.format.DateFormat.getTimeFormat(context).format(
        java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, minuteOfDay / 60); set(java.util.Calendar.MINUTE, minuteOfDay % 60) }.time)

    private fun goodnightShort() = when {
        Goodnight.active -> "Sleeps at ${android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(Goodnight.endsAt))}" +
            if (Goodnight.daily >= 0) " · every night" else ""
        else -> "Off"
    }

    private val goodnightOptions = listOf(15 to "15 min", 30 to "30 min", 45 to "45 min", 60 to "1 hour", 90 to "1 h 30", 120 to "2 hours")
    private var bedtime = -1

    /** In some minutes (a row of six), or at a bedtime (left and right change it by 15 min), once or every night. */
    private fun goodnightScreen() {
        title.text = "Goodnight"
        hint.text = if (Goodnight.active) "On: the TV goes to sleep at ${android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(Goodnight.endsAt))}" +
            (if (Goodnight.daily >= 0) ", and every night at that time." else ".") else
            "In the last minute the sound fades and the screen dims, over any app. Then playback pauses and the TV goes to standby."
        if (bedtime < 0) bedtime = Goodnight.daily.takeIf { it >= 0 } ?: (23 * 60)
        val gap = D.ui(12f); val w = (colW - gap * 2) / 3
        var first: View? = null
        goodnightOptions.forEachIndexed { i, (min, label) ->
            val r = SRow(context)
            r.addView(context.label(19f, "sans-serif-medium", bold = true, color = INK).apply { text = label; gravity = Gravity.CENTER }, LayoutParams(MATCH, MATCH))
            r.setOnClickListener { Prefs.goodnightDaily = -1; Goodnight.set(min); show(Screen.GOODNIGHT); hint.text = "Goodnight in $label. Change it here or from the phone remote." }
            body.addView(r, LayoutParams(w, rowH).apply { leftMargin = (i % 3) * (w + gap); topMargin = (i / 3) * step })
            if (first == null) first = r
        }
        // Bedtime: left and right move it by a quarter of an hour; OK sets it for tonight.
        val bed = SRow(context)
        val bedLine = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(D.ui(22f), 0, D.ui(22f), 0) }
        val bedCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        bedCol.addView(context.label(19f, "sans-serif-medium", bold = true, color = INK).apply { text = "At a set time" })
        bedCol.addView(context.label(15f, color = INK2).apply { text = "Left and right to change, OK to set" }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = D.ui(3f) })
        bedLine.addView(bedCol, LinearLayout.LayoutParams(0, WRAP, 1f))
        val timeV = context.label(26f, "sans-serif-medium", color = INK).apply { text = "‹  ${clockText(bedtime)}  ›" }
        bedLine.addView(timeV)
        bed.addView(bedLine, LayoutParams(MATCH, MATCH))
        bed.setOnKeyListener { _, code, ev ->
            if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val d = when (code) { KeyEvent.KEYCODE_DPAD_LEFT -> -15; KeyEvent.KEYCODE_DPAD_RIGHT -> 15; else -> 0 }
            if (d == 0) return@setOnKeyListener false
            bedtime = ((bedtime + d) % 1440 + 1440) % 1440; timeV.text = "‹  ${clockText(bedtime)}  ›"; true
        }
        bed.setOnClickListener { Goodnight.setAt(bedtime, Goodnight.daily >= 0); show(Screen.GOODNIGHT) }
        body.addView(bed, LayoutParams(MATCH, rowH).apply { topMargin = 2 * step })
        // Every night: the same bedtime comes back each day until it's turned off.
        val nightly = Goodnight.daily >= 0
        val every = SRow(context)
        val evLine = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(D.ui(22f), 0, D.ui(22f), 0) }
        evLine.addView(context.label(19f, "sans-serif-medium", bold = true, color = INK).apply { text = "Every night" }, LinearLayout.LayoutParams(0, WRAP, 1f))
        evLine.addView(context.label(17f, "sans-serif-medium", color = if (nightly) 0xFF30D158.toInt() else INK2).apply { text = if (nightly) "On · ${clockText(Goodnight.daily)}" else "Off" })
        every.addView(evLine, LayoutParams(MATCH, MATCH))
        every.setOnClickListener { if (nightly) { Prefs.goodnightDaily = -1; if (!Goodnight.active) Goodnight.cancel() } else Goodnight.setAt(bedtime, true); show(Screen.GOODNIGHT) }
        body.addView(every, LayoutParams(MATCH, rowH).apply { topMargin = 3 * step })
        if (Goodnight.active || nightly) {
            val off = SRow(context).apply { fill = 0xFFE5484D.toInt() }
            off.addView(context.label(19f, "sans-serif-medium", bold = true, color = Color.WHITE).apply { text = "Turn off"; gravity = Gravity.CENTER }, LayoutParams(MATCH, MATCH))
            off.setOnClickListener { Goodnight.off(); show(Screen.GOODNIGHT) }
            body.addView(off, LayoutParams(MATCH, rowH).apply { topMargin = 4 * step })
        }
        first?.requestFocus()
    }

    // ---------- about ----------

    /** Credits for the films (grouped by creator) and the data the home screen uses. */
    private fun aboutScreen() {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
        title.text = "About & credits"
        hint.text = "MoLakaz TV $version, made in Mauritius. Open source (MIT): github.com/benosss/molakaz-tv. The nature films are from Pexels and free to use: thank you to the people who shot them."
        val credits = Scenes.credits()
        val card = SRow(context, D.u(22f))
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(D.ui(28f), D.ui(22f), D.ui(28f), D.ui(20f)) }
        val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(context.label(13f, "sans-serif-medium", bold = true, color = INK2).apply { text = "NATURE FILMS FROM PEXELS"; letterSpacing = 0.14f }, LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(context.label(14f, "sans-serif-medium", color = INK2).apply { text = "pexels.com" })
        col.addView(head)
        if (credits.isEmpty()) col.addView(context.label(16f, color = INK).apply { text = "Videos by the Pexels community · pexels.com" }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = D.ui(14f) })
        // Three columns, filled top to bottom.
        val cols = 3; val rows = (credits.size + cols - 1) / cols
        val grid = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        for (c in 0 until cols) {
            val g = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            for (r in 0 until rows) {
                val (by, places) = credits.getOrNull(c * rows + r) ?: continue
                g.addView(context.label(17f, "sans-serif-medium", bold = true, color = INK).apply { text = by; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
                    LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = if (r == 0) 0 else D.ui(14f) })
                g.addView(context.label(13.5f, color = INK2).apply { text = places.joinToString(" · "); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END },
                    LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = D.ui(2f) })
            }
            grid.addView(g, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (c > 0) leftMargin = D.ui(26f) })
        }
        col.addView(grid, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = D.ui(16f) })
        col.addView(View(context).apply { setBackgroundColor(argbA(0.16f, 0xFFFFFF)) }, LinearLayout.LayoutParams(MATCH, D.ui(1f)).apply { topMargin = D.ui(18f) })
        col.addView(context.label(13.5f, color = INK2).apply {
            text = "Weather by Open-Meteo  ·  Fresh picks from Netflix Top 10, YouTube Charts and Spotify  ·  Location by ipwho.is  ·  Playback by AndroidX Media3\n" +
                "Ambient sound from Wikimedia Commons: waves by J.D. Savanyu (CC0), rain by kvgarlic (CC0), dawn chorus by Robert EA Harvey (CC BY-SA 4.0)"; maxLines = 3; ellipsize = null
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = D.ui(12f) })
        card.addView(col, LayoutParams(MATCH, WRAP))
        body.addView(card, LayoutParams(MATCH, WRAP))
        card.requestFocus()
    }

    /** A white glyph on a rounded colour tile, like the icons in iOS Settings. */
    private fun chip(res: Int, color: Int): android.graphics.drawable.Drawable {
        val bg = android.graphics.drawable.GradientDrawable().apply {
            setColor(color); cornerRadius = D.u(11f)
            colors = intArrayOf(mix(color, 0xFFFFFFFF.toInt(), 0.12f), color); orientation = android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM
        }
        val glyph = context.getDrawable(res)!!.mutate().apply { setTint(Color.WHITE) }
        val inset = D.ui(10f)
        return android.graphics.drawable.LayerDrawable(arrayOf(bg, android.graphics.drawable.InsetDrawable(glyph, inset)))
    }

    // ---------- quick access ----------

    private fun appLabel(pkg: String) = runCatching { context.packageManager.getApplicationInfo(pkg, 0).loadLabel(context.packageManager).toString() }.getOrDefault(pkg)
    private fun appIcon(pkg: String) = runCatching { context.packageManager.getApplicationIcon(pkg) }.getOrNull()

    private fun railScreen() {
        title.text = "Quick access apps"
        hint.text = "Select an app to pick it up, move it with up and down, then select again to drop it. Press right to remove."
        rail = Prefs.rail.filter { runCatching { context.packageManager.getPackageInfo(it, 0) }.isSuccess }.toMutableList()
        rail.forEachIndexed { i, pkg ->
            val r = row(i, appLabel(pkg), null, appIcon(pkg)) { pickUp(railRows.indexOf(railRows.first { it.tag == pkg })) }
            r.tag = pkg
            val remove = SRow(context, D.u(14f)).apply {
                fill = 0xFFE5484D.toInt()
                addView(context.label(15f, "sans-serif-medium", bold = true, color = Color.WHITE).apply { text = "Remove"; gravity = Gravity.CENTER }, LayoutParams(MATCH, MATCH))
                setOnClickListener { removeApp(pkg) }
            }
            r.addView(remove, LayoutParams(D.ui(110f), D.ui(44f)).apply { gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL; rightMargin = D.ui(16f) })
            r.setOnKeyListener { _, code, ev -> if (ev.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_RIGHT && moving < 0) { remove.requestFocus(); true } else false }
            remove.setOnKeyListener { _, code, ev -> if (ev.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_LEFT) { r.requestFocus(); true } else false }
            railRows += r
        }
        if (rail.size < Prefs.MAX_RAIL) row(rail.size, "Add an app", "Up to ${Prefs.MAX_RAIL} apps in the quick access bar", chip(R.drawable.ic_plus, 0xFF2F80ED.toInt())) { show(Screen.ADD) }
        railRows.firstOrNull()?.requestFocus() ?: body.getChildAt(0)?.requestFocus()
    }

    private fun pickUp(i: Int) { if (i < 0) return; moving = i; railRows[i].lifted = true; railRows[i].bringToFront() }

    private fun swap(a: Int, b: Int) {
        rail.add(b, rail.removeAt(a)); railRows.add(b, railRows.removeAt(a))
        // Every row glides to its new slot; the lifted one follows the remote.
        railRows.forEachIndexed { idx, r -> val home = (r.layoutParams as LayoutParams).topMargin; r.springTo(DynamicAnimation.TRANSLATION_Y, (idx * step - home).toFloat(), Spring.GLIDE_K, 0.82f) }
        moving = b
    }

    /** Edge bump while moving an app: one at a time, always back to where the row is headed (a held key repeats faster). */
    private var bumping = false
    private fun bump() {
        if (bumping) return
        val r = railRows[moving]; val y = r.springRest(DynamicAnimation.TRANSLATION_Y); bumping = true
        r.springTo(DynamicAnimation.TRANSLATION_Y, y + D.u(6f), 900f, 0.5f)
        r.postDelayed({ r.springTo(DynamicAnimation.TRANSLATION_Y, y, 600f, 0.55f) }, 70)
        r.postDelayed({ bumping = false }, 420)
    }

    private fun drop() { railRows.getOrNull(moving)?.lifted = false; moving = -1; Prefs.rail = rail; onRail() }

    private fun removeApp(pkg: String) {
        rail.remove(pkg); Prefs.rail = rail; onRail()
        show(Screen.RAIL)
    }

    private fun addScreen() {
        title.text = "Add an app"; hint.text = "Pick an app for the quick access bar."
        val pm = context.packageManager
        val seen = LinkedHashMap<String, String>()
        for (cat in arrayOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER))
            for (ri in pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                val p = ri.activityInfo.packageName
                if (p != context.packageName && p !in rail) seen.putIfAbsent(p, ri.loadLabel(pm).toString())
            }
        val apps = seen.entries.sortedBy { it.value.lowercase() }.take(6)
        apps.forEachIndexed { i, (pkg, label) ->
            row(i, label, null, appIcon(pkg)) { rail.add(pkg); Prefs.rail = rail; onRail(); show(Screen.RAIL) }.also { if (i == 0) it.requestFocus() }
        }
        if (apps.isEmpty()) hint.text = "Every installed app is already in the quick access bar."
    }

    // ---------- location ----------

    private fun placeScreen() {
        val p = Prefs.place
        title.text = "Location"; hint.text = "Now: ${p.name}, ${p.country}. Weather, sunrise and Netflix picks follow this."
        val detect = row(0, "Use this box's location", "Detect it automatically from the internet connection") {}
        detect.setOnClickListener {
            hint.text = "Finding your location…"
            Locate.detect { r -> if (r == null) hint.text = "Couldn't detect it. Try searching for your town instead." else choose(r) }
        }
        detect.requestFocus()
        val box = SRow(context)
        val input = EditText(context).apply {
            hint = "Search for a town or city"
            setHintTextColor(INK2); setTextColor(INK)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, D.u(19f)); background = null
            setSingleLine(); imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(D.ui(22f), 0, D.ui(22f), 0)
        }
        box.addView(input, LayoutParams(MATCH, MATCH))
        box.setOnClickListener { input.requestFocus(); (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(input, 0) }
        body.addView(box, LayoutParams(MATCH, rowH).apply { topMargin = step })
        val results = ArrayList<View>()
        var pending: Runnable? = null
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                pending?.let { removeCallbacks(it) }
                val q = s.toString().trim(); if (q.length < 2) return
                pending = Runnable {
                    Locate.search(q) { list ->
                        results.forEach { body.removeView(it) }; results.clear()
                        list.take(5).forEachIndexed { i, r -> results += row(i + 2, r.name, r.line) { choose(r) } }
                    }
                }.also { postDelayed(it, 450) }
            }
        })
    }

    // ---------- screensaver ----------

    private val saverOptions = listOf(60_000 to "1 minute", 120_000 to "2 minutes", 300_000 to "5 minutes", 600_000 to "10 minutes",
        900_000 to "15 minutes", 1_800_000 to "30 minutes", 3_600_000 to "1 hour", Int.MAX_VALUE to "Never")

    private fun saverMs() = runCatching {
        android.provider.Settings.System.getInt(context.contentResolver, android.provider.Settings.System.SCREEN_OFF_TIMEOUT)
    }.getOrDefault(300_000)

    private fun saverLabel(ms: Int) = saverOptions.firstOrNull { it.first == ms }?.second ?: when {
        ms >= Int.MAX_VALUE / 2 -> "Never"; ms < 60_000 -> "${ms / 1000} seconds"; else -> "${ms / 60_000} minutes"
    }

    /** Two columns of choices; the current one carries a tick. Android starts the screensaver after this idle time. */
    private fun saverScreen() {
        title.text = "Background & sound"
        hint.text = "The nature screensaver starts after this long with no button presses. Below: ambient sound and card previews."
        val cur = saverMs()
        val w = (colW - gap) / 2
        var focus: View? = null
        saverOptions.forEachIndexed { i, (ms, label) ->
            val r = SRow(context)
            val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(D.ui(22f), 0, D.ui(18f), 0) }
            line.addView(context.label(19f, "sans-serif-medium", bold = true, color = INK).apply { text = label }, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (ms == cur) line.addView(ImageView(context).apply { setImageResource(R.drawable.ic_check); setColorFilter(0xFF30D158.toInt()) }, LinearLayout.LayoutParams(D.ui(26f), D.ui(26f)))
            r.addView(line, LayoutParams(MATCH, MATCH))
            r.setOnClickListener { setSaver(ms, label) }
            body.addView(r, LayoutParams(w, rowH).apply { leftMargin = (i % 2) * (w + gap); topMargin = (i / 2) * step })
            if (ms == cur || focus == null && i == 0) focus = r
        }
        if (saverOptions.none { it.first == cur }) hint.text = "Now: ${saverLabel(cur)}. " + hint.text
        // Ambient sound: a quiet nature bed that follows the time of day (and the rain), never over other apps' sound.
        val amb = SRow(context)
        val ambLine = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(D.ui(22f), 0, D.ui(22f), 0) }
        val ambCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        ambCol.addView(context.label(19f, "sans-serif-medium", bold = true, color = INK).apply { text = "Ambient sound" })
        ambCol.addView(context.label(15f, color = INK2).apply { text = "Birds at dawn, the shore by day, waves at sunset, rain when it rains" }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = D.ui(3f) })
        ambLine.addView(ambCol, LinearLayout.LayoutParams(0, WRAP, 1f))
        val ambV = context.label(17f, "sans-serif-medium", color = INK).apply { text = Ambient.label() }
        ambLine.addView(ambV)
        amb.addView(ambLine, LayoutParams(MATCH, MATCH))
        amb.setOnClickListener { Ambient.mode = (Ambient.mode + 1) % 3; ambV.text = Ambient.label() }
        val below = ((saverOptions.size + 1) / 2) * step
        body.addView(amb, LayoutParams(MATCH, rowH).apply { topMargin = below })
        // Previews: resting on a Continue watching or Fresh picks card plays it, muted, full screen behind Home.
        val pv = SRow(context)
        val pvLine = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(D.ui(22f), 0, D.ui(22f), 0) }
        val pvCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        pvCol.addView(context.label(19f, "sans-serif-medium", bold = true, color = INK).apply { text = "Previews" })
        pvCol.addView(context.label(15f, color = INK2).apply { text = "Rest on a card: YouTube videos and trailers play muted, Spotify songs play 30 seconds" }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = D.ui(3f) })
        pvLine.addView(pvCol, LinearLayout.LayoutParams(0, WRAP, 1f))
        // On → On, music silent → Off → On.
        fun pvText() = when { !Prefs.previews -> "Off"; Prefs.musicSound -> "On"; else -> "Music silent" }
        val pvV = context.label(17f, "sans-serif-medium", color = if (Prefs.previews) 0xFF30D158.toInt() else INK2).apply { text = pvText() }
        pvLine.addView(pvV)
        pv.addView(pvLine, LayoutParams(MATCH, MATCH))
        pv.setOnClickListener {
            when { !Prefs.previews -> { Prefs.previews = true; Prefs.musicSound = true }; Prefs.musicSound -> Prefs.musicSound = false; else -> Prefs.previews = false }
            pvV.text = pvText(); pvV.setTextColor(if (Prefs.previews) 0xFF30D158.toInt() else INK2); onPreviews()
        }
        body.addView(pv, LayoutParams(MATCH, rowH).apply { topMargin = below + step })
        focus?.requestFocus()
    }

    private fun setSaver(ms: Int, label: String) {
        val cr = context.contentResolver
        if (!android.provider.Settings.System.canWrite(context)) {
            hint.text = "Allow MoLakaz TV to change system settings on the next screen, then come back."
            runCatching {
                context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:" + context.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return
        }
        android.provider.Settings.System.putInt(cr, android.provider.Settings.System.SCREEN_OFF_TIMEOUT, ms)
        show(Screen.SAVER)
        hint.text = if (ms == Int.MAX_VALUE) "Screensaver is off." else "The screensaver now starts after $label."
    }

    // ---------- phone remote ----------

    /** QR code and address, a phone showing the remote, and how to keep it on the Home Screen (iPhone and Android). */
    private fun remoteScreen() {
        val url = Remote.url()
        title.text = "Phone remote"
        hint.text = "Use your phone as the remote: same Wi-Fi as the TV, nothing to install."
        if (url == null) { row(0, "No network", "Connect the TV to Wi-Fi, then come back here.") {}.requestFocus(); return }
        // Left: the code and the address.
        val card = SRow(context, D.u(24f))
        val qrSize = D.ui(196f)
        card.addView(ImageView(context).apply {
            setImageBitmap(Remote.qr(url, qrSize)); background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = D.u(16f) }
            val ip = D.ui(10f); setPadding(ip, ip, ip, ip)
        }, LayoutParams(qrSize + D.ui(20f), qrSize + D.ui(20f)).apply { leftMargin = D.ui(20f); topMargin = D.ui(20f) })
        card.addView(context.label(13f, "sans-serif-medium", bold = true, color = INK2).apply { text = "OR TYPE IN THE BROWSER"; letterSpacing = 0.1f },
            LayoutParams(WRAP, WRAP).apply { leftMargin = D.ui(22f); topMargin = D.ui(20f + 216f + 18f) })
        card.addView(context.label(21f, "sans-serif-medium", bold = true, color = INK).apply { text = url.removePrefix("http://") },
            LayoutParams(WRAP, WRAP).apply { leftMargin = D.ui(22f); topMargin = D.ui(20f + 216f + 40f) })
        val status = context.label(14f, color = INK2).apply { text = "Checking key control…"; maxLines = 2; ellipsize = null }
        card.addView(status, LayoutParams(D.ui(216f), WRAP).apply { leftMargin = D.ui(22f); topMargin = D.ui(20f + 216f + 78f) })
        body.addView(card, LayoutParams(D.ui(256f), D.ui(380f)))
        // Middle: what it looks like on a phone.
        body.addView(PhoneFrame(context), LayoutParams(D.ui(196f), D.ui(404f)).apply { leftMargin = D.ui(292f); topMargin = -D.ui(12f) })
        // Right: keep it one tap away.
        val tips = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fun head(t: String, top: Float) = tips.addView(context.label(13f, "sans-serif-medium", bold = true, color = INK2).apply { text = t; letterSpacing = 0.12f },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = D.ui(top) })
        fun step(n: Int, t: String) = tips.addView(context.label(17f, color = INK).apply { text = "$n.  $t"; maxLines = 2; ellipsize = null; setLineSpacing(D.u(3f), 1f) },
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = D.ui(10f) })
        head("IPHONE", 4f)
        step(1, "Point the Camera at the code and tap the link")
        step(2, "In Safari, tap Share (the square with an arrow)")
        step(3, "Tap Add to Home Screen, then Add")
        head("ANDROID", 30f)
        step(1, "Point the camera (or Google Lens) at the code")
        step(2, "In Chrome, tap the ⋮ menu at the top right")
        step(3, "Tap Add to Home screen (or Install app)")
        tips.addView(context.label(15f, color = INK2).apply { text = "It then opens full screen, like an app."; maxLines = 2; ellipsize = null },
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = D.ui(26f) })
        body.addView(tips, LayoutParams(D.ui(470f), WRAP).apply { leftMargin = D.ui(530f); topMargin = D.ui(6f) })
        card.requestFocus()
        fun check() = Remote.warmUp { ok ->
            status.text = if (ok) "Ready: presses from the phone reach the TV." else "Not ready: if the TV asks \"Allow USB debugging?\", tick Always allow and press OK."
        }
        card.setOnClickListener { status.text = "Checking key control…"; check() }
        check()
    }

    private fun choose(r: Locate.Result) {
        Prefs.place = Prefs.Place(r.name, r.country, r.lat, r.lon, r.cc)
        onPlace()
        show(Screen.MAIN)
    }
}
