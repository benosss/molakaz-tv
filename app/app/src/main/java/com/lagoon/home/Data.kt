package com.lagoon.home

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * Lets us read media sessions (granted once over adb). Android keeps it bound, so it also keeps the process alive and
 * tracking playback for Continue watching even when the home screen itself isn't running.
 */
class NowPlayingService : NotificationListenerService() {
    override fun onListenerConnected() { super.onListenerConnected(); MediaHub.start(applicationContext) }
}

/** One-time setup shared by the home screen, the listener service and the screensaver. */
class HomeApp : android.app.Application() {
    override fun onCreate() {
        super.onCreate(); BootTrace.mark("app start")
        Safety.install(this)
        Prefs.init(this); I18n.init(this); Apps.init(this); Updater.init(this); Picks.init(this); ContinueStore.init(this); Goodnight.init(this); Ambient.init(this); Remote.start(this)
        // First run on a box with a live TV app: put it first in the quick access bar.
        if (!Prefs.railSaved) LiveTv.find(this)?.let { Prefs.rail = listOf(it) + Apps.defaultRail().filter { p -> Apps.installed(p) } }
        BootTrace.mark("app ready")
    }
}

private val mainH = Handler(Looper.getMainLooper())

/** Startup timing, in ms since the process started (logcat tag BootTrace). */
object BootTrace {
    fun mark(what: String) = android.util.Log.i("BootTrace", "${SystemClock.uptimeMillis() - android.os.Process.getStartUptimeMillis()}ms $what")
}

object MediaHub {
    class Now(val pkg: String, val title: String, val sub: String, val art: Bitmap?, val playing: Boolean,
              private val pos: Long, val dur: Long, private val at: Long, private val speed: Float,
              val mediaId: String? = null, val artUri: String? = null,
              /** The app dropped its session after pausing in the background; we keep showing the track for a while. */
              val ghost: Boolean = false) {
        fun position(): Long = if (playing) pos + ((SystemClock.elapsedRealtime() - at) * speed).toLong() else pos
        fun paused() = Now(pkg, title, sub, art, false, position(), dur, SystemClock.elapsedRealtime(), 0f, mediaId, artUri, true)
    }

    /** Music apps keep playing in the background, so a paused track is worth keeping on Home. Video apps stop instead. */
    private val AUDIO = setOf("com.spotify.tv.android", "com.spotify.music", "com.google.android.youtube.tvmusic",
        "com.google.android.apps.youtube.music", "deezer.android.tv", "com.aspiro.tidal", "com.soundcloud.android",
        "com.apple.android.music", "com.amazon.music.tv", "com.pandora.android.atv")
    private const val GHOST_MS = 15 * 60_000L

    var now: Now? = null
        private set
    var permitted = false
        private set
    private val listeners = ArrayList<() -> Unit>()
    fun listen(f: () -> Unit) { listeners += f }
    private var ctrls: List<MediaController> = emptyList()
    private val cbs = HashMap<MediaController, MediaController.Callback>()
    private val onChange = MediaSessionManager.OnActiveSessionsChangedListener { attach(it ?: emptyList()) }
    private var app: Context? = null

    private var started = false
    /** Safe to call again (Home calls it on every resume): it re-reads the sessions in case a callback was missed. */
    fun start(ctx: Context) {
        app = ctx.applicationContext
        val msm = ctx.applicationContext.getSystemService(MediaSessionManager::class.java)
        val cn = ComponentName(ctx, NowPlayingService::class.java)
        try {
            if (!started) { msm.addOnActiveSessionsChangedListener(onChange, cn, mainH); started = true }
            attach(msm.getActiveSessions(cn)); permitted = true
        } catch (e: SecurityException) { permitted = false; listeners.forEach { it() } }
    }

