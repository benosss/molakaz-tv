package com.lagoon.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.media.audiofx.Visualizer
import android.os.SystemClock
import android.util.Log
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Live spectrum of whatever the box is playing (Android's Visualizer on the output mix, permission granted over adb).
 * Bands are log-spaced 60 Hz – 10 kHz. If the box hands us silence (protected or passthrough audio), [live] goes
 * false and the bars fall back to a gentle synthetic motion so the card never looks frozen.
 */
object AudioViz {
    const val BANDS = 11
    private val level = FloatArray(BANDS)
    private var v: Visualizer? = null
    private var lastSignal = 0L
    val live get() = v != null && SystemClock.uptimeMillis() - lastSignal < 1500
    fun level(i: Int) = level[i]

    private val owners = HashSet<String>()

    /** Start listening for [who] ("np": Now playing, "music": a song preview); it runs while anyone wants it. */
    fun start(ctx: Context, who: String = "np") {
        owners += who
        if (v != null) return
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        v = runCatching {
            Visualizer(0).apply {
                enabled = false
                captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
                scalingMode = Visualizer.SCALING_MODE_NORMALIZED
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(vis: Visualizer?, wave: ByteArray?, rate: Int) {}
                    override fun onFftDataCapture(vis: Visualizer?, fft: ByteArray?, rate: Int) { if (fft != null) analyse(fft, rate / 1000f) }
                }, Visualizer.getMaxCaptureRate(), false, true)
                enabled = true
            }
        }.onFailure { Log.w("AudioViz", "no visualizer: $it") }.getOrNull()
    }

    fun stop(who: String = "np") {
        owners -= who
        if (owners.isNotEmpty()) return
        v?.let { runCatching { it.enabled = false; it.release() } }
        v = null
    }

    private val edges = FloatArray(BANDS + 1) { i -> (60.0 * (10_000.0 / 60.0).pow(i / BANDS.toDouble())).toFloat() }

    private fun analyse(fft: ByteArray, sampleRate: Float) {
        val n = fft.size; val binHz = sampleRate / n
        var any = false
        for (b in 0 until BANDS) {
            val lo = max(1, (edges[b] / binHz).toInt()); val hi = max(lo, (edges[b + 1] / binHz).toInt()).coerceAtMost(n / 2 - 1)
            var m = 0f
            for (k in lo..hi) m = max(m, hypot(fft[2 * k].toFloat(), fft[2 * k + 1].toFloat()))
            // ~14 dB floor to ~48 dB peak, a little contrast, and a slight lift for the quieter top end
            // (tuned on this box: the old 6 dB floor kept most bars pinned near the top).
            val db = 20f * log10(m + 1e-3f)
            val x = ((db - 14f) / 34f).coerceIn(0f, 1f).pow(1.4f) * (1f + 0.25f * b / BANDS)
            level[b] = x.coerceAtMost(1f)
            if (m > 2f) any = true
        }
        if (any) lastSignal = SystemClock.uptimeMillis()
    }
}

/** Mirrored equaliser bars in the app's brand colour; eases up fast, falls back slowly, rests as small dots. */
class EqView(ctx: Context) : View(ctx) {
    var color = 0xFF4ADE80.toInt()
        set(v) { if (field != v) { field = v; shaderH = -1 }; invalidate() }
    private val n = AudioViz.BANDS
    private val shown = FloatArray(n) { 0.08f }
    private val phase = FloatArray(n * 2) { Random.nextFloat() * 6.28f }
    private var playing = false
    private var lastNs = 0L
    private var shaderH = -1
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    fun setPlaying(on: Boolean) {
        if (on == playing) return
        playing = on; lastNs = 0L; postInvalidateOnAnimation()
    }

    private fun synth(i: Int, t: Float): Float {
        val tilt = 0.5f + 0.4f * sin(PI.toFloat() * (i + 0.5f) / n)
        val a = sin(t * (2.1f + i * 0.37f) + phase[i]) * 0.5f + 0.5f
        val b = sin(t * (4.7f + i * 0.53f) + phase[n + i]) * 0.5f + 0.5f
        val beat = (sin(t * 7.5f) * 0.5f + 0.5f).pow(6)
        return (tilt * (0.3f + 0.45f * a * b + 0.3f * beat)).coerceIn(0.08f, 1f)
    }

    override fun onDraw(c: Canvas) {
        val h = height.toFloat(); if (h <= 0f) return
        if (shaderH != height) {
            shaderH = height
            val light = mix(color, 0xFFFFFFFF.toInt(), 0.55f)
            bar.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(light, color, light), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            glow.color = color; glow.alpha = 70
        }
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 1f / 60f else ((now - lastNs) / 1e9f).coerceAtMost(0.05f)
        lastNs = now
        val t = (now % 3_600_000_000_000L) / 1e9f
        val live = AudioViz.live
        var moving = false
        for (i in 0 until n) {
            val target = when { !playing -> 0.06f; live -> max(0.06f, AudioViz.level(i)); else -> synth(i, t) }
            val k = if (target > shown[i]) 24f else 7f
            shown[i] += (target - shown[i]) * (1f - exp(-k * dt))
            if (abs(target - shown[i]) > 0.002f) moving = true
        }
        val bw = D.u(3f); val gap = (width - n * bw) / (n - 1); val cy = h / 2f
        for (i in 0 until n) {
            val bh = max(bw, shown[i] * h)
            val x = i * (bw + gap)
            r.set(x - bw * 0.6f, cy - bh / 2f - bw * 0.3f, x + bw * 1.6f, cy + bh / 2f + bw * 0.3f)
            c.drawRoundRect(r, bw, bw, glow)
            r.set(x, cy - bh / 2f, x + bw, cy + bh / 2f)
            c.drawRoundRect(r, bw / 2f, bw / 2f, bar)
        }
        // 30 fps is plenty for bars and keeps the full-screen UI layer from redrawing every vsync.
        if (playing || moving) postInvalidateDelayed(28)
    }
}
