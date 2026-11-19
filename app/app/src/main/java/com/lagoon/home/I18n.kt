package com.lagoon.home

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/**
 * Translations. Each language is one file, assets/i18n/<code>.json, keyed by the English text in the code:
 * `tr("Good evening")`, or with values: `tr("{n} apps on the left", "n" to 4)`. A string a file doesn't have stays in
 * English, so a missing translation never breaks anything. Settings › Language picks one, or follows the TV.
 */
object I18n {
    class Lang(val code: String, val name: String, val locale: Locale)

    /** In their own names. Kreol Morisien uses French dates and numbers (Android has no locale data for it). */
    val LANGS = listOf(
        Lang("en", "English", Locale.ENGLISH), Lang("fr", "Français", Locale.FRENCH), Lang("mfe", "Kreol Morisien", Locale.FRENCH),
        Lang("es", "Español", Locale("es")), Lang("pt", "Português", Locale("pt")), Lang("de", "Deutsch", Locale.GERMAN),
        Lang("it", "Italiano", Locale.ITALIAN), Lang("nl", "Nederlands", Locale("nl")), Lang("pl", "Polski", Locale("pl")),
        Lang("sv", "Svenska", Locale("sv")), Lang("da", "Dansk", Locale("da")), Lang("nb", "Norsk", Locale("nb")),
        Lang("fi", "Suomi", Locale("fi")), Lang("cs", "Čeština", Locale("cs")), Lang("ro", "Română", Locale("ro")),
        Lang("hu", "Magyar", Locale("hu")), Lang("tr", "Türkçe", Locale("tr")), Lang("ru", "Русский", Locale("ru")),
        Lang("uk", "Українська", Locale("uk")), Lang("el", "Ελληνικά", Locale("el")), Lang("ja", "日本語", Locale.JAPANESE),
        Lang("ko", "한국어", Locale.KOREAN), Lang("zh-Hans", "简体中文", Locale.SIMPLIFIED_CHINESE),
        Lang("zh-Hant", "繁體中文", Locale.TRADITIONAL_CHINESE), Lang("hi", "हिन्दी", Locale("hi")), Lang("id", "Bahasa Indonesia", Locale("in")),
        Lang("vi", "Tiếng Việt", Locale("vi")), Lang("th", "ไทย", Locale("th")),
    )

    var lang: Lang = LANGS[0]; private set
    private var table: Map<String, String> = emptyMap()
    private var systemLocale: Locale = Locale.getDefault()

    fun init(ctx: Context) {
        // The TV's own language, from the system (Locale.getDefault() is ours to change, below).
        systemLocale = android.content.res.Resources.getSystem().configuration.locales[0] ?: Locale.ENGLISH
        lang = LANGS.firstOrNull { it.code == Prefs.language } ?: auto()
        table = load(ctx, lang.code)
        // Dates ("Wednesday 18 November") follow the chosen language too.
        Locale.setDefault(if (Prefs.language == null) systemLocale else lang.locale)
    }

    /** The TV's own language, if we have it, else English. */
    fun auto(): Lang {
        val l = systemLocale
        val code = when (l.language) {
            "zh" -> if (l.script == "Hant" || l.country in setOf("TW", "HK", "MO")) "zh-Hant" else "zh-Hans"
            "no", "nn" -> "nb"
            "in" -> "id"
            else -> l.language
        }
        return LANGS.firstOrNull { it.code == code } ?: LANGS[0]
    }

    private fun load(ctx: Context, code: String): Map<String, String> = if (code == "en") emptyMap() else runCatching {
        val o = JSONObject(ctx.assets.open("i18n/$code.json").bufferedReader().readText())
        o.keys().asSequence().associateWith { o.getString(it) }.filterValues { it.isNotBlank() }
    }.getOrDefault(emptyMap())

    fun t(s: String, vararg args: Pair<String, Any?>): String {
        var r = table[s] ?: s
        for ((k, v) in args) r = r.replace("{$k}", v.toString())
        return r
    }
}

/** The text in the chosen language (see [I18n]). */
fun tr(s: String, vararg args: Pair<String, Any?>) = I18n.t(s, *args)