    private fun attach(list: List<MediaController>) {
        cbs.forEach { (c, cb) -> c.unregisterCallback(cb) }; cbs.clear()
        ctrls = list
        for (c in list) {
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
                override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
                override fun onSessionDestroyed() = refresh()
            }
            c.registerCallback(cb, mainH); cbs[c] = cb
        }
        refresh()
    }

    fun anyPlaying() = faked?.playing == true || ctrls.any { it.playbackState?.state == PlaybackState.STATE_PLAYING }

    fun toggle() {
        val n = now ?: return
        val c = ctrls.firstOrNull { it.packageName == n.pkg }
        if (c != null) {
            if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
            return
        }
        // No session any more (Spotify drops it when paused in the background): Android still routes a play key to
        // the last media app, which wakes it up and resumes.
        val am = app?.getSystemService(android.media.AudioManager::class.java) ?: return
        val t = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY, 0))
        am.dispatchMediaKeyEvent(android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PLAY, 0))
    }

    /** Goodnight: pause everything that plays. */
    fun pauseAll() = mainH.post { ctrls.filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }.forEach { it.transportControls.pause() } }

    private fun ctlNow() = now?.let { n -> ctrls.firstOrNull { it.packageName == n.pkg } }

    /** The app's media session, if it has one open. */
    fun controller(pkg: String): MediaController? = ctrls.firstOrNull { it.packageName == pkg }

    /**
     * Once [pkg] has a session (and, with a [title], once it has loaded that song), run [then] with it. Checked four
     * times a second; gives up after [ms]. For commands that have to wait for an app to start or a song to load.
     */
    fun whenReady(pkg: String, title: String?, ms: Long, then: (MediaController) -> Unit) {
        val until = SystemClock.uptimeMillis() + ms
        val check = object : Runnable {
            override fun run() {
                val c = controller(pkg)
                val t = c?.metadata?.let { str(it, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE) }
                if (c != null && (title == null || t.equals(title, true))) { then(c); return }
                if (SystemClock.uptimeMillis() < until) mainH.postDelayed(this, 250)
            }
        }
        mainH.post(check)
    }

    fun canSkip() = ctlNow()?.playbackState?.actions?.let { it and (PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0L } ?: false

    /** Phone remote: next or previous track (no-op if the app doesn't offer it). */
    fun skip(next: Boolean): Boolean {
        val c = ctlNow() ?: return false
        mainH.post { if (next) c.transportControls.skipToNext() else c.transportControls.skipToPrevious() }
        return true
    }

    fun seek(ms: Long): Boolean {
        val c = ctlNow() ?: return false
        if (ms < 0) return false
        mainH.post { c.transportControls.seekTo(ms) }
        return true
    }

    /** Diagnostics only: show a made-up track (null = back to the real sessions). */
    /** Diagnostics: every field an app puts in its media session (the "meta" debug command). */
    fun debugMeta(): String = ctrls.joinToString(" || ") { c ->
        val md = c.metadata
        c.packageName + " actions=" + c.playbackState?.actions + " " + (md?.keySet()?.joinToString(", ") { k ->
            k.substringAfterLast('.') + "=" + (md.getString(k) ?: md.getLong(k).takeIf { it != 0L }?.toString() ?: md.getBitmap(k)?.let { "bitmap ${it.width}x${it.height}" } ?: "?")
        } ?: "no metadata") + " desc.mediaId=" + md?.description?.mediaId + " desc.mediaUri=" + md?.description?.mediaUri + " extras=" + c.extras?.keySet()
    }

    /** Diagnostics: a made-up track, treated as really playing (so previews stay silent, as they would). */
    private var faked: Now? = null
    fun debugFake(n: Now?) { faked = n; if (n == null) { now = null; refresh() } else { now = n; listeners.forEach { it() } } }

    private fun str(md: MediaMetadata?, vararg keys: String) = keys.firstNotNullOfOrNull { k -> md?.getString(k)?.takeIf { it.isNotBlank() } }

    private fun read(ctl: MediaController): Now? {
        val md = ctl.metadata ?: return null; val st = ctl.playbackState
        // Empty strings count as missing: some apps set DISPLAY_TITLE to "" and put the real title elsewhere.
        val title = str(md, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE)
            ?: md.description?.title?.toString()?.takeIf { it.isNotBlank() } ?: return null
        val sub = str(md, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
            MediaMetadata.METADATA_KEY_ALBUM) ?: md.description?.subtitle?.toString() ?: ""
        val art = md.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val mediaId = str(md, MediaMetadata.METADATA_KEY_MEDIA_ID) ?: md.description?.mediaId
        val artUri = str(md, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
            ?: md.description?.iconUri?.toString()
        return Now(ctl.packageName, title, sub, art, st?.state == PlaybackState.STATE_PLAYING, st?.position ?: 0L,
            md.getLong(MediaMetadata.METADATA_KEY_DURATION), st?.lastPositionUpdateTime ?: SystemClock.elapsedRealtime(), st?.playbackSpeed ?: 1f,
            mediaId, artUri)
    }

    private val settle = Runnable { refresh(settled = true) }
    private val expire = Runnable { if (now?.ghost == true) { now = null; listeners.forEach { it() } } }
    private var lastLogged = ""

    private fun refresh(settled: Boolean = false) {
        val c = ctrls.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: ctrls.firstOrNull { it.metadata != null }
        var n = c?.let { read(it) }
        val prev = now
        // Between tracks apps briefly report no title, no metadata or no session at all. Hold the last track for the
        // same app, and only let the card go once nothing has come back for a moment.
        if (n == null && c != null && prev != null && !prev.ghost && prev.pkg == c.packageName) n = prev
        if (n == null && prev != null) {
            if (!settled) { mainH.removeCallbacks(settle); mainH.postDelayed(settle, 1500); return }
            n = when {
                prev.ghost -> prev
                prev.pkg in AUDIO -> prev.paused().also { mainH.removeCallbacks(expire); mainH.postDelayed(expire, GHOST_MS) }
                else -> null
            }
        }
        mainH.removeCallbacks(settle)
        if (n != null && !n.ghost) mainH.removeCallbacks(expire)
        now = n
        val tag = "${n?.pkg}|${n?.title}|${n?.playing}|${n?.ghost}"
        if (tag != lastLogged) { lastLogged = tag; android.util.Log.i("MediaHub", "now $tag art=${n?.art != null}") }
        if (n != null && !n.ghost) ContinueStore.record(n)
        listeners.forEach { it() }
    }
}

/** Android keeps Netflix/YouTube "watch next" lists private, so we remember what actually played. */
object ContinueStore {
    class Item(val pkg: String, val title: String, val sub: String, val art: String?, val pos: Long, val dur: Long, val ts: Long, val link: String? = null) {
        val key get() = "$pkg|$title"
        /** Watched to the end (last 3%, at least the final 20 s): no longer worth "continuing". */
        val finished get() = dur > 60_000 && pos >= dur - maxOf(20_000L, dur * 3 / 100)
    }
    var items: List<Item> = emptyList()
        private set
    private lateinit var dir: File
    private val listeners = ArrayList<() -> Unit>()
    fun listen(f: () -> Unit) { listeners += f }
    private var lastSave = 0L

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "cw").apply { mkdirs() }
        runCatching {
            val a = JSONArray(File(dir, "items.json").readText())
            items = (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                val pkg = o.getString("pkg"); val title = o.getString("title")
                val art = o.optString("art").ifBlank { null } ?: artFile("$pkg|$title").takeIf { it.exists() }?.path
                Item(pkg, title, o.optString("sub"), art, o.optLong("pos"), o.optLong("dur"), o.optLong("ts"), o.optString("link").ifBlank { null })
            }
        }
        items.filter { it.link == null }.forEach { resolveLink(it.pkg, it.title, it.sub) }
    }

    fun record(n: MediaHub.Now) {
        if (!Apps.watched(n.pkg) || !::dir.isInitialized) return
        val key = "${n.pkg}|${n.title}"
        val prev = items.firstOrNull { it.key == key }
        // Spotify sends a new title a moment before its new cover, so the first picture for a track can be the previous
        // track's. Whenever the picture changes for a title, save the new one under a new name (the shelf caches by path).
        val artPath = n.art?.let { bmp ->
            val sg = signature(bmp)
            if (artSig[key] == sg && prev?.art != null) return@let prev.art
            val f = File(dir, Integer.toHexString(key.hashCode()) + "-" + Integer.toHexString(sg) + ".jpg")
            if (!f.exists()) runCatching {
                val s = 480f / bmp.width
                val b = if (s < 1f) Bitmap.createScaledBitmap(bmp, 480, (bmp.height * s).roundToInt().coerceAtLeast(1), true) else bmp
                val tmp = File(f.path + ".part")
                tmp.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 86, it) }
                tmp.renameTo(f)
            }
            if (!f.exists()) return@let prev?.art
            artSig[key] = sg
            prev?.art?.takeIf { it != f.path }?.let { File(it).delete() }
            f.path
        } ?: prev?.art ?: artFile(key).takeIf { it.exists() }?.path
        if (artPath == null) n.artUri?.takeIf { it.startsWith("http") }?.let { fetchArt(key, it) }
        val link = deepLink(n) ?: prev?.link
        // Apps report 0 when a video ends or the session resets: keep the last real position.
        val pos = n.position().let { p -> if (p < 1000 && prev != null && prev.pos > 5000) prev.pos else p }
        val orderChanged = items.firstOrNull()?.key != key
        // Artwork and resume links often arrive a moment after the title: save and redraw as soon as they do.
        val detailChanged = (artPath != null && artPath != prev?.art) || (link != null && prev?.link == null)
        items = (listOf(Item(n.pkg, n.title, n.sub, artPath, pos, n.dur, System.currentTimeMillis(), link)) + items.filter { it.key != key }).take(10)
        val t = SystemClock.elapsedRealtime()
        if (orderChanged || detailChanged || t - lastSave > 15_000) { lastSave = t; save() }
        if (orderChanged || detailChanged) listeners.forEach { it() }
        if (link == null) resolveLink(n.pkg, n.title, n.sub)
    }

    /** Resume links: YouTube at the exact second, Spotify by track URI, Netflix by title id. */
    private fun deepLink(n: MediaHub.Now): String? {
        val id = n.mediaId?.takeIf { it.isNotBlank() } ?: return null
        return when (n.pkg) {
            "com.google.android.youtube.tv" -> Regex("[A-Za-z0-9_-]{11}").find(id)?.value?.let { "https://www.youtube.com/watch?v=$it" }
            "com.spotify.tv.android" -> id.takeIf { it.startsWith("spotify:") }
            "com.netflix.ninja" -> Regex("\\d{6,10}").find(id)?.value?.let { "https://www.netflix.com/watch/$it" }
            else -> null
        }
    }

    /**
     * YouTube and Netflix don't put an id in their media session, only the title. Match it against Fresh picks first,
     * then (YouTube only) take the top hit of a YouTube search for "title channel".
     */
    private val resolving = HashSet<String>()
    private val main = Handler(Looper.getMainLooper())
    private fun resolveLink(pkg: String, title: String, sub: String) {
        val key = "$pkg|$title"
        if (title.isBlank() || !resolving.add(key)) return
        val app = when (pkg) { "com.google.android.youtube.tv" -> "youtube"; "com.netflix.ninja" -> "netflix"; "com.spotify.tv.android" -> "spotify"; else -> return }
        val fromPicks = Picks.items.firstOrNull { it.app == app && it.title.equals(title, true) }?.link?.substringBefore("&")
        if (fromPicks == null && app != "youtube") return
        thread(name = "cw-link") {
            val link = fromPicks ?: youtubeSearch("$title $sub")?.let { "https://www.youtube.com/watch?v=$it" }
            if (link != null) main.post {
                val i = items.indexOfFirst { it.key == key }
                if (i >= 0 && items[i].link == null) {
                    val o = items[i]
                    items = items.toMutableList().also { it[i] = Item(o.pkg, o.title, o.sub, o.art, o.pos, o.dur, o.ts, link) }
                    save()
                }
            }
        }
    }

    fun youtubeSearch(q: String): String? = runCatching {
        val c = URL("https://www.youtube.com/results?hl=en&search_query=" + java.net.URLEncoder.encode(q, "UTF-8")).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36")
        c.setRequestProperty("Accept-Language", "en")
        val re = Regex("\"videoRenderer\":\\{\"videoId\":\"([A-Za-z0-9_-]{11})\"")
        // The first result sits ~850 KB into a ~1.4 MB page: stream it and stop as soon as it appears.
        c.inputStream.bufferedReader().use { r ->
            val sb = StringBuilder(); val buf = CharArray(32 * 1024)
            while (true) {
                val n = r.read(buf); if (n < 0) break
                val from = (sb.length - 64).coerceAtLeast(0); sb.append(buf, 0, n)
                re.find(sb, from)?.let { return@runCatching it.groupValues[1] }
                if (sb.length > 3_000_000) break
            }
        }
        null
    }.getOrNull()

    private fun artFile(key: String) = File(dir, Integer.toHexString(key.hashCode()) + ".jpg")

    /** What a cover looks like, in one number: an 8x8 thumbnail, hashed. Cached per bitmap (apps resend the same one). */
    private val artSig = HashMap<String, Int>()
    private var sigOf: Bitmap? = null
    private var sigVal = 0
    private fun signature(b: Bitmap): Int {
        if (b === sigOf) return sigVal
        val v = runCatching {
            val t = Bitmap.createScaledBitmap(b, 8, 8, true)
            var h = 17; for (y in 0 until 8) for (x in 0 until 8) h = h * 31 + (t.getPixel(x, y) and 0xF0F0F0)
            h
        }.getOrDefault(b.generationId)
        sigOf = b; sigVal = v
        return v
    }

    /** No picture in the session (it happens): fetch the cover from the link the app gave instead. */
    private val fetching = HashSet<String>()
    private fun fetchArt(key: String, uri: String) {
        if (!fetching.add(key)) return
        thread(name = "cw-art") {
            val f = artFile(key)
            runCatching {
                val b = URL(uri).openStream().use { android.graphics.BitmapFactory.decodeStream(it) } ?: return@runCatching
                val s = 480f / b.width
                val sb = if (s < 1f) Bitmap.createScaledBitmap(b, 480, (b.height * s).roundToInt().coerceAtLeast(1), true) else b
                val tmp = File(f.path + ".part"); tmp.outputStream().use { sb.compress(Bitmap.CompressFormat.JPEG, 86, it) }; tmp.renameTo(f)
            }
            main.post {
                fetching.remove(key)
                val i = items.indexOfFirst { it.key == key }
                if (f.exists() && i >= 0 && items[i].art == null) {
                    val o = items[i]
                    items = items.toMutableList().also { it[i] = Item(o.pkg, o.title, o.sub, f.path, o.pos, o.dur, o.ts, o.link) }
                    save(); listeners.forEach { it() }
                }
            }
        }
    }

    private fun save() {
        val a = JSONArray()
        items.forEach { a.put(JSONObject().put("pkg", it.pkg).put("title", it.title).put("sub", it.sub).put("art", it.art ?: "").put("pos", it.pos).put("dur", it.dur).put("ts", it.ts).put("link", it.link ?: "")) }
        runCatching { File(dir, "items.json").writeText(a.toString()) }
        val used = items.mapNotNull { it.art }.toSet()
        dir.listFiles { f -> f.name.endsWith(".jpg") && f.path !in used }?.forEach { it.delete() }
    }
}

