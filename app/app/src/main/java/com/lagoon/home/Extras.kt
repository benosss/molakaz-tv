package com.lagoon.home

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import kotlin.concurrent.thread
import kotlin.math.roundToInt

private val mainH = Handler(Looper.getMainLooper())

/** my.t live TV: found by package (operator box and app versions), or by an app whose name is "my.t". */
object LiveTv {
    private val KNOWN = listOf("com.mttv.muott", "com.zte.iptvclient.android.mu", "mu.mt.iptv", "mu.mt.tv", "com.google.android.tv")

    fun find(ctx: Context): String? {
        val pm = ctx.packageManager
        KNOWN.firstOrNull { launchIntent(pm, it) != null }?.let { return it }
        for (cat in arrayOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER))
            for (ri in pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                val label = ri.loadLabel(pm).toString().lowercase()
                if (Regex("""\bmy\.?t\b""").containsMatchIn(label) && "care" !in label && "money" !in label) return ri.activityInfo.packageName
            }
        return null
    }

    private fun launchIntent(pm: PackageManager, pkg: String) = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)

    fun label(ctx: Context, pkg: String) = runCatching { ctx.packageManager.getApplicationInfo(pkg, 0).loadLabel(ctx.packageManager).toString() }.getOrDefault("Live TV")

    fun open(ctx: Context): Boolean {
        val pkg = find(ctx) ?: return false
        val i = launchIntent(ctx.packageManager, pkg) ?: return false
        return runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }
}

/** The box's own home screen (Google TV launcher): switched off so Home is the only one, and back with one press. */
object OriginalHome {
    private val PKGS = listOf("com.google.android.tvlauncher", "com.google.android.tvrecommendations", "com.google.android.apps.tv.launcherx")

    fun present(ctx: Context) = PKGS.filter { runCatching { ctx.packageManager.getPackageInfo(it, PackageManager.MATCH_DISABLED_COMPONENTS) }.isSuccess }

    fun exists(ctx: Context) = present(ctx).isNotEmpty()

    fun isOff(ctx: Context) = present(ctx).firstOrNull()?.let {
        ctx.packageManager.getApplicationEnabledSetting(it) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
    } ?: false

    /** Runs through the box's adb (the same one-time approval as the phone remote). */
    fun set(ctx: Context, off: Boolean, done: (Boolean) -> Unit) {
        val pkgs = present(ctx)
        thread(name = "orig-home") {
            val cmd = pkgs.joinToString("; ") { if (off) "pm disable-user --user 0 $it" else "pm enable $it" }
            val ok = Remote.shell(cmd) != null
            // MoLakaz TV chosen again after the safety net handed the TV back: the crash count starts afresh.
            if (ok && off) Safety.reset()
            mainH.post { done(ok && isOff(ctx) == off) }
        }
    }
}

/**
 * Sleep timer. In the last minute the music fades out and the screen dims to black, with "press any button to keep
 * watching" on top of whatever app is open; then playback pauses, the box goes to standby and the volume comes back to
 * where it was, ready for tomorrow.
 */
object Goodnight {
    const val FADE = 60_000L
    var endsAt = 0L
        private set
    val active get() = endsAt > System.currentTimeMillis()
    private val listeners = ArrayList<() -> Unit>()
    fun listen(f: () -> Unit) { listeners += f }
    private lateinit var app: Context
    private var savedVol = -1
    private var overlay: Overlay? = null

    /** Restores a timer that was running (or the nightly bedtime) after the app restarts. */
    fun init(ctx: Context) {
        app = ctx.applicationContext
        val at = Prefs.goodnightAt
        when {
            at > System.currentTimeMillis() + FADE -> arm(at, quiet = true)
            daily >= 0 -> arm(nextAt(daily), quiet = true)
        }
    }

    /** Bedtime every night, as minutes after midnight (-1 = off). */
    val daily get() = Prefs.goodnightDaily

    fun minutesLeft() = if (!active) 0 else ((endsAt - System.currentTimeMillis() + 59_999) / 60_000).toInt()

    /** In [minutes] from now (0 = off for tonight). */
    fun set(minutes: Int) {
        cancel(notify = false)
        if (minutes <= 0) { changed(); return }
        arm(System.currentTimeMillis() + minutes * 60_000L)
    }

