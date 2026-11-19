package com.lagoon.home

import android.content.Context
import android.graphics.Color
import org.json.JSONObject
import java.io.File

/**
 * The streaming apps Home knows by name: what their cards are called and coloured, whose titles get trailer previews,
 * which ones Continue watching follows, and the quick-access bar of a fresh box (only apps that are installed show).
 *
 * More apps can be added without a new build: a file named extra_apps.json next to the films
 * (Android/data/com.lagoon.home/files/, copied there by the first-run setup from a USB stick, or with adb push):
 *
 *   {"apps": [{"pkg": "com.example.tv", "name": "Example", "rail": true, "continue": true, "trailers": true,
 *              "colors": ["#1A1A22", "#08080B"]}]}
 *
 * "liveTv": true marks a TV operator's live TV app: the remote's TV button opens it, and a new bar starts with it.
 */
object Apps {
    class App(val pkg: String, val name: String, val rail: Boolean = false, val watch: Boolean = false,
              val trailers: Boolean = false, val colors: IntArray? = null, val extra: Boolean = false, val liveTv: Boolean = false)

    private fun c(a: Long, b: Long) = intArrayOf(a.toInt(), b.toInt())

    private val BUILT_IN = listOf(
        App("com.netflix.ninja", "Netflix", rail = true, watch = true, trailers = true, colors = c(0xFF3F0A0D, 0xFF0B0B0F)),
        App("com.google.android.youtube.tv", "YouTube", rail = true, watch = true, colors = c(0xFF3B0A10, 0xFF0F0F12)),
        App("com.spotify.tv.android", "Spotify", rail = true, watch = true, colors = c(0xFF14532D, 0xFF05140B)),
        App("com.amazon.amazonvideo.livingroom", "Prime Video", rail = true, watch = true, trailers = true, colors = c(0xFF0A2A4D, 0xFF050E1A)),
        App("com.disney.disneyplus", "Disney+", rail = true, watch = true, trailers = true, colors = c(0xFF0B1A4A, 0xFF050A1C)),
        App("com.apple.atve.androidtv.appletv", "Apple TV", rail = true, watch = true, trailers = true, colors = c(0xFF1C1C1E, 0xFF000000)),
        App("com.plexapp.android", "Plex", rail = true, watch = true, colors = c(0xFF2A2210, 0xFF0F0C05)),
    )
    private val DEFAULT_CARD = c(0xFF0E7490, 0xFF062A3A)

    /** The bar on the project page's screenshots: well-known apps only. */
    private val SHOWCASE_RAIL = listOf("com.spotify.tv.android", "com.netflix.ninja", "com.google.android.youtube.tv",
        "com.amazon.amazonvideo.livingroom", "com.apple.atve.androidtv.appletv", "com.plexapp.android")

    private lateinit var app: Context
    private var all: List<App> = BUILT_IN

    fun init(ctx: Context) { app = ctx.applicationContext; reload() }

    /** Read extra_apps.json again (after the setup copied one in). */
    fun reload() {
        val f = extrasFile() ?: return
        val extra = runCatching {
            val a = JSONObject(f.readText()).getJSONArray("apps")
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                val cs = o.optJSONArray("colors")
                App(o.getString("pkg"), o.optString("name", o.getString("pkg")), o.optBoolean("rail"), o.optBoolean("continue"),
                    o.optBoolean("trailers"), if (cs != null && cs.length() == 2) intArrayOf(Color.parseColor(cs.getString(0)), Color.parseColor(cs.getString(1))) else null,
                    extra = true, liveTv = o.optBoolean("liveTv"))
            }
        }.getOrElse { emptyList() }
        all = BUILT_IN.filter { b -> extra.none { it.pkg == b.pkg } } + extra
    }

    fun extrasFile(): File? = if (::app.isInitialized) app.getExternalFilesDir(null)?.let { File(it, "extra_apps.json") }?.takeIf { it.exists() } else null

    /**
     * Screenshots for the project page: extra apps hidden, the well-known ones in the bar (drawn from
     * files/showcase/<package>.png when they aren't installed), and a generic town. Turned on and off with
     * `am broadcast -a com.lagoon.home.TOUR --es do showcase --ez on true|false`.
     */
    var showcase: Boolean
        get() = Prefs.showcase
        set(v) { Prefs.showcase = v }

    private fun visible() = if (showcase) all.filter { !it.extra } else all

    fun name(pkg: String): String? = visible().firstOrNull { it.pkg == pkg }?.name
    fun card(pkg: String?): IntArray = visible().firstOrNull { it.pkg == pkg }?.colors ?: DEFAULT_CARD
    fun trailers(pkg: String) = visible().any { it.pkg == pkg && it.trailers }
    fun watched(pkg: String) = all.any { it.pkg == pkg && it.watch }
    /** Hidden on the project page's screenshots. */
    fun hidden(pkg: String) = showcase && all.any { it.pkg == pkg && it.extra }
    fun extras(): List<App> = all.filter { it.extra }
    /** Live TV apps named in extra_apps.json, in their order. */
    fun liveTv(): List<String> = all.filter { it.liveTv }.map { it.pkg }

    /** The quick-access bar of a box that hasn't been set up yet (installed apps only, the bar takes the first six). */
    fun defaultRail(): List<String> = if (showcase) SHOWCASE_RAIL else all.filter { it.rail }.map { it.pkg }

    fun showcaseIcon(pkg: String): File? = if (!showcase) null else app.getExternalFilesDir(null)?.let { File(it, "showcase/$pkg.png") }?.takeIf { it.exists() }

    fun installed(pkg: String): Boolean {
        if (hidden(pkg)) return false
        if (showcaseIcon(pkg) != null) return true
        return runCatching { app.packageManager.getPackageInfo(pkg, 0) }.isSuccess
    }
}
