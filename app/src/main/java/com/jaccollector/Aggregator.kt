package com.jaccollector

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Health of one source, shown in the UI. */
class SourceState {
    @Volatile var failures = 0
    @Volatile var benchedUntil = 0L
    @Volatile var lastMs = 0L
    @Volatile var lastCount = -1
    @Volatile var lastError: String? = null
    @Volatile var lastAt = 0L
    /** Languages seen in this server's answers ("ru", "uk", "en"), collected over time. */
    val langs: MutableSet<String> = java.util.Collections.synchronizedSet(LinkedHashSet())
}

/**
 * Asks every enabled source at once, waits up to the deadline, merges the answers and drops
 * duplicates by info-hash. Answers that arrive after the deadline are cached and used for the
 * same query next time, so a slow source still helps.
 */
object Aggregator {
    private const val FAILS_TO_BENCH = 3
    private const val BENCH_MS = 15 * 60 * 1000L
    private const val CACHE_TTL_MS = 30 * 60 * 1000L
    private const val REPEAT_MS = 60 * 1000L
    private const val RATE_LIMIT_BACKOFF_MS = 10 * 60 * 1000L

    val states = ConcurrentHashMap<String, SourceState>()
    private val pool = Executors.newCachedThreadPool()

    private class Cached(val at: Long, val items: List<JSONObject>)