    /** At a time of day: tonight (or tomorrow if that time has passed), and every night if [everyNight]. */
    fun setAt(minuteOfDay: Int, everyNight: Boolean) {
        cancel(notify = false)
        Prefs.goodnightDaily = if (everyNight) minuteOfDay else -1
        arm(nextAt(minuteOfDay))
    }

    /** Off, including the nightly bedtime. */
    fun off() { Prefs.goodnightDaily = -1; cancel() }

    /** The next time the clock shows [minuteOfDay], at least two minutes away. */
    fun nextAt(minuteOfDay: Int): Long {
        val c = java.util.Calendar.getInstance()
        c.set(java.util.Calendar.HOUR_OF_DAY, minuteOfDay / 60); c.set(java.util.Calendar.MINUTE, minuteOfDay % 60)
        c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0)
        if (c.timeInMillis < System.currentTimeMillis() + 2 * 60_000L) c.add(java.util.Calendar.DAY_OF_MONTH, 1)
        return c.timeInMillis
    }

    private fun arm(at: Long, quiet: Boolean = false) {
        endsAt = at; Prefs.goodnightAt = at
        mainH.postDelayed(beginFade, (at - System.currentTimeMillis() - FADE).coerceAtLeast(0))
        Log.i("Goodnight", "armed for ${java.util.Date(at)} (every night: ${daily >= 0})")
        // The dimming layer over other apps needs "draw over other apps"; grant it now so the last minute works anywhere.
        if (!android.provider.Settings.canDrawOverlays(app)) thread { Remote.shell("appops set ${app.packageName} SYSTEM_ALERT_WINDOW allow") }
        if (!quiet) changed()
    }

    /** Diagnostics: the last-minute screen for a few seconds, without touching the sound or the timer. */
    fun preview() {
        val o = Overlay(app) { }
        o.show(); var left = 47_000L
        val tick = object : Runnable { override fun run() { o.update(0.35f, left); left -= 1000; if (left > 39_000) mainH.postDelayed(this, 1000) else o.dismiss() } }
        mainH.post(tick)
    }

    fun cancel(notify: Boolean = true) {
        mainH.removeCallbacks(beginFade); mainH.removeCallbacks(fadeStep); mainH.removeCallbacks(sleepNow)
        restoreVolume()
        overlay?.dismiss(); overlay = null
        endsAt = 0L; Prefs.goodnightAt = 0L
        if (notify) changed()
    }

    /** "Keep watching" in the last minute: skip tonight; a nightly bedtime comes back tomorrow. */
    private fun keepWatching() { cancel(notify = false); if (daily >= 0) arm(nextAt(daily), quiet = true); changed() }

    private fun changed() = listeners.forEach { it() }

    private val am get() = app.getSystemService(AudioManager::class.java)

    private val beginFade = Runnable {
        savedVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        overlay = Overlay(app) { keepWatching() }.also { it.show() }
        mainH.post(fadeStep); mainH.postDelayed(sleepNow, (endsAt - System.currentTimeMillis()).coerceAtLeast(0))
    }

    /** Music down in even steps, screen towards black, a little every second. */
    private val fadeStep = object : Runnable {
        override fun run() {
            val left = (endsAt - System.currentTimeMillis()).coerceAtLeast(0)
            val t = 1f - left.toFloat() / FADE
            if (savedVol > 0) am.setStreamVolume(AudioManager.STREAM_MUSIC, (savedVol * (1f - t)).roundToInt(), 0)
            overlay?.update(t, left)
            if (left > 0) mainH.postDelayed(this, 1000)
        }
    }

    private val sleepNow = Runnable {
        Log.i("Goodnight", "sleeping")
        MediaHub.pauseAll()
        endsAt = 0L
        thread { Remote.key(KeyEvent.KEYCODE_SLEEP) }
        // Give the pause a moment to land, then put the volume back for next time (nothing plays now).
        Prefs.goodnightAt = 0L
        mainH.postDelayed({ restoreVolume(); overlay?.dismiss(); overlay = null; if (daily >= 0) arm(nextAt(daily), quiet = true); changed() }, 2500)
    }

    private fun restoreVolume() {
        if (savedVol >= 0) runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, savedVol, 0) }
        savedVol = -1
    }

    /** Full-screen black that darkens over the last minute; focusable, so any button press reaches it and cancels. */
    private class Overlay(private val ctx: Context, private val onKey: () -> Unit) {
        private val wm = ctx.getSystemService(WindowManager::class.java)
        private var root: FrameLayout? = null
        private val title = ctx.label(30f, "sans-serif-light", shadow = true)
        private val sub = ctx.label(17f, shadow = true).apply { alpha = 0.8f; text = tr("Press any button to keep watching") }

        fun show() {
            if (!android.provider.Settings.canDrawOverlays(ctx)) return
            val r = object : FrameLayout(ctx) {
                override fun dispatchKeyEvent(e: KeyEvent): Boolean { if (e.action == KeyEvent.ACTION_UP) onKey(); return true }
            }.apply { setBackgroundColor(Color.BLACK); alpha = 0f; isFocusable = true; isFocusableInTouchMode = true }
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                setPadding(D.ui(34f), D.ui(24f), D.ui(34f), D.ui(24f))
                background = GradientDrawable().apply { setColor(argbA(0.10f, 0xFFFFFF)); cornerRadius = D.u(26f); setStroke(D.ui(1f), argbA(0.18f, 0xFFFFFF)) }
            }
            card.addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_moon); setColorFilter(Color.WHITE) }, LinearLayout.LayoutParams(D.ui(34f), D.ui(34f)))
            card.addView(title, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = D.ui(12f) })
            card.addView(sub, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = D.ui(6f) })
            r.addView(card, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
            val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT)
            runCatching { wm.addView(r, lp); r.requestFocus(); root = r }.onFailure { Log.w("Goodnight", "overlay: $it") }
        }

        fun update(t: Float, leftMs: Long) {
            if (root == null) show()   // the permission may have landed after the fade began
            val r = root ?: return
            // The card stays readable while everything behind it goes dark.
            r.setBackgroundColor(argbA((0.25f + 0.70f * t).coerceAtMost(0.95f), 0x000000))
            if (r.alpha < 1f) r.animate().alpha(1f).setDuration(600).start()
            val s = (leftMs / 1000).toInt()
            title.text = tr("Goodnight in {time}", "time" to "0:%02d".format(s.coerceAtLeast(0)))
        }

        fun dismiss() {
            val r = root ?: return
            root = null
            r.animate().alpha(0f).setDuration(300).withEndAction { runCatching { wm.removeView(r) } }.start()
        }
    }
}

