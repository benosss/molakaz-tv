package com.lagoon.home

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Songs for previews, without an account or a key. A Spotify track id (Fresh picks) is looked up on Spotify's public
 * embed page: the 30-second clip Spotify offers, its 640 px cover and the colour Spotify's own player puts behind it.
 * A song known only by title and artist (Spotify's TV app reports no track id to Continue watching) is found with
 * Apple's iTunes Search API instead, which has a 30-second clip and a big cover for nearly every song. The clips and
 * covers of a row's songs are fetched one by one in the background as soon as the row loads, so a preview starts from
 * files already on the box.
 */
object SpotifyTracks {
    class Info(val clip: String?, val art: String?, val color: Int)

    private const val TAG = "SpotifyTracks"
    private const val KEEP = 40
    private const val UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36"
    private val h = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val info = LinkedHashMap<String, Info>()                 // guarded by lock; oldest first
    private val waiting = HashMap<String, MutableList<(Info?) -> Unit>>()   // main thread only
    private lateinit var dir: File
    private lateinit var store: File
    // The row's tracks are fetched one at a time at low priority; the card that has focus gets its own lane.
    private val slow = Executors.newSingleThreadExecutor { r ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "spotify-warm")
    }
    private val fast = Executors.newSingleThreadExecutor { r -> Thread(r, "spotify-now") }

    fun init(ctx: Context) {
        if (::dir.isInitialized) return
        dir = File(ctx.cacheDir, "music").apply { mkdirs() }
        store = File(ctx.filesDir, "spotify.json")
        runCatching {
            val o = JSONObject(store.readText())
            synchronized(lock) {
                o.keys().forEach { k -> val a = o.getJSONArray(k); info[k] = Info(a.optString(0).ifBlank { null }, a.optString(1).ifBlank { null }, a.optInt(2)) }
            }
        }
    }

    /** The track id in a Spotify link (spotify:track:… or open.spotify.com/track/…), or null. */
    fun id(link: String?): String? =
        link?.let { Regex("""(?:spotify:track:|open\.spotify\.com/(?:embed/)?track/)([A-Za-z0-9]{22})""").find(it)?.groupValues?.get(1) }

    /** A song known only by its title and artist (found on iTunes). */
    fun searchKey(title: String, artist: String): String? = if (title.isBlank()) null else "q:" + title.trim() + "\u001F" + artist.trim()

    private fun fileName(key: String) = if (!key.startsWith("q:")) key else "q" + java.security.MessageDigest.getInstance("SHA-1")
        .digest(key.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
    fun clipFile(id: String) = File(dir, "${fileName(id)}.clip")     // mp3 (Spotify) or m4a (iTunes): ExoPlayer sniffs it
    fun artFile(id: String) = File(dir, "${fileName(id)}.jpg")
    fun cached(id: String): Info? = synchronized(lock) { info[id] }

    /**
     * A track's details, from memory or looked up now (with its big cover, which the scene needs). [cb] runs on the
     * main thread, with null if Spotify couldn't be reached.
     */
    fun get(id: String, cb: (Info?) -> Unit) {
        cached(id)?.takeIf { artFile(id).exists() || it.art == null }?.let { cb(it); return }
        waiting[id]?.let { it += cb; return }
        waiting[id] = mutableListOf(cb)
        fast.execute {
            val i = cached(id) ?: lookup(id)
            if (i != null) download(i.art, artFile(id))
            h.post { deliver(id, i) }
        }
    }

    /** Fetch the clips and covers of a row's tracks in the background, one after another. */
    fun warm(ids: List<String>) {
        for (id in ids.distinct()) slow.execute {
            val i = cached(id) ?: lookup(id)?.also { x -> h.post { deliver(id, x) } } ?: return@execute
            download(i.art, artFile(id))
            download(i.clip, clipFile(id))
        }
    }

    private fun deliver(id: String, i: Info?) {
        if (i != null) {
            val gone = synchronized(lock) {
                info.remove(id); info[id] = i
                val extra = info.keys.take((info.size - KEEP).coerceAtLeast(0))
                extra.forEach { info.remove(it) }
                extra
            }
            gone.forEach { clipFile(it).delete(); artFile(it).delete() }
            save()
        }
        waiting.remove(id)?.forEach { it(i) }
    }

    private fun save() {
        val o = JSONObject()
        synchronized(lock) { info.forEach { (k, v) -> o.put(k, JSONArray().put(v.clip ?: "").put(v.art ?: "").put(v.color)) } }
        runCatching { store.writeText(o.toString()) }
    }

    private fun lookup(id: String): Info? = if (id.startsWith("q:")) itunes(id) else spotify(id)

    private fun itunes(key: String): Info? = runCatching {
        val parts = key.removePrefix("q:").split('\u001F')
        // "Song (feat. X)" and "[Remastered]" only get in the way of a search.
        val title = parts[0].replace(Regex("""\s*[(\[].*?[)\]]"""), "").trim().ifBlank { parts[0] }
        val lead = parts.getOrElse(1) { "" }.split(',', '&').first().trim()
        val term = java.net.URLEncoder.encode("$title $lead".trim(), "UTF-8")
        val rs = JSONObject(http("https://itunes.apple.com/search?term=$term&media=music&entity=song&limit=8").toString(Charsets.UTF_8)).getJSONArray("results")
        val list = (0 until rs.length()).map { rs.getJSONObject(it) }
        val versions = Regex("karaoke|tribute|instrumental|originally performed", RegexOption.IGNORE_CASE)
        fun ok(r: JSONObject) = r.optString("previewUrl").startsWith("https://") && !versions.containsMatchIn(r.optString("trackName") + " " + r.optString("artistName"))
        val best = list.firstOrNull { ok(it) && it.optString("trackName").startsWith(title, true) && it.optString("artistName").contains(lead, true) }
            ?: list.firstOrNull { ok(it) && it.optString("artistName").contains(lead, true) }
            ?: return@runCatching null
        Info(best.optString("previewUrl"), best.optString("artworkUrl100").replace("100x100bb", "600x600bb").takeIf { it.startsWith("https://") }, 0)
    }.onFailure { Log.w(TAG, "itunes $key: $it") }.getOrNull()

    private fun spotify(id: String): Info? = runCatching {
        val html = http("https://open.spotify.com/embed/track/$id").toString(Charsets.UTF_8)
        val json = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)!!.groupValues[1]
        val e = JSONObject(json).getJSONObject("props").getJSONObject("pageProps").getJSONObject("state")
            .getJSONObject("data").getJSONObject("entity")
        val clip = e.optJSONObject("audioPreview")?.optString("url")?.takeIf { it.startsWith("https://") }
        val vi = e.optJSONObject("visualIdentity")
        val imgs = vi?.optJSONArray("image")
        var art: String? = null; var best = 0
        if (imgs != null) for (k in 0 until imgs.length()) {
            val o = imgs.getJSONObject(k); val w = o.optInt("maxWidth")
            if (w > best) { best = w; art = o.optString("url").takeIf { it.startsWith("https://") } }
        }
        val c = vi?.optJSONObject("backgroundBase")
        Info(clip, art, if (c != null) Color.rgb(c.optInt("red"), c.optInt("green"), c.optInt("blue")) else 0)
    }.onFailure { Log.w(TAG, "lookup $id: $it") }.getOrNull()

    /** Download once: to a temporary name, then renamed, so a half-written file is never used. */
    private fun download(url: String?, f: File) {
        if (url == null || f.length() > 0) return
        runCatching {
            val bytes = http(url)
            val part = File(f.path + ".part"); part.writeBytes(bytes); part.renameTo(f)
        }.onFailure { Log.w(TAG, "download ${f.name}: $it") }
    }

    private fun http(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 12000; c.setRequestProperty("User-Agent", UA)
        try { return c.inputStream.use { it.readBytes() } } finally { c.disconnect() }
    }
}

