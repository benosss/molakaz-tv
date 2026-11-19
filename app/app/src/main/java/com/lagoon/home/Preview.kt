package com.lagoon.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * What a shelf card can preview: a YouTube video, a title whose trailer we look up on YouTube, or a Spotify [track]
 * (see [MusicPreview]; [cover] and [tint] are the card's own picture and colours, for a scene before the big cover is
 * in). [startSec] is where the card's own record says to start, written at [since] (ms); a later preview of the same
 * video wins over it.
 */
class PreviewSrc(val videoId: String? = null, val startSec: Int = 0, val trailerOf: String? = null, val since: Long = 0L,
                 val track: String? = null, val cover: android.graphics.Bitmap? = null, val tint: IntArray? = null,
                 val title: String? = null, val sub: String? = null)

/** The dimming that keeps Home's text readable over a preview; lives in Home's own layer (see [Preview]). */
fun previewScrim(ctx: Context) = View(ctx).apply {
    background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(argbA(0.45f, 0x000000), argbA(0.05f, 0x000000), argbA(0.10f, 0x000000), argbA(0.70f, 0x000000)))
    alpha = 0f
}

/**
 * How far each previewed video got. A YouTube card shows it as its progress and opens YouTube right there; a trailer
 * picks up where its last preview stopped. Kept in a small file so it lasts across restarts.
 */
object Watched {
    class Mark(val t: Float, val dur: Float, val at: Long)
    private val marks = LinkedHashMap<String, Mark>()
    private var file: File? = null
    private var dirty = false
    private const val KEEP = 300

    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.filesDir, "previewed.json"); file = f
        runCatching {
            val o = JSONObject(f.readText())
            o.keys().forEach { k -> val a = o.getJSONArray(k); marks[k] = Mark(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getLong(2)) }
        }
    }

    operator fun get(id: String): Mark? = marks[id]

    fun put(id: String, t: Float, dur: Float) {
        marks.remove(id); marks[id] = Mark(t, dur, System.currentTimeMillis()); dirty = true
        while (marks.size > KEEP) marks.remove(marks.keys.first())
    }

    fun save() {
        if (!dirty) return
        dirty = false
        val o = JSONObject()
        marks.forEach { (k, m) -> o.put(k, JSONArray().put(m.t.toDouble()).put(m.dur.toDouble()).put(m.at)) }
        runCatching { file?.writeText(o.toString()) }
    }

    private fun atEnd(m: Mark) = m.dur > 0f && m.t > m.dur - 10f

    /** Where to open [id] in YouTube: the preview's place, if newer than [since] and not at the very start or end; else -1. */
    fun resume(id: String, since: Long = 0L): Int {
        val m = marks[id] ?: return -1
        return if (m.at <= since || m.t < 3f || atEnd(m)) -1 else m.t.toInt()
    }

    /** The preview's place as a fraction of the video, if newer than [since]; else null. */
    fun fraction(id: String, since: Long = 0L): Float? {
        val m = marks[id] ?: return null
        return if (m.at <= since || m.dur <= 0f || m.t < 1f) null else (m.t / m.dur).coerceIn(0f, 1f)
    }

    /** Where a preview of [id] starts: where the last one stopped (back to the start once it reached the end). */
    fun startFor(id: String, src: PreviewSrc): Int {
        val m = marks[id] ?: return src.startSec
        if (m.at <= src.since) return src.startSec
        return if (atEnd(m)) 0 else m.t.toInt()
    }
}

/**
 * Previews on focus, like Netflix and Apple TV: rest on a card and a muted preview fades in behind Home.
 * YouTube cards play their own video (Continue watching resumes where you stopped, and a card's progress line moves
 * with the preview, so opening it carries on from there); film apps' cards (Netflix, Prime Video…) play the title's trailer, found on
 * YouTube by name and remembered.
 *
 * How it stays fast and sharp on this box:
 * - YouTube players loaded as top-level embed pages, so we can reach the video: the picture is cover-fitted (and bars
 *   burned into the video are cropped), the player chrome is hidden, and the stream is held at 1080p.
 * - Nothing shows until the video is playing in HD (it starts blurry otherwise); a ring on the card says it's coming.
 * - Two players. The cards focus is likely to land on next are pre-rolled into the one not in use: loaded, buffered in
 *   HD and paused a frame in, so when focus lands there the preview only has to start and fade in. While a preview
 *   plays, the next card in the direction you're moving gets ready; leaving a card pauses its player, so coming back
 *   is instant too. Pre-rolls wait while a preview is starting (this CPU is easily swamped) and run one at a time.
 * - Trailer look-ups for the whole row run in the background as soon as the row loads.
 * While a preview shows, the scenery behind it pauses.
 */
