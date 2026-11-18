package com.lagoon.home

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

/**
 * First run on a new box, done from the TV itself (no computer): MoLakaz TV gives itself what it needs through the
 * box's own adb (one "Allow USB debugging?" to accept), installs the apps found on a USB stick, brings in the nature
 * films (from the stick, else downloaded), and becomes the home screen.
 *
 * A USB stick for it has a folder named MoLakaz:
 *   MoLakaz/MoLakaz-TV.apk        the app (installed by hand with a file manager; it does the rest)
 *   MoLakaz/films/                clips/, blur/, ambient/ and scenes.json, as in the films download
 *   MoLakaz/apps/<name>.apk       optional: more apps to install
 *   MoLakaz/extra_apps.json       optional: names, colours and shelf places for those apps (see [Apps])
 */
object Setup {
    /** The films, for boxes set up without a stick: clips/, blur/, ambient/ and scenes.json in one zip. */
    const val FILMS_URL = "https://github.com/benosss/molakaz-tv/releases/download/films-v1/molakaz-films.zip"
    private const val TAG = "Setup"

    fun files(ctx: Context): File = ctx.getExternalFilesDir(null)!!
    fun hasFilms(ctx: Context) = File(files(ctx), "scenes.json").exists()
    fun adbOn(ctx: Context) = Settings.Global.getInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
    fun devOn(ctx: Context) = Settings.Global.getInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
    fun listenerOn(ctx: Context) = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")?.contains(ctx.packageName) == true

    /** Something a fresh box lacks: the films, or the access Now playing and Continue watching need. */
    fun needed(ctx: Context) = !hasFilms(ctx) || !listenerOn(ctx)

    /** Skipped with Back: not asked again until the app starts afresh. */
    var later = false

    internal fun log(m: String) = Log.i(TAG, m)
}

/** The setup screen: five steps with a tick each, the next thing to do in words, and OK to go on. */
class SetupScreen(ctx: Context, private val done: (Boolean) -> Unit) : GlassPanel(ctx, 0f, 0f) {
    private enum class State { WELCOME, DEV, WORKING, FAILED, DONE }
    private enum class Mark { TODO, RUN, OK, SKIP, FAIL }
    private class Step(val title: String, var sub: String, var mark: Mark = Mark.TODO)

    private val h = Handler(Looper.getMainLooper())
    private var state = State.WELCOME
    private val steps = listOf(
        Step("Allow the set-up", "One question on the TV: allow it"),
        Step("Permissions", "Now playing, sound, the screensaver"),
        Step("Apps", "From the USB stick, if there are any"),
        Step("Nature films", "From the USB stick, or downloaded"),
        Step("Home screen", "MoLakaz TV opens with the Home button"),
    )
    private val title = ctx.label(34f, "sans-serif-medium", bold = true)
    private val hint = ctx.label(17f).apply { maxLines = 6; ellipsize = null; setLineSpacing(0f, 1.18f) }
    private val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val rows = ArrayList<Pair<StepIcon, Pair<TextView, TextView>>>()
    private val action = ctx.label(17f, "sans-serif-medium", bold = true)
    private val later = ctx.label(15f).apply { alpha = 0.75f }
    private val bar = Bar(ctx).apply { visibility = INVISIBLE }
    val isOpen get() = visibility == VISIBLE