object Weather {
    class W(val temp: Int, val code: Int, val isDay: Boolean, val sunrise: Long, val sunset: Long)
    /** Starts from the last saved reading, so the first film after a boot already knows the time of sunset and the sky. */
    var w: W? = runCatching { Prefs.weather?.let { val o = JSONObject(it)
        W(o.getInt("t"), o.getInt("c"), o.getBoolean("d"), o.getLong("r"), o.getLong("s")) } }.getOrNull()
        private set

    fun fetch(cb: () -> Unit) = thread(name = "weather") {
        val r = runCatching {
            val pl = Prefs.place
            val c = URL("https://api.open-meteo.com/v1/forecast?latitude=${pl.lat}&longitude=${pl.lon}" +
                "&current=temperature_2m,weather_code,is_day&daily=sunrise,sunset&timezone=auto&timeformat=unixtime&forecast_days=1").openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val cur = j.getJSONObject("current"); val d = j.getJSONObject("daily")
            W(cur.getDouble("temperature_2m").roundToInt(), cur.getInt("weather_code"), cur.getInt("is_day") == 1,
                d.getJSONArray("sunrise").getLong(0) * 1000L, d.getJSONArray("sunset").getLong(0) * 1000L)
        }.getOrNull()
        mainH.post {
            if (r != null) { w = r; Prefs.weather = JSONObject().put("t", r.temp).put("c", r.code).put("d", r.isDay).put("r", r.sunrise).put("s", r.sunset).toString() }
            cb()
        }
    }

