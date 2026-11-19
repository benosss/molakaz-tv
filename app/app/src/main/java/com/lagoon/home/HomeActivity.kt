package com.lagoon.home

import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.net.Uri
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import android.view.SurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateFormat
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.animation.doOnEnd
import androidx.dynamicanimation.animation.DynamicAnimation
import java.io.File
import java.util.Calendar
import java.util.Date

class HomeActivity : Activity() {
    private lateinit var gl: GLSurfaceView
    private lateinit var backdrop: Backdrop
    private lateinit var videoView: SurfaceView
    private lateinit var carousel: Carousel
    private lateinit var preview: Preview
    private lateinit var music: MusicPreview
    private lateinit var scrim: View
    private lateinit var ui: FrameLayout

    private lateinit var rail: GlassPanel
    private val railTiles = ArrayList<Tile>()
    private val railActions = ArrayList<(View) -> Unit>()

    private lateinit var clockBox: LinearLayout
    private lateinit var greet: TextView
    private lateinit var clock: TextView
    private lateinit var date: TextView

    private lateinit var status: GlassPanel
    private lateinit var wxIcon: ImageView
    private lateinit var temp: TextView
    private lateinit var wxText: TextView
    private lateinit var sep: View
    private lateinit var wifiIcon: ImageView
    private lateinit var speed: TextView
    private lateinit var gnSep: TextView
    private lateinit var gnIcon: ImageView
    private lateinit var gnText: TextView

    private lateinit var where: LinearLayout
    private lateinit var whereText: TextView
    private lateinit var whereAbout: TextView

    private lateinit var np: GlassPanel
    private lateinit var npArt: ArtView
    private lateinit var npLabel: TextView
    private lateinit var npTitle: TextView
    private lateinit var npSub: TextView
    private lateinit var npBar: Bar
    private lateinit var npGlyph: ImageView
    private lateinit var npViz: EqView
    private var resumed = false

    private lateinit var shelf: Shelf
    private lateinit var allApps: AllApps
    private lateinit var settings: HomeSettings
    private lateinit var setup: SetupScreen

    private val h = Handler(Looper.getMainLooper())
    private enum class Zone { RAIL, NOW, STATUS, TABS, SHELF }
    private var zone = Zone.RAIL
    private var railIdx = 0
    private var saver = 0f
    private var inSaver = false
    private var themeAnim: ValueAnimator? = null
    private var saverAnim: ValueAnimator? = null
    private var lastPlace: String? = "\u0000"
    private var lastMinute = -1
    private var speedScheduled = false
    private val artCache = HashMap<String, Bitmap?>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BootTrace.mark("activity onCreate")
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        D.s = resources.displayMetrics.widthPixels / 1280f
        // Transparent window: HWUI starts each frame with a cheap tile clear, and the video/GL layers show through.
        window.setFormat(PixelFormat.TRANSLUCENT)
        window.setBackgroundDrawable(null)

