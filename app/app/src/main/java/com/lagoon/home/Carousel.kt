package com.lagoon.home

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.SurfaceView
import android.view.animation.PathInterpolator
import androidx.annotation.OptIn
import androidx.core.animation.doOnEnd
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import kotlin.math.abs

/**
 * The scenic background, shared by Home and the screensaver. Films play in an order picked by [Shuffle], one after
 * another with a real cross-fade, and every film is shown sharp, by the hardware video plane, from its first frame:
 *
 *  1. Near the end of a film, a second player ([next]) cues the SAME film a moment ahead into a GL texture; when the
 *     plane reaches that frame the copy starts and fades in over it (same picture, so nothing changes on screen).
 *  2. Under that cover, the main player ([player], on the plane) jumps to the next film and waits on its first frame.
 *  3. The copy of the old film then fades out over [XF] ms, revealing the new film: the cross-fade. The second decoder
 *     is freed until the next change.
 *
 * Starts and decoder restarts (Home coming back) hide behind the veil: the film's own blur, then sharp. If the box ever
 * can't run the second decoder, changes fall back to the veil too.
 *
 * Films are either their own files (scenes.json v3: clips/<name>.mp4) or parts of one carousel.mp4 (v1).
 */
@OptIn(UnstableApi::class)
class Carousel(private val ctx: Context, private val video: SurfaceView, private val gl: GLSurfaceView, private val bd: Backdrop,
               private val onScene: (Scene?) -> Unit) {
    companion object {
        const val XF = 2000L          // cross-fade
        const val PREP = 3500L        // cue the copy, cover, and jump, before the cross-fade starts
        const val COVER = 350L        // the copy fading in over the identical picture
        const val VEIL_IN = 700L
        const val MIX = 520L
        const val VEIL_OUT = 1150L
        // The glass (rail, panels) is a blur: it looks fully changed at ~90% of its blend, while a sharp picture still
        // shows the old film's edges until ~99%. So the glass runs the picture's own fade, GLASS_LAG behind (with the old
        // 300 ms start and a 1.8 s fade it read ~0.2 s early at the end). The place name changes halfway.
        const val GLASS_LAG = 350L
        private val easeIn: TimeInterpolator = PathInterpolator(0.4f, 0f, 0.7f, 1f)
        fun dir(ctx: Context): File? = ctx.getExternalFilesDir(null)

        /** v1: one keyframe folder for carousel.mp4 (count first guessed from the duration, then counted). v3: per film. */
        fun wireKeys(ctx: Context, bd: Backdrop, onReady: () -> Unit) {
            if (Scenes.perFilm) return
            val keys = File(dir(ctx), "blur")
            bd.keyDir = keys
            bd.keyCount = ((Scenes.durationMs / bd.keyStepMs).toInt()).let { it - it % 2 }
            kotlin.concurrent.thread(name = "keys") {
                val n = keys.list { _, name -> name.endsWith(".jpg") }?.size ?: 0
                Handler(Looper.getMainLooper()).post { bd.keyCount = n - n % 2; onReady() }
            }
        }

        /** Set by a cross-fade that couldn't start (no second decoder on this box): use the veil from then on. */
        @Volatile private var xfBroken = false
    }

    var player: ExoPlayer? = null
        private set
    private var next: ExoPlayer? = null
    var scene: Scene? = null
        private set
    private val h = Handler(Looper.getMainLooper())
    private val single = File(dir(ctx), "carousel.mp4")
    private enum class Phase { IDLE, SEEKING, REVEALING, PLAYING, XF_PREP, XF_COVER, XF_JUMP, XF_FADE, VEILING }
    private var phase = Phase.IDLE
    private var veilAnim: ValueAnimator? = null
    private var mixAnim: ValueAnimator? = null
    private var fadeAnim: ValueAnimator? = null
    private var mixDone = true
    private var running = false
    private var mainFile: File? = null
    private var nextFile: File? = null
    private var xfScene: Scene? = null
    private var xfFrames = 0L
    private var xfPrepAt = 0L
    /** How far ahead of the plane the copy is cued (grows if the copy's first frame is late). */
    private var xfLead = 900L
    private var cuePlaying = false
    private var xfFails = 0
    private var xfTarget = 0L
    private var cueTries = 0

    private val canVeil get() = (Scenes.perFilm && (scene?.keyCount ?: 0) >= 2) || (!Scenes.perFilm && bd.keyCount >= 2)

    private fun fileOf(s: Scene) = s.file ?: single
    private val available get() = Scenes.count > 0 && (Scenes.perFilm || single.exists())

    // A jump is done when the first frame from the new place is about to show (see build()).
    private class Jump(val at: Long, val prevPts: Long, val prevAt: Long)
    @Volatile private var pending: Jump? = null
    @Volatile private var lastPts = Long.MIN_VALUE
    @Volatile private var lastFrameAt = 0L

    init {
        bd.onIncomingSurface = { sf -> h.post { next?.let { p -> if (sf != null) p.setVideoSurface(sf) else p.clearVideoSurface() } } }
    }

    /** Choose where to start and show its blurred frame right away, before the player exists (Home's first frame). */
    private var primed = -1L
    fun prime() {
        val to = resolve()
        primed = to
        bd.posMs = to
        if (canVeil) { bd.veil = 1f; bd.cover = 0f } else bd.cover = 1f
        gl.requestRender()
    }

    /** Start or restart playback (Home resumed, screensaver attached). Veiled until a frame from the right place shows. */
    fun start() {
        running = true
        // Cross-fades get a fresh chance each time (a few failures in one session shouldn't leave only the veil).
        xfBroken = false; xfFails = 0
        if (!available) { bd.cover = 0f; bd.veil = 0f; gl.requestRender(); return }
        val p = player ?: build()
        cancelAnims(); dropNext()
        val to = if (primed >= 0) primed else resolve()
        primed = -1
        if (canVeil) { bd.veil = 1f; bd.swapMix = 1f } else bd.cover = 1f
        scene?.let { load(p, it) }
        jump(to)
        p.playWhenReady = true
        h.removeCallbacks(pump); h.post(pump)
    }

    /** Carry on after [pause] without jumping (e.g. when a preview closes). */
    fun resume() {
        if (player == null || running) return
        running = true
        player?.play(); if (phase == Phase.XF_COVER || phase == Phase.XF_JUMP || phase == Phase.XF_FADE) next?.play()
        h.removeCallbacks(pump); h.post(pump)
    }


    fun pause() {
        running = false
        h.removeCallbacks(pump)
        player?.let { mark(it.currentPosition); it.pause() }
        next?.pause()
    }

    /** Free the hardware decoders while hidden (the screensaver and other apps need them). */
    fun stop() {
        running = false
        h.removeCallbacks(pump); h.removeCallbacks(revealTimeout)
        cancelAnims(); dropNext()
        next?.stop(); nextFile = null; preloadedFor = null
        player?.let { if (phase == Phase.PLAYING || phase == Phase.REVEALING) mark(it.currentPosition); it.stop() }
        mainFile = null
        phase = Phase.IDLE; pending = null
        if (canVeil) bd.veil = 1f   // so the first frame when we come back is the blur, not black
    }

    fun release() {
        stop()
        player?.release(); player = null
        next?.release(); next = null
        bd.onIncomingSurface = null
    }

    private fun mark(pos: Long) = Playback.mark(pos, scene?.name)

    private fun build(): ExoPlayer = carouselPlayer(ctx).apply {
        volume = 0f
        repeatMode = if (Scenes.perFilm) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE
        setVideoSurfaceView(video)
        setVideoFrameMetadataListener { ptsUs, _, _, _ ->
            val t = SystemClock.elapsedRealtime(); val pts = ptsUs / 1000
            lastPts = pts; lastFrameAt = t
            val j = pending ?: return@setVideoFrameMetadataListener
            if (t - j.at < 60) return@setVideoFrameMetadataListener
            // Still the old place (a frame queued before the jump): wait for the new one.
            if (j.prevAt > 0 && j.at - j.prevAt < 250 && abs(pts - j.prevPts) < 600) return@setVideoFrameMetadataListener
            pending = null
            h.post { landed() }
        }
        selfHeal { if (Scenes.perFilm) Playback.now() else Scenes.durationMs.let { d -> if (d > 0) Playback.now() % d else 0L } }
        addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) Log.i("Carousel", "film ended before the change") }
        })
        player = this
    }

    private fun buildNext(): ExoPlayer? {
        next?.let { return it }
        val sf = bd.incomingSurface ?: return null
        return carouselPlayer(ctx).apply { volume = 0f; setVideoSurface(sf); next = this }
    }

    private fun load(p: ExoPlayer, s: Scene) {
        val f = fileOf(s)
        if (f != mainFile || p.playbackState == Player.STATE_IDLE) {
            p.setMediaItem(MediaItem.fromUri(Uri.fromFile(f))); p.prepare(); mainFile = f
        }
        if (s.keyDir != null) { bd.keyDir = s.keyDir; bd.keyCount = s.keyCount }
    }

    /** Carry on where Home or the screensaver left off if there's enough of that film left; else pick the next film. */
    private fun resolve(): Long {
        if (Playback.known) {
            if (Scenes.perFilm) {
                val s = Scenes.list.firstOrNull { it.name == Playback.scene }
                val now = Playback.now()
                if (s != null && now < s.to - XF - PREP - 3000) { setScene(s); return now.coerceAtLeast(0) }
            } else {
                val d = Scenes.durationMs
                val now = if (d > 0) Playback.now() % d else 0L
                val s = Scenes.sceneAt(now)
                if (s != null && now >= s.from && now < s.to - XF - PREP - 3000) { setScene(s); return now }
            }
        }
        val s = Shuffle.next(scene) ?: return 0L
        setScene(s)
        return s.from
    }

    private fun setScene(s: Scene, notifyAfter: Long = 0) {
        if (s === scene) return
        scene = s
        if (s.keyDir != null) { bd.keyDir = s.keyDir; bd.keyCount = s.keyCount }
        h.removeCallbacks(notifyScene)
        if (notifyAfter > 0 && Motion.on) h.postDelayed(notifyScene, notifyAfter) else onScene(s)
    }
    private val notifyScene = Runnable { onScene(scene) }

    private fun jump(to: Long) {
        val p = player ?: return
        if (to != target) retries = 0
        target = to
        if (phase != Phase.XF_JUMP) phase = Phase.SEEKING
        pending = Jump(SystemClock.elapsedRealtime(), lastPts, lastFrameAt)
        if (phase == Phase.SEEKING) { bd.posMs = to }
        mark(to)
        p.seekTo(to)
        h.removeCallbacks(revealTimeout); h.postDelayed(revealTimeout, 3500)
    }

    /**
     * The box's decoder sometimes stalls after a cold start and never outputs a frame. Restart it up to twice (still
     * hidden); after that, show it anyway rather than hide the screen forever.
     */
    private var retries = 0
    private var target = 0L
    private val revealTimeout = Runnable {
        if (phase != Phase.SEEKING && phase != Phase.XF_JUMP) return@Runnable
        val p = player
        if (p != null && retries < 2) {
            retries++
            Log.w("Carousel", "no frame after jump, restarting the decoder (try $retries)")
            val s = if (phase == Phase.XF_JUMP) xfScene else scene
            p.stop(); mainFile = null; s?.let { load(p, it) }; jump(target); p.playWhenReady = running
        } else { Log.w("Carousel", "no frame after jump, carrying on"); pending = null; landed() }
    }

    /** Diagnostics: hold the veil at [v] for a while (to look at it), then carry on. */
    fun debugVeil(v: Float, ms: Long) {
        cancelAnims(); phase = Phase.IDLE; bd.veil = v; gl.requestRender()
        h.postDelayed({ phase = Phase.PLAYING; veilTo(0f, VEIL_OUT, Ease.out) {} }, ms)
    }

    /** The main player shows the new place. */
    private fun landed() {
        h.removeCallbacks(revealTimeout)
        retries = 0
        when (phase) {
            Phase.XF_JUMP -> startFade()   // the next film is playing on the plane, under the copy: fade the copy away
            Phase.SEEKING -> {
                phase = Phase.REVEALING
                if (!canVeil) {
                    ValueAnimator.ofFloat(bd.cover, 0f).apply {
                        duration = if (Motion.on) 1400 else 0; interpolator = Ease.inOut
                        addUpdateListener { bd.cover = it.animatedValue as Float; gl.requestRender() }
                        doOnEnd { if (phase == Phase.REVEALING) phase = Phase.PLAYING }
                        start()
                    }
                    return
                }
                val wait = if (mixDone) 0L else MIX / 2
                h.postDelayed({
                    if (phase != Phase.REVEALING) return@postDelayed
                    veilTo(0f, VEIL_OUT, Ease.out) { if (phase == Phase.REVEALING) phase = Phase.PLAYING }
                }, wait)
            }
            else -> {}
        }
    }

    /** Video position → glass keyframes and the shared timeline; starts the change near the film's end. */
    private val pump = object : Runnable {
        override fun run() {
            val p = player ?: return
            val s = scene
            when (phase) {
                Phase.PLAYING, Phase.REVEALING -> {
                    val pos = p.currentPosition
                    bd.posMs = pos; mark(pos)
                    if (phase == Phase.PLAYING && s != null) {
                        val left = s.to - pos
                        // A couple of seconds into each film, get the copy's player ready on it, so the change is a quick seek.
                        if (!xfBroken && preloadedFor !== s && pos > s.from + 2000 && left > XF + PREP + 1500) preloadCopy(s, p)
                        if (!xfBroken && left <= XF + PREP) beginXf()
                        else if (xfBroken && left <= VEIL_IN + 150) veilChange()
                        else if (pos < s.from - 1500) veilChange()   // looped or drifted out of the film
                    }
                }
                Phase.XF_PREP -> {
                    val pos = p.currentPosition; mark(pos); bd.posMs = pos
                    val np = next
                    when {
                        // The copy's player shows its first frame at the cue: hold it there (paused seeks don't draw here).
                        np != null && cuePlaying && bd.incomingFrames > xfFrames -> {
                            np.playWhenReady = false; cuePlaying = false; xfTarget = np.currentPosition
                        }
                        // The copy waits on the cued frame and the plane has just reached it: start the copy, fade it in.
                        np != null && !cuePlaying && pos >= xfTarget - 10 -> {
                            xfFails = 0
                            np.playWhenReady = true
                            phase = Phase.XF_COVER
                            Log.i("Carousel", "cover: plane ${pos - xfTarget}ms past the cued frame (lead $xfLead)")
                            fadeIncoming(1f, COVER) { jumpToNext() }
                        }
                        // The plane passed the cued frame before the copy was ready: cue further ahead.
                        np != null && cuePlaying && pos > xfTarget + 60 && cueTries < 2 -> {
                            cueTries++; xfLead = (xfLead + (pos - xfTarget) + 300).coerceAtMost(3000); cue(np, p)
                        }
                        s != null && (s.to - pos < XF + 1200 || SystemClock.elapsedRealtime() - xfPrepAt > 5000) -> {
                            Log.w("Carousel", "second decoder gave no picture in time: veil for this change")
                            if (++xfFails >= 3) xfBroken = true
                            val nx = xfScene; dropNext(); phase = Phase.PLAYING; veilChange(nx)
                        }
                    }
                }
                Phase.XF_COVER, Phase.XF_JUMP -> next?.let { n -> val pos = n.currentPosition; bd.posMs = pos; mark(pos) }
                else -> {}
            }
            if (veilAnim == null && mixAnim == null && fadeAnim == null) gl.requestRender()
            if (running) h.postDelayed(this, if (phase == Phase.XF_PREP) 16 else 100)
        }
    }

    // ---------------- cross-fade ----------------

    private var preloadedFor: Scene? = null

    private fun preloadCopy(cur: Scene, p: ExoPlayer) {
        preloadedFor = cur
        val np = buildNext() ?: return
        val f = fileOf(cur)
        if (f != nextFile || np.playbackState == Player.STATE_IDLE) { np.setMediaItem(MediaItem.fromUri(Uri.fromFile(f))); np.prepare(); nextFile = f }
        np.playWhenReady = false
        np.seekTo((cur.to - XF - PREP + xfLead).coerceAtLeast(cur.from))
    }

    private fun beginXf() {
        val cur = scene ?: return
        val p = player ?: return
        val nx = Shuffle.next(cur) ?: return
        val np = buildNext() ?: run { veilChange(nx); return }
        phase = Phase.XF_PREP; xfScene = nx; cueTries = 0; xfPrepAt = SystemClock.elapsedRealtime()
        // The copy plays the film that's ending, so the new film is only ever shown by the plane, at full sharpness.
        val f = fileOf(cur)
        if (f != nextFile || np.playbackState == Player.STATE_IDLE) { np.setMediaItem(MediaItem.fromUri(Uri.fromFile(f))); np.prepare(); nextFile = f }
        cue(np, p)
    }

    /** Play the copy from about [xfLead] ms ahead of the plane until its first frame is up, then hold it (see pump). */
    private fun cue(np: ExoPlayer, p: ExoPlayer) {
        xfTarget = p.currentPosition + xfLead
        xfFrames = bd.incomingFrames
        np.seekTo(xfTarget)
        np.playWhenReady = true; cuePlaying = true
    }

    /** Under the copy: the plane jumps to the next film and plays it; the fade starts on its first frame (landed()). */
    private fun jumpToNext() {
        val nx = xfScene ?: return
        val p = player ?: return
        phase = Phase.XF_JUMP
        load(p, nx)
        jump(nx.from)
        p.playWhenReady = true
    }

    /** The cross-fade itself: the copy of the old film fades out over the new film on the plane. */
    private fun startFade() {
        val nx = xfScene ?: return
        phase = Phase.XF_FADE
        // Glass: keep the old film's blur (before the keyframes switch to the new film), then blend to the new one.
        glassAnim?.cancel(); glassAnim = null
        bd.freeze(next?.currentPosition ?: bd.posMs)
        setScene(nx, notifyAfter = XF / 2)
        fadeIncoming(0f, XF) { dropNext(); phase = Phase.PLAYING }
        glassAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = if (Motion.on) GLASS_LAG else 0; duration = if (Motion.on) XF else 0
            interpolator = Ease.inOut
            addUpdateListener { bd.swapMix = it.animatedValue as Float; gl.requestRender() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { bd.swapMix = 1f; if (glassAnim === this@apply) glassAnim = null; gl.requestRender() }
            })
            start()
        }
    }
    private var glassAnim: ValueAnimator? = null

    private fun fadeIncoming(to: Float, ms: Long, end: () -> Unit) {
        fadeAnim?.cancel()
        val swap = phase == Phase.XF_FADE
        fadeAnim = ValueAnimator.ofFloat(bd.incoming, to).apply {
            duration = if (Motion.on) ms else 0; interpolator = Ease.inOut
            addUpdateListener {
                val v = it.animatedValue as Float
                bd.incoming = v
                if (swap) player?.let { p -> bd.posMs = p.currentPosition }
                else if (phase == Phase.XF_COVER) next?.let { n -> bd.posMs = n.currentPosition }
                gl.requestRender()
            }
            var cancelled = false
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationCancel(a: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(a: android.animation.Animator) {
                    if (fadeAnim === this@apply) fadeAnim = null
                    if (!cancelled) end()
                }
            })
            start()
        }
    }

    /** Hide the copy and pause its player (it stays ready for the next change; [stop] frees its decoder). */
    private fun dropNext() {
        fadeAnim?.cancel(); fadeAnim = null
        bd.incoming = 0f
        next?.pause()
        xfScene = null
    }

    // ---------------- veil change (fallback) ----------------

    private fun veilChange(chosen: Scene? = null) {
        if (!canVeil) { (chosen ?: Shuffle.next(scene))?.let { s -> player?.let { load(it, s) }; setScene(s); jump(s.from) }; return }
        phase = Phase.VEILING
        veilTo(1f, VEIL_IN, easeIn) {
            val p = player ?: return@veilTo
            val nx = chosen ?: Shuffle.next(scene) ?: run { phase = Phase.PLAYING; return@veilTo }
            bd.freeze(p.currentPosition)
            setScene(nx)
            load(p, nx)
            jump(nx.from)
            mixDone = false
            mixAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = if (Motion.on) MIX else 0; interpolator = Ease.inOut
                addUpdateListener { bd.swapMix = it.animatedValue as Float; gl.requestRender() }
                doOnEnd { mixAnim = null; mixDone = true; bd.swapMix = 1f }
                start()
            }
        }
    }

    private fun veilTo(to: Float, ms: Long, ease: TimeInterpolator, end: () -> Unit) {
        veilAnim?.cancel()
        if (!Motion.on) { bd.veil = to; gl.requestRender(); veilAnim = null; end(); return }
        veilAnim = ValueAnimator.ofFloat(bd.veil, to).apply {
            duration = ms; interpolator = ease
            addUpdateListener {
                bd.veil = it.animatedValue as Float
                if (phase == Phase.VEILING || phase == Phase.REVEALING) player?.let { p -> bd.posMs = p.currentPosition }
                gl.requestRender()
            }
            var cancelled = false
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationCancel(a: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(a: android.animation.Animator) { if (veilAnim === this@apply) veilAnim = null; if (!cancelled) end() }
            })
            start()
        }
    }

    private fun cancelAnims() {
        veilAnim?.cancel(); veilAnim = null
        mixAnim?.cancel(); mixAnim = null; mixDone = true; glassAnim?.cancel(); glassAnim = null; bd.swapMix = 1f
        fadeAnim?.cancel(); fadeAnim = null
    }
}