    fun desc(c: Int) = when {
        c == 0 -> "Clear"; c <= 2 -> "Partly cloudy"; c == 3 -> "Cloudy"; c <= 48 -> "Fog"; c <= 57 -> "Drizzle"
        c <= 67 -> "Rain"; c <= 77 -> "Snow"; c <= 82 -> "Showers"; else -> "Storm"
    }

    fun icon(c: Int, day: Boolean) = when { c >= 51 -> R.drawable.ic_rain; c >= 2 -> R.drawable.ic_cloud; day -> R.drawable.ic_sun; else -> R.drawable.ic_moon }
}

/** Hourly while the TV is on; skipped while anything streams; capped at 4 s / 15 MB. */
object SpeedTest {
    /** Last result survives restarts so the pill never falls back to a bare "Wi-Fi". */
    var mbps: Int?
        get() = Prefs.lastMbps.takeIf { it > 0 }
        private set(v) { Prefs.lastMbps = v ?: 0 }
    var running = false
        private set

    fun run(cb: () -> Unit) {
        if (running) return
        running = true; cb()
        thread(name = "speedtest") {
            val r = runCatching {
                val c = URL("https://speed.cloudflare.com/__down?bytes=25000000").openConnection() as HttpURLConnection
                c.connectTimeout = 6000; c.readTimeout = 6000
                val buf = ByteArray(64 * 1024); var total = 0L; var first = 0L
                c.inputStream.use { inp ->
                    while (true) {
                        val n = inp.read(buf); if (n < 0) break
                        if (first == 0L) first = SystemClock.elapsedRealtime()
                        total += n
                        if (SystemClock.elapsedRealtime() - first > 4000 || total > 15_000_000) break
                    }
                }
                c.disconnect()
                val sec = (SystemClock.elapsedRealtime() - first).coerceAtLeast(250) / 1000.0
                (total * 8 / sec / 1_000_000).roundToInt()
            }.getOrNull()
            mainH.post { running = false; if (r != null) mbps = r; cb() }
        }
    }
}

