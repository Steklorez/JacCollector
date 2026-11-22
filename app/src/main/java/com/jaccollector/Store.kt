package com.jaccollector

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One upstream JacRed / Jackett server. */
data class Source(
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val apiKey: String = "",
    /** Audience language from the public list ("ru", "uk", "en"...); empty = added by the user. */
    val lang: String = "",
    /** Switched off because the public list marks it dead; switched back on when it recovers. */
    val communityOff: Boolean = false,
) {
    /** Came from the public list (as opposed to added by the user). */
    val isPublic get() = lang.isNotEmpty()

    fun toJson(): JSONObject = JSONObject()
        .put("name", name).put("url", url).put("enabled", enabled).put("apiKey", apiKey).put("lang", lang)
        .put("communityOff", communityOff)

    companion object {
        fun fromJson(o: JSONObject) = Source(
            name = o.optString("name"),
            url = o.optString("url"),
            enabled = o.optBoolean("enabled", true),
            apiKey = o.optString("apiKey", ""),
            lang = o.optString("lang", ""),
            communityOff = o.optBoolean("communityOff", false),
        )
    }
}

/** Settings kept in SharedPreferences. */
object Store {
    const val PORT = 9117
    private const val PREFS = "settings"

    val DEFAULT_SOURCES = listOf(
        Source("jr.maxvol.pro", "https://jr.maxvol.pro", lang = "ru"),
        Source("jacred.su", "https://jacred.su", lang = "ru"),
        Source("jac.red", "https://jac.red", lang = "ru"),
        Source("jac.black", "https://jac.black", lang = "ru"),
        Source("jacred.xyz", "https://jacred.xyz", lang = "ru"),
    )

    /**
     * Order of the list: the user's own additions first, then servers in the device language,
     * then Russian, Ukrainian, English, anything else. Nobody is excluded, only the order changes.
     */
    fun priority(src: Source, myLang: String): Int {
        if (src.lang.isEmpty()) return 0
        val l = src.lang.split(',').map { it.trim() }   // a server may serve several, e.g. "ru,uk"
        return when {
            myLang in l -> 1
            "ru" in l -> 2
            "uk" in l -> 3
            "en" in l -> 4
            else -> 5
        }
    }

    /** "ru,uk" -> "рус · укр" (plain words on purpose: no flags). */
    fun flags(langs: Collection<String>): String = langs.filter { it.isNotBlank() }.joinToString(" · ") {
        when (it.trim()) {
            "ru" -> "рус"; "uk" -> "укр"; "en" -> "англ"; else -> it.trim()
        }
    }

    val DEADLINES = listOf(1500, 2500, 4000, 7000, 12000)

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun sources(c: Context): List<Source> {
        val raw = prefs(c).getString("sources", null) ?: return DEFAULT_SOURCES
        return try {
            val a = JSONArray(raw)
            val defaults = DEFAULT_SOURCES.associateBy { normalizeUrl(it.url).lowercase() }
            (0 until a.length()).map { Source.fromJson(a.getJSONObject(it)) }.map { s ->
                // lists saved by 1.0 have no language: tag the built-in servers
                val d = defaults[normalizeUrl(s.url).lowercase()]
                if (s.lang.isEmpty() && s.apiKey.isEmpty() && d != null) s.copy(lang = d.lang) else s
            }
        } catch (e: Exception) {
            DEFAULT_SOURCES
        }
    }

    fun saveSources(c: Context, list: List<Source>) {
        val a = JSONArray()
        list.forEach { a.put(it.toJson()) }
        prefs(c).edit().putString("sources", a.toString()).apply()
    }

    /**
     * "Use the shared list". One switch for both directions: when on, the box gets new servers
     * from the public list and in return sends the public addresses the user adds and reports
     * about dead public servers. When off, nothing is sent and nothing is fetched.
     */
    fun shareEnabled(c: Context): Boolean = prefs(c).getBoolean("share", true)
    fun setShareEnabled(c: Context, v: Boolean) = prefs(c).edit().putBoolean("share", v).apply()

