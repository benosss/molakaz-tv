package com.lagoon.home

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.text.format.DateFormat
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import java.io.File
import java.util.Date
import kotlin.random.Random

/**
 * System screensaver: the same scenic carousel, full brightness, with clock, date and where it was filmed.
 * Picks up the film exactly where the home screen left it, shuffles on from there, and hands the position back on wake.
 */
class VideoDream : DreamService() {
    private var carousel: Carousel? = null
    private lateinit var gl: GLSurfaceView
    private val h = Handler(Looper.getMainLooper())
    private lateinit var cover: View
    private lateinit var block: LinearLayout
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var placeRow: LinearLayout
    private lateinit var placeText: TextView
    private lateinit var aboutText: TextView
    private var lastPlace: String? = "\u0000"

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false; isFullscreen = true; isScreenBright = true
        D.s = resources.displayMetrics.widthPixels / 1280f
        val dir = getExternalFilesDir(null)
        Scenes.load(File(dir, "scenes.json"))

        val root = FrameLayout(this)
        val video = SurfaceView(this)  // dream window is opaque; keep the standard hole
        root.addView(video, FrameLayout.LayoutParams(MATCH, MATCH))
        // The veil layer (blurred video for the jumps between films), no dimming: the screensaver stays bright.
        gl = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2); setEGLConfigChooser(8, 8, 8, 8, 0, 0)
            // Full 1080p here (Home uses 720p for its glass): during each cross-fade the outgoing film is drawn on this
            // layer, and the screensaver has nothing else on it, so it can afford to keep the picture sharp.
            holder.setFormat(PixelFormat.TRANSLUCENT); setZOrderMediaOverlay(true); holder.setFixedSize(1920, 1080)
        }
        // Bright by day; at night a gentle dim, so a dark room isn't lit up by a noon beach.
        val night = Shuffle.moment() == Shuffle.Moment.NIGHT
        val bd = Backdrop(gl).apply { dimBase = if (night) 0.22f else 0f; dimTop = 0f; dimBottom = 0f; glassPanels = false; screenW = resources.displayMetrics.widthPixels.toFloat() }
        gl.setRenderer(bd); gl.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        root.addView(gl, FrameLayout.LayoutParams(MATCH, MATCH))
        // Soft vignette behind the text only, so the scenery stays bright.
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BL_TR, intArrayOf(argbA(0.55f, 0x000A14), argbA(0.18f, 0x000A14), 0))
        }, FrameLayout.LayoutParams(MATCH, MATCH))

        block = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        clock = label(108f, "sans-serif-light", shadow = true).apply { ellipsize = null; setPadding(0, 0, D.ui(6f), 0) }
        date = label(24f, "sans-serif-medium", shadow = true)
        placeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; alpha = 0f }
        placeText = label(17f, "sans-serif-medium", shadow = true)
        placeRow.addView(ImageView(this).apply { setImageResource(R.drawable.ic_pin) }, LinearLayout.LayoutParams(D.ui(16f), D.ui(16f)).apply { rightMargin = D.ui(7f) })
        placeRow.addView(placeText)
        // Explicit WRAP: a vertical LinearLayout defaults children to MATCH_PARENT, and with a WRAP sibling (the place row)
        // it sizes itself to that sibling only, which clipped the clock to "5:.." under short place names.
        block.addView(clock, LinearLayout.LayoutParams(WRAP, WRAP)); block.addView(date, LinearLayout.LayoutParams(WRAP, WRAP))
        block.addView(placeRow, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = D.ui(14f) })
        // Landmarks: one line to learn something by, under the place.
        aboutText = label(15f, shadow = true).apply { alpha = 0f }
        block.addView(aboutText, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = D.ui(4f); leftMargin = D.ui(23f) })
        root.addView(block, place(WRAP, WRAP, l = 72f, b = 64f))

        qrCard = buildQrCard()?.also { root.addView(it, place(WRAP, WRAP, r = 72f, b = 64f)) }

        cover = View(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(cover, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)

        // The GL layer does the fading in now (blur, then sharp), so the black cover only bridges until it draws.
        cover.alpha = if (Scenes.count > 0) 1f else 0f
        Carousel.wireKeys(this, bd) { gl.requestRender() }
        carousel = Carousel(this, video, gl, bd) { s -> showPlace(s?.label, s?.about) }.also { it.prime() }
        h.postDelayed({ cover.animate().alpha(0f).setDuration(250).start() }, 120)
    }

    // ---------- phone remote card: the first minute of every screensaver ----------

    private var qrCard: View? = null
    private var qrBar: View? = null
    private val QR_MS = 60_000L

    /** Smoked-glass card, bottom right, level with the clock: code, a short line, the address, and a one-minute countdown. */
    private fun buildQrCard(): View? {
        val url = Remote.url() ?: return null
        val pad = D.ui(28f)
        // Vertical LinearLayout: the countdown (match width) takes the width of the row above it, not the screen's.
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; alpha = 0f
            background = GradientDrawable().apply {
                cornerRadius = D.u(30f)
                colors = intArrayOf(argbA(0.40f, 0x0A1A24), argbA(0.52f, 0x061018)); orientation = GradientDrawable.Orientation.TL_BR
                setStroke(D.ui(1.2f), argbA(0.22f, 0xFFFFFF))
            }
            elevation = D.u(18f)
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(pad, pad, pad + D.ui(10f), D.ui(22f)) }
        val qrSize = D.ui(124f)
        row.addView(ImageView(this).apply {
            setImageBitmap(Remote.qr(url, qrSize))
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = D.u(16f) }
            val ip = D.ui(11f); setPadding(ip, ip, ip, ip)
        }, LinearLayout.LayoutParams(qrSize + D.ui(22f), qrSize + D.ui(22f)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(label(23f, "sans-serif-medium", shadow = true).apply { text = "Phone remote" })
        col.addView(label(16f, shadow = true).apply { text = "Scan with your phone's camera\nto control this TV"; maxLines = 2; ellipsize = null; alpha = 0.86f; setLineSpacing(D.u(4f), 1f) },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = D.ui(10f) })
        col.addView(label(14f, shadow = true).apply { text = url.removePrefix("http://"); alpha = 0.6f; letterSpacing = 0.02f },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = D.ui(16f) })
        row.addView(col, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = D.ui(30f) })
        card.addView(row, LinearLayout.LayoutParams(WRAP, WRAP))
        // Countdown hairline under the content.
        val line = FrameLayout(this)
        val track = View(this).apply { background = GradientDrawable().apply { setColor(argbA(0.18f, 0xFFFFFF)); cornerRadius = D.u(2f) } }
        val bar = View(this).apply { background = GradientDrawable().apply { setColor(argbA(0.85f, 0xFFFFFF)); cornerRadius = D.u(2f) }; pivotX = 0f }
        line.addView(track, FrameLayout.LayoutParams(MATCH, MATCH)); line.addView(bar, FrameLayout.LayoutParams(MATCH, MATCH))
        card.addView(line, LinearLayout.LayoutParams(MATCH, D.ui(3f)).apply { leftMargin = pad; rightMargin = pad; bottomMargin = D.ui(22f) })
        qrBar = bar
        return card
    }

    private fun showQrCard() {
        val c = qrCard ?: return
        c.translationY = D.u(14f)
        c.animate().alpha(1f).translationY(0f).setDuration(700).setInterpolator(Ease.out).start()
        qrBar?.let { b -> b.scaleX = 1f; b.animate().scaleX(0f).setDuration(QR_MS).setInterpolator(android.view.animation.LinearInterpolator()).start() }
        h.postDelayed({ c.animate().alpha(0f).translationY(D.u(8f)).setDuration(900).setInterpolator(Ease.exit).start() }, QR_MS)
    }

    private fun showPlace(label: String?, about: String? = null) {
        if (label == lastPlace) return
        lastPlace = label
        if (label == null) { placeRow.fadeTo(0f); aboutText.fadeTo(0f); return }
        placeRow.fadeTo(0f, -D.u(6f), 240) {
            placeText.text = label; placeRow.translationY = D.u(8f); placeRow.fadeTo(1f, 0f, 700)
        }
        aboutText.fadeTo(0f, -D.u(6f), 240) {
            aboutText.text = about.orEmpty(); aboutText.translationY = D.u(8f)
            if (!about.isNullOrBlank()) aboutText.fadeTo(0.78f, 0f, 700, 90)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            val now = Date()
            clock.text = DateFormat.format(if (DateFormat.is24HourFormat(this@VideoDream)) "HH:mm" else "h:mm", now)
            date.text = DateFormat.format("EEEE d MMMM", now)
            h.postDelayed(this, 1000)
        }
    }

    /** Burn-in care: the text block drifts a little every minute, slowly enough to go unnoticed. */
    private val drift = object : Runnable {
        override fun run() {
            block.animate().translationX(D.u(Random.nextInt(-24, 25).toFloat())).translationY(D.u(Random.nextInt(-16, 17).toFloat()))
                .setDuration(4000).setInterpolator(Ease.inOut).start()
            h.postDelayed(this, 60_000)
        }
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted(); gl.onResume(); carousel?.start(); h.post(tick); h.postDelayed(drift, 60_000)
        Ambient.enter(Ambient.Where.SAVER)
        h.postDelayed({ showQrCard() }, 1600)
    }

    override fun onDreamingStopped() {
        h.removeCallbacks(tick); h.removeCallbacks(drift)
        carousel?.pause(); Ambient.leave(Ambient.Where.SAVER)
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        carousel?.release(); carousel = null
        gl.onPause()
        super.onDetachedFromWindow()
    }
}