/** One timeline shared by the home screen and the screensaver, so the video never jumps between them. */
object Playback {
    @Volatile private var pos = 0L
    @Volatile private var at = 0L
    /** Which film [pos] is in (v3: positions are per film). */
    @Volatile var scene: String? = null
        private set
    fun mark(p: Long, name: String? = scene) { pos = p; scene = name; at = SystemClock.elapsedRealtime() }
    fun now(): Long = if (at == 0L) 0L else pos + (SystemClock.elapsedRealtime() - at)
    val known get() = at != 0L
}

/** One film in the carousel: where it sits in carousel.mp4, its clean part (no baked cross-fade) and its mood tags. */
class Scene(val index: Int, val name: String, val label: String, val by: String, val start: Long, val from: Long, val to: Long, val tags: Set<String>,
            /** v3: the film's own file and keyframe folder (v1 films all live in carousel.mp4 + blur/). */
            val file: File? = null, val keyDir: File? = null, val keyCount: Int = 0,
            /** A line to learn something by, for landmarks ("Rock-cut Buddhist temples, 2nd century BCE"). */
            val about: String = "",
            /** Average picture level, 0-255 (-1 = unknown): dark films suit the night. */
            val apl: Float = -1f) {
    /** The country (last part of the label), so two films from the same place don't follow each other. */
    val region: String get() = label.substringAfterLast(", ").substringBefore(" ·").trim()
}