/**
 * Answers "lagoon.local" on the home network (multicast DNS), so the phone remote has a name that survives the router
 * handing the box a new address. iPhones resolve .local names everywhere; Android phones only sometimes.
 */
object Mdns {
    const val HOST = "lagoon"
    val url get() = "http://$HOST.local:${Remote.PORT}"
    private val GROUP: InetAddress = InetAddress.getByName("224.0.0.251")
    private const val PORT = 5353
    @Volatile var running = false
        private set

    fun start(ctx: Context) {
        if (running) return
        running = true
        val wifi = ctx.applicationContext.getSystemService(WifiManager::class.java)
        // Wi-Fi drops multicast to save power unless someone holds this lock.
        val lock = runCatching { wifi?.createMulticastLock("lagoon-mdns")?.apply { setReferenceCounted(false); acquire() } }.getOrNull()
        thread(name = "mdns", isDaemon = true) {
            while (true) {
                val ip = Remote.lanAddress()
                if (ip == null) { Thread.sleep(5000); continue }
                runCatching { serve(ip) }.onFailure { Log.w("Mdns", "restart: $it") }
                Thread.sleep(2000)
            }
        }
        if (lock == null) Log.w("Mdns", "no multicast lock")
    }

    private fun serve(ip: Inet4Address) {
        val nif = NetworkInterface.getByInetAddress(ip)
        MulticastSocket(null).use { s ->
            s.reuseAddress = true
            s.bind(InetSocketAddress(PORT))
            s.timeToLive = 255
            if (nif != null) { s.networkInterface = nif; s.joinGroup(InetSocketAddress(GROUP, PORT), nif) } else s.joinGroup(GROUP)
            s.soTimeout = 20_000
            Log.i("Mdns", "answering $HOST.local = ${ip.hostAddress}")
            repeat(2) { send(s, answer(ip, 0, null, 120), InetSocketAddress(GROUP, PORT)); Thread.sleep(1000) }
            val buf = ByteArray(9000)
            while (true) {
                val p = DatagramPacket(buf, buf.size)
                try { s.receive(p) } catch (e: SocketTimeoutException) { if (Remote.lanAddress() != ip) return; continue }
                val q = question(buf, p.length) ?: continue
                val legacy = p.port != PORT
                val reply = answer(ip, if (legacy) q.id else 0, if (legacy) q.raw else null, if (legacy) 10 else 120, q.type == 28)
                send(s, reply, if (legacy || q.unicast) InetSocketAddress(p.address, p.port) else InetSocketAddress(GROUP, PORT))
            }
        }
    }

