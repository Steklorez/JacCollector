package com.jaccollector

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL

/**
 * Shared list of servers. Works only while "use the shared list" is on (Store.shareEnabled):
 *  - public addresses that users add by hand go to Firestore `submissions`
 *    (only working, non-adult JacRed servers; base URL + a few numbers);
 *  - boxes report servers of the public list that stop or start answering to Firestore `health`;
 *  - the curated public list (sources.json) is pulled once a day: recommended servers are added,
 *    dead ones switched off, removed ones deleted; the rest is in the catalog.
 */
object Community {
    private const val PROJECT = "jaccollector-e7a403"
    private const val COMMIT_URL =
        "https://firestore.googleapis.com/v1/projects/$PROJECT/databases/(default)/documents:commit"
    private const val DOC_PREFIX = "projects/$PROJECT/databases/(default)/documents/submissions/"
    val LIST_URL: String = BuildConfig.LIST_URL
    private const val SYNC_EVERY_MS = 24 * 60 * 60 * 1000L
    private const val CATALOG_TTL_MS = 10 * 60 * 1000L

    // anti-spam limits for what one box may send per day
    private const val MAX_SHARES_PER_DAY = 10
    private const val MAX_HEALTH_PER_DAY = 30

    // ---------- sharing ----------

    /** Only public servers are shared: no keys, no LAN/localhost addresses. */
    fun isShareable(src: Source): Boolean {
        if (src.apiKey.isNotBlank()) return false
        val host = try { URI(src.url).host?.lowercase() } catch (e: Exception) { null } ?: return false
        if (!host.contains('.')) return false
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".lan") ||
            host.endsWith(".home") || host.endsWith(".internal")) return false
        if (host.matches(Regex("[0-9.]+"))) {
            val p = host.split('.').mapNotNull { it.toIntOrNull() }
            if (p.size != 4) return false
            if (p[0] == 10 || p[0] == 127 || p[0] == 0 ||
                (p[0] == 192 && p[1] == 168) ||
                (p[0] == 172 && p[1] in 16..31) ||
                (p[0] == 169 && p[1] == 254) ||
                (p[0] == 100 && p[1] in 64..127)) return false
        }
        return true
    }

    /**
     * A personal server: one's own Jackett with a key, or anything in the home network.
     * Only these are allowed while the shared list is off.
     */
    fun isPersonal(src: Source): Boolean = src.apiKey.isNotBlank() || !isShareable(src)

    /** Leaves only personal servers (used when the shared list is switched off). */
    fun keepOnlyPersonal(ctx: Context): Int {
        val cur = Store.sources(ctx)
        val keep = cur.filter { !it.isPublic && isPersonal(it) }
        Store.saveSources(ctx, keep)
        return cur.size - keep.size
    }

    /** Brings back the built-in servers and pulls the public list (shared list switched on). */
    fun restorePublic(ctx: Context) {
        val cur = Store.sources(ctx).toMutableList()
        val have = cur.map { Store.normalizeUrl(it.url).lowercase() }.toHashSet()
        val dismissed = Store.dismissed(ctx)
        val fresh = ArrayList<Source>()
        for (d in Store.DEFAULT_SOURCES) {
            val k = Store.normalizeUrl(d.url).lowercase()
            if (k !in have && k !in dismissed) { cur.add(d); fresh.add(d) }
        }
        Store.saveSources(ctx, cur.sortedBy { Store.priority(it, deviceLang()) })
        Aggregator.probeNew(ctx, fresh)
        syncNow(ctx)
    }

    /** Base URL as sent: scheme://host[:port], lower case. */
    private fun baseOf(url: String): String? = try {
        val u = URI(Store.normalizeUrl(url))
        val port = if (u.port > 0) ":${u.port}" else ""
        "${u.scheme.lowercase()}://${u.host.lowercase()}$port"
    } catch (e: Exception) { null }

    /** Sends the address in the background if sharing is on. Never throws. */
    fun shareInBackground(ctx: Context, src: Source) {
        if (!Store.shareEnabled(ctx) || !isShareable(src)) return
        val app = ctx.applicationContext
        val base0 = baseOf(src.url) ?: return
        // the same address once a day, and at most a few addresses a day from one box
        if (!Store.allow(app, "share:$base0", 24 * 60 * 60 * 1000L)) return
        if (!Store.takeDaily(app, "shares", MAX_SHARES_PER_DAY)) return
        Thread {
            try {
                val base = base0
                // A domain that points into the home network is somebody's private server.
                if (!resolvesPublic(URI(base).host)) return@Thread
                // Quick check from this box, so the collected data says whether it worked here.
                if (ADULT_WORDS.containsMatchIn(base)) {
                    RequestLog.add("not shared $base: 18+")
                    return@Thread
                }
                var results = 0
                var ru = 0
                var adult = false
                // Only a working search server is worth sharing: it must answer a test search with
                // a Jackett/JacRed JSON that has a "Results" list. Dead hosts, ordinary websites and
                // typos are dropped here and never leave the box.
                try {
                    val body = Aggregator.httpGet("$base/api/v2.0/indexers/all/results?Query=matrix", 5000, 15000)
                    val arr = JSONObject(body).get("Results") as JSONArray
                    results = arr.length()
                    adult = hasAdult(arr)
                } catch (e: Exception) {
                    RequestLog.add("not shared $base: not a working JacRed")
                    return@Thread
                }
                // How "Russian" the server is: releases with Cyrillic titles for a Russian query.
                try {
                    val q = java.net.URLEncoder.encode("Матрица", "UTF-8")
                    val arr = JSONObject(Aggregator.httpGet("$base/api/v2.0/indexers/all/results?Query=$q", 5000, 15000))
                        .optJSONArray("Results") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        if (CYRILLIC.containsMatchIn(arr.optJSONObject(i)?.optString("Title") ?: "")) ru++
                    }
                    if (hasAdult(arr)) adult = true
                } catch (_: Exception) {}
                // Servers that index adult trackers are not collected (the user can still use them).
                if (adult) {
                    RequestLog.add("not shared $base: 18+ trackers")
                    return@Thread
                }
                submit(base, true, results, ru, deviceLang(), versionName(app))
            } catch (e: Exception) {
                RequestLog.add("share failed: ${e.message}")
            }
        }.start()
    }

    private val CYRILLIC = Regex("[А-Яа-яЁё]")

    /** Same list as in firestore.rules: such addresses are never collected. */
    private val ADULT_WORDS = Regex("porn|xxx|sex|hentai|nsfw|adult|erot", RegexOption.IGNORE_CASE)

    /** Known adult trackers (as JacRed/Jackett name them in the "Tracker" field). */
    private val ADULT_TRACKERS = Regex(
        "pornolab|empornium|pornbay|pornleech|sukebei|happyfappy|exoticaz|cheggit|xxxtor|" +
            "pornotorrent|kinkyporn|fappaizuri|pornhd|porn|xxx|hentai",
        RegexOption.IGNORE_CASE)

    /** True if a test search shows adult trackers or releases in the XXX category (6000-6999). */
    private fun hasAdult(arr: JSONArray): Boolean {
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            if (ADULT_TRACKERS.containsMatchIn(it.optString("Tracker", ""))) return true
            val cats = it.optJSONArray("Category") ?: continue
            for (j in 0 until cats.length()) {
                if (cats.optInt(j, 0) in 6000..6999) return true
            }
        }
        return false
    }

    /** System language, e.g. "ru", "en", "uk". */
    fun deviceLang(): String = java.util.Locale.getDefault().language.lowercase().take(5)

    private fun submit(base: String, ok: Boolean, results: Int, ru: Int, lang: String, version: String) {
        val id = base.replace("://", "_").replace(':', '_')
        val name = DOC_PREFIX + id
        val meta = JSONObject()
            .put("v", JSONObject().put("stringValue", version.take(20)))
            .put("ok", JSONObject().put("booleanValue", ok))
            .put("results", JSONObject().put("integerValue", results.coerceIn(0, 100000).toString()))
            .put("ru", JSONObject().put("integerValue", ru.coerceIn(0, 100000).toString()))
            .put("lang", JSONObject().put("stringValue", lang))

        // 1) first time: create the document
        val createFields = JSONObject(meta.toString())
            .put("url", JSONObject().put("stringValue", base))
            .put("count", JSONObject().put("integerValue", "1"))
        val create = JSONObject().put("writes", JSONArray().put(JSONObject()
            .put("update", JSONObject().put("name", name).put("fields", createFields))
            .put("currentDocument", JSONObject().put("exists", false))
            .put("updateTransforms", JSONArray()
                .put(JSONObject().put("fieldPath", "firstAt").put("setToServerValue", "REQUEST_TIME"))
                .put(JSONObject().put("fieldPath", "lastAt").put("setToServerValue", "REQUEST_TIME")))))
        val code = post(COMMIT_URL, create.toString())
        if (code == 200) {
            RequestLog.add("shared $base")
            return
        }
        // 2) already there: bump the counter
        val bump = JSONObject().put("writes", JSONArray().put(JSONObject()
            .put("update", JSONObject().put("name", name).put("fields", meta))
            .put("updateMask", JSONObject().put("fieldPaths",
                JSONArray().put("v").put("ok").put("results").put("ru").put("lang")))
            .put("currentDocument", JSONObject().put("exists", true))
            .put("updateTransforms", JSONArray()
                .put(JSONObject().put("fieldPath", "count").put("increment", JSONObject().put("integerValue", "1")))
                .put(JSONObject().put("fieldPath", "lastAt").put("setToServerValue", "REQUEST_TIME")))))
        val code2 = post(COMMIT_URL, bump.toString())
        RequestLog.add(if (code2 == 200) "shared $base (again)" else "share $base: HTTP $code/$code2")
    }

    private fun post(url: String, json: String): Int {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 8000
            c.readTimeout = 15000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(json.toByteArray()) }
            val code = c.responseCode
            try { (if (code < 400) c.inputStream else c.errorStream)?.close() } catch (_: Exception) {}
            code
        } finally {
            c.disconnect()
        }
    }

    private fun versionName(ctx: Context): String = try {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (e: Exception) { "?" }

    // ---------- public list ----------

    /** One server of the public list. */
    class Entry(val name: String, val url: String, val lang: String, val recommended: Boolean, val dead: Boolean)

    class Catalog(val entries: List<Entry>, val removed: Set<String>)

    private fun parse(text: String): Catalog {
        val root = JSONObject(text)
        val arr = root.optJSONArray("sources") ?: JSONArray()
        val list = ArrayList<Entry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("url").isBlank()) continue
            val url = Store.normalizeUrl(o.optString("url"))
            list.add(Entry(
                name = o.optString("name").ifBlank { url.substringAfter("://") },
                url = url,
                // "ru", "uk", "en"...: decides only the order, nobody is excluded
                // one or more of "ru", "uk", "en" ("ru,uk"); decides only the order, nobody is excluded
                lang = o.optString("lang", "ru").lowercase().replace(" ", "").take(20).ifBlank { "ru" },
                recommended = o.optBoolean("recommended", false),
                dead = o.optString("status", "") == "dead",
            ))
        }
        val removed = HashSet<String>()
        root.optJSONArray("removed")?.let { r ->
            for (i in 0 until r.length()) removed.add(Store.normalizeUrl(r.optString(i)).lowercase())
        }
        return Catalog(list, removed)
    }

    /** The public list for the catalog screen (network, or the last copy when offline). */
    fun catalog(ctx: Context): Catalog? {
        val cached = Store.catalogCache(ctx)
        // the list changes rarely: download it at most every 10 minutes
        val fresh = cached != null && System.currentTimeMillis() - Store.catalogTime(ctx) < CATALOG_TTL_MS
        val text = if (fresh) cached else try {
            getText(LIST_URL).also { Store.setCatalogCache(ctx, it) }
        } catch (e: Exception) {
            cached
        } ?: return null
        return try { parse(text) } catch (e: Exception) { null }
    }

    fun syncIfDue(ctx: Context) {
        if (!Store.shareEnabled(ctx)) return
        if (System.currentTimeMillis() - Store.lastSync(ctx) < SYNC_EVERY_MS) return
        syncNow(ctx)
    }

    /**
     * Applies the public list to the local one. Returns how many servers were added, or -1.
     *  - recommended servers that the user has never deleted are added;
     *  - public servers marked dead are switched off, and back on when the mark is gone
     *    (only those switched off this way; the user's own choice is never overridden);
     *  - public servers listed in "removed" disappear.
     * Servers the user added by hand are never touched.
     */
    fun syncNow(ctx: Context): Int {
        if (!Store.shareEnabled(ctx)) return 0
        return try {
            val text = getText(LIST_URL)
            val cat = parse(text)
            Store.setCatalogCache(ctx, text)
            val byUrl = cat.entries.associateBy { it.url.lowercase() }
            val dismissed = Store.dismissed(ctx)
            val myLang = deviceLang()
            var changed = false
            val result = ArrayList<Source>()
            for (s in Store.sources(ctx)) {
                val key = Store.normalizeUrl(s.url).lowercase()
                if (s.isPublic && key in cat.removed) { changed = true; continue }
                val e = byUrl[key]
                var n = s
                if (s.isPublic && e != null) {
                    if (e.dead && s.enabled && !s.communityOff) n = s.copy(enabled = false, communityOff = true)
                    if (!e.dead && s.communityOff) n = s.copy(enabled = true, communityOff = false)
                }
                if (n != s) changed = true
                result.add(n)
            }
            val have = result.map { Store.normalizeUrl(it.url).lowercase() }.toHashSet()
            val fresh = ArrayList<Source>()
            for (e in cat.entries) {
                val key = e.url.lowercase()
                if (!e.recommended || e.dead || key in have || key in dismissed || key in cat.removed) continue
                val s = Source(e.name, e.url, true, lang = e.lang)
                result.add(s)
                fresh.add(s)
                have.add(key)
            }
            val added = fresh.size
            if (added > 0 || changed) Store.saveSources(ctx, result.sortedBy { Store.priority(it, myLang) })
            Aggregator.probeNew(ctx, fresh)
            Store.setLastSync(ctx, System.currentTimeMillis())
            RequestLog.add("public list: +$added")
            added
        } catch (e: Exception) {
            RequestLog.add("public list failed: ${e.javaClass.simpleName}")
            -1
        }
    }

    /** Adds a server from the catalog (also if it was deleted earlier). */
    fun addFromCatalog(ctx: Context, e: Entry) {
        val cur = Store.sources(ctx).toMutableList()
        if (cur.any { Store.normalizeUrl(it.url).equals(e.url, true) }) return
        Store.undismiss(ctx, e.url)
        val s = Source(e.name, e.url, !e.dead, lang = e.lang, communityOff = e.dead)
        cur.add(s)
        Store.saveSources(ctx, cur.sortedBy { Store.priority(it, deviceLang()) })
        Aggregator.probeNew(ctx, listOf(s))
    }

    // ---------- health reports ----------

    private const val HEALTH_PREFIX = "projects/$PROJECT/databases/(default)/documents/health/"
    private const val REPORT_EVERY_MS = 24 * 60 * 60 * 1000L

    /**
     * Tells the shared database that a server from the public list stopped (or started)
     * answering here. At most once a day per server per box; only public-list servers.
     */
    fun reportHealth(ctx: Context, src: Source, ok: Boolean) {
        if (!Store.shareEnabled(ctx) || !src.isPublic || !isShareable(src)) return
        val base = baseOf(src.url) ?: return
        val now = System.currentTimeMillis()
        if (now - Store.lastReport(ctx, base) < REPORT_EVERY_MS) return
        if (!Store.takeDaily(ctx, "health", MAX_HEALTH_PER_DAY)) return
        Store.setLastReport(ctx, base, now)
        val app = ctx.applicationContext
        Thread {
            try {
                val name = HEALTH_PREFIX + base.replace("://", "_").replace(':', '_')
                val counter = if (ok) "ok" else "fail"
                val stamp = if (ok) "lastOkAt" else "lastFailAt"
                val create = JSONObject().put("writes", JSONArray().put(JSONObject()
                    .put("update", JSONObject().put("name", name).put("fields", JSONObject()
                        .put("url", JSONObject().put("stringValue", base))
                        .put("ok", JSONObject().put("integerValue", if (ok) "1" else "0"))
                        .put("fail", JSONObject().put("integerValue", if (ok) "0" else "1"))))
                    .put("currentDocument", JSONObject().put("exists", false))
                    .put("updateTransforms", JSONArray()
                        .put(JSONObject().put("fieldPath", "lastAt").put("setToServerValue", "REQUEST_TIME"))
                        .put(JSONObject().put("fieldPath", stamp).put("setToServerValue", "REQUEST_TIME")))))
                var code = post(COMMIT_URL, create.toString())
                if (code != 200) {
                    val bump = JSONObject().put("writes", JSONArray().put(JSONObject()
                        .put("transform", JSONObject().put("document", name).put("fieldTransforms", JSONArray()
                            .put(JSONObject().put("fieldPath", counter).put("increment",
                                JSONObject().put("integerValue", "1")))
                            .put(JSONObject().put("fieldPath", "lastAt").put("setToServerValue", "REQUEST_TIME"))
                            .put(JSONObject().put("fieldPath", stamp).put("setToServerValue", "REQUEST_TIME"))))
                        .put("currentDocument", JSONObject().put("exists", true))))
                    code = post(COMMIT_URL, bump.toString())
                }
                if (code != 200) Store.setLastReport(app, base, 0) // retry later
                RequestLog.add("health ${src.name}: ${if (ok) "ok" else "down"} ${if (code == 200) "sent" else "HTTP $code"}")
            } catch (e: Exception) {
                Store.setLastReport(app, base, 0)
            }
        }.start()
    }

    private fun getText(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000
            c.readTimeout = 15000
            c.setRequestProperty("User-Agent", "JacCollector")
            if (c.responseCode != 200) throw RuntimeException("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }

    private fun resolvesPublic(host: String): Boolean = try {
        InetAddress.getAllByName(host).none { it.isSiteLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress }
    } catch (e: Exception) { false }
}
