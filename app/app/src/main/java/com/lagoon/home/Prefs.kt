package com.lagoon.home

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

object Prefs {
    private lateinit var sp: SharedPreferences
    const val MAX_RAIL = 6

    class Place(val name: String, val country: String, val lat: Double, val lon: Double, val cc: String = "")

    fun init(ctx: Context) { sp = ctx.getSharedPreferences("home", Context.MODE_PRIVATE) }

    var place: Place
        get() = if (showcase) Place("Port Louis", "Mauritius", -20.1609, 57.5012, "MU") else Place(sp.getString("place_name", "Port Louis")!!, sp.getString("place_country", "Mauritius")!!,
            sp.getString("place_lat", "-20.1609")!!.toDouble(), sp.getString("place_lon", "57.5012")!!.toDouble(), sp.getString("place_cc", "MU")!!)
        set(v) { sp.edit().putString("place_name", v.name).putString("place_country", v.country)
            .putString("place_lat", v.lat.toString()).putString("place_lon", v.lon.toString()).putString("place_cc", v.cc).apply() }

    /** A town was chosen (in Settings, or found by the first-run setup). */
    val placeSaved get() = sp.contains("place_name")

    var rail: List<String>
        get() = if (showcase) Apps.defaultRail() else sp.getString("rail", null)?.split(",")?.filter { it.isNotBlank() } ?: Apps.defaultRail()
        set(v) { sp.edit().putString("rail", v.take(MAX_RAIL).joinToString(",")).apply() }

    val railSaved get() = sp.contains("rail")

    var lastMbps: Int
        get() = sp.getInt("last_mbps", 0)
        set(v) { sp.edit().putInt("last_mbps", v).apply() }

    /** Films still to play in this round of the shuffle bag. */
    var sceneBag: List<String>
        get() = sp.getString("scene_bag", "")!!.split(",").filter { it.isNotBlank() }
        set(v) { sp.edit().putString("scene_bag", v.joinToString(",")).apply() }

    var recentScenes: List<String>
        get() = sp.getString("recent_scenes", "")!!.split(",").filter { it.isNotBlank() }
        set(v) { sp.edit().putString("recent_scenes", v.joinToString(",")).apply() }

    var goodnightAt: Long
        get() = sp.getLong("gn_at", 0L)
        set(v) { sp.edit().putLong("gn_at", v).apply() }

    var goodnightDaily: Int
        get() = sp.getInt("gn_daily", -1)
        set(v) { sp.edit().putInt("gn_daily", v).apply() }

    /** Ambient sound: 0 off, 1 on the screensaver, 2 screensaver and Home. */
    var ambient: Int
        get() = sp.getInt("ambient", 1)
        set(v) { sp.edit().putInt("ambient", v).apply() }

    /** Previews of the shelf's cards behind Home (YouTube videos, trailers). */
    var previews: Boolean
        get() = sp.getBoolean("previews", true)
        set(v) { sp.edit().putBoolean("previews", v).apply() }

    /** Spotify previews play their 30-second clip (else the scene shows, silent). */
    var musicSound: Boolean
        get() = sp.getBoolean("music_sound", true)
        set(v) { sp.edit().putBoolean("music_sound", v).apply() }

    /** Screenshot mode for the project page (see [Apps.showcase]). */
    var showcase: Boolean
        get() = sp.getBoolean("showcase", false)
        set(v) { sp.edit().putBoolean("showcase", v).apply() }

    var weather: String?
        get() = sp.getString("weather", null)
        set(v) { sp.edit().putString("weather", v).apply() }
}

/** Location lookup without accounts: Open-Meteo geocoding for search, IP geolocation for "detect". */
object Locate {
    class Result(val name: String, val region: String, val country: String, val lat: Double, val lon: Double, val cc: String = "") {
        val line get() = listOf(region, country).filter { it.isNotBlank() && it != name }.joinToString(", ")
    }
    private val main = Handler(Looper.getMainLooper())

    fun search(q: String, cb: (List<Result>) -> Unit) = thread(name = "geo") {
        val r = runCatching {
            val j = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=8&language=en&format=json&name=" + URLEncoder.encode(q, "UTF-8")))
            val a = j.optJSONArray("results") ?: return@runCatching emptyList<Result>()
            (0 until a.length()).map { val o = a.getJSONObject(it)
                Result(o.getString("name"), o.optString("admin1"), o.optString("country"), o.getDouble("latitude"), o.getDouble("longitude"), o.optString("country_code")) }
        }.getOrDefault(emptyList())
        main.post { cb(r) }
    }

    fun detect(cb: (Result?) -> Unit) = thread(name = "ipgeo") {
        val r = runCatching {
            val o = JSONObject(get("https://ipwho.is/"))
            if (!o.optBoolean("success", true)) null
            else Result(o.getString("city"), o.optString("region"), o.optString("country"), o.getDouble("latitude"), o.getDouble("longitude"), o.optString("country_code"))
        }.getOrNull() ?: runCatching {
            val o = JSONObject(get("https://ipapi.co/json/"))
            Result(o.getString("city"), o.optString("region"), o.optString("country_name"), o.getDouble("latitude"), o.getDouble("longitude"), o.optString("country_code"))
        }.getOrNull()
        main.post { cb(r) }
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000; c.setRequestProperty("User-Agent", "LagoonHome/1.0")
        return c.inputStream.bufferedReader().use { it.readText() }
    }
}