    init {
        visibility = GONE
        val left = D.ui(140f)
        addView(title, LayoutParams(WRAP, WRAP).apply { leftMargin = left; topMargin = D.ui(70f) })
        addView(hint, LayoutParams(D.ui(470f), WRAP).apply { leftMargin = left; topMargin = D.ui(140f) })
        addView(bar, LayoutParams(D.ui(470f), D.ui(4f)).apply { leftMargin = left; topMargin = D.ui(372f) })
        addView(action, LayoutParams(WRAP, WRAP).apply { leftMargin = left; topMargin = D.ui(560f) })
        addView(later, LayoutParams(WRAP, WRAP).apply { leftMargin = left; topMargin = D.ui(596f) })
        for (s in steps) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val icon = StepIcon(ctx)
            row.addView(icon, LinearLayout.LayoutParams(D.ui(30f), D.ui(30f)).apply { rightMargin = D.ui(18f) })
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val t = ctx.label(19f, "sans-serif-medium", bold = true).apply { text = s.title }
            val sub = ctx.label(14.5f).apply { text = s.sub; alpha = 0.78f }
            col.addView(t); col.addView(sub, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = D.ui(2f) })
            row.addView(col, LinearLayout.LayoutParams(D.ui(420f), WRAP))
            list.addView(row, LinearLayout.LayoutParams(WRAP, D.ui(74f)))
            rows += icon to (t to sub)
        }
        addView(list, LayoutParams(WRAP, WRAP).apply { leftMargin = D.ui(690f); topMargin = D.ui(150f) })
        listOf<TextView>(title, hint, action, later).forEach { it.setShadowLayer(D.u(6f), 0f, D.u(1f), argbA(0.45f, 0x000000)) }
    }

    private val veil = Paint()
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        veil.color = argbA(lerp(0.38f, 0.22f, Theme.t), 0x000000)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), veil)
    }

    fun open() {
        visibility = VISIBLE; alpha = 0f
        animate().alpha(1f).setDuration(400).setInterpolator(Ease.out).start()
        welcome()
    }

    private fun close(finished: Boolean) {
        h.removeCallbacks(watchDev)
        animate().alpha(0f).setDuration(260).setInterpolator(Ease.exit).withEndAction { visibility = GONE; done(finished) }.start()
    }

    private fun render() {
        steps.forEachIndexed { i, s -> rows[i].first.mark = s.mark; rows[i].second.second.text = s.sub }
        when (state) {
            State.WELCOME -> { action.text = "OK   Start"; later.text = "BACK   Later" }
            State.DEV -> { action.text = "OK   Open the TV's settings"; later.text = "BACK   Later" }
            State.WORKING -> { action.text = ""; later.text = "" }
            State.FAILED -> { action.text = "OK   Try again"; later.text = "BACK   Later" }
            State.DONE -> { action.text = "OK   Start watching"; later.text = "" }
        }
    }

    private fun welcome() {
        state = State.WELCOME
        title.text = "Welcome to MoLakaz TV"
        hint.text = "Let's get this TV ready. It takes about two minutes, and nothing on the TV is removed: " +
            "the old home screen is only switched off, and Settings can bring it back.\n\nIf you have the MoLakaz USB stick, keep it plugged in."
        render()
    }

    /** Before anything: the box's own adb must be on (Developer options › USB debugging). */
    private fun begin() {
        if (!Setup.adbOn(context)) { needDev(); return }
        state = State.WORKING; render()
        work()
    }

    private fun needDev() {
        state = State.DEV
        title.text = "One switch first"
        hint.text = if (!Setup.devOn(context))
            "1. Press OK: the TV's settings open on About.\n2. Go down to Build and press OK on it 7 times, until it says you're a developer.\n" +
                "3. Press Back, open Developer options, and turn on USB debugging.\n4. Press Home: set-up carries on by itself."
        else "1. Press OK: the TV's settings open on Developer options.\n2. Turn on USB debugging.\n3. Press Home: set-up carries on by itself."
        render()
        h.removeCallbacks(watchDev); h.postDelayed(watchDev, 1000)
    }

    /** Carry on by itself as soon as USB debugging is on. */
    private val watchDev = object : Runnable {
        override fun run() {
            if (state != State.DEV || !isOpen) return
            if (Setup.adbOn(context)) { begin(); return }
            h.postDelayed(this, 1000)
        }
    }

    private fun openDevSettings() {
        val a = if (Setup.devOn(context)) Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS else Settings.ACTION_DEVICE_INFO_SETTINGS
        runCatching { context.startActivity(Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }

    private fun ui(f: () -> Unit) = h.post { f(); render() }
    private fun mark(i: Int, m: Mark, sub: String? = null) = ui { steps[i].mark = m; if (sub != null) steps[i].sub = sub }
    private fun say(t: String) = ui { hint.text = t }
    private fun progress(f: Float) = ui { bar.visibility = if (f in 0f..1f) VISIBLE else INVISIBLE; if (f in 0f..1f) bar.value = f }

    private fun fail(i: Int, why: String) = ui {
        steps[i].mark = Mark.FAIL; state = State.FAILED
        title.text = "That didn't work"; hint.text = why; bar.visibility = INVISIBLE
    }

    private fun work() = thread(name = "setup") {
        val ctx = context; val pkg = ctx.packageName
        val dst = Setup.files(ctx).path
        ui { title.text = "Setting up…" }

        // 1. The box's adb, with this app's own key: the TV asks once.
        mark(0, Mark.RUN, "Waiting for your OK on the TV")
        say("A message on the TV asks \"Allow USB debugging?\".\n\nTick \"Always allow from this computer\", then choose OK.")
        if (Remote.shell("echo ok")?.contains("ok") != true) {
            fail(0, "The TV didn't allow it. Press OK to ask again, and this time choose OK on the TV's message (with \"Always allow\" ticked).")
            return@thread
        }
        mark(0, Mark.OK, "Allowed")

        // 2. What the features need: media sessions (Now playing, Continue watching), the visualiser, the screensaver
        //    delay and Goodnight's dimming, and the nature screensaver itself.
        mark(1, Mark.RUN); say("Giving MoLakaz TV what it needs…")
        val svc = "$pkg/$pkg.NowPlayingService"
        val cur = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners").orEmpty()
        val listeners = if (svc in cur) cur else listOf(cur, svc).filter { it.isNotBlank() }.joinToString(":")
        Remote.shell("settings put secure enabled_notification_listeners '$listeners'; pm grant $pkg android.permission.RECORD_AUDIO; " +
            "appops set $pkg WRITE_SETTINGS allow; appops set $pkg SYSTEM_ALERT_WINDOW allow; " +
            "settings put secure screensaver_components $pkg/$pkg.VideoDream; settings put secure screensaver_enabled 1; " +
            "settings put secure screensaver_activate_on_sleep 1")
        mark(1, if (Setup.listenerOn(ctx)) Mark.OK else Mark.SKIP, if (Setup.listenerOn(ctx)) "Done" else "Now playing stays off on this box")

        // 3. A USB stick with a MoLakaz folder: its apps, and how Home should show them.
        val usb = Remote.shell("ls -d /storage/*/MoLakaz 2>/dev/null")?.lines()?.map { it.trim() }
            ?.firstOrNull { it.startsWith("/storage/") && !it.startsWith("/storage/emulated") && !it.startsWith("/storage/self") }
        Setup.log("usb: $usb")
        mark(2, Mark.RUN)
        val apks = usb?.let { u -> Remote.shell("ls '$u/apps/' 2>/dev/null")?.lines()?.map { it.trim() }?.filter { it.endsWith(".apk", true) } }.orEmpty()
        apks.forEachIndexed { k, a ->
            mark(2, Mark.RUN, "Installing ${a.removeSuffix(".apk")} (${k + 1} of ${apks.size})")
            say("Installing ${a.removeSuffix(".apk")} from the USB stick…")
            val r = Remote.shell("pm install -r '$usb/apps/$a'")
            Setup.log("install $a: ${r?.trim()}")
        }
        if (usb != null && Remote.shell("[ -f '$usb/extra_apps.json' ] && cp '$usb/extra_apps.json' '$dst/extra_apps.json' && echo copied")?.contains("copied") == true) {
            Apps.reload()
            // The stick's apps join the quick-access bar (the bar may already have been saved with the box's live TV app).
            h.post { val add = Apps.extras().filter { it.rail && Apps.installed(it.pkg) }.map { it.pkg }.filter { it !in Prefs.rail }; if (add.isNotEmpty()) Prefs.rail = Prefs.rail + add }
        }
        mark(2, if (apks.isEmpty()) Mark.SKIP else Mark.OK, if (apks.isEmpty()) "None to add" else "${apks.size} installed")

        // 4. The films: copied from the stick if it has them, else downloaded.
        if (!Setup.hasFilms(ctx)) {
            mark(3, Mark.RUN)
            val fromUsb = usb != null && Remote.shell("[ -f '$usb/films/scenes.json' ] && echo yes")?.contains("yes") == true
            val ok = if (fromUsb) copyFilms("$usb/films", dst) else downloadFilms(File(dst))
            progress(-1f)
            if (!ok || !Setup.hasFilms(ctx)) {
                fail(3, if (fromUsb) "The films couldn't be copied from the USB stick. Check that it's still plugged in, then press OK."
                    else "The films couldn't be downloaded. Check the Wi-Fi (Settings › Network), then press OK to try again.")
                return@thread
            }
            mark(3, Mark.OK, if (fromUsb) "Copied from the USB stick" else "Downloaded")
        } else mark(3, Mark.OK, "Already here")

        // 5. The home screen: the box's own launcher is switched off (not removed), MoLakaz TV becomes Home.
        mark(4, Mark.RUN); say("Making MoLakaz TV the home screen…")
        val off = OriginalHome.present(ctx).joinToString("; ") { "pm disable-user --user 0 $it" }
        Remote.shell((if (off.isNotEmpty()) "$off; " else "") + "cmd package set-home-activity $pkg/$pkg.HomeActivity")
        mark(4, Mark.OK, "Done")

        // Weather and picks for where the box is, if it was never chosen.
        if (!Prefs.placeSaved) Locate.detect { r -> if (r != null) Prefs.place = Prefs.Place(r.name, r.country, r.lat, r.lon, r.cc) }

        ui {
            state = State.DONE; bar.visibility = INVISIBLE
            title.text = "All set"
            hint.text = "Press Home on the remote any time to come back here.\n\n" +
                "Tip: Settings › Phone remote turns any phone into a remote, and Settings › Original home screen brings back the old one."
        }
    }

    /** Copy the films over with the box's own shell (an app can't read a USB stick by itself), a few at a time. */
    private fun copyFilms(src: String, dst: String): Boolean {
        val units = Remote.shell("cd '$src' && ls -dp clips/* blur/* ambient/* 2>/dev/null")?.lines()?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
        if (units.isEmpty()) return false
        Remote.shell("mkdir -p '$dst/clips' '$dst/blur' '$dst/ambient'")
        val chunks = units.chunked(4)
        chunks.forEachIndexed { k, chunk ->
            mark(3, Mark.RUN, "Copying from the USB stick: ${((k + 1) * 100) / chunks.size}%")
            progress((k + 1f) / chunks.size)
            val cmd = chunk.joinToString("; ") { u ->
                if (u.endsWith("/")) { val d = u.trimEnd('/'); "mkdir -p '$dst/$d' && cp -r '$src/$d/.' '$dst/$d/'" } else "cp '$src/$u' '$dst/$u'"
            }
            if (Remote.shell(cmd) == null) return false
        }
        // The film list last: Home only reads it once everything it names is in place.
        return Remote.shell("cp '$src/scenes.json' '$dst/scenes.json.part' && mv '$dst/scenes.json.part' '$dst/scenes.json' && echo ok")?.contains("ok") == true
    }

    /** Download the films zip and unpack it as it arrives (no room on these boxes for the zip and its contents). */
    private fun downloadFilms(dst: File): Boolean = runCatching {
        say("Downloading the nature films. On a good Wi-Fi this takes a few minutes; the TV can be used again as soon as it's done.")
        val c = URL(Setup.FILMS_URL).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 30_000; c.instanceFollowRedirects = true
        val total = c.contentLengthLong.takeIf { it > 0 } ?: 400_000_000L
        var read = 0L; var lastPct = -1
        val counting = object : java.io.FilterInputStream(c.inputStream) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { n ->
                if (n > 0) { read += n; val pct = (read * 100 / total).toInt(); if (pct != lastPct) { lastPct = pct; progress(read.toFloat() / total); mark(3, Mark.RUN, "Downloading: $pct% of ${total / 1_000_000} MB") } }
            }
        }
        var scenes: ByteArray? = null
        ZipInputStream(counting.buffered(1 shl 16)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val name = e.name.removePrefix("films/").trimStart('/')
                if (e.isDirectory || name.isBlank() || ".." in name) continue
                if (name == "scenes.json") { scenes = z.readBytes(); continue }
                val f = File(dst, name); f.parentFile?.mkdirs()
                val part = File(f.path + ".part")
                part.outputStream().use { z.copyTo(it, 1 shl 16) }
                part.renameTo(f)
            }
        }
        c.disconnect()
        val s = scenes ?: return@runCatching false
        File(dst, "scenes.json.part").writeBytes(s); File(dst, "scenes.json.part").renameTo(File(dst, "scenes.json"))
    }.onFailure { Setup.log("download: $it") }.getOrDefault(false)

    fun key(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return true
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> when (state) {
                State.WELCOME, State.FAILED -> begin()
                State.DEV -> openDevSettings()
                State.DONE -> close(true)
                State.WORKING -> {}
            }
            KeyEvent.KEYCODE_BACK -> if (state != State.WORKING && state != State.DONE) { Setup.later = true; close(false) } else if (state == State.DONE) close(true)
        }
        return true
    }

    /** Back on screen (from the TV's settings): carry on if USB debugging is now on. */
    fun resumed() { if (state == State.DEV) { if (Setup.adbOn(context)) begin() else needDev() } }

    /** Pending ring, turning arc, green tick, a dash for skipped, a red mark for failed. */
    private class StepIcon(ctx: Context) : View(ctx) {
        var mark = Mark.TODO
            set(v) { if (field != v) { field = v; invalidate() } }
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val cx = w / 2; val cy = height / 2f; val rad = w / 2 - D.u(2f)
            p.strokeWidth = D.u(2.4f)
            when (mark) {
                Mark.TODO -> { p.color = argbA(0.42f, 0xFFFFFF); c.drawCircle(cx, cy, rad, p) }
                Mark.RUN -> {
                    p.color = argbA(0.22f, 0xFFFFFF); c.drawCircle(cx, cy, rad, p)
                    val t = (android.os.SystemClock.uptimeMillis() % 1100L) / 1100f
                    p.color = Color.WHITE; r.set(cx - rad, cy - rad, cx + rad, cy + rad); c.drawArc(r, t * 360f - 90f, 100f, false, p)
                    postInvalidateOnAnimation()
                }
                Mark.OK, Mark.SKIP, Mark.FAIL -> {
                    fill.color = when (mark) { Mark.OK -> 0xFF30D158.toInt(); Mark.SKIP -> argbA(0.35f, 0xFFFFFF); else -> 0xFFFF453A.toInt() }
                    c.drawCircle(cx, cy, rad + D.u(1f), fill)
                    p.color = Color.WHITE; p.strokeWidth = D.u(2.8f)
                    when (mark) {
                        Mark.OK -> { c.drawLine(cx - rad * 0.42f, cy + rad * 0.02f, cx - rad * 0.08f, cy + rad * 0.36f, p); c.drawLine(cx - rad * 0.08f, cy + rad * 0.36f, cx + rad * 0.46f, cy - rad * 0.32f, p) }
                        Mark.SKIP -> c.drawLine(cx - rad * 0.38f, cy, cx + rad * 0.38f, cy, p)
                        else -> { c.drawLine(cx, cy - rad * 0.45f, cx, cy + rad * 0.12f, p); c.drawPoint(cx, cy + rad * 0.42f, p) }
                    }
                }
            }
        }
    }
}
