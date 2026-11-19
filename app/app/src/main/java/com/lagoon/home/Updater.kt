package com.lagoon.home

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * Updates from this project's GitHub releases. Twice a day it asks GitHub for the latest release; a newer one is
 * downloaded and only kept if it matches the SHA-256 listed in the release's SHA256SUMS.txt, is signed with the same key
 * as the app on the box, and really is a newer version. It's then installed through the box's own adb (no prompts, the
 * same one-time permission as the set-up) at a quiet moment: when the screensaver is on or nobody has pressed a button
 * on Home for a while, or straight away from Settings › Updates.
 */
object Updater {
    private const val TAG = "Updater"
    private const val REPO = "benosss/molakaz-tv"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val APK = "MoLakaz-TV.apk"
    private const val EVERY = 12 * 3600_000L

    enum class State { IDLE, CHECKING, UP_TO_DATE, DOWNLOADING, READY, INSTALLING, FAILED }

    var state = State.IDLE; private set
    var latest: String? = null; private set
    var message = ""; private set
    private lateinit var app: Context
    private val h = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<() -> Unit>()
    fun listen(f: () -> Unit) { listeners += f }
    private fun changed(s: State, m: String = message) { h.post { state = s; message = m; listeners.forEach { it() } } }

    val current: String get() = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "0"
    private fun file() = File(app.getExternalFilesDir(null), "update/$APK")
    private val sp get() = app.getSharedPreferences("updates", Context.MODE_PRIVATE)

    fun init(ctx: Context) {
        app = ctx.applicationContext
        // A downloaded update that is now the installed version (or older) has done its job.
        val f = file()
        if (f.exists()) {
            val v = runCatching { app.packageManager.getPackageArchiveInfo(f.path, 0)?.versionName }.getOrNull()
            if (v == null || newer(v, current) <= 0) f.delete() else { latest = v; state = State.READY; message = "Version $v is ready to install" }
        }
    }

    /** Ask GitHub now if [force], else only if the last look was more than 12 hours ago. */
    fun check(force: Boolean = false) {
        if (state == State.CHECKING || state == State.DOWNLOADING || state == State.INSTALLING) return
        if (!force && System.currentTimeMillis() - sp.getLong("checked", 0L) < EVERY) return
        changed(State.CHECKING, "Looking for updates…")
        thread(name = "update-check") {
            runCatching {
                val rel = JSONObject(String(http(API)))
                val tag = rel.getString("tag_name").removePrefix("v")
                sp.edit().putLong("checked", System.currentTimeMillis()).apply()
                if (newer(tag, current) <= 0) { changed(State.UP_TO_DATE, "Up to date: version $current"); return@thread }
                val assets = rel.getJSONArray("assets")
                var apkUrl: String? = null; var apkSize = -1L; var sumsUrl: String? = null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    when (a.getString("name")) {
                        APK -> { apkUrl = a.getString("browser_download_url"); apkSize = a.optLong("size", -1) }
                        "SHA256SUMS.txt" -> sumsUrl = a.getString("browser_download_url")
                    }
                }
                if (apkUrl == null || sumsUrl == null) { changed(State.FAILED, "The latest release has no app to install"); return@thread }
                val want = String(http(sumsUrl)).lines().firstOrNull { it.trim().endsWith(APK) }?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.lowercase()
                    ?: run { changed(State.FAILED, "The release lists no checksum for the app"); return@thread }
                changed(State.DOWNLOADING, "Downloading version $tag…")
                val bytes = http(apkUrl)
                val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                if ((apkSize > 0 && bytes.size.toLong() != apkSize) || sha != want) { changed(State.FAILED, "The download didn't match its checksum"); return@thread }
                val f = file(); f.parentFile?.mkdirs()
                val part = File(f.path + ".part"); part.writeBytes(bytes)
                if (!sameKey(part.path)) { part.delete(); changed(State.FAILED, "The update isn't signed with this app's key"); return@thread }
                val v = app.packageManager.getPackageArchiveInfo(part.path, 0)?.versionName
                if (v == null || newer(v, current) <= 0) { part.delete(); changed(State.UP_TO_DATE, "Up to date: version $current"); return@thread }
                part.renameTo(f)
                h.post { latest = v }
                Log.i(TAG, "version $v downloaded and checked")
                changed(State.READY, "Version $v is ready to install")
            }.onFailure { Log.w(TAG, "check: $it"); changed(State.FAILED, "Couldn't reach GitHub. It will try again later.") }
        }
    }

    /** Install the downloaded update through the box's adb. The app restarts by itself as the new version. */
    fun install() {
        if (state != State.READY) return
        val f = file(); if (!f.exists()) { changed(State.IDLE, ""); return }
        changed(State.INSTALLING, "Installing version $latest… MoLakaz TV restarts by itself")
        thread(name = "update-install") {
            // Android ends this process as soon as the new version is in, so this only returns if something went wrong.
            val r = Remote.shell("pm install -r '${f.path}'")
            Log.w(TAG, "install returned: ${r?.trim()}")
            changed(State.FAILED, if (r == null) "Allow USB debugging on the TV to install updates" else "The update couldn't be installed: ${r.trim().take(60)}")
        }
    }

    private fun sameKey(path: String): Boolean = runCatching {
        @Suppress("DEPRECATION")
        val mine = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_SIGNATURES).signatures
        @Suppress("DEPRECATION")
        val theirs = app.packageManager.getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES)?.signatures
        theirs != null && mine.isNotEmpty() && theirs.toSet() == mine.toSet()
    }.getOrDefault(false)

    /** Compares "1.2.10" with "1.2.9" the way people do: >0 if [a] is newer. */
    fun newer(a: String, b: String): Int {
        val x = a.split('.', '-').map { it.toIntOrNull() ?: 0 }; val y = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) { val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }; if (d != 0) return d }
        return 0
    }

    private fun http(url: String): ByteArray {
        var u = URL(url)
        repeat(5) {
            val c = u.openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 30_000; c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "MoLakazTV-updater"); c.setRequestProperty("Accept", "application/vnd.github+json, */*")
            val code = c.responseCode
            if (code in 300..399) { u = URL(u, c.getHeaderField("Location")); c.disconnect(); return@repeat }
            try { if (code != 200) throw java.io.IOException("HTTP $code"); return c.inputStream.use { it.readBytes() } } finally { c.disconnect() }
        }
        throw java.io.IOException("too many redirects")
    }
}