@SuppressLint("SetJavaScriptEnabled")
class Preview(private val ctx: Context, private val host: FrameLayout,
              private val onShown: (Boolean) -> Unit, private val onLoading: (Boolean) -> Unit,
              private val onActive: (Boolean) -> Unit, private val onProgress: (PreviewSrc, Float, Float) -> Unit) {
    companion object {
        const val SHOW_AFTER = 800L       // never sooner than this after focus lands (TV previews start after ~1 s)
        const val DEBOUNCE = 200L         // card to card: let the focus settle before switching the player
        const val SPINNER_AFTER = 250L    // no ring for a preview that's about to show anyway
        const val GIVE_UP = 24000L        // still no HD picture by then: stop the ring (it still fades in if HD arrives)
        const val SETTLE = 2000L          // a preview that just appeared has the box to itself this long before a pre-roll
        const val CUE_LIMIT = 25000L      // a pre-roll that hasn't reached HD by then is paused, and the next one goes
        private const val SLOTS = 2
        private const val TAG = "Preview"
        private const val EMBED = "https://www.youtube.com/embed/"
    }
    private val h = Handler(Looper.getMainLooper())
    // VISIBLE at alpha 0 rather than INVISIBLE: a hidden WebView counts as a background tab and stops the video.
    private val layer = FrameLayout(ctx).apply { alpha = 0f; setBackgroundColor(Color.BLACK) }
    var shown = false
        private set
    private var shownAt = 0L

    /** On/off from Settings. Off: nothing loads, and the players are freed. */
    var enabled = Prefs.previews
        set(v) {
            if (field == v) return
            field = v
            if (!v) { focus(null); queue.clear(); slots.forEach { drop(it) } }
        }

    /** One YouTube player (a WebView) and what's in it. */
    private inner class Slot(val n: Int) {
        var web: WebView? = null
        var pageReady = false
        var id: String? = null
        var start = 0
        var cue = false          // loading as a pre-roll: pause once it plays in HD
        var good = false         // has played in HD (and may now be paused there)
        var playing = false      // told to play (or autoplaying a load)
        var loadAt = 0L
        var used = 0L            // when focus last wanted it (the least recently used one is reloaded first)
        val giveUp = Runnable {
            if (good) return@Runnable
            Log.i(TAG, "no HD picture for $id in time: keeping the scenery for now")
            if (wantedId == id) onLoading(false)
            pauseIdle()
        }
    }
    private val slots = List(SLOTS) { Slot(it) }
    private var front: Slot? = null                   // the one drawn (on top, opaque) when the layer shows
    private val failed = HashSet<String>()            // refuses embedding

    // What the user is on.
    private var wanted: PreviewSrc? = null
    private var wantedId: String? = null
    private var focusAt = 0L
    private var stopped = false
    private var savedAt = 0L

    // Pre-rolls.
    private val queue = ArrayList<PreviewSrc>()
    private val keep = HashSet<String>()              // pre-rolled this round: not to be pushed out by the next in line
    private var prefetching: Slot? = null

    private val trailers = HashMap<String, String>()
    private val searching = HashMap<String, MutableList<(String) -> Unit>>()
    private val cacheFile = File(ctx.filesDir, "trailers.json")
    private val debug = File(ctx.getExternalFilesDir(null), "debug").exists()

    init {
        host.addView(layer, FrameLayout.LayoutParams(MATCH, MATCH))
        // No scrim here: anything drawn over the WebView costs ~20 ms a frame on this GPU. Home draws it in its own
        // (cached) layer instead, see [previewScrim].
        runCatching { JSONObject(cacheFile.readText()).let { o -> o.keys().forEach { k -> trailers[k] = o.getString(k) } } }
        Watched.init(ctx)
        if (debug) WebView.setWebContentsDebuggingEnabled(true)
    }

    private fun now() = SystemClock.uptimeMillis()

    private fun ensureWeb(s: Slot): WebView = s.web ?: WebView(ctx).apply {
        settings.javaScriptEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.domStorageEnabled = true
        setBackgroundColor(Color.BLACK)
        isFocusable = false; isFocusableInTouchMode = false
        webChromeClient = WebChromeClient()
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView, url: String) { if (url.startsWith(EMBED)) v.evaluateJavascript(HOOK, null) }
            // The player never navigates anywhere (end screens, logo links).
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = true
            // The pages' process can be killed to free memory for other apps: start afresh next time instead of crashing.
            override fun onRenderProcessGone(v: WebView, d: RenderProcessGoneDetail): Boolean {
                Log.w(TAG, "player process gone"); h.post { slots.forEach { drop(it) } }; return true
            }
        }
        addJavascriptInterface(Bridge(s), "Lakaz")
        alpha = if (front === s) 1f else 0f
        layer.addView(this, 0, FrameLayout.LayoutParams(MATCH, MATCH))
        s.web = this
    }

    private fun drop(s: Slot) {
        val w = s.web ?: return
        s.web = null; s.pageReady = false; s.id = null; s.good = false; s.cue = false; s.playing = false
        h.removeCallbacks(s.giveUp)
        if (s === prefetching) { prefetching = null; h.removeCallbacks(cueTimeout) }
        if (s === front) { if (shown) hide(); front = null }
        layer.removeView(w); runCatching { w.destroy() }
        updateActive()
    }

    private fun slotWith(id: String?) = if (id == null) null else slots.firstOrNull { it.web != null && it.id == id }

    // ---------- focus ----------

    /** A card got focus (null = focus left the cards). */
    fun focus(src: PreviewSrc?) {
        h.removeCallbacks(reveal); h.removeCallbacks(startLoad); h.removeCallbacks(spin)
        val fromCard = wanted != null
        wanted = src; wantedId = null; focusAt = now()
        if (shown) hide()
        onLoading(false)
        if (src == null || !enabled) { pauseIdle(); return }
        resolve(src) { id ->
            if (wanted !== src || id in failed) return@resolve
            wantedId = id
            val s = slotWith(id)
            if (s != null) {
                s.used = now()
                // Wanted now, not just pre-rolled: the next pre-roll waits until this one has shown.
                if (s === prefetching) { prefetching = null; h.removeCallbacks(cueTimeout) }
                pauseIdle()
                if (s.good) scheduleReveal()                                // played in HD before: resumes as it fades in
                else { play(s); h.postDelayed(spin, SPINNER_AFTER) }        // pre-rolled or loading: play to the HD check
            } else {
                pauseIdle()
                h.postDelayed(startLoad, if (fromCard) DEBOUNCE else 0)
                h.postDelayed(spin, SPINNER_AFTER)
            }
        }
    }

    /** Pre-roll the cards focus is likely to land on next, most likely first, into players nothing wanted is in. */
    fun prepare(list: List<PreviewSrc?>) {
        if (!enabled) return
        queue.clear(); list.filterNotNullTo(queue); keep.clear()
        // A pre-roll for cards that are no longer likely (another tab, focus moved on) gives way at once.
        prefetching?.let { s ->
            val ids = queue.mapNotNull { it.videoId ?: it.trailerOf?.let { t -> trailers[t] } }
            if (s.id !in ids) { prefetching = null; h.removeCallbacks(cueTimeout); if (s.id != wantedId) pause(s); updateActive() }
        }
        h.removeCallbacks(pump); h.post(pump)
    }
    fun prepare(src: PreviewSrc?) = prepare(listOf(src))

    private val pump = Runnable { nextPrefetch() }

    private fun nextPrefetch() {
        if (prefetching != null || queue.isEmpty() || !enabled || stopped) return
        if (!settled()) { h.removeCallbacks(pump); h.postDelayed(pump, 1000); return }
        val src = queue.removeAt(0)
        resolve(src) { id ->
            if (id in failed || slotWith(id) != null) { keep += id; h.post(pump); return@resolve }
            if (!settled() || prefetching != null) { queue.add(0, src); h.removeCallbacks(pump); h.postDelayed(pump, 1000); return@resolve }
            val s = spare() ?: return@resolve
            keep += id
            prefetching = s
            h.removeCallbacks(cueTimeout); h.postDelayed(cueTimeout, CUE_LIMIT)
            load(s, id, Watched.startFor(id, src), cue = true)
        }
    }

    /** True when a pre-roll won't slow down the preview the user is waiting for (or watching start). */
    private fun settled(): Boolean {
        if (wanted == null) return true
        val id = wantedId ?: return wanted?.trailerOf?.let { it !in searching } ?: true
        if (id in failed) return true
        val s = slotWith(id) ?: return false
        return if (s.good) shown && now() - shownAt >= SETTLE else now() - maxOf(s.loadAt, focusAt) > GIVE_UP
    }

    /** A player for a pre-roll: never the one with the wanted video or one pre-rolled this round. Ready pages first. */
    private fun spare(): Slot? = slots.filter { it.id == null || (it.id != wantedId && it.id !in keep) }
        .minByOrNull { (if (it.web != null && it.pageReady) 0L else 1L shl 50) + (if (it.id == null) 0L else it.used + 1) }

    /** A player for the wanted video: a ready page if there is one (much faster than a new one), least recently used. */
    private fun pick(): Slot = slots.minBy { (if (it.web != null && it.pageReady) 0L else 1L shl 50) + (if (it.id == null) 0L else it.used + 1) }

    private val cueTimeout = Runnable {
        val s = prefetching ?: return@Runnable
        prefetching = null
        Log.i(TAG, "pre-roll of ${s.id} took too long: paused")
        if (s.id != wantedId) pause(s)
        updateActive()
        h.post(pump)
    }

    /** Look up the trailers for a whole row ahead of time (one at a time, remembered). */
    fun warmTrailers(titles: List<String>) {
        if (!enabled) return
        val todo = titles.distinct().filter { it !in trailers && it !in searching }
        if (todo.isEmpty()) return
        thread(name = "trailers") {
            for (t in todo) {
                if (t in trailers) continue
                val id = ContinueStore.youtubeSearch("$t official trailer") ?: continue
                h.post { trailers[t] = id; saveTrailers() }
            }
        }
    }

    /** Home went to the background: pause, keep what's loaded. */
    fun stop() {
        h.removeCallbacks(reveal); h.removeCallbacks(startLoad); h.removeCallbacks(spin); h.removeCallbacks(pump)
        queue.clear(); wanted = null; wantedId = null; stopped = true
        prefetching?.let { prefetching = null; h.removeCallbacks(cueTimeout); pause(it) }
        if (shown) hide() else pauseIdle()
        onLoading(false)
        Watched.save()
        slots.forEach { it.web?.onPause() }
        // Another app is in front: give its memory back after a while (this box has little to spare). Home builds the
        // players again and pre-rolls the likely cards when it comes back.
        h.removeCallbacks(release); h.postDelayed(release, 120_000)
    }

    fun start() { stopped = false; h.removeCallbacks(release); slots.forEach { it.web?.onResume() } }
    private val release = Runnable { if (slots.any { it.web != null }) { Log.i(TAG, "releasing the players while Home is in the background"); slots.forEach { drop(it) } } }

    /** Memory is short: keep only the player with the wanted video. */
    fun trim() = slots.filter { it.id == null || it.id != wantedId }.forEach { drop(it) }

    /** Create a player early (the first load is slow on this box), with the likely first card in it if there is one. */
    fun warmUp(first: PreviewSrc?) { if (!enabled) return; if (first != null) prepare(first) else ensureWeb(pick()) }

    // ---------- players ----------

    private val startLoad = Runnable {
        val id = wantedId ?: return@Runnable; val src = wanted ?: return@Runnable
        val s = slotWith(id) ?: pick()
        if (s === prefetching) { prefetching = null; h.removeCallbacks(cueTimeout) }
        load(s, id, Watched.startFor(id, src), cue = false)
    }
    private val spin = Runnable { if (!shown && wantedId != null && wantedId !in failed) onLoading(true) }

    private fun load(s: Slot, id: String, start: Int, cue: Boolean) {
        s.id = id; s.start = start; s.cue = cue; s.good = false; s.playing = true; s.loadAt = now()
        s.used = if (cue) 0L else now()
        h.removeCallbacks(s.giveUp); if (!cue) h.postDelayed(s.giveUp, GIVE_UP)
        val w = ensureWeb(s)
        if (s.pageReady) w.evaluateJavascript("__lk.load('$id',$start,$cue)", null)
        else w.loadUrl("$EMBED$id?autoplay=1&mute=1&controls=0&playsinline=1&rel=0&iv_load_policy=3&cc_load_policy=0&disablekb=1&fs=0&enablejsapi=1&start=$start",
            mapOf("Referer" to "https://lakaz.home/"))
        updateActive()
        Log.i(TAG, "load $id from ${start}s in player ${s.n}" + if (cue) " (pre-roll)" else "")
    }

    private fun play(s: Slot) {
        s.playing = true; s.cue = false
        s.web?.evaluateJavascript("window.__lk&&__lk.play()", null)
        updateActive()
    }

    private fun pause(s: Slot) {
        if (!s.playing) return
        s.playing = false
        s.web?.evaluateJavascript("window.__lk&&__lk.pause()", null)
    }

    /** Pause every player but the wanted one and a pre-roll in progress (the shown one keeps going while it fades out). */
    private fun pauseIdle() {
        val fading = !shown && layer.alpha > 0.01f
        for (s in slots) if (s.id != wantedId && s !== prefetching && !(fading && s === front)) pause(s)
        updateActive()
    }

    /**
     * While a player runs, the WebView redraws Home's window for every video frame. Re-drawing the whole interface
     * each time costs ~100 ms here (10 fps), so Home caches it as one layer meanwhile.
     */
    private var isActive = false
    /** A player is loading or playing (Home's speed test waits for it). */
    val streaming get() = isActive
    private fun updateActive() {
        h.removeCallbacks(idleOff)
        if (shown || slots.any { it.web != null && it.playing }) { if (!isActive) { isActive = true; onActive(true) } }
        else if (isActive) h.postDelayed(idleOff, 600)   // let the last frames settle first
    }
    private val idleOff = Runnable { if (isActive && !shown && slots.none { it.web != null && it.playing }) { isActive = false; onActive(false) } }

    private fun scheduleReveal() {
        h.removeCallbacks(reveal)
        h.postDelayed(reveal, (focusAt + SHOW_AFTER - now()).coerceAtLeast(0L))
    }

    private val reveal = Runnable {
        val s = slotWith(wantedId) ?: return@Runnable
        if (!s.good || shown) return@Runnable
        shown = true; shownAt = now()
        if (front !== s) {
            front = s
            for (o in slots) o.web?.alpha = if (o === s) 1f else 0f
            s.web?.bringToFront()
        }
        play(s)
        h.removeCallbacks(spin); onLoading(false)
        layer.animate().cancel()
        layer.animate().alpha(1f).setDuration(if (Motion.on) 650 else 0).setInterpolator(Ease.out).start()
        onShown(true)
        Log.i(TAG, "showing ${s.id} ${now() - focusAt} ms after focus (player ${s.n})")
    }

    private fun hide() {
        if (shown) { onShown(false); Watched.save() }
        shown = false
        layer.animate().cancel()
        layer.animate().alpha(0f).setDuration(if (Motion.on) 360 else 0).setInterpolator(Ease.exit)
            .withEndAction { if (!shown) pauseIdle() }.start()
    }

    // ---------- trailers ----------

    private fun resolve(src: PreviewSrc, cb: (String) -> Unit) {
        src.videoId?.let { cb(it); return }
        val t = src.trailerOf ?: return
        trailers[t]?.let { cb(it); return }
        searching[t]?.let { it += cb; return }
        searching[t] = mutableListOf(cb)
        thread(name = "trailer") {
            val id = ContinueStore.youtubeSearch("$t official trailer")
            h.post {
                val cbs = searching.remove(t) ?: return@post
                if (id == null) { Log.i(TAG, "no trailer for $t"); return@post }
                trailers[t] = id; saveTrailers()
                cbs.forEach { it(id) }
            }
        }
    }

    private fun saveTrailers() { runCatching { cacheFile.writeText(JSONObject(trailers as Map<*, *>).toString()) } }

    private inner class Bridge(private val s: Slot) {
        @JavascriptInterface fun ready() { h.post {
            if (s.web == null) return@post
            s.pageReady = true
            Log.i(TAG, "player ${s.n} ready")
            s.id?.let { s.web?.evaluateJavascript("__lk.load('$it',${s.start},${s.cue})", null) }
        } }
        @JavascriptInterface fun status(m: String) { if (debug) Log.i(TAG, "${s.n}: $m") }
        /** The video is playing in HD (bars, if any, cropped). */
        @JavascriptInterface fun good(id: String, height: Int) { h.post {
            if (id != s.id || s.good) return@post
            s.good = true; h.removeCallbacks(s.giveUp)
            Log.i(TAG, "$id ready at ${height}p in ${now() - s.loadAt} ms (player ${s.n})" + if (wantedId == id) "" else ", paused")
            if (wantedId == id) scheduleReveal() else pauseIdle()
        } }
        /** A pre-roll reached HD and paused itself a frame in. */
        @JavascriptInterface fun cued(id: String) { h.post {
            if (id != s.id) return@post
            Log.i(TAG, "$id pre-rolled in ${now() - s.loadAt} ms (player ${s.n})")
            if (wantedId == id) play(s)      // focus got there first: carry on to the HD check
            else s.playing = false
            if (s === prefetching) { prefetching = null; h.removeCallbacks(cueTimeout); h.postDelayed(pump, 300) }
            updateActive()
        } }
        /** Where the shown video is (s), every quarter second while it plays. */
        @JavascriptInterface fun time(id: String, t: Double, dur: Double) { h.post {
            if (id != s.id || !shown || s !== front || id != wantedId || dur <= 0.0) return@post
            Watched.put(id, t.toFloat(), dur.toFloat())
            if (now() - savedAt > 10_000) { savedAt = now(); Watched.save() }   // survives the app being killed
            wanted?.let { onProgress(it, t.toFloat(), dur.toFloat()) }
        } }
        /** 101/150: the owner doesn't allow embedding (common for music videos): no preview for this card. */
        @JavascriptInterface fun error(id: String, code: Int) { h.post {
            Log.w(TAG, "player ${s.n} error $code for $id")
            failed += id
            if (id == s.id) { s.good = false; s.playing = false; h.removeCallbacks(s.giveUp) }
            if (s === prefetching) { prefetching = null; h.removeCallbacks(cueTimeout); h.post(pump) }
            if (wantedId == id) { h.removeCallbacks(spin); onLoading(false); if (shown) hide() }
            updateActive()
        } }
        @JavascriptInterface fun bars(id: String, inset: String) { Log.i(TAG, "$id has bars in the picture: cropped $inset") }
        /** Played to the end: fade back to the scenery; next time it loads again (from the start). */
        @JavascriptInterface fun ended(id: String) { h.post {
            if (id != s.id) return@post
            val wasShown = shown && s === front
            s.id = null; s.good = false; s.playing = false
            if (wasShown) hide()
            updateActive()
        } }
    }
}

