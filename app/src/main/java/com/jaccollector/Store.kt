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
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name).put("url", url).put("enabled", enabled).put("apiKey", apiKey)

    companion object {
        fun fromJson(o: JSONObject) = Source(
            name = o.optString("name"),
            url = o.optString("url"),
            enabled = o.optBoolean("enabled", true),
            apiKey = o.optString("apiKey", ""),
        )
    }
}

/** Settings kept in SharedPreferences. */
object Store {
    const val PORT = 9117
    private const val PREFS = "settings"

    val DEFAULT_SOURCES = listOf(
        Source("jr.maxvol.pro", "https://jr.maxvol.pro"),
        Source("jacred.su", "https://jacred.su"),
        Source("jac.red", "https://jac.red"),
        Source("jac.black", "https://jac.black"),
        Source("jacred.xyz", "https://jacred.xyz"),
    )

    val DEADLINES = listOf(1500, 2500, 4000, 7000, 12000)

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun sources(c: Context): List<Source> {
        val raw = prefs(c).getString("sources", null) ?: return DEFAULT_SOURCES
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { Source.fromJson(a.getJSONObject(it)) }
        } catch (e: Exception) {
            DEFAULT_SOURCES
        }
    }

    fun saveSources(c: Context, list: List<Source>) {
        val a = JSONArray()
        list.forEach { a.put(it.toJson()) }
        prefs(c).edit().putString("sources", a.toString()).apply()
    }

    fun shareEnabled(c: Context): Boolean = prefs(c).getBoolean("share", true)
    fun setShareEnabled(c: Context, v: Boolean) = prefs(c).edit().putBoolean("share", v).apply()

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