    /**
     * Anti-spam: true at most once per [intervalMs] for [key]. Used for buttons and network
     * actions so that hammering a button does not turn into a flood of requests.
     */
    @Synchronized
    fun allow(c: Context, key: String, intervalMs: Long): Boolean {
        val p = prefs(c)
        val now = System.currentTimeMillis()
        val last = p.getLong("t_$key", 0)
        if (now - last in 0 until intervalMs) return false
        p.edit().putLong("t_$key", now).apply()
        return true
    }

    /** Seconds left until [allow] lets [key] through again. */
    fun waitSeconds(c: Context, key: String, intervalMs: Long): Long {
        val left = intervalMs - (System.currentTimeMillis() - prefs(c).getLong("t_$key", 0))
        return if (left > 0) (left + 999) / 1000 else 0
    }

    /** Anti-spam: at most [max] uses of [key] per calendar day. */
    @Synchronized
    fun takeDaily(c: Context, key: String, max: Int): Boolean {
        val p = prefs(c)
        val day = (System.currentTimeMillis() / 86_400_000L).toString()
        val stored = p.getString("d_$key", "") ?: ""
        val count = if (stored.startsWith("$day:")) stored.substringAfter(':').toIntOrNull() ?: 0 else 0
        if (count >= max) return false
        p.edit().putString("d_$key", "$day:${count + 1}").apply()
        return true
    }

    fun catalogTime(c: Context): Long = prefs(c).getLong("catalogAt", 0)

    /** Last copy of the public list, for the catalog screen when offline. */
    fun catalogCache(c: Context): String? = prefs(c).getString("catalog", null)
    fun setCatalogCache(c: Context, v: String) =
        prefs(c).edit().putString("catalog", v).putLong("catalogAt", System.currentTimeMillis()).apply()

    /** When this box last reported the health of a server (url -> time), to report at most daily. */
    fun lastReport(c: Context, url: String): Long = try {
        JSONObject(prefs(c).getString("reports", "{}") ?: "{}").optLong(url, 0)
    } catch (e: Exception) { 0 }
    fun setLastReport(c: Context, url: String, t: Long) {
        val o = try { JSONObject(prefs(c).getString("reports", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        o.put(url, t)
        prefs(c).edit().putString("reports", o.toString()).apply()
    }

    fun lastSync(c: Context): Long = prefs(c).getLong("lastSync", 0)
    fun setLastSync(c: Context, v: Long) = prefs(c).edit().putLong("lastSync", v).apply()

    /** Sources the user deleted; the public list must not bring them back. */
    fun dismissed(c: Context): Set<String> = prefs(c).getStringSet("dismissed", emptySet()) ?: emptySet()
    fun dismiss(c: Context, url: String) {
        val s = HashSet(dismissed(c))
        s.add(normalizeUrl(url).lowercase())
        prefs(c).edit().putStringSet("dismissed", s).apply()
    }
    fun clearDismissed(c: Context) = prefs(c).edit().remove("dismissed").apply()
    fun undismiss(c: Context, url: String) {
        val s = HashSet(dismissed(c))
        s.remove(normalizeUrl(url).lowercase())
        prefs(c).edit().putStringSet("dismissed", s).apply()
    }

    fun deadlineMs(c: Context): Int = prefs(c).getInt("deadline", 2500)
    fun setDeadlineMs(c: Context, v: Int) = prefs(c).edit().putInt("deadline", v).apply()

    /** Turns "jacred.su", "http://x:9117/" etc. into a base URL without trailing slash. */
    fun normalizeUrl(input: String): String {
        var s = input.trim().trimEnd('/')
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
        val cut = s.indexOf("/api/")
        if (cut > 0) s = s.substring(0, cut)
        return s
    }
}