/**
 * Spotify cards' previews. Rest on one and the screen behind Home becomes the song: its cover, full height on the
 * right, melting into the colour Spotify's own player uses for it, with a slow drift. Then its 30-second clip fades in.
 * Never over music that's already playing (the scene still shows, silent), never with "Music silent" in Settings, and
 * the ambient bed steps aside while a clip plays.
 *
 * Optimistic, so it feels instant:
 * - The scene is built the moment focus lands, from the best cover at hand: the big one if it's in, else the card's own
 *   picture, swapped for the big one in place when it arrives.
 * - The clip player is prepared (from the file fetched ahead, else streamed) before the scene shows, so the sound starts
 *   with the picture. Leaving pauses it; coming back carries on where it was.
 * - Card to card, the next song's scene cross-fades straight over the last, without dipping back to the scenery.
 */
class MusicPreview(private val ctx: Context, host: FrameLayout,
                   private val onShown: (Boolean) -> Unit, private val onActive: (Boolean) -> Unit,
                   private val onProgress: (PreviewSrc, Float, Float) -> Unit) {
    companion object {
        const val SHOW_AFTER = 420L     // focus has to rest this long (no flashes while scrolling past)
        const val FADE_IN = 650L
        const val FADE_OUT = 360L
        const val SOUND_IN = 1500L
        const val SOUND_OUT = 320L
        const val VOLUME = 0.8f
        private const val SCENES = 4
        private const val TAG = "MusicPreview"
    }

    private val h = Handler(Looper.getMainLooper())
    private val views = List(2) { SceneView(ctx).also { v -> v.alpha = 0f; host.addView(v, FrameLayout.LayoutParams(MATCH, MATCH)) } }
    private var front = 0
    var shown = false
        private set
    private var wanted: PreviewSrc? = null
    private var showing: String? = null
    private var focusAt = 0L

    /** On/off with the other previews (Settings). */
    var enabled = Prefs.previews
        set(v) { field = v; if (!v) focus(null) }

    private val scenes = LinkedHashMap<String, Bitmap>()   // composed, by track, newest last
    private val sharp = HashSet<String>()                   // composed from the big cover
    private val composer = Executors.newSingleThreadExecutor { r -> Thread(r, "music-scene") }

    private var player: ExoPlayer? = null
    private var playerId: String? = null
    private var sounding = false
    private val left = HashMap<String, Long>()              // where each clip was when focus left it
    private var vol = 0f
    private var volAnim: ValueAnimator? = null
    private val am = ctx.getSystemService(android.media.AudioManager::class.java)

    init { SpotifyTracks.init(ctx) }

    private fun now() = SystemClock.uptimeMillis()

    /** A card got focus: [src] with a track is a Spotify card (anything else, or null, hides the scene). */
    fun focus(src: PreviewSrc?) {
        h.removeCallbacks(reveal)
        val id = src?.track
        if (id != null && id == wanted?.track && (shown || scenes.containsKey(id))) { wanted = src; if (!shown) scheduleReveal(); return }
        wanted = src; focusAt = now()
        if (id == null || !enabled) { hide(); return }
        soundOut()
        val cached = SpotifyTracks.cached(id)
        if (cached != null) prepareClip(id, cached)
        compose(id, src) { if (wanted === src) scheduleReveal() }
        SpotifyTracks.get(id) { i ->
            if (i == null) return@get
            if (playerId != id) prepareClip(id, i)
            // The big cover came in after the scene was built from the card's picture: build it again, swap in place.
            if (id !in sharp && SpotifyTracks.artFile(id).exists()) compose(id, src, force = true) {
                if (showing == id) scenes[id]?.let { views[front].set(it) }
            }
            if (wanted?.track == id && shown && !sounding) soundIn(id)
        }
    }

    /** Fetch the clips and covers of these tracks ahead (a row just loaded). */
    fun warm(ids: List<String>) { if (enabled && ids.isNotEmpty()) SpotifyTracks.warm(ids) }

    /** Build the scenes of the cards focus is likely to land on next, so they show the moment it does. */
    fun prepare(list: List<PreviewSrc>) {
        if (!enabled) return
        for (src in list.take(2)) { val id = src.track ?: continue; if (!scenes.containsKey(id)) compose(id, src) {} }
    }

    private fun scheduleReveal() { h.removeCallbacks(reveal); h.postDelayed(reveal, (focusAt + SHOW_AFTER - now()).coerceAtLeast(0L)) }

    private val reveal = Runnable {
        val src = wanted ?: return@Runnable; val id = src.track ?: return@Runnable
        val b = scenes[id] ?: run { hide(); return@Runnable }
        val crossFade = shown && showing != id
        if (shown && showing == id) { soundIn(id); return@Runnable }
        val next = if (crossFade) 1 - front else front
        val v = views[next]
        v.set(b); v.bringToFront()
        if (crossFade) views[front].let { old ->
            old.animate().cancel()
            old.animate().alpha(0f).setDuration(FADE_IN).setInterpolator(Ease.inOut).withEndAction { old.stopDrift() }.start()
        }
        front = next; showing = id
        v.animate().cancel()
        v.animate().alpha(1f).setDuration(if (Motion.on) FADE_IN else 0).setInterpolator(Ease.out).start()
        v.startDrift()
        if (!shown) { shown = true; h.removeCallbacks(inactive); onActive(true); onShown(true) }
        Log.i(TAG, "showing $id ${now() - focusAt} ms after focus" + if (id in sharp) "" else " (card cover for now)")
        soundIn(id)
    }

    private fun hide() {
        h.removeCallbacks(reveal)
        soundOut()
        if (!shown) return
        shown = false; showing = null
        views.forEach { v ->
            v.animate().cancel()
            v.animate().alpha(0f).setDuration(if (Motion.on) FADE_OUT else 0).setInterpolator(Ease.exit).withEndAction { v.stopDrift() }.start()
        }
        onShown(false)
        h.postDelayed(inactive, FADE_OUT + 120)
    }
    private val inactive = Runnable { if (!shown) onActive(false) }

    /** Home went to the background: silence now (no fade, another app is taking over), keep what's loaded. */
    fun stop() {
        h.removeCallbacks(reveal); wanted = null
        volAnim?.cancel(); player?.let { p -> playerId?.let { left[it] = p.currentPosition }; p.pause(); p.volume = 0f }
        vol = 0f; sounding = false; h.removeCallbacks(tick); Ambient.hold(false)
        if (shown) { shown = false; showing = null; views.forEach { it.animate().cancel(); it.alpha = 0f; it.stopDrift() }; onShown(false); onActive(false) }
        h.removeCallbacks(release); h.postDelayed(release, 120_000)
    }

    fun start() { h.removeCallbacks(release) }

    /** Memory goes back while another app is in front: the player and the scenes are rebuilt when needed. */
    private val release = Runnable {
        player?.release(); player = null; playerId = null
        scenes.clear(); sharp.clear(); views.forEach { it.set(null) }
    }

    // ---------- the scene ----------

    private fun compose(id: String, src: PreviewSrc, force: Boolean = false, done: () -> Unit) {
        if (!force && scenes.containsKey(id)) { done(); return }
        val color = SpotifyTracks.cached(id)?.color ?: 0
        composer.execute {
            val t0 = SystemClock.uptimeMillis()
            val big = SpotifyTracks.artFile(id).takeIf { it.length() > 0 }?.let { runCatching { BitmapFactory.decodeFile(it.path) }.getOrNull() }
            val b = runCatching { SceneView.compose(big ?: src.cover, color, src.tint) }.getOrNull()
            Log.i(TAG, "scene for $id built in ${SystemClock.uptimeMillis() - t0} ms" + if (big == null) " (card cover)" else "")
            h.post {
                if (b == null) { done(); return@post }     // reveal finds no scene and hides the last one
                scenes.remove(id); scenes[id] = b
                if (big != null) sharp += id else sharp -= id
                while (scenes.size > SCENES) { val k = scenes.keys.first(); if (k == showing) break; scenes.remove(k); sharp -= k }
                done()
            }
        }
    }

    // ---------- the clip ----------

    private fun ensurePlayer(): ExoPlayer = player ?: ExoPlayer.Builder(ctx).build().apply {
        // No audio focus: a preview must never pause what the user is listening to (it stays silent instead).
        setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
        volume = 0f
        addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    playerId?.let { left[it] = 0L }
                    sounding = false; h.removeCallbacks(tick); Ambient.hold(false)
                    wanted?.let { w -> if (w.track == playerId) onProgress(w, 1f, 1f) }
                }
            }
        })
        player = this
    }

    private fun prepareClip(id: String, i: SpotifyTracks.Info) {
        if (playerId == id) return
        val f = SpotifyTracks.clipFile(id)
        val uri = if (f.length() > 0) Uri.fromFile(f) else i.clip?.let { Uri.parse(it) } ?: return
        val p = ensurePlayer()
        playerId?.let { left[it] = p.currentPosition }
        p.playWhenReady = false; p.volume = 0f
        p.setMediaItem(MediaItem.fromUri(uri), left[id] ?: 0L)
        p.prepare()
        playerId = id
    }

    /** Sound is welcome: allowed in Settings and nothing else is playing (our own ambient bed aside). */
    private fun audible(): Boolean {
        if (!Prefs.musicSound) return false
        if (MediaHub.permitted) return !MediaHub.anyPlaying()
        return !(am.isMusicActive && !Ambient.audible)
    }

    private fun soundIn(id: String) {
        if (sounding || playerId != id || !audible()) return
        val p = player ?: return
        if (p.playbackState == Player.STATE_ENDED) p.seekTo(0L)
        sounding = true
        Ambient.hold(true)
        p.play()
        fadeVolume(1f, SOUND_IN) {}
        h.removeCallbacks(tick); h.post(tick)
    }

    private fun soundOut() {
        h.removeCallbacks(tick)
        if (!sounding) return
        sounding = false
        val p = player ?: return
        val id = playerId
        fadeVolume(0f, SOUND_OUT) {
            if (!sounding) { p.pause(); id?.let { left[it] = p.currentPosition.let { pos -> if (p.duration > 0 && pos > p.duration - 1500) 0L else pos } } }
            if (!sounding) Ambient.hold(false)
        }
    }

    private fun fadeVolume(to: Float, ms: Long, end: () -> Unit) {
        volAnim?.cancel()
        val from = vol
        volAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms
            addUpdateListener { vol = from + (to - from) * it.animatedFraction; player?.volume = VOLUME * vol * vol }
            var cancelled = false
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationCancel(a: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(a: android.animation.Animator) { if (!cancelled) end() }
            })
            start()
        }
    }

    /** Where the clip is, four times a second: the card's edge line follows it. */
    private val tick = object : Runnable {
        override fun run() {
            val p = player ?: return; val w = wanted ?: return
            if (w.track == playerId && p.duration > 0) onProgress(w, p.currentPosition / 1000f, p.duration / 1000f)
            if (sounding) h.postDelayed(this, 250)
        }
    }

    /** One full-screen picture, drawn as a single bitmap (the GPU on these boxes has little to spare). */
    private class SceneView(ctx: Context) : View(ctx) {
        private var b: Bitmap? = null
        private val p = Paint(Paint.FILTER_BITMAP_FLAG)
        private val dst = Rect()
        private var drift: ValueAnimator? = null

        fun set(bm: Bitmap?) { if (b !== bm) { b = bm; invalidate() } }
        override fun hasOverlappingRendering() = false   // one draw: fades need no offscreen copy
        override fun onDraw(c: Canvas) { val bm = b ?: return; dst.set(0, 0, width, height); c.drawBitmap(bm, null, dst, p) }

        /** A slow push-in towards the cover, over about the length of the clip. */
        fun startDrift() {
            drift?.cancel(); scaleX = 1f; scaleY = 1f
            if (!Motion.on) return
            pivotX = width.toFloat(); pivotY = height * 0.42f
            drift = ValueAnimator.ofFloat(1f, 1.05f).apply {
                duration = 32_000; interpolator = LinearInterpolator()
                addUpdateListener { val s = it.animatedValue as Float; scaleX = s; scaleY = s }
                start()
            }
        }
        fun stopDrift() { drift?.cancel(); drift = null; scaleX = 1f; scaleY = 1f }

        companion object {
            private const val W = 1280; private const val H = 720

            /**
             * The song's colour fills the screen (Spotify's own, else the cover's, toned so white text reads on it), with a
             * soft blur of the cover's light on it; the cover sits full height on the right, its left side melting away.
             */
            fun compose(art: Bitmap?, spotifyColor: Int, tint: IntArray?): Bitmap {
                val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888); val c = Canvas(out)
                val base = tone(when { spotifyColor != 0 -> spotifyColor; art != null -> average(art); else -> tint?.get(0) ?: 0xFF14532D.toInt() })
                c.drawColor(base)
                if (art == null) {
                    tint?.let { t -> c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, W.toFloat(), H.toFloat(), t[0], t[1], Shader.TileMode.CLAMP) }) }
                    return out
                }
                // The cover's light, blurred to nothing but colour, over the whole screen.
                c.drawBitmap(blurred(art), null, Rect(-W / 4, -H / 3, W, H + H / 3), Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 120 })
                // The cover, full height on the right; its left 60% fades out over what's behind (no seam).
                val s = H.toFloat(); val x0 = W - s
                val sc = c.saveLayer(x0, 0f, W.toFloat(), s, null)
                c.drawBitmap(art, null, RectF(x0, 0f, W.toFloat(), s), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
                c.drawRect(x0, 0f, W.toFloat(), s, Paint().apply {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                    shader = LinearGradient(x0, 0f, x0 + s * 0.6f, 0f, intArrayOf(0x00000000, 0x59000000, 0xD9000000.toInt(), 0xFF000000.toInt()),
                        floatArrayOf(0f, 0.35f, 0.75f, 1f), Shader.TileMode.CLAMP)
                })
                c.restoreToCount(sc)
                return out
            }

            /** Dark and rich enough for white text, whatever the source colour. */
            private fun tone(c: Int): Int {
                val hsv = FloatArray(3); Color.colorToHSV(c, hsv)
                hsv[1] = hsv[1].coerceIn(0.25f, 0.9f); hsv[2] = hsv[2].coerceIn(0.2f, 0.42f)
                return Color.HSVToColor(hsv)
            }

            /** Area average by halving (a single big downscale only samples a few pixels). */
            private fun shrink(b: Bitmap, to: Int): Bitmap {
                var x = b
                while (x.width / 2 >= to && x.height / 2 >= to) x = Bitmap.createScaledBitmap(x, x.width / 2, x.height / 2, true)
                return Bitmap.createScaledBitmap(x, to, to, true)
            }

            private fun average(b: Bitmap) = shrink(b, 1).getPixel(0, 0)

            /** A tiny, well-blurred copy: drawn stretched to the whole screen it's just soft colour. */
            private fun blurred(b: Bitmap): Bitmap {
                val n = 48
                val small = Bitmap.createScaledBitmap(shrink(b, 12), n, n, true)
                val px = IntArray(n * n); small.getPixels(px, 0, n, 0, 0, n, n)
                repeat(3) { boxBlur(px, n, 3) }
                return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
            }

            private fun boxBlur(px: IntArray, n: Int, r: Int) {
                val tmp = IntArray(px.size)
                for (pass in 0..1) {
                    val src = if (pass == 0) px else tmp; val dst = if (pass == 0) tmp else px
                    for (y in 0 until n) for (x in 0 until n) {
                        var rr = 0; var gg = 0; var bb = 0; var k = 0
                        for (d in -r..r) {
                            val xx = if (pass == 0) (x + d).coerceIn(0, n - 1) else x
                            val yy = if (pass == 0) y else (y + d).coerceIn(0, n - 1)
                            val c = src[yy * n + xx]; rr += (c shr 16) and 255; gg += (c shr 8) and 255; bb += c and 255; k++
                        }
                        dst[y * n + x] = (0xFF shl 24) or ((rr / k) shl 16) or ((gg / k) shl 8) or (bb / k)
                    }
                }
            }
        }
    }
}
