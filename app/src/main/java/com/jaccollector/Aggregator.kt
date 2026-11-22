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
    /** Last good answer / last failure (not counting 429), for the 12-hourly health summary. */
    @Volatile var lastOkAt = 0L
    @Volatile var lastFailAt = 0L
    /** The server answered 429 "slow down": leave it alone until then, even for manual checks. */
    @Volatile var rateLimitedUntil = 0L
    /** 429 answers in a row; makes the pause longer each time, reset by a normal answer. */
    @Volatile var rateLimits = 0
    @Volatile var rateLoaded = false
    /**
     * Careful mode for servers that answer 429 to bursts: at least this many ms between two
     * requests to this server (0 = no limit). Learned from 429s, eased off after a run of good
     * answers, kept across restarts.
     */
    @Volatile var gapMs = 0L
    /** Earliest time the next request to this server may go (careful mode). */
    @Volatile var nextSlot = 0L
    /** Good answers in a row since the gap last changed. */
    @Volatile var goodStreak = 0
    /** Times of the requests sent in the last minute (own per-server budget). */
    val recent = ArrayDeque<Long>()
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
    // Pause after 429s in a row. The first one is short: usually the server only disliked a burst,
    // and careful mode (below) already spaces the next requests out.
    private val RATE_LIMIT_STEPS_MS = longArrayOf(
        60 * 1000L, 10 * 60 * 1000L, 30 * 60 * 1000L, 2 * 60 * 60 * 1000L, 6 * 60 * 60 * 1000L)
    private const val MAX_PER_MINUTE = 12

    // Careful mode: gap after the first 429, its ceiling, and how many good answers ease it off.
    private const val GAP_START_MS = 2000L
    private const val GAP_MAX_MS = 15000L
    private const val GAP_EASE_AFTER = 30
    /** A request that would have to queue longer than this is dropped (the answer would be late anyway). */
    private const val MAX_QUEUE_MS = 6000L

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
        healthSummary(app, all)
        return Answer(sorted, report)
    }

    /**
     * Feeds the shared rating: for each public server in use, "worked here" if it gave a good
     * answer in the last 12 hours, "down here" if it is failing now and gave none. Each server is
     * reported at most every 12 hours (Community.reportHealth), so this is cheap to call on every search.
     */
    private fun healthSummary(ctx: Context, sources: List<Source>) {
        val since = System.currentTimeMillis() - 12 * 60 * 60 * 1000L
        for (src in sources) {
            if (!src.isPublic) continue
            val st = states[src.name] ?: continue
            when {
                st.lastOkAt > since -> Community.reportHealth(ctx, src, true)
                st.lastFailAt > since && st.failures >= FAILS_TO_BENCH -> Community.reportHealth(ctx, src, false)
            }
        }
    }

    private fun fromCache(src: Source, queryKey: String, maxAgeMs: Long = CACHE_TTL_MS): List<JSONObject>? {
        val c = cache[src.name + "|" + queryKey] ?: return null
        return if (System.currentTimeMillis() - c.at < maxAgeMs) c.items else null
    }

    /** The server said "too many requests"; [retryAfterSec] from its Retry-After header, 0 if none. */
    class RateLimited(val retryAfterSec: Long) : Exception("HTTP 429")

    /** We hold back ourselves: this box already sent enough requests to the server this minute. */
    class LocalLimit : Exception("local limit")

    /**
     * Own budget per server: at most MAX_PER_MINUTE requests in any 60 seconds, fewer in
     * careful mode (one per gap).
     */
    private fun takeToken(st: SourceState): Boolean = synchronized(st.recent) {
        val now = System.currentTimeMillis()
        while (st.recent.isNotEmpty() && now - st.recent.first() > 60_000) st.recent.removeFirst()
        val max = if (st.gapMs > 0) minOf(MAX_PER_MINUTE.toLong(), 60_000 / st.gapMs).toInt() else MAX_PER_MINUTE
        if (st.recent.size >= max) return false
        st.recent.addLast(now)
        true
    }

    /**
     * Careful mode: requests to the server go one at a time with [SourceState.gapMs] between
     * them. Returns how long to wait before sending, or -1 to drop the request (too long a queue:
     * several searches at once, e.g. a player asking for the title, the original title and the year).
     */
    private fun takeSlot(st: SourceState): Long = synchronized(st) {
        if (st.gapMs <= 0) return 0
        val now = System.currentTimeMillis()
        val at = maxOf(now, st.nextSlot)
        if (at - now > MAX_QUEUE_MS) return -1
        st.nextSlot = at + st.gapMs
        at - now
    }

    private fun fetch(ctx: Context, src: Source, params: List<Pair<String, String>>, queryKey: String): List<JSONObject> {
        val st = state(src.name)
        // the 429 pause survives restarts of the app and reboots of the box
        if (!st.rateLoaded) {
            st.rateLoaded = true
            val (until, count) = Store.rateLimit(ctx, src.name)
            if (until > st.rateLimitedUntil) { st.rateLimitedUntil = until; st.benchedUntil = maxOf(st.benchedUntil, until) }
            st.rateLimits = maxOf(st.rateLimits, count)
            st.gapMs = maxOf(st.gapMs, Store.gap(ctx, src.name))
        }
        if (st.rateLimitedUntil > System.currentTimeMillis()) throw LocalLimit()
        val wait = takeSlot(st)
        if (wait < 0) throw LocalLimit()
        if (wait > 0) Thread.sleep(wait)
        if (st.rateLimitedUntil > System.currentTimeMillis()) throw LocalLimit() // 429 while queued
        if (!takeToken(st)) throw LocalLimit()
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
            st.lastOkAt = System.currentTimeMillis()
            if (st.rateLimits != 0) { st.rateLimits = 0; Store.setRateLimit(ctx, src.name, 0, 0) }
            // careful mode eases off slowly: after a long run of good answers the gap shrinks a bit
            if (st.gapMs > 0 && ++st.goodStreak >= GAP_EASE_AFTER) {
                st.goodStreak = 0
                st.gapMs = (st.gapMs * 3 / 4).let { if (it < 1000) 0 else it }
                Store.setGap(ctx, src.name, st.gapMs)
            }
            st.lastError = null
            st.lastCount = out.size
            if (wasDown) Community.reportHealth(ctx, src, true)
            return out
        } catch (e: RateLimited) {
            // The server asks us to slow down. Back off, longer each time in a row
            // (10 min, 30 min, 2 h, 6 h), and never less than its Retry-After. Not a failure:
            // it never turns into a "dead server" report.
            st.rateLimits++
            // from now on space the requests to this server out (wider after each 429)
            st.gapMs = if (st.gapMs <= 0) GAP_START_MS else minOf(st.gapMs * 2, GAP_MAX_MS)
            st.goodStreak = 0
            Store.setGap(ctx, src.name, st.gapMs)
            val step = RATE_LIMIT_STEPS_MS[minOf(st.rateLimits - 1, RATE_LIMIT_STEPS_MS.size - 1)]
            val wait = maxOf(step, e.retryAfterSec * 1000)
            st.benchedUntil = System.currentTimeMillis() + wait
            st.rateLimitedUntil = st.benchedUntil
            st.lastError = "просит подождать (HTTP 429)"
            Store.setRateLimit(ctx, src.name, st.rateLimitedUntil, st.rateLimits)
            throw e
        } catch (e: Exception) {
            st.failures++
            st.lastFailAt = System.currentTimeMillis()
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
        val now = System.currentTimeMillis()
        list.forEach { val st = state(it.name); if (st.rateLimitedUntil <= now) st.benchedUntil = 0 }
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
        val now = System.currentTimeMillis()
        list.forEach { src ->
            val st = state(src.name)
            if (st.rateLimitedUntil <= now) st.benchedUntil = 0
            pool.submit { probeOne(app, src) }
        }
    }

    /**
     * Test search: a Russian and a Latin query, so the languages of the server show up
     * (Russian JacReds return little for "matrix", international ones nothing for "Матрица").
     */
    private fun probeOne(ctx: Context, src: Source) {
        if (state(src.name).rateLimitedUntil > System.currentTimeMillis()) return
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
            if (code == 429) {
                val ra = c.getHeaderField("Retry-After")?.trim()?.toLongOrNull() ?: 0L
                throw RateLimited(ra.coerceIn(0, 24 * 60 * 60))
            }
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