    private class Q(val id: Int, val unicast: Boolean, val raw: ByteArray, val type: Int)

    /** The first question for HOST.local (type A, AAAA or ANY) in a query, or null. */
    private fun question(b: ByteArray, n: Int): Q? {
        if (n < 12) return null
        val id = ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
        if (b[2].toInt() and 0x80 != 0) return null   // a response, not a query
        val qd = ((b[4].toInt() and 0xFF) shl 8) or (b[5].toInt() and 0xFF)
        var o = 12
        repeat(qd) {
            val start = o
            val (name, end) = readName(b, n, o) ?: return null
            o = end
            if (o + 4 > n) return null
            val type = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
            val cls = ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
            o += 4
            if (name.equals("$HOST.local", true) && (type == 1 || type == 28 || type == 255)) return Q(id, cls and 0x8000 != 0, b.copyOfRange(start, o), type)
        }
        return null
    }

    private fun readName(b: ByteArray, n: Int, from: Int): Pair<String, Int>? {
        val parts = ArrayList<String>(); var o = from; var end = -1; var hops = 0
        while (o < n) {
            val len = b[o].toInt() and 0xFF
            when {
                len == 0 -> { if (end < 0) end = o + 1; return parts.joinToString(".") to end }
                len and 0xC0 == 0xC0 -> {
                    if (o + 1 >= n || ++hops > 8) return null
                    if (end < 0) end = o + 2
                    o = ((len and 0x3F) shl 8) or (b[o + 1].toInt() and 0xFF)
                }
                else -> { if (o + 1 + len > n) return null; parts += String(b, o + 1, len, Charsets.UTF_8); o += 1 + len }
            }
        }
        return null
    }

    /**
     * Our A record, or for an IPv6 (AAAA) question an NSEC record saying "A only": without that, Apple devices wait
     * ~5 s for an IPv6 answer that never comes before they connect.
     */
    private fun answer(ip: Inet4Address, id: Int, question: ByteArray?, ttl: Int, noV6: Boolean = false): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun u16(v: Int) { out.write(v shr 8 and 0xFF); out.write(v and 0xFF) }
        val name = java.io.ByteArrayOutputStream().apply { for (l in listOf(HOST, "local")) { write(l.length); write(l.toByteArray()) }; write(0) }.toByteArray()
        u16(id); u16(0x8400); u16(if (question != null) 1 else 0); u16(1); u16(0); u16(0)
        question?.let { out.write(it) }
        out.write(name)
        if (noV6) {
            u16(47); u16(if (question != null) 1 else 0x8001)   // NSEC
            u16(ttl shr 16); u16(ttl and 0xFFFF); u16(name.size + 3); out.write(name); out.write(0); out.write(1); out.write(0x40)
        } else {
            u16(1); u16(if (question != null) 1 else 0x8001)   // A, IN (+ cache flush on multicast)
            u16(ttl shr 16); u16(ttl and 0xFFFF); u16(4); out.write(ip.address)
        }
        return out.toByteArray()
    }

    private fun send(s: MulticastSocket, data: ByteArray, to: InetSocketAddress) = runCatching { s.send(DatagramPacket(data, data.size, to)) }
}