/** Where each part of the carousel was filmed, and which part of the file is clean footage. */
object Scenes {
    /** v1 files cross-fade for 2 s around each scene's start: keep 1 s clear on each side. */
    private const val XF = 1000L
    var list: List<Scene> = emptyList()
        private set
    private var duration = 0L
    val durationMs get() = duration
    val count get() = list.size

    /** True when each film is its own file (scenes.json v3). */
    var perFilm = false
        private set

    fun load(f: File) = runCatching {
        val j = JSONObject(f.readText())
        if (j.has("clips")) {
            val dir = f.parentFile!!
            val a = j.getJSONArray("clips")
            list = (0 until a.length()).map { a.getJSONObject(it) }.filter { o -> File(dir, o.getString("file")).exists() && !o.optBoolean("skip") }
                .mapIndexed { i, o ->
                    val tags = o.optJSONArray("tags")?.let { t -> (0 until t.length()).map { t.getString(it) }.toSet() } ?: emptySet()
                    Scene(i, o.getString("name"), o.getString("label"), o.optString("by"), 0L, 0L, (o.getDouble("duration") * 1000).toLong(), tags,
                        File(dir, o.getString("file")), File(dir, o.optString("keys", "blur/" + o.getString("name"))), o.optInt("keyCount", 0),
                        o.optString("about"), o.optDouble("apl", -1.0).toFloat())
                }
            duration = list.sumOf { it.to }; perFilm = true
            return@runCatching
        }
        perFilm = false
        duration = (j.getDouble("duration") * 1000).toLong()
        val a = j.getJSONArray("scenes")
        val raw = (0 until a.length()).map { a.getJSONObject(it) }
        val starts = raw.map { (it.getDouble("start") * 1000).toLong() }
        // v1 files end with a repeat of the first film, only there to close the loop: not a scene of its own.
        val real = if (raw.size > 2 && raw.last().optString("name") == raw.first().optString("name") && !raw.last().has("to")) raw.dropLast(1) else raw
        list = real.mapIndexed { i, o ->
            val start = starts[i]; val next = starts.getOrNull(i + 1) ?: duration
            val from = if (o.has("from")) (o.getDouble("from") * 1000).toLong() else if (i == 0) 0L else start + XF
            val to = if (o.has("to")) (o.getDouble("to") * 1000).toLong() else next - XF
            val tags = o.optJSONArray("tags")?.let { t -> (0 until t.length()).map { t.getString(it) }.toSet() } ?: emptySet()
            Scene(i, o.optString("name"), o.getString("label"), o.optString("by"), start, from, to, tags)
            .let { sc -> if (o.optBoolean("skip")) null else sc }
        }.filterNotNull().filter { it.to - it.from > 5000 }.mapIndexed { i, s -> Scene(i, s.name, s.label, s.by, s.start, s.from, s.to, s.tags) }
    }.isSuccess

