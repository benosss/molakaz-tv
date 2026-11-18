package com.lagoon.home

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

/**
 * Fresh picks, refreshed hourly, from apps already on the box and without any developer keys:
 *  - Netflix: official Top 10 films + series for the chosen location's country (Netflix Tudum), with artwork and title IDs.
 *  - YouTube: this week's trending videos from YouTube Charts for the location's country (US chart where a country has none).
 *  - Spotify: this week's New Music Friday (public embed), cover art via Spotify oEmbed.
 */
object Picks {
    class Pick(val app: String, val badge: String, val title: String, val sub: String, val art: String, val link: String)

    var items: List<Pick> = emptyList()
        private set
    var updatedAt = 0L
        private set
    private lateinit var dir: File
    private val listeners = ArrayList<() -> Unit>()
    fun listen(f: () -> Unit) { listeners += f }
    private val main = Handler(Looper.getMainLooper())
    private const val FORMAT = 2
    private const val UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36"

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "picks").apply { mkdirs() }
        runCatching {
            val j = JSONObject(File(dir, "picks.json").readText())
            // Bump FORMAT when the sources change so an update refreshes straight away.
            updatedAt = if (j.optInt("v") == FORMAT) j.optLong("at") else 0L
            val a = j.getJSONArray("items")
            items = (0 until a.length()).map { val o = a.getJSONObject(it); Pick(o.getString("app"), o.getString("badge"), o.getString("title"), o.optString("sub"), o.optString("art"), o.getString("link")) }
        }
    }

    fun stale() = System.currentTimeMillis() - updatedAt > 55 * 60_000L

    fun refresh(place: Prefs.Place) = thread(name = "picks") {
        val nf = runCatching { netflix(place.country) }.getOrDefault(emptyList())
        val yt = runCatching { youtube(place.cc) }.getOrDefault(emptyList())
        val sp = runCatching { spotify() }.getOrDefault(emptyList())
        val mixed = ArrayList<Pick>()
        for (i in 0 until 6) { nf.getOrNull(i)?.let { mixed += it }; if (i < 4) { yt.getOrNull(i)?.let { mixed += it }; sp.getOrNull(i)?.let { mixed += it } } }
        if (mixed.isEmpty()) return@thread
        // Fetch artwork once, keep it on disk.
        mixed.forEach { p -> runCatching { artFile(p.art).let { f -> if (!f.exists() && p.art.isNotBlank()) download(p.art, f) } } }
        val a = JSONArray()
        mixed.forEach { a.put(JSONObject().put("app", it.app).put("badge", it.badge).put("title", it.title).put("sub", it.sub).put("art", it.art).put("link", it.link)) }
        runCatching { File(dir, "picks.json").writeText(JSONObject().put("v", FORMAT).put("at", System.currentTimeMillis()).put("items", a).toString()) }
        // Drop artwork that is no longer in the list.
        val keep = mixed.map { artFile(it.art).name }.toSet() + "picks.json"
        dir.listFiles()?.forEach { if (it.name !in keep) it.delete() }
        main.post { items = mixed; updatedAt = System.currentTimeMillis(); listeners.forEach { it() } }
    }

    fun bitmap(p: Pick, maxW: Int): Bitmap? = runCatching {
        val f = artFile(p.art); if (!f.exists()) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(f.path, o)
        var s = 1; while (o.outWidth / (s * 2) >= maxW) s *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
    }.getOrNull()

    /** Opens the pick in its app; a YouTube video at [atSec] when that's set (where its preview got to). */
    fun open(ctx: Context, p: Pick, atSec: Int = -1) {
        val pkg = when (p.app) { "netflix" -> "com.netflix.ninja"; "youtube" -> "com.google.android.youtube.tv"; else -> "com.spotify.tv.android" }
        val link = if (p.app == "youtube" && atSec > 0) p.link.replace(Regex("""[?&]t=\d+s?"""), "") + (if ('?' in p.link) "&" else "?") + "t=${atSec}s" else p.link
        val deep = Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = runCatching { ctx.startActivity(deep) }.isSuccess
        if (!ok) ctx.packageManager.getLeanbackLaunchIntentForPackage(pkg)?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    // ---- sources ----

    private fun netflix(country: String): List<Pick> {
        val slug = country.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        val out = ArrayList<Pick>()
        for ((path, kind) in listOf("" to "Film", "/tv" to "Series")) {
            val html = get("https://www.netflix.com/tudum/top10/$slug$path") ?: get("https://www.netflix.com/tudum/top10$path") ?: continue
            val re = Regex("\"storyArt\":\\{.*?\"url\":\"(https://[^\"]+)\".*?\"top10Video\":\\{\"__typename\":\"Top10PulseVideo\",\"title\":\"((?:[^\"\\\\]|\\\\.)*)\",\"videoId\":(\\d+)", RegexOption.DOT_MATCHES_ALL)
            re.findAll(html).take(5).forEachIndexed { i, m ->
                val title = m.groupValues[2].replace("\\'", "'").replace("\\\"", "\"")
                out += Pick("netflix", "NETFLIX · #${i + 1} IN ${country.uppercase()}", title, "$kind · Top 10 this week", m.groupValues[1], "https://www.netflix.com/title/${m.groupValues[3]}")
            }
        }
        // Interleave films and series so both show near the front.
        val films = out.filter { it.sub.startsWith("Film") }; val shows = out.filter { it.sub.startsWith("Series") }
        return (0 until maxOf(films.size, shows.size)).flatMap { listOfNotNull(films.getOrNull(it), shows.getOrNull(it)) }
    }

    /** YouTube Charts "Trending" (the public charts.youtube.com endpoint and its web key). Not every country has a chart. */
    private fun youtube(cc: String): List<Pick> {
        for (c in listOf(cc.lowercase(), "us").filter { it.length == 2 }.distinct()) {
            val body = JSONObject()
                .put("context", JSONObject().put("client", JSONObject().put("clientName", "WEB_MUSIC_ANALYTICS").put("clientVersion", "2.0").put("hl", "en").put("gl", "US").put("theme", "MUSIC")))
                .put("browseId", "FEmusic_analytics_charts_home")
                .put("query", "perspective=CHART_DETAILS&chart_params_country_code=$c&chart_params_chart_type=TRENDING_VIDEOS&chart_params_period_type=RIGHT_NOW")
            val json = post("https://charts.youtube.com/youtubei/v1/browse?alt=json&key=AIzaSyCzEW7JUJdSql0-2V4tHUb6laYm4iAE_dM", body.toString()) ?: continue
            val views = runCatching {
                JSONObject(json).getJSONObject("contents").getJSONObject("sectionListRenderer").getJSONArray("contents").getJSONObject(0)
                    .getJSONObject("musicAnalyticsSectionRenderer").getJSONObject("content").getJSONArray("videos").getJSONObject(0).getJSONArray("videoViews")
            }.getOrNull() ?: continue
            val where = if (c == cc.lowercase()) "" else " (${c.uppercase()})"
            val out = (0 until views.length()).mapNotNull { i ->
                val v = views.getJSONObject(i); val id = v.optString("id"); if (id.length != 11) return@mapNotNull null
                val artist = v.optJSONArray("artists")?.optJSONObject(0)?.optString("name").orEmpty()
                Pick("youtube", "YOUTUBE · #${i + 1} TRENDING$where", v.optString("title"), artist, thumb(v, id), "https://www.youtube.com/watch?v=$id")
            }.take(4)
            if (out.isNotEmpty()) return out
        }
        return emptyList()
    }

    private fun spotify(): List<Pick> {
        val html = get("https://open.spotify.com/embed/playlist/37i9dQZF1DX4JAvHpjipBk") ?: return emptyList()
        val json = Regex("<script id=\"__NEXT_DATA__\" type=\"application/json\">(.*?)</script>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1) ?: return emptyList()
        val list = JSONObject(json).getJSONObject("props").getJSONObject("pageProps").getJSONObject("state").getJSONObject("data").getJSONObject("entity").getJSONArray("trackList")
        return (0 until minOf(4, list.length())).mapNotNull { i ->
            val t = list.getJSONObject(i); val uri = t.optString("uri"); if (!uri.startsWith("spotify:track:")) return@mapNotNull null
            val art = runCatching { JSONObject(get("https://open.spotify.com/oembed?url=" + URLEncoder.encode(uri, "UTF-8"))!!).optString("thumbnail_url") }.getOrDefault("")
            Pick("spotify", "SPOTIFY · NEW MUSIC FRIDAY", t.optString("title"), t.optString("subtitle"), art, uri)
        }
    }

    // ---- io ----

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")

    private fun artFile(url: String) = File(dir, Integer.toHexString(url.hashCode()) + ".img")

    private fun get(url: String): String? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 15000; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA); c.setRequestProperty("Accept-Language", "en")
        if (c.responseCode != 200) return null
        c.inputStream.bufferedReader().use { it.readText() }
    }.getOrNull()

    /** Widest 16:9 thumbnail the chart lists (maxres when it exists), else the always-present 320px one. */
    private fun thumb(v: JSONObject, id: String): String {
        val t = v.optJSONObject("thumbnail")?.optJSONArray("thumbnails") ?: return "https://i.ytimg.com/vi/$id/mqdefault.jpg"
        return (0 until t.length()).map { t.getJSONObject(it) }.firstOrNull { it.optString("url").contains("maxresdefault") }?.optString("url")
            ?: "https://i.ytimg.com/vi/$id/mqdefault.jpg"
    }

    private fun post(url: String, json: String): String? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 15000; c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Referer", "https://charts.youtube.com/"); c.setRequestProperty("Origin", "https://charts.youtube.com")
        c.outputStream.use { it.write(json.toByteArray()) }
        if (c.responseCode != 200) return null
        c.inputStream.bufferedReader().use { it.readText() }
    }.getOrNull()

    private fun download(url: String, f: File) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 15000; c.setRequestProperty("User-Agent", UA)
        if (c.responseCode != 200) return
        val tmp = File(f.path + ".part")
        c.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
        tmp.renameTo(f)
    }
}