        val root = FrameLayout(this)
        // Layer 1: video straight to the hardware video plane (no GPU work, full 1080p).
        videoView = HoleFreeSurfaceView(this)
        root.addView(videoView, FrameLayout.LayoutParams(MATCH, MATCH))
        // Layer 2: translucent GL glass/dim layer at 720p (hardware scaler upsamples it).
        gl = HoleFreeGLSurfaceView(this).apply {
            setEGLContextClientVersion(2); setEGLConfigChooser(8, 8, 8, 8, 0, 0); preserveEGLContextOnPause = true
            holder.setFormat(PixelFormat.TRANSLUCENT); setZOrderMediaOverlay(true)
            holder.setFixedSize(1280, 720)
        }
        backdrop = Backdrop(gl)
        backdrop.screenW = resources.displayMetrics.widthPixels.toFloat()
        gl.setRenderer(backdrop)
        gl.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        root.addView(gl, FrameLayout.LayoutParams(MATCH, MATCH))
        // Previews on focus sit between the scenery and the UI.
        val previewHost = FrameLayout(this)
        root.addView(previewHost, FrameLayout.LayoutParams(MATCH, MATCH))
        val onProgress = { src: PreviewSrc, t: Float, dur: Float -> if (zone == Zone.SHELF && shelf.itemAt(shelf.index)?.preview === src) shelf.previewAt(shelf.index, t, dur) }
        preview = Preview(this, previewHost, onShown = ::previewShown, onLoading = { on -> shelf.setLoading(shelf.index, on && zone == Zone.SHELF) },
            onActive = { on -> videoActive = on; cacheUi() }, onProgress = onProgress)
        // Spotify cards: the song's cover scene and its clip, above the video players (only one of the two shows).
        // While a song's scene is up it says everything about the music, so the Now playing line steps aside (one
        // cover, one title, one visualiser on screen, never two).
        music = MusicPreview(this, previewHost, onShown = { on -> musicStage = on; previewShown(on); renderNow() },
            onActive = { on -> musicActive = on; cacheUi() }, onProgress = onProgress)
        ui = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        root.addView(ui, FrameLayout.LayoutParams(MATCH, MATCH))
        // Under everything else in Home's layer: the dimming over a preview (drawn once, not per video frame).
        scrim = previewScrim(this)
        ui.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))

        buildRail(); buildClock(); buildStatus(); buildWhere(); buildNowPlaying(); buildShelf()
        allApps = AllApps(this) { pkg, v -> closeAllApps(); launch(pkg, v) }
        root.addView(allApps, FrameLayout.LayoutParams(MATCH, MATCH))
        settings = HomeSettings(this, onRail = { rebuildRail(true) }, onPlace = { renderStatus(); refreshWeatherNow(); Picks.refresh(Prefs.place) },
            onPreviews = { preview.enabled = Prefs.previews; music.enabled = Prefs.previews; if (Prefs.previews) predictSoon(1500) },
            iconFor = { entry, size, style -> iconBitmap(entry, size, style) })
        root.addView(settings, FrameLayout.LayoutParams(MATCH, MATCH))
        // A box without the films or the access MoLakaz TV needs: the first-run setup, over everything.
        // Home's own layer fades out behind it, as for Settings, so the steps read on clean glass.
        setup = SetupScreen(this) { finished -> ui.animate().alpha(1f).setDuration(320).start(); if (finished) recreate() }
        root.addView(setup, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)
        if (Setup.needed(this) && !Setup.later) h.postDelayed({ if (!isDestroyed && !setup.isOpen) openSetup() }, 700)

        Glass.onChanged = { glSoon() }
        val created = android.os.SystemClock.uptimeMillis()
        root.viewTreeObserver.addOnPreDrawListener {
            Glass.update()
            if (!firstDrawn) {
                // Hold the very first frame (and so the end of the boot animation) until the blurred opening film is on
                // the GL layer, so the first thing on screen is never black. Gives up after 700 ms.
                if (!backdrop.drawnOnce && android.os.SystemClock.uptimeMillis() - created < 700) { gl.requestRender(); return@addOnPreDrawListener false }
                onFirstDraw()
            }
            true
        }

        ContinueStore.listen { if (shelf.tab == 0 && zone != Zone.SHELF) shelf.reload(true) }
        MediaHub.listen { renderNow(); music.mediaChanged() }
        Picks.listen { if (shelf.tab == 1 && zone != Zone.SHELF) shelf.reload(true) }
        h.postDelayed(picksLoop, 3000)
        MediaHub.start(this)
        Scenes.load(File(getExternalFilesDir(null), "scenes.json"))
        Carousel.wireKeys(this, backdrop) { gl.requestRender() }
        carousel = Carousel(this, videoView, gl, backdrop) { s -> showPlace(s?.label, s?.about) }
        carousel.prime()

        Theme.listen { tintGlassText(); updateDim() }
        Goodnight.listen { renderStatus() }
        applyTheme(false); tintGlassText(); updateDim()
        tick(); renderStatus(); renderNow(); refreshWeather()
        shelf.post { shelf.reload(false) }
        h.post { setFocus(Zone.RAIL, 0) }
        androidx.core.content.ContextCompat.registerReceiver(this, tourReceiver, android.content.IntentFilter("com.lagoon.home.TOUR"),
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        BootTrace.mark("onCreate done")
    }

    /** A preview (video or music) came up or went: dim for legibility, pause the scenery, hide the place line. */
    private fun previewShown(on: Boolean) {
        scrim.animate().alpha(if (on) 1f else 0f).setDuration(if (on) 650 else 360).start()
        shelf.previewLive(shelf.index, on && zone == Zone.SHELF)
        if (on) { carousel.pause(); where.fadeTo(0f) } else { if (resumed) carousel.resume(); lastPlace = "\u0000"; showPlace(carousel.scene?.label, carousel.scene?.about) }
    }

    /** While a preview plays, Home is cached as one layer (redrawing it per video frame costs ~100 ms on this box). */
    private var videoActive = false
    private var musicActive = false
    private var musicStage = false
    private fun cacheUi() { val on = videoActive || musicActive; if ((ui.layerType == View.LAYER_TYPE_HARDWARE) != on) ui.setLayerType(if (on) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_NONE, null) }

    // ---------- build ----------

    private fun buildRail() {
        rail = GlassPanel(this, D.u(34f))
        ui.addView(rail, FrameLayout.LayoutParams(D.ui(104f), MATCH).apply {
            leftMargin = D.ui(28f); topMargin = D.ui(28f); bottomMargin = D.ui(28f); gravity = Gravity.LEFT or Gravity.TOP
        })
        rebuildRail(false)
    }

    /** Quick-access bar from settings; spacing tightens a little when there are more apps. */
    private fun rebuildRail(animate: Boolean) {
        railTiles.forEach { rail.removeView(it) }; railTiles.clear(); railActions.clear()
        val apps = Prefs.rail.filter { installed(it) }.take(Prefs.MAX_RAIL)
        val gap = if (apps.size <= 4) 22f else 14f
        var top = 26f
        for (pkg in apps) { addRailTile(top, null, pkg, 0) { v -> launch(pkg, v) }; top += 62f + gap }
        addRailTile(null, 26f + 62f + 22f, null, R.drawable.ic_gear) { openSettings() }
        addRailTile(null, 26f, null, R.drawable.ic_grid) { openAllApps() }
        if (animate && Motion.on) railTiles.forEachIndexed { i, t -> t.alpha = 0f; t.translationX = -D.u(14f); t.fadeTo(1f, 0f, 380, 35L * i); t.springTo(DynamicAnimation.TRANSLATION_X, 0f, Spring.SOFT_K, Spring.SOFT_Z) }
        if (zone == Zone.RAIL) railIdx = railIdx.coerceIn(0, railTiles.size - 1)
    }

    private fun addRailTile(top: Float?, bottom: Float?, pkg: String?, glyph: Int, action: (View) -> Unit) {
        val t = Tile(this, D.u(17f))
        if (pkg != null) t.bitmap = iconBitmap(pkg, D.ui(62f))
        else if (Prefs.glassIcons) { t.bitmap = GlassIcon.glyph(getDrawable(glyph)!!.mutate(), D.ui(62f)); t.raise = false }
        else { t.glyph = getDrawable(glyph)!!.mutate(); t.raise = false }
        rail.addView(t, FrameLayout.LayoutParams(D.ui(62f), D.ui(62f)).apply {
            gravity = Gravity.CENTER_HORIZONTAL or (if (top != null) Gravity.TOP else Gravity.BOTTOM)
            if (top != null) topMargin = D.ui(top) else bottomMargin = D.ui(bottom!!)
        })
        railTiles += t; railActions += action
    }

    private fun buildClock() {
        // Top right, "Lock Screen": GOOD EVENING · MONDAY 16 NOVEMBER, a thin clock, then one line of weather and speed.
        clockBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        greet = label(13f, "sans-serif-medium", bold = true, shadow = true).apply { letterSpacing = 0.14f; alpha = 0.9f; ellipsize = null }
        clock = label(112f, "sans-serif-thin", shadow = true).apply { ellipsize = null; letterSpacing = -0.02f; setPadding(D.ui(8f), 0, 0, 0) }
        date = label(13f)   // kept for the caption text; not shown on its own
        clockBox.addView(greet, LinearLayout.LayoutParams(WRAP, WRAP))
        clockBox.addView(clock, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = D.ui(2f) })
        ui.addView(clockBox, place(WRAP, WRAP, r = 56f, t = 30f))
    }

    private fun buildStatus() {
        // No panel at rest: the line sits on the video like the clock. Selecting it brings the glass in (and runs a speed test).
        status = GlassPanel(this, D.u(20f)).apply { surface = 0f }
        val pad = D.ui(14f)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(pad, 0, pad, 0) }
        val gap = D.ui(8f)
        wxIcon = ImageView(this)
        temp = label(17f, "sans-serif-medium", bold = true, shadow = true)
        wxText = label(17f, shadow = true).apply { alpha = 0.86f }
        sep = label(17f, shadow = true).apply { text = "·"; alpha = 0.45f }
        wifiIcon = ImageView(this).apply { setImageResource(R.drawable.ic_wifi) }
        speed = label(17f, "sans-serif-medium", bold = true, shadow = true)
        row.addView(wxIcon, LinearLayout.LayoutParams(D.ui(21f), D.ui(21f)))
        row.addView(temp, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = gap })
        row.addView(wxText, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = gap })
        row.addView(sep, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = D.ui(12f); rightMargin = D.ui(12f) })
        row.addView(wifiIcon, LinearLayout.LayoutParams(D.ui(19f), D.ui(19f)))
        row.addView(speed, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = gap })
        // Goodnight countdown, only while the timer runs.
        gnSep = label(17f, shadow = true).apply { text = "·"; alpha = 0.45f; visibility = View.GONE }
        gnIcon = ImageView(this).apply { setImageResource(R.drawable.ic_moon); setColorFilter(Color.WHITE); visibility = View.GONE }
        gnText = label(17f, "sans-serif-medium", bold = true, shadow = true).apply { visibility = View.GONE }
        row.addView(gnSep, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = D.ui(12f); rightMargin = D.ui(12f) })
        row.addView(gnIcon, LinearLayout.LayoutParams(D.ui(18f), D.ui(18f)))
        row.addView(gnText, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = gap })
        status.addView(row, FrameLayout.LayoutParams(WRAP, MATCH))
        // Shifted right by the padding so the text lines up with the clock's right edge when the glass is hidden.
        ui.addView(status, place(WRAP, D.ui(40f), r = 56f - 14f, t = 186f))
    }

    private fun buildWhere() {
        where = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; alpha = 0f }
        val pin = ImageView(this).apply { setImageResource(R.drawable.ic_pin); alpha = 0.92f }
        whereText = label(15f, "sans-serif-medium", shadow = true).apply { alpha = 0.92f }
        where.addView(pin, LinearLayout.LayoutParams(D.ui(15f), D.ui(15f)).apply { rightMargin = D.ui(7f) })
        where.addView(whereText)
        // Landmarks add a line to learn something by, quieter than the name.
        whereAbout = label(15f, shadow = true).apply { alpha = 0.72f; visibility = View.GONE }
        where.addView(whereAbout, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = D.ui(10f) })
        // On the shelf's tab row, right-aligned: level with "Continue watching" and "Fresh picks".
        ui.addView(where, place(WRAP, D.ui(34f), r = 56f, b = 34f + 200f - 34f))
    }

    private fun buildNowPlaying() {
        // "Whisper": no panel at rest. Glowing cover, title, artist and a hairline progress bar float on the video, with the
        // visualiser beside them. Selecting it fades the glass in and shows play/pause over the bars.
        np = GlassPanel(this, D.u(24f)).apply { surface = 0f; alpha = 0f; visibility = View.INVISIBLE }
        npArt = ArtView(this, D.u(14f)).apply { placeholder = getDrawable(R.drawable.ic_music)!!.mutate(); glow = true }
        np.addView(npArt, place(D.ui(64f), D.ui(64f), l = 14f, t = 12f))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        npLabel = label(11f, "sans-serif-medium", bold = true, shadow = true).apply { letterSpacing = 0.09f }
        npTitle = label(20f, bold = true, color = Color.WHITE, shadow = true)
        npSub = label(14f, color = Color.WHITE, shadow = true).apply { alpha = 0.86f }
        npBar = Bar(this).apply { onVideo = true }
        col.addView(npLabel, LinearLayout.LayoutParams(MATCH, WRAP))
        col.addView(npTitle, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = D.ui(3f) })
        col.addView(npSub, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = D.ui(1f) })
        col.addView(npBar, LinearLayout.LayoutParams(D.ui(200f), D.ui(2f)).apply { topMargin = D.ui(9f) })
        np.addView(col, place(D.ui(236f), WRAP, l = 94f, t = 14f))
        npViz = EqView(this).apply { alpha = 0f }
        np.addView(npViz, place(D.ui(58f), D.ui(34f), l = 338f, t = 27f))
        npGlyph = ImageView(this).apply { alpha = 0f }
        np.addView(npGlyph, place(D.ui(26f), D.ui(26f), l = 354f, t = 31f))
        // Offset by the inner padding so the cover sits where the old card's art did.
        ui.addView(np, place(D.ui(412f), D.ui(88f), l = 150f, t = 28f))
    }

    private fun buildShelf() {
        shelf = Shelf(this, ::shelfItems) { tab ->
            if (tab == 0) tr("Nothing to continue yet. Play something on Netflix, YouTube or Spotify and it shows up here.")
            else if (Picks.updatedAt == 0L) tr("Fresh picks are loading: Netflix Top 10 in {country}, popular on YouTube and New Music Friday on Spotify.", "country" to Prefs.place.country)
            else tr("Fresh picks couldn't refresh. They'll try again soon.")
        }
        // The shelf's view reaches past what it shows (see Shelf.PAD_X): the visible row still spans 164..1224 x 486..686.
        ui.addView(shelf, place(MATCH, D.ui(200f + Shelf.PAD_TOP + Shelf.PAD_BOTTOM), l = 164f - Shelf.PAD_X, r = 56f - Shelf.PAD_X, b = 34f - Shelf.PAD_BOTTOM))
        // Trailer look-ups for the whole row start as soon as it loads; the card focus will land on gets pre-rolled.
        shelf.onItems = { items ->
            preview.warmTrailers(items.mapNotNull { it.preview?.trailerOf })
            music.warm(items.mapNotNull { it.preview?.track })
            if (zone != Zone.SHELF) predictSoon(if (zone == Zone.TABS) 250 else 1200)
            else if (preview.shown) shelf.previewLive(shelf.index, true)
        }
    }

    /**
     * Pre-roll the previews of the cards focus is likely to reach next, most likely first (the preview keeps as many
     * as it has spare players for, and waits while a preview is starting). On a card: the next one in the direction
     * you're moving, else the one behind. Down from the tabs lands on the first card; right from the rail returns to
     * where the row was left.
     */
    private var shelfDir = 1
    private val predict = Runnable {
        if (!resumed || settings.isOpen || allApps.isOpen) return@Runnable
        val i = shelf.index
        // Only video previews need a player pre-rolled (Spotify cards' clips and covers are fetched with the row): cards
        // without one are passed over, up to a few cards along.
        fun along(from: Int, d: Int): Int? { var j = from; repeat(5) { val it = shelf.itemAt(j) ?: return null; if (it.preview != null && it.preview.track == null) return j; j += d }; return null }
        val next = when (zone) {
            Zone.SHELF -> listOf(along(i + shelfDir, shelfDir), along(i - shelfDir, -shelfDir))
            Zone.TABS -> along(0, 1).let { a -> listOf(a, a?.let { along(it + 1, 1) }) }
            else -> along(i, 1).let { a -> listOf(a, a?.let { along(it + 1, 1) } ?: along(i - 1, -1)) }
        }
        preview.prepare(next.map { it?.let { j -> shelf.itemAt(j)?.preview } })
        // Spotify cards nearby get their scenes built ahead (no player to pre-roll, just a picture).
        val near = when (zone) { Zone.SHELF -> listOf(i + shelfDir, i - shelfDir, i + 2 * shelfDir); Zone.TABS -> listOf(0, 1, 2); else -> listOf(i, i + 1) }
        music.prepare(near.mapNotNull { j -> shelf.itemAt(j)?.preview?.takeIf { p -> p.track != null } })
    }
    private fun predictSoon(ms: Long) { h.removeCallbacks(predict); h.postDelayed(predict, ms) }

    // ---------- content ----------

    private fun shelfItems(tab: Int): List<ShelfItem> {
        if (tab == 1) return Picks.items.map { p ->
            val yt = if (p.app == "youtube") ytId(p.link) else null
            val pic = Picks.bitmap(p, D.ui(250f)); val tint = Apps.card(APP_PKG[p.app])
            val pv = when (p.app) {
                "youtube" -> yt?.let { PreviewSrc(it) }
                "netflix" -> PreviewSrc(trailerOf = p.title)
                "spotify" -> SpotifyTracks.id(p.link)?.let { PreviewSrc(track = it, cover = pic, tint = tint, title = p.title, sub = p.sub) }
                else -> null
            }
            // A YouTube pick that's been previewed shows how far, and opens there.
            ShelfItem(Picks.localize(p.badge), p.title, Picks.localize(p.sub), yt?.let { Watched.fraction(it) } ?: -1f, pic, tint, pv) {
                Picks.open(this, p, yt?.let { Watched.resume(it) } ?: -1)
            }
        }
        return ContinueStore.items.filterNot { it.finished || Apps.hidden(it.pkg) }.map { it ->
            val yt = if (it.pkg == "com.google.android.youtube.tv") it.link?.let { l -> ytId(l) } else null
            // A YouTube video previewed since it was last watched: the preview's place is where it carries on from.
            val mark = yt?.let { id -> Watched[id]?.takeIf { m -> m.at > it.ts && m.dur > 0f } }
            val pos = mark?.let { m -> (m.t * 1000).toLong() } ?: it.pos
            val dur = mark?.let { m -> (m.dur * 1000).toLong() } ?: it.dur
            fun subAt(posMs: Long, durMs: Long): String {
                val left = if (durMs > 0) ((durMs - posMs).coerceAtLeast(0) / 60000L) else -1L
                return if (left > 0) ("${it.sub.ifBlank { Apps.name(it.pkg) ?: "" }} · " + tr("{n} min left", "n" to left)).trimStart(' ', '·') else it.sub
            }
            val pic = it.art?.let { p -> artCache.getOrPut(p) { BitmapFactory.decodeFile(p) } }; val tint = Apps.card(it.pkg)
            // Previews: YouTube resumes its own video a few seconds before where you stopped; film apps show the trailer;
            // Spotify plays 30 seconds of the song over its cover.
            val pv = when {
                it.pkg == "com.google.android.youtube.tv" -> yt?.let { id -> PreviewSrc(id, ((it.pos / 1000) - 3).toInt().coerceAtLeast(0), since = it.ts) }
                Apps.trailers(it.pkg) -> PreviewSrc(trailerOf = it.title)
                it.pkg == "com.spotify.tv.android" -> (SpotifyTracks.id(it.link) ?: SpotifyTracks.searchKey(it.title, it.sub))?.let { id -> PreviewSrc(track = id, cover = pic, tint = tint, title = it.title, sub = it.sub) }
                else -> null
            }
            ShelfItem((Apps.name(it.pkg) ?: "").uppercase(), it.title, subAt(pos, dur), if (dur > 0) pos.toFloat() / dur else -1f,
                pic, tint, pv,
                subFor = if (yt != null) { t, d -> subAt((t * 1000).toLong(), (d * 1000).toLong()) } else null) { v -> resume(it, v) }
        }
    }

    /** Whisper only exists while something plays: it fades and settles in, and fades out when playback ends. */
    private var npShown = false
    private fun showNow(on: Boolean) {
        if (on == npShown) return
        npShown = on
        if (!on && zone == Zone.NOW) setFocus(Zone.TABS, 0)
        np.animate().cancel()
        val ms = if (Motion.on) 1L else 0L
        if (on) {
            np.visibility = View.VISIBLE
            if (np.alpha < 0.05f) { np.translationY = -D.u(8f); np.scaleX = 0.97f; np.scaleY = 0.97f }
            np.animate().alpha(1f).translationY(0f).setDuration(420 * ms).setInterpolator(Ease.out).start()
            np.springScale(1f, Spring.SOFT_K, Spring.SOFT_Z)
        } else {
            np.animate().alpha(0f).translationY(-D.u(6f)).setDuration(260 * ms).setInterpolator(Ease.exit)
                .withEndAction { if (!npShown) np.visibility = View.INVISIBLE }.start()
        }
    }

    private fun renderNow() {
        val n = MediaHub.now
        showNow(n != null && !inSaver && !musicStage)
        if (n == null) {
            npLabel.text = tr("NOW PLAYING"); npLabel.setTextColor(Theme.ink2)
            swap(npTitle, tr("Nothing playing"))
            swap(npSub, if (MediaHub.permitted) tr("Music and shows appear here") else tr("Media access is off"))
            npArt.set(null); npBar.visibility = View.INVISIBLE
            npGlyph.setImageResource(R.drawable.ic_play)
        } else {
            npLabel.text = (if (n.playing) tr("NOW PLAYING") else tr("PAUSED")) + " · " + (Apps.name(n.pkg) ?: "").uppercase()
            npLabel.setTextColor(brandOnVideo(n.pkg))
            swap(npTitle, n.title); swap(npSub, n.sub.ifBlank { Apps.name(n.pkg) ?: "" })
            artFor(n) { b -> if (MediaHub.now?.title == n.title) npArt.set(b) }
            npBar.visibility = if (n.dur > 0) View.VISIBLE else View.INVISIBLE
            if (n.dur > 0) npBar.value = n.position().toFloat() / n.dur
            npGlyph.setImageResource(if (n.playing) R.drawable.ic_pause else R.drawable.ic_play)
            npViz.color = brandOnVideo(n.pkg)
        }
        npGlyph.setColorFilter(Color.WHITE)
        npControls()
    }

    /**
     * Playing: the visualiser. Selected: it dims and the play/pause glyph shows over it. Paused: the play glyph.
     * Nothing playing: the faint glyph, as before. Audio is only analysed while Home is on screen and music plays.
     */
    private fun npControls() {
        val n = MediaHub.now; val playing = n?.playing == true; val focused = zone == Zone.NOW
        npViz.setPlaying(playing)
        val vizA = when { n == null -> 0f; focused -> 0.22f; playing -> 1f; else -> 0f }
        val glyphA = when { n == null -> 0f; focused -> 1f; playing -> 0f; else -> 0.9f }
        if (npViz.alpha != vizA) npViz.animate().alpha(vizA).setDuration(if (Motion.on) 240 else 0).setInterpolator(Ease.out).start()
        if (npGlyph.alpha != glyphA) npGlyph.animate().alpha(glyphA).setDuration(if (Motion.on) 240 else 0).setInterpolator(Ease.out).start()
        if (resumed && playing && !inSaver) AudioViz.start(this) else AudioViz.stop()
    }

    private fun renderStatus() {
        val w = Weather.w
        if (w != null) {
            wxIcon.setImageResource(Weather.icon(w.code, w.isDay)); temp.text = "${w.temp}°"
            wxText.text = Prefs.place.name
        } else { wxIcon.setImageResource(R.drawable.ic_cloud); temp.text = "--°"; wxText.text = Prefs.place.name }
        speed.text = when { SpeedTest.running -> tr("Testing…"); SpeedTest.mbps != null -> "${SpeedTest.mbps} Mbps"; else -> "Wi-Fi" }
        val gn = Goodnight.active
        listOf<View>(gnSep, gnIcon, gnText).forEach { it.visibility = if (gn) View.VISIBLE else View.GONE }
        if (gn) gnText.text = "${Goodnight.minutesLeft()} min"
    }

    private fun tintGlassText() {
        val ink = Theme.ink; val ink2 = Theme.ink2
        // The weather/speed line sits on the video, so it stays white like the clock in both themes.
        temp.setTextColor(Color.WHITE); speed.setTextColor(Color.WHITE); wxText.setTextColor(Color.WHITE)
        (sep as TextView).setTextColor(Color.WHITE)
        wifiIcon.setColorFilter(Color.WHITE); wxIcon.setColorFilter(Color.WHITE); npGlyph.setColorFilter(Color.WHITE)
        npTitle.setTextColor(Color.WHITE); npSub.setTextColor(Color.WHITE)
        MediaHub.now?.let { npLabel.setTextColor(brandOnVideo(it.pkg)) } ?: npLabel.setTextColor(Color.WHITE)
        npArt.invalidate()
    }

    /** Brand colours bright enough to read straight on the video, day or night. */
    private fun brandOnVideo(pkg: String) = when (pkg) {
        "com.spotify.tv.android" -> 0xFF4ADE80.toInt()
        "com.netflix.ninja" -> 0xFFFF5A5F.toInt()
        "com.google.android.youtube.tv" -> 0xFFFF6B6B.toInt()
        else -> 0xFFB5E6FF.toInt()
    }

    private fun brand(pkg: String) = when (pkg) {
        "com.spotify.tv.android" -> mix(0xFF127A3A.toInt(), 0xFF4ADE80.toInt(), Theme.t)
        "com.netflix.ninja" -> mix(0xFFC8000C.toInt(), 0xFFFF5A63.toInt(), Theme.t)
        "com.google.android.youtube.tv" -> mix(0xFFCC0029.toInt(), 0xFFFF6B7D.toInt(), Theme.t)
        else -> Theme.ink2
    }

    /** Text change as a quick cross-fade, so updates never pop. */
    private fun swap(tv: TextView, text: String) {
        val target = tv.getTag(R.id.swap_target) as? String ?: tv.text.toString()
        if (target == text) return
        tv.setTag(R.id.swap_target, text)
        if (!Motion.on || tv.text.isEmpty()) { tv.text = text; tv.alpha = 1f; return }
        tv.animate().cancel()
        tv.animate().alpha(0f).setDuration(120).setInterpolator(Ease.exit).withEndAction {
            if (tv.getTag(R.id.swap_target) == text) { tv.text = text; tv.animate().alpha(1f).setDuration(260).setInterpolator(Ease.out).start() }
        }.start()
    }

    /** Apps like YouTube send an artwork link instead of a picture: fetch it once, cache by link. */
    private val artByUri = HashMap<String, Bitmap>()
    private fun artFor(n: MediaHub.Now, cb: (Bitmap?) -> Unit) {
        n.art?.let { cb(it); return }
        val uri = n.artUri?.takeIf { it.startsWith("http") } ?: run { cb(null); return }
        artByUri[uri]?.let { cb(it); return }
        kotlin.concurrent.thread(name = "art") {
            val b = runCatching { java.net.URL(uri).openStream().use { BitmapFactory.decodeStream(it) } }.getOrNull()
            h.post { if (b != null) artByUri[uri] = b; cb(b) }
        }
    }

    /** Glass moves are folded into the next ~30 fps GL frame instead of forcing 60 fps GL redraws. */
    private var glPending = false
    private fun glSoon() {
        if (glPending) return
        glPending = true
        h.postDelayed({ glPending = false; gl.requestRender() }, 33)
    }

    // ---------- time, weather, theme ----------

    private val ticker = object : Runnable { override fun run() { tick(); h.postDelayed(this, 1000) } }

    private fun tick() {
        val now = Date(); val cal = Calendar.getInstance()
        val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        if (minute != lastMinute) {
            lastMinute = minute
            clock.text = DateFormat.format(if (DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm", now)
            date.text = DateFormat.format("EEEE d MMMM", now)
            val hr = cal.get(Calendar.HOUR_OF_DAY)
            val hello = tr(when { hr < 5 -> "Good night"; hr < 12 -> "Good morning"; hr < 17 -> "Good afternoon"; else -> "Good evening" })
            greet.text = "$hello  ·  ${date.text}".uppercase()
            applyTheme(true)
            if (Goodnight.active || gnText.visibility == View.VISIBLE) renderStatus()
        }
        MediaHub.now?.let { if (it.playing && it.dur > 0) npBar.value = it.position().toFloat() / it.dur }
    }

    private fun showPlace(label: String?, about: String? = null) {
        if (label == lastPlace) return
        // Hidden while a preview plays; shown again (with the current film) when it ends.
        if (::preview.isInitialized && (preview.shown || music.shown)) return
        lastPlace = label
        if (label == null) { where.fadeTo(0f); return }
        val set = { whereText.text = label; whereAbout.text = about.orEmpty(); whereAbout.visibility = if (about.isNullOrBlank()) View.GONE else View.VISIBLE }
        if (where.alpha < 0.05f || !Motion.on) { set(); where.translationY = D.u(8f); where.fadeTo(1f, 0f, 600); return }
        where.fadeTo(0f, -D.u(6f), 220) {
            set(); where.translationY = D.u(8f); where.fadeTo(1f, 0f, 620)
        }
    }

    private fun refreshWeatherNow() = Weather.fetch { renderStatus(); applyTheme(true) }

    private val picksLoop = object : Runnable {
        override fun run() { if (Picks.stale()) Picks.refresh(Prefs.place); h.postDelayed(this, 10 * 60_000L) }
    }

    private fun refreshWeather() {
        Weather.fetch { renderStatus(); applyTheme(true) }
        h.postDelayed({ refreshWeather() }, if (Weather.w == null) 5 * 60_000L else 30 * 60_000L)
    }

    private fun nightNow(): Boolean {
        val w = Weather.w; val now = System.currentTimeMillis()
        if (w != null) return now < w.sunrise || now >= w.sunset
        val hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY); return hr < 6 || hr >= 18
    }

    /** Day (0) or night (1) held for screenshots; null follows the sun. */
    private var forcedTheme: Float? = null

    private fun applyTheme(animate: Boolean) {
        val target = forcedTheme ?: if (nightNow()) 1f else 0f
        if (Theme.t == target && themeAnim == null) return
        themeAnim?.cancel()
        if (!animate || !Motion.on) { Theme.set(target); themeAnim = null; return }
        themeAnim = ValueAnimator.ofFloat(Theme.t, target).apply {
            duration = 2200; interpolator = Ease.inOut
            addUpdateListener { Theme.set(it.animatedValue as Float) }
            doOnEnd { themeAnim = null }
            start()
        }
    }

    private fun updateDim() {
        val t = Theme.t; val s = saver
        backdrop.dimBase = lerp(lerp(0.10f, 0.36f, t), lerp(0.0f, 0.14f, t), s)
        backdrop.dimTop = lerp(lerp(0.30f, 0.24f, t), 0.10f, s)
        backdrop.dimBottom = lerp(lerp(0.44f, 0.36f, t), 0.14f, s)
        backdrop.glassLift = lerp(0.0f, -0.03f, t)
        backdrop.glassSat = lerp(1.45f, 1.2f, t)
        gl.requestRender()
    }

    // ---------- speed test ----------

    private val speedLoop = object : Runnable {
        override fun run() {
            // Not while something streams (a preview loading or playing too): it would measure the leftovers.
            if (MediaHub.anyPlaying() || preview.streaming) { h.postDelayed(this, if (preview.streaming) 60_000L else 10 * 60_000L); return }
            SpeedTest.run { renderStatus() }
            h.postDelayed(this, 60 * 60_000L)
        }
    }

    // ---------- video ----------

    /** The screen comes first; the video player (~200 ms to build on this box) starts right after the first frame. */
    private var firstDrawn = false
    private fun onFirstDraw() {
        firstDrawn = true
        BootTrace.mark("first UI draw")
        h.post {
            if (isDestroyed) return@post
            if (resumed) carousel.start()
            BootTrace.mark("player started")
            h.postDelayed({ if (!isDestroyed) { preview.warmUp(null); predictSoon(0) } }, 4000)
        }
    }

    // ---------- focus ----------

    private fun setFocus(z: Zone, i: Int) {
        when (zone) {
            Zone.RAIL -> railTiles.getOrNull(railIdx)?.setFocusState(false)
            Zone.NOW -> { np.setFocusState(false); np.surfaceTo(0f); h.post { npControls() } }
            Zone.STATUS -> { status.setFocusState(false); status.surfaceTo(0f) }
            Zone.TABS -> shelf.focusTabs(false)
            Zone.SHELF -> shelf.focusCard(shelf.index, false)
        }
        zone = z
        when (z) {
            Zone.RAIL -> { railIdx = i.coerceIn(0, railTiles.size - 1); railTiles[railIdx].setFocusState(true) }
            Zone.NOW -> { np.setFocusState(true); np.surfaceTo(1f); h.post { npControls() } }
            Zone.STATUS -> { status.setFocusState(true); status.surfaceTo(1f) }
            Zone.TABS -> shelf.focusTabs(true)
            Zone.SHELF -> shelf.focusCard(i, true)
        }
        // Video previews and Spotify scenes each have their own player; the card decides which one runs.
        val src = if (z == Zone.SHELF) shelf.itemAt(shelf.index)?.preview else null
        if (src?.track != null) { preview.focus(null); music.focus(src) } else { music.focus(null); preview.focus(src) }
        // Heading for the row: get its card ready. On a card: get its neighbour ready once its own preview is up.
        if (z == Zone.TABS) predictSoon(0) else if (z == Zone.SHELF) predictSoon(600)
    }

    private fun focusedView(): View = when (zone) {
        Zone.RAIL -> railTiles[railIdx]; Zone.NOW -> np; Zone.STATUS -> status; Zone.TABS -> shelf.pill; Zone.SHELF -> shelf
    }

    /**
     * tvOS-style edge bump: a small spring nudge when there is nowhere further to go. One bump per press: a held key
     * repeats ~20 times a second, faster than a bump comes back, and reading the position mid-bump as the rest used to
     * walk the icon away step by step. The rest is where the view is headed (a sliding pill's target), never a bump.
     */
    private var keyRepeat = false
    private val nudging = HashSet<View>()
    private fun nudge(dx: Float, dy: Float) {
        val v = focusedView()
        if (!Motion.on || keyRepeat || v in nudging) return
        val p = if (dx != 0f) DynamicAnimation.TRANSLATION_X else DynamicAnimation.TRANSLATION_Y
        val rest = v.springRest(p)
        nudging += v
        v.springTo(p, rest + D.u(if (dx != 0f) dx else dy), 900f, 0.5f)
        h.postDelayed({ v.springTo(p, rest, 600f, 0.55f) }, 70)
        h.postDelayed({ nudging -= v }, 420)
    }

    private fun move(code: Int) {
        val L = KeyEvent.KEYCODE_DPAD_LEFT; val R = KeyEvent.KEYCODE_DPAD_RIGHT
        val U = KeyEvent.KEYCODE_DPAD_UP; val Dn = KeyEvent.KEYCODE_DPAD_DOWN
        val railMid = 2.coerceAtMost(railTiles.size - 1)
        when (zone) {
            Zone.RAIL -> when (code) {
                U -> if (railIdx > 0) setFocus(Zone.RAIL, railIdx - 1) else nudge(0f, -5f)
                Dn -> if (railIdx < railTiles.size - 1) setFocus(Zone.RAIL, railIdx + 1) else nudge(0f, 5f)
                R -> if (railIdx <= 1 && npShown) setFocus(Zone.NOW, 0) else if (railIdx <= 1) setFocus(Zone.STATUS, 0) else if (shelf.count > 0) setFocus(Zone.SHELF, shelf.index) else setFocus(Zone.TABS, 0)
                L -> nudge(-5f, 0f)
            }
            Zone.NOW -> when (code) { L -> setFocus(Zone.RAIL, 0); R -> setFocus(Zone.STATUS, 0); Dn -> setFocus(Zone.TABS, 0); U -> nudge(0f, -5f) }
            Zone.STATUS -> when (code) { L -> if (npShown) setFocus(Zone.NOW, 0) else setFocus(Zone.RAIL, 0); Dn -> setFocus(Zone.TABS, 0); R -> nudge(5f, 0f); U -> nudge(0f, -5f) }
            Zone.TABS -> when (code) {
                L -> if (shelf.tab > 0) shelf.selectTab(shelf.tab - 1) else setFocus(Zone.RAIL, railMid)
                R -> if (shelf.tab < 1) shelf.selectTab(shelf.tab + 1) else nudge(5f, 0f)
                U -> if (npShown) setFocus(Zone.NOW, 0) else setFocus(Zone.STATUS, 0)
                Dn -> if (shelf.count > 0) setFocus(Zone.SHELF, 0) else nudge(0f, 5f)
            }
            Zone.SHELF -> when (code) {
                L -> if (shelf.index > 0) { shelfDir = -1; setFocus(Zone.SHELF, shelf.index - 1) } else setFocus(Zone.RAIL, railMid)
                R -> if (shelf.index < shelf.count - 1) { shelfDir = 1; setFocus(Zone.SHELF, shelf.index + 1) } else nudge(5f, 0f)
                U -> setFocus(Zone.TABS, 0)
                Dn -> nudge(0f, 5f)
            }
        }
    }

    private fun activate() {
        when (zone) {
            Zone.RAIL -> { val i = railIdx; val t = railTiles[i]; t.press(); h.postDelayed({ railActions[i](t) }, 90) }
            Zone.NOW -> { np.springScale(0.98f, 900f, 0.7f); h.postDelayed({ np.springScale(1.035f, Spring.FOCUS_K, 0.75f) }, 100); MediaHub.toggle() }
            Zone.STATUS -> SpeedTest.run { renderStatus() }
            Zone.TABS -> {}
            Zone.SHELF -> shelf.activate(shelf.index)
        }
    }

    // ---------- overlay, screensaver, entrance ----------

    private fun openSetup() {
        ui.animate().alpha(0f).setDuration(260).setInterpolator(Ease.exit).start()
        setup.open()
    }

    private fun openSettings(page: String? = null) {
        ui.animate().alpha(0f).setDuration(260).setInterpolator(Ease.exit).start()
        settings.open(page)
    }

    private fun closeSettings() {
        settings.close { predictSoon(1500) }
        ui.animate().alpha(1f).setDuration(320).setInterpolator(Ease.out).start()
        renderStatus()
    }

    private fun openAllApps() {
        ui.animate().alpha(0f).setDuration(260).setInterpolator(Ease.exit).start()
        allApps.open()
    }

    private fun closeAllApps() {
        allApps.close { predictSoon(1500) }
        ui.animate().alpha(1f).setDuration(320).setInterpolator(Ease.out).start()
    }

    private val idle = Runnable { enterSaver() }
    private fun resetIdle() { h.removeCallbacks(idle); armQuietInstall() }   // idle is handled by the system screensaver (VideoDream)

    /** A downloaded update installs once Home has been left alone for a while, with nothing playing or previewing. */
    private val quietInstall = Runnable {
        if (Updater.state == Updater.State.READY && resumed && !settings.isOpen && !setup.isOpen && !preview.shown && !music.shown && !MediaHub.anyPlaying())
            Updater.install()
    }
    private fun armQuietInstall() { h.removeCallbacks(quietInstall); if (Updater.state == Updater.State.READY) h.postDelayed(quietInstall, 120_000) }

    private fun animateSaver(to: Float, ms: Long) {
        saverAnim?.cancel()
        saverAnim = ValueAnimator.ofFloat(saver, to).apply {
            duration = ms; interpolator = Ease.inOut
            addUpdateListener { saver = it.animatedValue as Float; updateDim() }
            start()
        }
    }

    private fun enterSaver() {
        if (allApps.isOpen || inSaver) return
        inSaver = true
        listOf<View>(np, status, shelf, rail).forEachIndexed { i, v -> v.fadeTo(0f, D.u(10f), 800, 80L * i) }
        greet.fadeTo(0f, 0f, 700)
        animateSaver(1f, 1600)
    }

    private fun wake() {
        inSaver = false
        listOf<View>(rail, np, status, shelf).forEachIndexed { i, v -> if (v !== np || npShown) v.fadeTo(1f, 0f, 460, 45L * i) }
        greet.fadeTo(1f, 0f, 460)
        animateSaver(0f, 600)
    }

    private fun animateIn() {
        if (!Motion.on) return
        rail.alpha = 0f; rail.translationX = -D.u(28f)
        rail.animate().alpha(1f).setDuration(420).setInterpolator(Ease.out).withLayer().start()
        rail.springTo(DynamicAnimation.TRANSLATION_X, 0f, Spring.SOFT_K, Spring.SOFT_Z)
        listOf<View>(clockBox, status, np, shelf).forEachIndexed { i, v ->
            if (v === np && !npShown) return@forEachIndexed
            v.alpha = 0f; v.translationY = D.u(14f); v.fadeTo(1f, 0f, 560, 55L * (i + 1))
        }
    }

    // ---------- lifecycle & keys ----------

    override fun onResume() {
        super.onResume()
        resumed = true
        gl.onResume()
        // Re-read the media sessions: a callback can be missed while another app is in front.
        MediaHub.start(this)
        npControls()
        // The decoder was released in onStop (it can't move to the recreated surface): start again, veiled until it shows.
        if (firstDrawn) carousel.start()
        Ambient.enter(Ambient.Where.HOME)
        preview.start(); music.start(); if (firstDrawn) predictSoon(2500)
        if (setup.isOpen) setup.resumed()
        Updater.check(); armQuietInstall()
        if (!inSaver) animateIn()
        lastMinute = -1; h.removeCallbacks(ticker); h.post(ticker)
        resetIdle()
        if (!speedScheduled) { speedScheduled = true; h.postDelayed(speedLoop, 20_000) }
    }

    override fun onPause() {
        resumed = false; AudioViz.stop(); preview.stop(); music.stop(); Ambient.leave(Ambient.Where.HOME)
        carousel.pause(); gl.onPause()
        h.removeCallbacks(ticker); h.removeCallbacks(idle)
        super.onPause()
    }

    override fun onStop() {
        // Free the hardware decoder while hidden: the screensaver and other apps need it, and this box's decoder
        // fails when ExoPlayer tries to move it onto the new surface we get when we come back.
        carousel.stop()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (allApps.isOpen) closeAllApps()
        if (settings.isOpen) closeSettings()
        if (inSaver) wake()
        setFocus(Zone.RAIL, 0)
    }

    /** Diagnostics and screenshots, from adb: am broadcast -a com.lagoon.home.TOUR --es do <command> [extras]. */
    private val tourReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context?, i: Intent?) {
            if (i == null) return
            val cmd = i.getStringExtra("do")
            android.util.Log.i("Tour", "command $cmd")
            when (cmd) {
                "np" -> android.util.Log.i("MediaHub", "state now=${MediaHub.now?.title} playing=${MediaHub.now?.playing} npShown=$npShown vis=${np.visibility} alpha=${np.alpha} inSaver=$inSaver resumed=$resumed | ${music.debugState()}")
                "viz" -> vizProbe()
                "vizdemo" -> vizDemo()
                "npdemo" -> npDemo(i)
                "veil" -> carousel.debugVeil(i.getFloatExtra("v", 1f), 9000)
                "gn" -> Goodnight.preview()
                "pv" -> preview.focus(i.getStringExtra("id")?.let { PreviewSrc(it, i.getIntExtra("s", 0)) } ?: i.getStringExtra("trailer")?.let { PreviewSrc(trailerOf = it) })
                "pvpre" -> preview.prepare(i.getStringExtra("id")?.let { PreviewSrc(it, i.getIntExtra("s", 0)) })
                "pvoff" -> { preview.focus(null); music.focus(null) }
                "mp" -> music.focus(PreviewSrc(track = i.getStringExtra("id")))
                "showcase" -> showcase(i.getBooleanExtra("on", true))
                "setup" -> openSetup()
                // Focus a zone (and a tab and card) without remote presses.
                "focus" -> {
                    if (settings.isOpen) closeSettings()
                    if (allApps.isOpen) closeAllApps()
                    val tab = i.getIntExtra("tab", -1)
                    if (tab in 0..1 && tab != shelf.tab) shelf.selectTab(tab)
                    val z = Zone.values().firstOrNull { it.name.equals(i.getStringExtra("zone"), true) } ?: Zone.RAIL
                    val idx = i.getIntExtra("i", 0)
                    h.postDelayed({ setFocus(z, idx) }, if (tab >= 0) 450 else 0)
                }
                "settings" -> if (!settings.isOpen) openSettings(i.getStringExtra("page"))
                "allapps" -> if (!allApps.isOpen) openAllApps()
                "close" -> { if (settings.isOpen) closeSettings(); if (allApps.isOpen) closeAllApps(); setFocus(Zone.RAIL, 0) }
                "musicsound" -> Prefs.musicSound = i.getBooleanExtra("on", true)
                "saver" -> enterSaver()
                "wake" -> wake()
                "place" -> {
                    Prefs.place = Prefs.Place(i.getStringExtra("name") ?: "Port Louis", i.getStringExtra("country") ?: "Mauritius",
                        i.getFloatExtra("lat", -20.16f).toDouble(), i.getFloatExtra("lon", 57.5f).toDouble(), i.getStringExtra("cc") ?: "MU")
                    renderStatus(); refreshWeatherNow()
                }
                "theme" -> { forcedTheme = i.getFloatExtra("t", -1f).takeIf { it in 0f..1f }; applyTheme(true) }
                "film" -> carousel.debugFilm(i.getStringExtra("name"))
                "iconstyle" -> { Prefs.iconStyle = i.getIntExtra("s", 0).coerceIn(0, 2); rebuildRail(true) }
                "tour" -> runTour()
                else -> android.util.Log.w("Tour", "unknown command $cmd")
            }
        }
    }

    /** Screenshot mode for the project page: see [Apps.showcase]. */
    private fun showcase(on: Boolean) {
        Apps.showcase = on
        rebuildRail(true); shelf.reload(true); renderStatus(); refreshWeatherNow(); renderNow()
        if (settings.isOpen) closeSettings()
    }

    /** Diagnostics: run the audio analyser for 8 s whatever is on screen and log what the box hands us. */
    private fun vizProbe() {
        AudioViz.start(this)
        for (k in 1..8) h.postDelayed({
            android.util.Log.i("AudioViz", "probe live=${AudioViz.live} " + (0 until AudioViz.BANDS).joinToString(" ") { "%.2f".format(AudioViz.level(it)) })
            if (k == 8 && !resumed) AudioViz.stop()
        }, k * 1000L)
    }

    /** Diagnostics: show the visualiser for 10 s even with nothing playing (synthetic motion), for looks and frame timing. */
    private fun vizDemo() {
        npViz.color = brand("com.spotify.tv.android"); npViz.animate().alpha(1f).setDuration(240).start()
        npGlyph.animate().alpha(0f).setDuration(240).start(); npViz.setPlaying(true)
        h.postDelayed({ npControls() }, 10_000)
    }

    /**
     * Diagnostics: a made-up Spotify track (15 s unless --ei secs), to check Now playing without logging in to anything.
     * --es title/artist name it (e.g. after a Continue watching card), --ez playing false shows it paused.
     */
    private fun npDemo(i: Intent) {
        val title = i.getStringExtra("title") ?: "Weightless"; val artist = i.getStringExtra("artist") ?: "Marconi Union"
        val art = ContinueStore.items.firstOrNull { it.title == title }?.art?.let { BitmapFactory.decodeFile(it) }
            ?: ContinueStore.items.firstNotNullOfOrNull { it.art }?.let { BitmapFactory.decodeFile(it) }
        MediaHub.debugFake(MediaHub.Now("com.spotify.tv.android", title, artist, art, i.getBooleanExtra("playing", true), 95_000, 467_000,
            android.os.SystemClock.elapsedRealtime(), 1f))
        h.removeCallbacks(npDemoEnd); h.postDelayed(npDemoEnd, i.getIntExtra("secs", 15) * 1000L)
    }
    private val npDemoEnd = Runnable { MediaHub.debugFake(null) }

    /** Benchmark: scripted focus moves from inside the app (adb input spawns a VM per key and skews timing). */
    private fun runTour() {
        val keys = intArrayOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_LEFT)
        keys.forEachIndexed { i, k -> h.postDelayed({ move(k) }, 600L + i * 350L) }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(tourReceiver) }
        carousel.release()
        super.onDestroy()
    }

    private var swallowUp = -1
    private val handled = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BACK)

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        if (setup.isOpen) return setup.key(e)
        if (settings.isOpen) {
            if (e.action == KeyEvent.ACTION_DOWN && settings.key(e) { closeSettings() }) { swallowUp = code; return true }
            if (e.action == KeyEvent.ACTION_UP && code == swallowUp) { swallowUp = -1; return true }
            return super.dispatchKeyEvent(e)
        }
        if (e.action != KeyEvent.ACTION_DOWN) return if (code in handled) true else super.dispatchKeyEvent(e)
        resetIdle()
        if (inSaver) { wake(); return true }
        if (allApps.isOpen) {
            if (code == KeyEvent.KEYCODE_BACK) { closeAllApps(); return true }
            return allApps.key(code) || super.dispatchKeyEvent(e)
        }
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> { keyRepeat = e.repeatCount > 0; move(code); keyRepeat = false; return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> { if (e.repeatCount == 0) activate(); return true }
            KeyEvent.KEYCODE_BACK -> { if (zone != Zone.RAIL || railIdx != 0) setFocus(Zone.RAIL, 0); return true }
            KeyEvent.KEYCODE_TV, KeyEvent.KEYCODE_GUIDE -> { if (LiveTv.open(this)) return true }
        }
        return super.dispatchKeyEvent(e)
    }

    // ---------- helpers ----------

    private fun resume(item: ContinueStore.Item, from: View) {
        // YouTube honours &t=: carry on from where its preview got to (if it played since), else a few seconds before
        // where it stopped.
        val link = item.link?.let { l ->
            val yt = item.pkg == "com.google.android.youtube.tv"
            val previewed = if (yt) ytId(l)?.let { Watched.resume(it, item.ts) } ?: -1 else -1
            when {
                previewed >= 0 -> "${stripT(l)}&t=${previewed}s"
                yt && item.pos > 15_000 && (item.dur <= 0 || item.pos < item.dur - 10_000) -> "${stripT(l)}&t=${item.pos / 1000 - 3}s"
                else -> l
            }
        }
        if (link != null) {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(item.pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val opts = ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.width, from.height).toBundle()
            if (runCatching { startActivity(i, opts) }.isSuccess) return
        }
        launch(item.pkg, from)
    }

    private fun stripT(link: String) = link.replace(Regex("""[?&]t=\d+s?"""), "")

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Short of memory: the preview keeps only the player it's showing (in the background, the 2-minute release
        // already frees them).
        if ((level == TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_RUNNING_CRITICAL || level >= TRIM_MEMORY_MODERATE) && ::preview.isInitialized) {
            android.util.Log.i("Preview", "memory low ($level): keeping only the player in use"); preview.trim()
        }
    }

    private fun ytId(link: String) = Regex("""[?&]v=([A-Za-z0-9_-]{11})""").find(link)?.groupValues?.get(1)
        ?: Regex("""youtu\.be/([A-Za-z0-9_-]{11})""").find(link)?.groupValues?.get(1)

    private fun installed(entry: String) = if (Inputs.isInput(entry)) Inputs.find(this, entry) != null else Apps.installed(entry)

    private fun launch(pkg: String, from: View?) {
        if (Inputs.isInput(pkg)) { Inputs.open(this, pkg.removePrefix(Inputs.PREFIX)); return }
        val i = packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg) ?: return
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opts = from?.let { ActivityOptions.makeScaleUpAnimation(it, 0, 0, it.width, it.height).toBundle() }
        runCatching { startActivity(i, opts) }
    }

    /** Full-bleed app icon: adaptive icons are drawn layer by layer so they fill the tile, not a system mask. */
    /** App icons are drawn once and kept as PNGs: loading another app's resources is slow on this box. */
    private val glassIcons = HashMap<String, Bitmap>()
    private fun iconBitmap(pkg: String, size: Int, style: Int = Prefs.iconStyle): Bitmap? {
        if (style == 2) return rawIcon(pkg, size)
        // Glass marks are read from a big copy of the icon, so their edges stay smooth.
        val tinted = style == 1
        return glassIcons.getOrPut("$pkg-$size-$tinted") { rawIcon(pkg, 256)?.let { runCatching { GlassIcon.make(it, size, tinted) }.getOrNull() } ?: rawIcon(pkg, size) ?: return null }
    }

    private fun rawIcon(pkg: String, size: Int): Bitmap? {
        if (Inputs.isInput(pkg)) return Inputs.find(this, pkg)?.let { Inputs.tile(it.label, size) }
        Apps.showcaseIcon(pkg)?.let { f -> BitmapFactory.decodeFile(f.path)?.let { return Bitmap.createScaledBitmap(it, size, size, true) } }
        val stamp = runCatching { packageManager.getPackageInfo(pkg, 0).lastUpdateTime }.getOrNull() ?: return null
        val f = File(cacheDir, "icons/$pkg-$size-$stamp.png")
        if (f.exists()) BitmapFactory.decodeFile(f.path)?.let { return it }
        val b = drawIcon(pkg, size) ?: return null
        runCatching {
            f.parentFile?.apply { mkdirs(); listFiles { x -> x.name.startsWith("$pkg-$size-") }?.forEach { it.delete() } }
            f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return b
    }

    private fun drawIcon(pkg: String, size: Int): Bitmap? = runCatching {
        val d = packageManager.getApplicationIcon(pkg)
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        if (d is AdaptiveIconDrawable) {
            val o = size / 4
            d.background?.apply { setBounds(-o, -o, size + o, size + o); draw(c) }
            d.foreground?.apply { setBounds(-o, -o, size + o, size + o); draw(c) }
        } else {
            c.drawColor(Color.BLACK); d.setBounds(0, 0, size, size); d.draw(c)
        }
        b
    }.getOrNull()

    companion object {
        val APP_PKG = mapOf("netflix" to "com.netflix.ninja", "youtube" to "com.google.android.youtube.tv", "spotify" to "com.spotify.tv.android")
    }
}