    /** The scene playing at a position of the file (cross-fade edges count for the nearer scene). */
    fun sceneAt(posMs: Long): Scene? = list.firstOrNull { posMs >= it.from - XF && posMs < it.to + XF } ?: list.lastOrNull { it.start <= posMs }

    fun at(posMs: Long): String? = sceneAt(posMs)?.label

    /** Pexels creators in order of appearance, each with the places they filmed (short names, no repeats). */
    fun credits(): List<Pair<String, List<String>>> {
        val m = LinkedHashMap<String, LinkedHashSet<String>>()
        for (s in list) if (s.by.isNotBlank()) m.getOrPut(s.by) { LinkedHashSet() } += s.label.substringBefore(" · ").substringBefore(",").trim()
        // "Ha Long Bay at sunset" next to "Ha Long Bay" says nothing new: keep the shorter name.
        return m.map { (by, places) -> by to places.filter { p -> places.none { q -> q != p && p.startsWith("$q ") } } }
    }
}

/**
 * Picks the next film: a shuffle bag over every film, at any time of day, so each round shows all of them in a new order.
 * Time of day and weather only pick the ambient sound (see Ambient), not the films.
 */
object Shuffle {
    enum class Moment { DAWN, DAY, GOLDEN, NIGHT }
    enum class Sky { CLEAR, CLOUDY, RAIN }

