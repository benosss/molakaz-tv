package com.lagoon.home

import android.animation.ValueAnimator
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Quiet nature sound behind the films. The films change every ~20 s, so the sound doesn't follow them: it follows the
 * moment instead, like the film choice does (birds at dawn, a shore by day, waves at golden hour, distant surf at night,
 * rain when it rains). Beds are long seamless loops; a change is a slow equal-power cross-fade, at most every
 * [MIN_CHANGE] ms. It fades out whenever another app plays something, and comes back when it stops.
 * Files: ambient/<dawn|day|golden|night|rain>.m4a next to the films.
 */
@OptIn(UnstableApi::class)
object Ambient {
    enum class Where { SAVER, HOME }
    const val OFF = 0; const val SAVER_ONLY = 1; const val EVERYWHERE = 2
    private const val XFADE = 12_000L
    private const val MIN_CHANGE = 10 * 60_000L
    private const val LEVEL = 0.9f

    private val h = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    private val active = HashSet<Where>()
    private val players = arrayOfNulls<ExoPlayer>(2)
    private val beds = arrayOfNulls<String>(2)
    private var cur = 0
    private var changedAt = 0L
    private var anim: ValueAnimator? = null
    private var level = 0f      // 0..1 master fade (in/out of Home/screensaver, ducking)
    private var mix = 1f        // 0..1 from the other player to the current one

    var mode: Int
        get() = Prefs.ambient
        set(v) { Prefs.ambient = v; update() }

    fun label(m: Int = mode) = when (m) { OFF -> "Off"; SAVER_ONLY -> "On the screensaver"; else -> "Screensaver and Home" }

    fun init(ctx: Context) { app = ctx.applicationContext; MediaHub.listen { update() } }

    fun enter(w: Where) { active += w; update(); h.removeCallbacks(tick); h.postDelayed(tick, 30_000) }
    fun leave(w: Where) { active -= w; update() }

    private val tick = object : Runnable { override fun run() { update(); if (active.isNotEmpty()) h.postDelayed(this, 30_000) } }

    private fun dir() = File(app.getExternalFilesDir(null), "ambient")

    private var held = false
    /** A music preview's clip is playing: the bed fades out until it's done. */
    fun hold(on: Boolean) { if (held != on) { held = on; update() } }
    /** The bed can be heard right now. */
    val audible get() = level > 0.05f && players.any { it?.isPlaying == true }

    private fun wanted() = ((Where.SAVER in active && mode >= SAVER_ONLY) || (Where.HOME in active && mode >= EVERYWHERE)) && !MediaHub.anyPlaying() && !held

    private fun pick(): String = if (Shuffle.sky() == Shuffle.Sky.RAIN) "rain" else when (Shuffle.moment()) {
        Shuffle.Moment.DAWN -> "dawn"; Shuffle.Moment.DAY -> "day"; Shuffle.Moment.GOLDEN -> "golden"; Shuffle.Moment.NIGHT -> "night"
    }

    private fun update() {
        if (!::app.isInitialized) return
        if (!wanted()) { fadeLevel(0f, 1500) { players.forEach { it?.pause() } }; return }
        val want = pick()
        val now = SystemClock.elapsedRealtime()
        when {
            beds[cur] == null -> { start(cur, want); mix = 1f; changedAt = now }
            beds[cur] != want && now - changedAt > MIN_CHANGE -> { val nx = 1 - cur; start(nx, want); cur = nx; mix = 0f; changedAt = now; Log.i("Ambient", "cross-fading to $want") }
            else -> players[cur]?.play()
        }
        if (level < 1f || mix < 1f) fadeLevel(1f, if (level < 0.01f) 7000 else XFADE) {}
    }

    private fun start(i: Int, bed: String) {
        val f = File(dir(), "$bed.m4a"); if (!f.exists()) return
        val p = players[i] ?: ExoPlayer.Builder(app).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), false)
            repeatMode = Player.REPEAT_MODE_ONE; volume = 0f
            players[i] = this
        }
        if (beds[i] != bed) {
            // Start somewhere in the loop, so every visit doesn't open on the same wave.
            p.setMediaItem(MediaItem.fromUri(Uri.fromFile(f))); p.prepare(); beds[i] = bed
            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) { p.removeListener(this); val d = p.duration; if (d > 20_000) p.seekTo((Math.random() * (d - 10_000)).toLong()) }
                }
            })
        }
        p.play()
    }

    /** One animator drives both the master level and the cross-fade between the two beds (equal power). */
    private fun fadeLevel(to: Float, ms: Long, end: () -> Unit) {
        anim?.cancel()
        val fromLevel = level; val fromMix = mix
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms
            addUpdateListener {
                val t = it.animatedFraction
                level = fromLevel + (to - fromLevel) * t
                if (to > 0f) mix = fromMix + (1f - fromMix) * t
                apply()
            }
            var cancelled = false
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationCancel(a: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(a: android.animation.Animator) {
                    if (!cancelled) { if (mix >= 1f) players[1 - cur]?.pause(); end() }
                }
            })
            start()
        }
    }

    /** Volume is linear in amplitude; squaring the fade makes fades in and out sound even to the ear. */
    private fun apply() {
        val a = (mix * PI / 2).toFloat()
        val l = level * level
        players[cur]?.volume = LEVEL * l * sin(a)
        players[1 - cur]?.volume = LEVEL * l * cos(a)
    }
}
