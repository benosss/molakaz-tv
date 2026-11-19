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
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
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
 * Spotify cards' previews: rest on one and the screen behind Home becomes the song, alive. A glow of the cover's own
 * colours drifts across the screen, the cover floats on the left and breathes with the bass, the title and artist sit
 * beside it over bars that dance to the music, and the 30-second clip fades in. Never over music that's already
 * playing (then it shows, muted, the bars moving gently by themselves), never with "Music silent" in Settings, and the
 * ambient bed steps aside while a clip plays.
 *
 * The colours come from the cover itself (the same picture as the card), never from a colour picked elsewhere.
 *
 * Optimistic, so it feels instant:
 * - A scene is only a cover and its colours, built off the main thread the moment focus lands (or ahead, for the cards
 *   next to it) from the best cover at hand: the big one if it's in, else the card's own picture, swapped for the big
 *   one in place when it arrives.
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

    /** What a scene needs: the cover, the colours taken from it, and the words. */
    class SceneData(val cover: Bitmap?, val palette: IntArray, val base: Int, val title: String, val artist: String)

    private val h = Handler(Looper.getMainLooper())
    private val views = List(2) { MusicScene(ctx).also { v -> v.alpha = 0f; host.addView(v, FrameLayout.LayoutParams(MATCH, MATCH)) } }
    private var front = 0
    var shown = false
        private set
    private var wanted: PreviewSrc? = null
    private var showing: String? = null
    private var focusAt = 0L

    /** On/off with the other previews (Settings). */
    var enabled = Prefs.previews
        set(v) { field = v; if (!v) focus(null) }

    private val scenes = LinkedHashMap<String, SceneData>()   // by track, newest last
    private val sharp = HashSet<String>()                      // built from the big cover
    private val composer = Executors.newSingleThreadExecutor { r -> Thread(r, "music-scene") }

    private var player: ExoPlayer? = null
    private var playerId: String? = null
    private var sounding = false
    private val left = HashMap<String, Long>()                 // where each clip was when focus left it
    private var vol = 0f
    private var volAnim: ValueAnimator? = null
    private val am = ctx.getSystemService(android.media.AudioManager::class.java)

    init {
        SpotifyTracks.init(ctx)
        views.forEach { v ->
            v.sounding = { sounding }
            v.clip = { val p = player; if (p != null && playerId == showing && p.duration > 0) (p.currentPosition / 1000f) to (p.duration / 1000f) else null }
        }
    }

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
                if (showing == id) scenes[id]?.let { views[front].data = it }
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
        val d = scenes[id] ?: run { hide(); return@Runnable }
        val crossFade = shown && showing != id
        if (shown && showing == id) { soundIn(id); return@Runnable }
        val next = if (crossFade) 1 - front else front
        val v = views[next]
        v.data = d; v.bringToFront()
        if (crossFade) views[front].let { old ->
            old.animate().cancel()
            old.animate().alpha(0f).setDuration(FADE_IN).setInterpolator(Ease.inOut).withEndAction { old.running = false }.start()
        }
        front = next; showing = id
        v.running = true
        v.animate().cancel()
        v.animate().alpha(1f).setDuration(if (Motion.on) FADE_IN else 0).setInterpolator(Ease.out).start()
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
            v.animate().alpha(0f).setDuration(if (Motion.on) FADE_OUT else 0).setInterpolator(Ease.exit).withEndAction { v.running = false }.start()
        }
        onShown(false)
        h.postDelayed(inactive, FADE_OUT + 120)
    }
    private val inactive = Runnable { if (!shown) onActive(false) }

    /** Home went to the background: silence now (no fade, another app is taking over), keep what's loaded. */
    fun stop() {
        h.removeCallbacks(reveal); wanted = null
        volAnim?.cancel(); player?.let { p -> playerId?.let { left[it] = p.currentPosition }; p.pause(); p.volume = 0f }
        vol = 0f; sounding = false; h.removeCallbacks(tick); Ambient.hold(false); AudioViz.stop("music")
        if (shown) { shown = false; showing = null; views.forEach { it.animate().cancel(); it.alpha = 0f; it.running = false }; onShown(false); onActive(false) }
        h.removeCallbacks(release); h.postDelayed(release, 120_000)
    }

    fun start() { h.removeCallbacks(release) }

    /** Memory goes back while another app is in front: the player and the scenes are rebuilt when needed. */
    private val release = Runnable {
        player?.release(); player = null; playerId = null
        scenes.clear(); sharp.clear(); views.forEach { it.data = null }
    }

    // ---------- the scene ----------

    private fun compose(id: String, src: PreviewSrc, force: Boolean = false, done: () -> Unit) {
        if (!force && scenes.containsKey(id)) { done(); return }
        composer.execute {
            val t0 = SystemClock.uptimeMillis()
            val big = SpotifyTracks.artFile(id).takeIf { it.length() > 0 }?.let { runCatching { BitmapFactory.decodeFile(it.path) }.getOrNull() }
            val cover = big ?: src.cover
            val pal = runCatching { MusicScene.palette(cover, src.tint) }.getOrNull()
            val (title, artist) = credits(src.title.orEmpty(), src.sub.orEmpty())
            val d = pal?.let { SceneData(cover, it.first, it.second, title, artist) }
            Log.i(TAG, "scene for $id ready in ${SystemClock.uptimeMillis() - t0} ms" + if (big == null) " (card cover)" else "")
            h.post {
                if (d == null) { done(); return@post }     // reveal finds no scene and hides the last one
                scenes.remove(id); scenes[id] = d
                if (big != null) sharp += id else sharp -= id
                while (scenes.size > SCENES) { val k = scenes.keys.first(); if (k == showing) break; scenes.remove(k); sharp -= k }
                done()
            }
        }
    }

    /** "Song (feat. A, B) [From X]" by "Artist": the song's name alone, and the guests with the artist. */
    private fun credits(title: String, artist: String): Pair<String, String> {
        val feat = Regex("""\s*[(\[]\s*(?:feat\.?|ft\.?|with)\s+([^)\]]+)[)\]]""", RegexOption.IGNORE_CASE).find(title)
        var name = if (feat != null) title.removeRange(feat.range) else title
        name = name.replace(Regex("""\s*\[[^\]]*]"""), "").trim().ifBlank { title }
        val by = if (feat != null) "$artist  ·  with ${feat.groupValues[1].trim()}".trimStart(' ', '·') else artist
        return name to by
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
                    sounding = false; h.removeCallbacks(tick); Ambient.hold(false); AudioViz.stop("music")
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
        AudioViz.start(ctx, "music")
        p.play()
        fadeVolume(1f, SOUND_IN) {}
        h.removeCallbacks(tick); h.post(tick)
        if (Apps.showcase) p.volume = 0f   // screenshots: the scene as it plays, without sound in the room
    }

    private fun soundOut() {
        h.removeCallbacks(tick)
        if (!sounding) return
        sounding = false
        val p = player ?: return
        val id = playerId
        fadeVolume(0f, SOUND_OUT) {
            if (!sounding) { p.pause(); id?.let { left[it] = p.currentPosition.let { pos -> if (p.duration > 0 && pos > p.duration - 1500) 0L else pos } } }
            if (!sounding) { Ambient.hold(false); AudioViz.stop("music") }
        }
    }

    private fun fadeVolume(to: Float, ms: Long, end: () -> Unit) {
        volAnim?.cancel()
        val from = vol
        volAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms
            addUpdateListener { vol = from + (to - from) * it.animatedFraction; player?.volume = if (Apps.showcase) 0f else VOLUME * vol * vol }
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
}