    private val cache: MutableMap<String, Cached> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Cached>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?) = size > 300
        })

    fun state(name: String): SourceState = states.getOrPut(name) { SourceState() }

    class Answer(val items: List<JSONObject>, val report: List<JSONObject>)

    /** [params] are the client's query params without the api key, in a stable order. */
    fun search(ctx: Context, params: List<Pair<String, String>>): Answer {
        val now = System.currentTimeMillis()
        val all = Store.sources(ctx).filter { it.enabled && it.url.isNotBlank() }
        var active = all.filter { state(it.name).benchedUntil < now }
        if (active.isEmpty()) active = all // everything benched: try anyway
        val queryKey = params.sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString("&") { it.first + "=" + it.second }

        val app = ctx.applicationContext
        // Anti-spam toward the public servers: a query repeated within a minute (players often
        // repeat, or a user mashes search) is answered from memory instead of asking every server again.
        val futures = active.map { src ->
            val recent = fromCache(src, queryKey, REPEAT_MS)
            val f: Future<List<JSONObject>> = if (recent != null)
                java.util.concurrent.FutureTask { recent }.also { it.run() } // ready at once (API 21-safe)
            else pool.submit<List<JSONObject>> { fetch(app, src, params, queryKey) }
            src to f
        }
        val deadline = now + Store.deadlineMs(ctx)

        val merged = LinkedHashMap<String, JSONObject>()
        val report = ArrayList<JSONObject>()
        for ((src, f) in futures) {
            val left = deadline - System.currentTimeMillis()
            var items: List<JSONObject>? = null
            var status = "ok"
            try {
                items = f.get(left.coerceAtLeast(1), TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                items = fromCache(src, queryKey)
                status = if (items != null) "late, cached" else "late"
            } catch (e: Exception) {
                items = fromCache(src, queryKey)
                status = "error"
            }
            items?.forEach { mergeInto(merged, it) }
            report.add(JSONObject()
                .put("ID", src.name).put("Name", src.name)
                .put("Status", if (status == "ok") 2 else 1)
                .put("Results", items?.size ?: 0)
                .put("Error", if (status == "ok") JSONObject.NULL else status))
        }
        val sorted = merged.values.sortedByDescending { it.optInt("Seeders", 0) }
        return Answer(sorted, report)
    }

    private fun fromCache(src: Source, queryKey: String, maxAgeMs: Long = CACHE_TTL_MS): List<JSONObject>? {
        val c = cache[src.name + "|" + queryKey] ?: return null
        return if (System.currentTimeMillis() - c.at < maxAgeMs) c.items else null
    }

    private fun fetch(ctx: Context, src: Source, params: List<Pair<String, String>>, queryKey: String): List<JSONObject> {
        val st = state(src.name)
        val t0 = System.currentTimeMillis()
        val wasDown = st.failures >= FAILS_TO_BENCH
        try {
            val p = ArrayList(params)
            if (src.apiKey.isNotBlank()) p.add("apikey" to src.apiKey)
            val qs = p.joinToString("&") { enc(it.first) + "=" + enc(it.second) }
            val url = Store.normalizeUrl(src.url) + "/api/v2.0/indexers/all/results?" + qs
            val body = httpGet(url, 4000, 25000)
            val results = JSONObject(body).optJSONArray("Results") ?: JSONArray()
            val out = ArrayList<JSONObject>(results.length())
            for (i in 0 until results.length()) {
                val it = results.optJSONObject(i) ?: continue
                normalizeItem(it, src.name)
                out.add(it)
            }
            cache[src.name + "|" + queryKey] = Cached(System.currentTimeMillis(), out)
            st.langs.addAll(detectLangs(out))
            st.failures = 0
            st.lastError = null
            st.lastCount = out.size
            if (wasDown) Community.reportHealth(ctx, src, true)
            return out
        } catch (e: Exception) {
            if (e.message == "HTTP 429") {
                // The server asks us to slow down: back off for a while. Not a failure, so it
                // never turns into a "dead server" report.
                st.benchedUntil = System.currentTimeMillis() + RATE_LIMIT_BACKOFF_MS
                st.lastError = "просит подождать (HTTP 429)"
                throw e
            }
            st.failures++
            st.lastError = (e.javaClass.simpleName + ": " + (e.message ?: "")).take(140)
            st.lastCount = -1
            if (st.failures >= FAILS_TO_BENCH) st.benchedUntil = System.currentTimeMillis() + BENCH_MS
            // three failures in a row: tell the shared list (public-list servers only, at most daily)
            if (st.failures == FAILS_TO_BENCH) Community.reportHealth(ctx, src, false)
            throw e
        } finally {
            st.lastMs = System.currentTimeMillis() - t0
            st.lastAt = System.currentTimeMillis()
        }
    }

    /** One test request per source, used by the "check" button. */
    fun probe(ctx: Context) {
        val app = ctx.applicationContext
        val list = Store.sources(ctx).filter { it.enabled || it.communityOff }
        list.forEach { state(it.name).benchedUntil = 0 }
        val fs: List<Future<*>> = list.map { src -> pool.submit { probeOne(app, src) } }
        fs.forEach { try { it.get(30, TimeUnit.SECONDS) } catch (_: Exception) {} }
        // a manual check is a good health sample: report what this box sees
        list.forEach { src ->
            val st = state(src.name)
            // a rate-limited server is alive, it just asked us to wait: no report either way
            if (st.lastError?.contains("429") != true) Community.reportHealth(app, src, st.lastError == null)
        }
    }

    /** Checks freshly added servers right away (in the background), so their status shows at once. */
    fun probeNew(ctx: Context, list: List<Source>) {
        if (list.isEmpty()) return
        val app = ctx.applicationContext
        list.forEach { src ->
            state(src.name).benchedUntil = 0
            pool.submit { probeOne(app, src) }
        }
    }

    /**
     * Test search: a Russian and a Latin query, so the languages of the server show up
     * (Russian JacReds return little for "matrix", international ones nothing for "Матрица").
     */
    private fun probeOne(ctx: Context, src: Source) {
        try { fetch(ctx, src, listOf("Query" to "matrix"), "probe-en") } catch (_: Exception) {}
        try { fetch(ctx, src, listOf("Query" to "Матрица"), "probe-ru") } catch (_: Exception) {}
    }

    private val UK_TRACKERS = Regex("toloka|mazepa|hurtom|uakino|uaserial", RegexOption.IGNORE_CASE)
    private val INTL_TRACKERS = Regex(
        "piratebay|yts|1337x|eztv|nyaa|knaben|torrentgalaxy|tgx|limetorrents|rarbg|bitsearch|" +
            "solidtorrents|kickass|torlock|magnetdl|zooqle|glodls", RegexOption.IGNORE_CASE)
    private val UK_LETTERS = Regex("[ЇїІіЄєҐґ]")
    private val CYR = Regex("[А-Яа-яЁё]")

    /** Which audiences a set of releases serves, judged by trackers and title letters. */
    fun detectLangs(items: List<JSONObject>): Set<String> {
        val out = LinkedHashSet<String>()
        for (it in items) {
            val tracker = it.optString("Tracker", "")
            val title = it.optString("Title", "")
            if (UK_TRACKERS.containsMatchIn(tracker) || UK_LETTERS.containsMatchIn(title)) out.add("uk")
            else if (CYR.containsMatchIn(title)) out.add("ru")
            if (INTL_TRACKERS.containsMatchIn(tracker)) out.add("en")
            if (out.size == 3) break
        }
        return out
    }

    fun httpGet(url: String, connectMs: Int, readMs: Int): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = connectMs
            c.readTimeout = readMs
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) JacCollector/1.0")
            c.setRequestProperty("Accept", "application/json")
            val code = c.responseCode
            if (code != 200) throw RuntimeException("HTTP $code")
            val text = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            if (!text.trimStart().startsWith("{")) throw RuntimeException("not JSON")
            return text
        } finally {
            c.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ---------- merging ----------

    private fun normalizeItem(it: JSONObject, sourceName: String) {
        val m = it.optString("MagnetUri", "")
        if (m.isNotEmpty() && m != "null") {
            val fixed = normalizeMagnet(m)
            if (fixed != null) it.put("MagnetUri", fixed) else it.put("MagnetUri", JSONObject.NULL)
        }
        if (it.optString("Tracker", "").isEmpty()) it.put("Tracker", sourceName)
    }

    private fun mergeInto(map: LinkedHashMap<String, JSONObject>, it: JSONObject) {
        val key = infoHash(it.optString("MagnetUri", ""))
            ?: it.optString("Guid", "").ifEmpty { null }
            ?: it.optString("Link", "").ifEmpty { null }
            ?: (it.optString("Title") + "|" + it.optLong("Size"))
        val old = map[key]
        if (old == null) {
            map[key] = it
            return
        }
        val oldHasMagnet = infoHash(old.optString("MagnetUri", "")) != null
        val newHasMagnet = infoHash(it.optString("MagnetUri", "")) != null
        val better = (newHasMagnet && !oldHasMagnet) ||
            (newHasMagnet == oldHasMagnet && it.optInt("Seeders", 0) > old.optInt("Seeders", 0))
        if (better) map[key] = it
    }

    private val HEX = Regex("xt=urn:btih:([0-9a-fA-F]{40})")
    private val B32 = Regex("xt=urn:btih:([A-Za-z2-7]{32})")

    fun infoHash(magnet: String): String? {
        HEX.find(magnet)?.let { return it.groupValues[1].lowercase() }
        B32.find(magnet)?.let { return base32ToHex(it.groupValues[1]) }
        return null
    }

    /** Some players only accept a 40-char hex btih, so base32 hashes are converted. */
    fun normalizeMagnet(magnet: String): String? {
        if (HEX.containsMatchIn(magnet)) return magnet
        val m = B32.find(magnet) ?: return null
        return magnet.replace(m.groupValues[1], base32ToHex(m.groupValues[1]))
    }

    private fun base32ToHex(s: String): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var buffer = 0L
        var bits = 0
        val sb = StringBuilder()
        for (ch in s.uppercase()) {
            buffer = (buffer shl 5) or alphabet.indexOf(ch).toLong()
            bits += 5
            if (bits >= 8) {
                bits -= 8
                sb.append(String.format("%02x", (buffer shr bits) and 0xFF))
            }
        }
        return sb.toString()
    }
}