/**
 * Runs inside YouTube's embed page. Hides the player's own chrome, fills the screen with the picture (cover, plus
 * cropping bars burned into the video), holds the stream at 1080p, tells us when the picture is really HD, and where
 * the video is while it plays. A pre-roll (cue) pauses itself as soon as it plays in HD.
 */
private const val HOOK = """
(function(){
 if (window.__lk) { return; }
 var lk = window.__lk = {want:null, last:''};
 // Only the picture shows: every other element of the player (title, channel, buttons, captions, "More videos",
 // end screens; the mobile and desktop players name them differently) is made invisible, and the video opts back in.
 var css = 'html,body{background:#000!important;overflow:hidden!important;margin:0}' +
  'body *{visibility:hidden!important}' +
  'video{visibility:visible!important}' +
  '.html5-video-player,#player,#movie_player{background:#000!important}' +
  '.html5-video-container{position:absolute!important;left:0!important;top:0!important;width:100%!important;height:100%!important}' +
  'video.html5-main-video{position:absolute!important;left:0!important;top:0!important;width:100%!important;height:100%!important;object-fit:cover!important;transition:object-view-box .8s ease}';
 var st = document.createElement('style'); st.textContent = css; document.documentElement.appendChild(st);
 var H = {hd2160:2160, hd1440:1440, hd1080:1080, hd720:720, large:480, medium:360, small:240, tiny:144};
 function P(){ return document.getElementById('movie_player'); }
 function V(){ return document.querySelector('video.html5-main-video') || document.querySelector('video'); }
 function hd(p){ try { p.setPlaybackQualityRange('hd1080', 'hd1080'); } catch(e) {} }
 lk.load = function(id, s, cue){
  var p = P(); if (!p || !p.loadVideoById) return false;
  lk.want = {id:id, s:s, t0:Date.now(), good:false, f0:-1, cue:!!cue};
  var v = V(); if (v) v.style.removeProperty('object-view-box');
  p.mute(); hd(p);
  var cur = (p.getVideoData && p.getVideoData().video_id) || '', ps = p.getPlayerState();
  if (cur === id && ps !== -1 && ps !== 5) p.playVideo();
  else p.loadVideoById({videoId:id, startSeconds:s, suggestedQuality:'hd1080'});
  return true;
 };
 lk.play = function(){ var w = lk.want; if (w) { w.cue = false; w.f0 = -1; } var p = P(); if (p && p.playVideo) { p.mute(); hd(p); p.playVideo(); } };
 lk.pause = function(){ var p = P(); if (p && p.pauseVideo) p.pauseVideo(); };
 function effH(v){ return Math.max(v.videoHeight, Math.round(v.videoWidth * 9 / 16)); }
 // Letterbox or pillarbox bars burned into the picture: measured a few times while it plays (black or fading
 // frames say nothing, so they're skipped) and cropped once two looks agree, so the preview always fills the screen.
 function measure(v){
  try {
   var W = 128, Hh = 72, c = lk.cv || (lk.cv = document.createElement('canvas')); c.width = W; c.height = Hh;
   var g = c.getContext('2d', {willReadFrequently: true}); g.drawImage(v, 0, 0, W, Hh); var d = g.getImageData(0, 0, W, Hh).data;
   var sum = 0, n = 0;
   for (var yy = 24; yy < 48; yy += 2) for (var xx = 40; xx < 88; xx += 2) { var j = (yy * W + xx) * 4; sum += d[j] + d[j+1] + d[j+2]; n++; }
   if (sum / n < 75) return null;
   function dark(x0, y0, dx, dy, k){ for (var q = 0; q < k; q++) { var i = ((y0 + dy * q) * W + (x0 + dx * q)) * 4; if (d[i] + d[i+1] + d[i+2] > 54) return false; } return true; }
   var t = 0, b = 0, l = 0, r = 0;
   while (t < 15 && dark(0, t, 1, 0, W)) t++;
   while (b < 15 && dark(0, Hh - 1 - b, 1, 0, W)) b++;
   while (l < 27 && dark(l, 0, 0, 1, Hh)) l++;
   while (r < 27 && dark(W - 1 - r, 0, 0, 1, Hh)) r++;
   if (t >= 15 || b >= 15 || Math.abs(t - b) > 2 || Math.min(t, b) < 2) { t = 0; b = 0; }   // real bars come in pairs
   if (l >= 27 || r >= 27 || Math.abs(l - r) > 2 || Math.min(l, r) < 2) { l = 0; r = 0; }
   return [t ? Math.max(t, b) + 1 : 0, l ? Math.max(l, r) + 1 : 0];
  } catch(e) { return null; }
 }
 function bars(v, w){
  var m = measure(v); w.looks = (w.looks || 0) + 1; if (!m) return;
  if (w.m && Math.abs(w.m[0] - m[0]) <= 1 && Math.abs(w.m[1] - m[1]) <= 1) w.agree = (w.agree || 1) + 1; else { w.m = m; w.agree = 1; }
  if (w.agree === 2 && !w.fitted) {
   w.fitted = 1;
   if (!m[0] && !m[1]) return;
   var y = (m[0] / 72 * 100).toFixed(2), x = (m[1] / 128 * 100).toFixed(2);
   v.style.setProperty('object-view-box', 'inset(' + y + '% ' + x + '% ' + y + '% ' + x + '%)', 'important');
   Lakaz.bars(w.id, y + '% ' + x + '%');
  }
 }
 function tick(){
  var p = P(), v = V(), w = lk.want; if (!p || !p.getPlayerState) return;
  var stt = p.getPlayerState(), id = ((p.getVideoData && p.getVideoData()) || {}).video_id || '';
  var ad = p.classList.contains('ad-showing') || p.classList.contains('ad-interrupting');
  var err = !!document.querySelector('.ytp-error');
  var lv = (p.getAvailableQualityLevels && p.getAvailableQualityLevels()) || [], maxH = 0;
  for (var i = 0; i < lv.length; i++) if ((H[lv[i]] || 0) > maxH) maxH = H[lv[i]];
  var q = v && v.getVideoPlaybackQuality ? v.getVideoPlaybackQuality() : null, fr = q ? q.totalVideoFrames : 0;
  var eh = v ? effH(v) : 0, target = Math.min(1080, maxH || 1080);
  var info = id + ' state=' + stt + ' ' + (v ? v.videoWidth + 'x' + v.videoHeight : '-') + ' max=' + maxH + ' q=' + (p.getPlaybackQuality ? p.getPlaybackQuality() : '') + (ad ? ' AD' : '') + (err ? ' ERR' : '') + (w && w.cue ? ' cue' : '') + ' ' + document.visibilityState + (stt === 1 ? ' @' + Math.floor(Date.now() / 5000) : '');
  if (info !== lk.last) { lk.last = info; Lakaz.status(info + ' t=' + (v ? v.currentTime.toFixed(1) : '-') + ' frames=' + fr + ' dropped=' + (q ? q.droppedVideoFrames : 0)); }
  if (!w || id !== w.id) return;
  if (err && !w.err) { w.err = 1; Lakaz.error(id, 150); return; }
  if (stt === 0 && w.good && !w.end) { w.end = 1; Lakaz.ended(id); }
  if (ad) return;
  var now = Date.now();
  if (w.good && (stt === 1 || stt === 2) && now - (w.lastT || 0) >= 240) {
   var ct = p.getCurrentTime ? p.getCurrentTime() : 0;
   if (w.lt === undefined || Math.abs(ct - w.lt) >= 0.2) { w.lastT = now; w.lt = ct; Lakaz.time(id, ct, p.getDuration ? p.getDuration() : 0); }
  }
  if (stt === 1 && w.good && !w.fitted && (w.looks || 0) < 12 && now - (w.lastLook || 0) > 1100) { w.lastLook = now; bars(v, w); }
  if (w.good) return;
  if (stt === 1 && (eh >= target * 0.95 || (now - w.t0 > 14000 && eh >= 700))) {
   if (w.f0 < 0) w.f0 = fr;
   else if (w.cue) { if (!w.cued && fr - w.f0 >= 2) { w.cued = 1; p.pauseVideo(); Lakaz.cued(id); } }
   else if (fr - w.f0 >= (w.cued ? 2 : 8)) { w.good = true; w.lastLook = now; bars(v, w); Lakaz.good(id, eh); }
  } else if (!w.cued) w.f0 = -1;
 }
 setInterval(tick, 120);
 try { P().addEventListener('onError', function(e){ var w = lk.want; if (w && !w.err) { w.err = 1; Lakaz.error(w.id, (e && e.data !== undefined) ? e.data : (e | 0)); } }); } catch(e) {}
 (function wait(){ var p = P(); if (p && p.loadVideoById) Lakaz.ready(); else setTimeout(wait, 100); })();
})();
"""