/**
 * The song's scene, redrawn ~30 times a second while it shows. Everything is a handful of textured quads (a colour, four
 * tinted glow sprites, the cover, a few words and bars), cheap enough for the GPUs in TV boxes.
 */
private class MusicScene(ctx: Context) : View(ctx) {
    var data: MusicPreview.SceneData? = null
        set(v) { if (field !== v) { field = v; cover = null; title = null; invalidate() } }
    var running = false
        set(v) { field = v; if (v) { t0 = SystemClock.uptimeMillis(); invalidate() } }
    var sounding: () -> Boolean = { false }
    var clip: () -> Pair<Float, Float>? = { null }

    private var t0 = 0L
    private val sprite = glowSprite()
    private val sp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val coverP = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rimP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val barP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowP = Paint(Paint.FILTER_BITMAP_FLAG)
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD); letterSpacing = 0.14f }
    private val titleP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD) }
    private val artistP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL) }
    private val timeP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL) }
    private var cover: android.graphics.BitmapShader? = null
    private var title: StaticLayout? = null
    private var shadow: Bitmap? = null
    private val r = RectF(); private val m = android.graphics.Matrix()
    private val bars = FloatArray(BARS)
    private var bass = 0f

    override fun hasOverlappingRendering() = false

    override fun onDraw(c: Canvas) {
        val d = data ?: return
        val w = width.toFloat(); val hh = height.toFloat(); val u = D.s
        val t = (SystemClock.uptimeMillis() - t0) / 1000f
        val live = sounding() && AudioViz.live
        // The music: the box's own output when the clip plays, else a gentle made-up motion.
        var b = 0f
        for (i in 0 until BARS) {
            val band = i * (AudioViz.BANDS - 1f) / (BARS - 1f); val lo = band.toInt(); val f = band - lo
            val raw = if (live) AudioViz.level(lo) * (1 - f) + AudioViz.level((lo + 1).coerceAtMost(AudioViz.BANDS - 1)) * f
                else 0.34f + 0.26f * kotlin.math.sin(t * (2.3f + i * 0.37f) + i * 0.9f) * kotlin.math.sin(t * 0.83f + i * 1.7f)
            bars[i] += (raw.coerceIn(0f, 1f) - bars[i]) * (if (raw > bars[i]) 0.55f else 0.18f)
            if (i < 4) b += bars[i]
        }
        bass += (b / 4f - bass) * 0.3f

        // 1. The cover's darkest colour, then its colours as four soft glows drifting on slow, different paths.
        c.drawColor(d.base)
        for (k in 0 until 4) {
            val px = PATHS[k]
            val cx = w * (0.5f + 0.42f * kotlin.math.sin(t * px[0] + px[2]))
            val cy = hh * (0.48f + 0.34f * kotlin.math.cos(t * px[1] + px[3]))
            val rad = hh * (0.78f + 0.12f * kotlin.math.sin(t * 0.21f + k)) * (1f + 0.07f * bass)
            sp.color = d.palette[k]; sp.alpha = (if (k == 0) 200 else 165)
            r.set(cx - rad, cy - rad, cx + rad, cy + rad); c.drawBitmap(sprite, null, r, sp)
        }

        // 2. The cover, floating, breathing with the bass, on a glow of its own colour.
        val cs = 290f * u; val cxL = 196f * u; val cyT = 158f * u
        val float = 4f * u * kotlin.math.sin(t * 1.05f)
        val scale = 1f + 0.035f * bass
        val ccx = cxL + cs / 2; val ccy = cyT + cs / 2 + float
        sp.color = d.palette[0]; sp.alpha = (90 + 120 * bass).toInt().coerceAtMost(255)
        val gr = cs * (0.95f + 0.15f * bass); r.set(ccx - gr, ccy - gr, ccx + gr, ccy + gr); c.drawBitmap(sprite, null, r, sp)
        val sh = shadow ?: shadowFor(cs).also { shadow = it }
        shadowP.alpha = 150
        c.save(); c.scale(scale, scale, ccx, ccy)
        c.drawBitmap(sh, ccx - sh.width / 2f, ccy - sh.height / 2f + 12f * u, shadowP)
        r.set(ccx - cs / 2, ccy - cs / 2, ccx + cs / 2, ccy + cs / 2)
        val bm = d.cover
        if (bm != null) {
            if (cover == null) cover = android.graphics.BitmapShader(bm, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val s = maxOf(cs / bm.width, cs / bm.height)
            m.setScale(s, s); m.postTranslate(r.left + (cs - bm.width * s) / 2, r.top + (cs - bm.height * s) / 2)
            cover!!.setLocalMatrix(m); coverP.shader = cover
        } else { coverP.shader = null; coverP.color = d.palette[1] }
        c.drawRoundRect(r, 18f * u, 18f * u, coverP)
        rimP.strokeWidth = 1.2f * u; rimP.color = argbA(0.22f, 0xFFFFFF)
        c.drawRoundRect(r, 18f * u, 18f * u, rimP)
        c.restore()

        // 3. The words, and the bars dancing under them.
        val x = 530f * u
        label.textSize = 13f * u; label.color = argbA(0.86f, 0xFFFFFF)
        c.drawText("SPOTIFY  ·  " + tr("PREVIEW") + if (sounding()) "" else "  ·  " + tr("MUTED"), x, 236f * u, label)
        val tl = title ?: layoutTitle(d.title, 640f * u, u).also { title = it }
        c.save(); c.translate(x, 248f * u); tl.draw(c); c.restore()
        var y = 248f * u + tl.height + 8f * u
        artistP.textSize = 23f * u; artistP.color = argbA(0.86f, 0xFFFFFF)
        c.drawText(TextUtils.ellipsize(d.artist, artistP, 640f * u, TextUtils.TruncateAt.END).toString(), x, y + artistP.textSize, artistP)
        y += artistP.textSize + 26f * u
        val bw = 7f * u; val gap = 4.5f * u; val maxH = 46f * u
        barP.color = argbA(0.92f, 0xFFFFFF)
        for (i in 0 until BARS) {
            val bh = (5f * u + bars[i] * maxH).coerceAtMost(maxH)
            val bx = x + i * (bw + gap)
            r.set(bx, y + maxH - bh, bx + bw, y + maxH); c.drawRoundRect(r, bw / 2, bw / 2, barP)
        }
        // 4. Where the 30 seconds are.
        if (sounding()) clip()?.let { (pos, dur) ->
            val py = y + maxH + 18f * u; val pw = BARS * (bw + gap) - gap
            barP.color = argbA(0.28f, 0xFFFFFF); r.set(x, py, x + pw, py + 3f * u); c.drawRoundRect(r, 2f * u, 2f * u, barP)
            barP.color = Color.WHITE; r.set(x, py, x + pw * (pos / dur).coerceIn(0f, 1f), py + 3f * u); c.drawRoundRect(r, 2f * u, 2f * u, barP)
            timeP.textSize = 13f * u; timeP.color = argbA(0.8f, 0xFFFFFF)
            c.drawText("0:%02d".format(pos.toInt().coerceIn(0, 59)), x + pw + 12f * u, py + 5f * u, timeP)
        }
        if (running && isAttachedToWindow && alpha > 0f) postInvalidateDelayed(33)
    }

    private fun layoutTitle(s: String, width: Float, u: Float): StaticLayout {
        titleP.textSize = 44f * u
        return StaticLayout.Builder.obtain(s, 0, s.length, titleP, width.toInt()).setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 0.98f).setIncludePad(false).build()
    }

    private fun shadowFor(cs: Float): Bitmap {
        val pad = (40f * D.s).toInt(); val size = (cs + pad * 2).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        Canvas(bmp).drawRoundRect(RectF(pad.toFloat(), pad.toFloat(), pad + cs, pad + cs), 18f * D.s, 18f * D.s,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = android.graphics.BlurMaskFilter(22f * D.s, android.graphics.BlurMaskFilter.Blur.NORMAL) })
        return bmp
    }

    companion object {
        const val BARS = 24
        /** Speeds and phases of the four glows (x, y): slow and never in step, so the picture never repeats visibly. */
        private val PATHS = arrayOf(floatArrayOf(0.23f, 0.17f, 0f, 1f), floatArrayOf(0.13f, 0.29f, 2.1f, 0.3f),
            floatArrayOf(0.31f, 0.11f, 4.2f, 2.6f), floatArrayOf(0.09f, 0.21f, 1.3f, 4.4f))

        /** A soft round glow: white in the middle fading to nothing, tinted by the paint's colour when drawn. */
        fun glowSprite(): Bitmap {
            val n = 128; val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ALPHA_8)
            val px = ByteArray(n * n)
            for (y in 0 until n) for (x in 0 until n) {
                val dx = (x + 0.5f - n / 2f) / (n / 2f); val dy = (y + 0.5f - n / 2f) / (n / 2f)
                val d = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtMost(1f)
                val a = (1f - d * d).let { it * it }          // smooth, no visible edge
                px[y * n + x] = (a * 255f).toInt().toByte()
            }
            bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(px))
            return bmp
        }

        /**
         * The cover's colours: four (strongest and most present first) for the glows, and a dark base made from the main
         * one, so the scene always matches the card. Grey covers get soft greys with a hint of their tint.
         */
        fun palette(cover: Bitmap?, tint: IntArray?): Pair<IntArray, Int> {
            if (cover == null) {
                val a = tint?.getOrNull(0) ?: 0xFF14532D.toInt(); val b = tint?.getOrNull(1) ?: 0xFF05140B.toInt()
                return intArrayOf(a, mix(a, Color.WHITE, 0.25f), b, a) to tone(a, 0.16f, 0.22f)
            }
            var x: Bitmap = cover
            while (x.width / 2 >= 24 && x.height / 2 >= 24) x = Bitmap.createScaledBitmap(x, x.width / 2, x.height / 2, true)
            val small = Bitmap.createScaledBitmap(x, 24, 24, true)
            val px = IntArray(24 * 24); small.getPixels(px, 0, 24, 0, 0, 24, 24)
            // Buckets by hue (12) for colourful pixels, one for greys; weighted by how vivid and how frequent.
            val sumR = FloatArray(13); val sumG = FloatArray(13); val sumB = FloatArray(13); val wt = FloatArray(13)
            val hsv = FloatArray(3)
            for (c in px) {
                Color.colorToHSV(c, hsv)
                val vivid = hsv[1] * hsv[2]
                val k = if (hsv[1] < 0.18f || hsv[2] < 0.12f) 12 else ((hsv[0] / 30f).toInt() % 12)
                val wgt = if (k == 12) 0.25f + 0.25f * hsv[2] else 0.4f + vivid * 1.6f
                sumR[k] += Color.red(c) * wgt; sumG[k] += Color.green(c) * wgt; sumB[k] += Color.blue(c) * wgt; wt[k] += wgt
            }
            val order = (0 until 12).filter { wt[it] > 0.5f }.sortedByDescending { wt[it] }
            fun avg(k: Int) = Color.rgb((sumR[k] / wt[k]).toInt(), (sumG[k] / wt[k]).toInt(), (sumB[k] / wt[k]).toInt())
            val colours = order.take(4).map { glow(avg(it)) }.toMutableList()
            if (colours.isEmpty()) colours += glow(if (wt[12] > 0f) avg(12) else 0xFF3A3A3C.toInt())
            // Fewer than four colours (a cover with one or two): lighter and darker shades of the last one fill in.
            while (colours.size < 4) colours += mix(colours.last(), if (colours.size % 2 == 0) Color.WHITE else Color.BLACK, 0.25f)
            return colours.toIntArray() to tone(colours[0], 0.14f, 0.20f)
        }

        /** A glow colour: rich but not neon, bright enough to light the dark base. */
        private fun glow(c: Int): Int {
            val hsv = FloatArray(3); Color.colorToHSV(c, hsv)
            hsv[1] = (hsv[1] * 1.15f).coerceIn(0f, 0.85f); hsv[2] = hsv[2].coerceIn(0.42f, 0.86f)
            return Color.HSVToColor(hsv)
        }

        /** The base: the main colour, deep and dark. */
        private fun tone(c: Int, vMin: Float, vMax: Float): Int {
            val hsv = FloatArray(3); Color.colorToHSV(c, hsv)
            hsv[1] = hsv[1].coerceIn(0f, 0.8f); hsv[2] = (hsv[2] * 0.35f).coerceIn(vMin, vMax)
            return Color.HSVToColor(hsv)
        }
    }
}