    private val recent: ArrayDeque<String> by lazy { ArrayDeque(Prefs.recentScenes) }

    fun moment(now: Long = System.currentTimeMillis()): Moment {
        val cal = java.util.Calendar.getInstance()
        fun minuteOf(t: Long): Int { cal.timeInMillis = t; return cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE) }
        val w = Weather.w
        // Only the time of day matters, so yesterday's cached sunrise and sunset are close enough.
        val rise = w?.let { minuteOf(it.sunrise) } ?: (6 * 60); val set = w?.let { minuteOf(it.sunset) } ?: (18 * 60)
        val m = minuteOf(now)
        return when {
            m >= rise - 45 && m < rise + 90 -> Moment.DAWN
            m >= set - 60 && m < set + 25 -> Moment.GOLDEN
            m >= set + 25 || m < rise - 45 -> Moment.NIGHT
            else -> Moment.DAY
        }
    }

    fun sky(): Sky { val c = Weather.w?.code ?: return Sky.CLEAR; return when { c in 51..67 || c >= 80 -> Sky.RAIN; c in 3..48 || c in 71..77 -> Sky.CLOUDY; else -> Sky.CLEAR } }

    /** Flow from one film to the next: no two films from the same country in a row, no harsh brightness jumps. */
    private fun flow(s: Scene, cur: Scene?): Float {
        if (cur == null) return 1f
        var f = 1f
        if (s.region.isNotEmpty() && s.region == cur.region) f *= 0.15f
        if (s.apl >= 0f && cur.apl >= 0f && kotlin.math.abs(s.apl - cur.apl) > 40f) f *= 0.4f
        return f
    }

    /**
     * The film after [cur]: a shuffle bag. Every film plays once, in random order, before any repeats, and the last few of
     * one round don't open the next. Flow only nudges the order so cross-fades stay smooth. Remembered across restarts.
     */
    fun next(cur: Scene?): Scene? {
        val all = Scenes.list; if (all.isEmpty()) return null
        val byName = all.associateBy { it.name }
        var bag = Prefs.sceneBag.mapNotNull { byName[it] }.filter { it !== cur }
        var refilled = false
        if (bag.isEmpty()) { bag = all.filter { it !== cur }.ifEmpty { all }; refilled = true }
        // The films that ended the last round wait a few turns, so a new round never opens with them.
        val hold = recent.take((all.size / 4).coerceIn(1, 5)).toSet()
        val pool = bag.filter { it.name !in hold }.ifEmpty { bag }
        val ws = pool.map { flow(it, cur) }
        var r = kotlin.random.Random.nextFloat() * ws.sum()
        var pick = pool.last()
        for (i in pool.indices) { r -= ws[i]; if (r <= 0f) { pick = pool[i]; break } }
        Prefs.sceneBag = bag.filter { it !== pick }.map { it.name }
        recent.addFirst(pick.name); while (recent.size > 12) recent.removeLast()
        Prefs.recentScenes = recent.toList()
        android.util.Log.i("Shuffle", "next ${pick.name} apl=${pick.apl}, ${bag.size - 1} left in the round" + if (refilled) " (new round)" else "")
        return pick
    }
}
