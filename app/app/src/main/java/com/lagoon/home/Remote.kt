package com.lagoon.home

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.media.AudioManager
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import dadb.AdbKeyPair
import dadb.Dadb
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Phone remote. The home app serves a small web remote on the Wi-Fi (port 8080); the phone saves it to its Home
 * Screen. Key presses go to [Injector], which the app starts through the box's own adb the first time it's needed.
 * Only devices on the local network are answered.
 */
object Remote {
    const val PORT = 8080
    private const val INJ_PORT = 47811
    private lateinit var app: Context
    private var started = false
    private val pool = Executors.newCachedThreadPool()
    private val token by lazy {
        val f = File(app.filesDir, "remote.token")
        f.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.length >= 16 }
            ?: ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }.also { f.writeText(it) }
    }

    fun start(ctx: Context) {
        if (started) return
        started = true; app = ctx.applicationContext
        thread(name = "remote-http", isDaemon = true) { serve() }
        pool.execute { runCatching { injector("ping") } }   // warm up so the first press is instant
        Mdns.start(app)
    }

    /** The address to type or scan, e.g. http://192.168.1.20:8080 (null when offline). */
    fun url(): String? = if (Apps.showcase) "http://192.168.1.20:$PORT" else lanIp()?.let { "http://$it:$PORT" }

    fun lanAddress(): Inet4Address? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.firstOrNull { it is Inet4Address && it.isSiteLocalAddress } as Inet4Address?
    }.getOrNull()

    private fun lanIp(): String? = lanAddress()?.hostAddress

    /** A stable id for this box, so a phone looking for "its" TV on the network can tell two boxes apart. */
    val boxId by lazy {
        val f = File(app.filesDir, "box.id")
        f.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.length >= 8 }
            ?: ByteArray(6).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }.also { f.writeText(it) }
    }

    /** Press a key on the box (through the key helper). */
    fun key(code: Int): Boolean = injector("key $code tap")

    /** Run a shell command through the box's own adb, as the shell user; null if adb isn't reachable or allowed. */
    fun shell(cmd: String): String? = runCatching {
        Dadb.create("127.0.0.1", 5555, keyPair()).use { it.shell(cmd).allOutput }
    }.onFailure { Log.w("Remote", "shell: $it") }.getOrNull()

    private fun keyPair(): AdbKeyPair {
        val dir = File(app.filesDir, "adb").apply { mkdirs() }
        val priv = File(dir, "adbkey"); val pub = File(dir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        return AdbKeyPair.read(priv, pub)
    }

    // ---------------- key injector (shell-user helper) ----------------

    private var sock: Socket? = null
    private var reader: BufferedReader? = null
    var injectorReady = false
        private set

    @Synchronized
    private fun injector(cmd: String): Boolean {
        for (attempt in 0..1) {
            try {
                val s = sock ?: connect() ?: run { startInjector(); connect() } ?: return false.also { injectorReady = false }
                s.getOutputStream().write("$token $cmd\n".toByteArray()); s.getOutputStream().flush()
                val r = reader!!.readLine() ?: throw java.io.IOException("closed")
                if (cmd == "ping" && r.startsWith("ok") && r.removePrefix("ok ").trim() != Injector.VERSION.toString()) {
                    // An older helper from before an app update: replace it.
                    runCatching { s.getOutputStream().write("$token quit\n".toByteArray()) }; drop(); startInjector(); continue
                }
                injectorReady = r.startsWith("ok")
                return injectorReady
            } catch (e: Exception) {
                Log.w("Remote", "injector: $e"); drop()
            }
        }
        return false
    }

    private fun connect(): Socket? = runCatching {
        Socket().apply { connect(InetSocketAddress("127.0.0.1", INJ_PORT), 400); tcpNoDelay = true; soTimeout = 3000 }
            .also { sock = it; reader = BufferedReader(InputStreamReader(it.getInputStream(), Charsets.UTF_8)) }
    }.getOrNull()

    private fun drop() { runCatching { sock?.close() }; sock = null; reader = null }

    /**
     * Start the helper over adb on 127.0.0.1:5555 with this app's own adb key. The very first time, the TV shows
     * "Allow USB debugging?" for that key; once allowed (with "Always allow"), it never asks again.
     */
    private fun startInjector() {
        val apk = app.applicationInfo.sourceDir
        Dadb.create("127.0.0.1", 5555, keyPair()).use { d ->
            // Stop any old helper in its own command: if the launch were in the same command line, pkill -f would match
            // (and kill) that shell too. "Injecto[r]" keeps the pattern from matching its own text.
            d.shell("pkill -f 'com.lagoon.home.Injecto[r]'")
            val r = d.shell("CLASSPATH=$apk nohup app_process /system/bin com.lagoon.home.Injector $INJ_PORT $token >/dev/null 2>&1 &")
            Log.i("Remote", "started key helper (exit ${r.exitCode})")
        }
        for (i in 0 until 30) { Thread.sleep(100); if (runCatching { Socket("127.0.0.1", INJ_PORT).close() }.isSuccess) return }
    }

    // ---------------- web server ----------------

    private fun serve() {
        while (true) {
            try {
                ServerSocket(PORT, 32).use { ss ->
                    while (true) { val c = ss.accept(); pool.execute { handle(c) } }
                }
            } catch (e: Exception) { Log.w("Remote", "http: $e"); Thread.sleep(3000) }
        }
    }

    private class Req(val method: String, val path: String, val query: Map<String, String>, val body: ByteArray)
    private class Res(val status: Int, val type: String, val body: ByteArray, val cache: Boolean = false)

    private fun handle(c: Socket) = runCatching {
        c.use {
            val addr = c.inetAddress
            if (!(addr.isSiteLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress)) return@use
            c.tcpNoDelay = true; c.soTimeout = 60_000
            val inp = c.getInputStream().buffered(); val out = c.getOutputStream()
            while (true) {
                val req = read(inp) ?: break
                val res = runCatching { route(req) }.getOrElse { Res(500, "text/plain", "error".toByteArray()) }
                write(out, res)
            }
        }
    }

    private fun readLine(i: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val x = i.read(); if (x < 0) return if (b.size() == 0) null else b.toString("UTF-8")
            if (x == '\n'.code) return b.toString("UTF-8").trimEnd('\r')
            b.write(x); if (b.size() > 8192) return null
        }
    }

    private fun read(i: InputStream): Req? {
        val first = readLine(i) ?: return null
        val parts = first.split(' '); if (parts.size < 2) return null
        var len = 0
        while (true) {
            val h = readLine(i) ?: return null
            if (h.isEmpty()) break
            if (h.startsWith("content-length:", true)) len = h.substringAfter(':').trim().toIntOrNull() ?: 0
        }
        val body = ByteArray(len.coerceAtMost(64 * 1024)); var n = 0
        while (n < body.size) { val r = i.read(body, n, body.size - n); if (r < 0) break; n += r }
        val target = parts[1]; val path = target.substringBefore('?')
        val q = target.substringAfter('?', "").split('&').filter { '=' in it }
            .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        return Req(parts[0], path, q, body)
    }

    private fun write(o: OutputStream, r: Res) {
        val reason = when (r.status) { 200 -> "OK"; 404 -> "Not Found"; else -> "Error" }
        // CORS: a remote page saved under the box's old address can still find and drive it at the new one.
        val head = "HTTP/1.1 ${r.status} $reason\r\nContent-Type: ${r.type}\r\nContent-Length: ${r.body.size}\r\nAccess-Control-Allow-Origin: *\r\n" +
            (if (r.cache) "Cache-Control: max-age=86400\r\n" else "Cache-Control: no-store\r\n") + "Connection: keep-alive\r\n\r\n"
        o.write(head.toByteArray()); o.write(r.body); o.flush()
    }

    private fun json(o: Any) = Res(200, "application/json", o.toString().toByteArray())

    private fun route(r: Req): Res = when (r.path) {
        "/", "/index.html" -> Res(200, "text/html; charset=utf-8", page())
        "/manifest.webmanifest" -> Res(200, "application/manifest+json", JSONObject()
            .put("name", "TV Remote").put("short_name", "TV Remote").put("start_url", "/").put("display", "standalone")
            .put("background_color", "#06121c").put("theme_color", "#06121c")
            .put("icons", JSONArray().put(JSONObject().put("src", "/icon.png").put("sizes", "180x180").put("type", "image/png")))
            .toString().toByteArray(), cache = true)
        "/icon.png" -> Res(200, "image/png", icon(), cache = true)
        "/app.png" -> appIcon(r.query["pkg"].orEmpty())?.let { Res(200, "image/png", it, cache = true) } ?: Res(404, "text/plain", ByteArray(0))
        "/state" -> json(state())
        "/ping" -> json(JSONObject().put("id", boxId).put("tv", "Living room TV").put("host", "${Mdns.HOST}.local"))
        "/media" -> json(JSONObject().put("ok", when (r.query["a"]) {
            "next" -> MediaHub.skip(true); "prev" -> MediaHub.skip(false); "toggle" -> { main { MediaHub.toggle() }; true }
            "seek" -> MediaHub.seek(r.query["ms"]?.toLongOrNull() ?: -1L); else -> false
        }))
        "/search" -> json(JSONObject().put("ok", search(r.query["app"].orEmpty(), r.query["q"].orEmpty())))
        "/power" -> json(JSONObject().put("ok", key(android.view.KeyEvent.KEYCODE_SLEEP)))
        "/goodnight" -> { val m = r.query["m"]?.toIntOrNull() ?: 0; main { Goodnight.set(m) }; Thread.sleep(50); json(JSONObject().put("ok", true).put("goodnight", goodnight())) }
        "/key" -> {
            val code = r.query["c"]?.toIntOrNull() ?: -1
            val a = r.query["a"]?.takeIf { it in setOf("tap", "down", "repeat", "up") } ?: "tap"
            json(JSONObject().put("ok", code > 0 && injector("key $code $a")))
        }
        "/text" -> {
            val t = String(r.body, Charsets.UTF_8).replace("\n", " ").take(200)
            json(JSONObject().put("ok", t.isEmpty() || injector("text $t")))
        }
        "/vol" -> {
            val am = app.getSystemService(AudioManager::class.java)
            val dir = when (r.query["d"]) { "up" -> AudioManager.ADJUST_RAISE; "down" -> AudioManager.ADJUST_LOWER; else -> AudioManager.ADJUST_TOGGLE_MUTE }
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
            json(JSONObject().put("ok", true).put("vol", am.getStreamVolume(AudioManager.STREAM_MUSIC)).put("max", am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)))
        }
        "/launch" -> {
            val pkg = r.query["pkg"].orEmpty()
            val i = app.packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: app.packageManager.getLaunchIntentForPackage(pkg)
            json(JSONObject().put("ok", i != null && runCatching { app.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess))
        }
        else -> Res(404, "text/plain", "not found".toByteArray())
    }

    private fun main(f: () -> Unit) = android.os.Handler(android.os.Looper.getMainLooper()).post(f)

    private fun goodnight() = JSONObject().put("on", Goodnight.active).put("min", Goodnight.minutesLeft()).put("at", Goodnight.endsAt)

    /** Search in an app by deep link: YouTube and Spotify open straight on their results. */
    private fun search(appName: String, q: String): Boolean {
        if (q.isBlank()) return false
        val enc = java.net.URLEncoder.encode(q.trim(), "UTF-8").replace("+", "%20")
        val (pkg, uri) = when (appName) {
            "youtube" -> "com.google.android.youtube.tv" to "https://www.youtube.com/results?search_query=$enc"
            "spotify" -> "com.spotify.tv.android" to "spotify:search:$enc"
            else -> return false
        }
        val i = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { app.startActivity(i) }.isSuccess
    }

    private fun state(): JSONObject {
        val n = MediaHub.now
        val apps = JSONArray()
        Prefs.rail.filter { runCatching { app.packageManager.getApplicationInfo(it, 0) }.isSuccess }.forEach { pkg ->
            val label = runCatching { app.packageManager.getApplicationInfo(pkg, 0).loadLabel(app.packageManager).toString() }.getOrDefault(pkg)
            apps.put(JSONObject().put("pkg", pkg).put("name", label))
        }
        val pm = app.packageManager
        val search = JSONArray()
        if (runCatching { pm.getPackageInfo("com.google.android.youtube.tv", 0) }.isSuccess) search.put("youtube")
        if (runCatching { pm.getPackageInfo("com.spotify.tv.android", 0) }.isSuccess) search.put("spotify")
        return JSONObject().put("tv", "Living room TV").put("ready", injectorReady).put("apps", apps).put("id", boxId)
            .put("host", "${Mdns.HOST}.local").put("ip", lanIp()).put("goodnight", goodnight()).put("search", search)
            .put("now", n?.let { JSONObject().put("title", it.title).put("sub", it.sub).put("app", Apps.name(it.pkg) ?: "").put("playing", it.playing)
                .put("pos", it.position()).put("dur", it.dur).put("canSkip", MediaHub.canSkip()) })
    }

    private fun page(): ByteArray = app.assets.open("remote.html").use { it.readBytes() }

    private val iconCache = HashMap<String, ByteArray>()

    private fun png(b: Bitmap) = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    /** Home Screen icon: a glass remote on a lagoon gradient. */
    private fun icon(): ByteArray = iconCache.getOrPut("_icon") {
        val s = 180; val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, s.toFloat(), s.toFloat(), 0xFF0E7490.toInt(), 0xFF0B1E3A.toInt(), Shader.TileMode.CLAMP) })
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0x33FFFFFF; c.drawRoundRect(RectF(58f, 26f, 122f, 154f), 30f, 30f, p)
        p.color = 0xCCFFFFFF.toInt(); p.style = Paint.Style.STROKE; p.strokeWidth = 4f; c.drawRoundRect(RectF(58f, 26f, 122f, 154f), 30f, 30f, p)
        p.style = Paint.Style.FILL; p.color = Color.WHITE
        c.drawCircle(90f, 66f, 17f, p); p.color = 0xFF0E7490.toInt(); c.drawCircle(90f, 66f, 8f, p)
        p.color = Color.WHITE; c.drawCircle(90f, 108f, 7f, p); c.drawCircle(90f, 132f, 7f, p)
        png(b)
    }

    private fun appIcon(pkg: String): ByteArray? = iconCache[pkg] ?: runCatching {
        val pm = app.packageManager
        val d = pm.getApplicationIcon(pkg)
        val s = 120; val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        d.setBounds(0, 0, s, s); d.draw(c)
        png(b).also { iconCache[pkg] = it }
    }.getOrNull()

    // ---------------- QR for Settings ----------------

    fun qr(text: String, size: Int): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
        val b = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ARGB_8888)
        val row = IntArray(m.width)
        for (y in 0 until m.height) { for (x in 0 until m.width) row[x] = if (m[x, y]) 0xFF0B1E3A.toInt() else Color.WHITE; b.setPixels(row, 0, m.width, 0, y, m.width, 1) }
        return b
    }

    /** Settings shows this while waiting for the one-time "Allow USB debugging?" approval. */
    fun warmUp(done: (Boolean) -> Unit) = pool.execute { val ok = injector("ping"); android.os.Handler(android.os.Looper.getMainLooper()).post { done(ok) } }
}
