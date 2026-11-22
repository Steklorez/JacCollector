package com.jaccollector

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL

/**
 * Shared list of sources:
 *  - addresses that users add by hand are sent to Firestore (only the public base URL, nothing else);
 *  - the curated public list (sources.json in the GitHub repo) is pulled once a day and new
 *    entries are added to the local list.
 */
object Community {
    private const val PROJECT = "jaccollector-e7a403"
    private const val COMMIT_URL =
        "https://firestore.googleapis.com/v1/projects/$PROJECT/databases/(default)/documents:commit"
    private const val DOC_PREFIX = "projects/$PROJECT/databases/(default)/documents/submissions/"
    const val LIST_URL = "https://raw.githubusercontent.com/Steklorez/JacCollector/main/sources.json"
    private const val SYNC_EVERY_MS = 24 * 60 * 60 * 1000L

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
        Thread {
            try {
                val base = baseOf(src.url) ?: return@Thread
                // A domain that points into the home network is somebody's private server.
                if (!resolvesPublic(URI(base).host)) return@Thread
                // Quick check from this box, so the collected data says whether it worked here.
                var ok = false
                var results = 0
                try {
                    val body = Aggregator.httpGet("$base/api/v2.0/indexers/all/results?Query=matrix", 5000, 15000)
                    results = JSONObject(body).optJSONArray("Results")?.length() ?: 0
                    ok = true
                } catch (_: Exception) {}
                submit(base, ok, results, versionName(app))
            } catch (e: Exception) {
                RequestLog.add("share failed: ${e.message}")
            }
        }.start()
    }

    private fun submit(base: String, ok: Boolean, results: Int, version: String) {
        val id = base.replace("://", "_").replace(':', '_')
        val name = DOC_PREFIX + id
        val meta = JSONObject()
            .put("v", JSONObject().put("stringValue", version.take(20)))
            .put("ok", JSONObject().put("booleanValue", ok))
            .put("results", JSONObject().put("integerValue", results.toString()))

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
            .put("updateMask", JSONObject().put("fieldPaths", JSONArray().put("v").put("ok").put("results")))
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

    fun syncIfDue(ctx: Context) {
        if (System.currentTimeMillis() - Store.lastSync(ctx) < SYNC_EVERY_MS) return
        syncNow(ctx)
    }

    /** Returns how many sources were added, or -1 on error. */
    fun syncNow(ctx: Context): Int = try {
        val text = getText(LIST_URL)
        val arr = JSONObject(text).optJSONArray("sources") ?: JSONArray()
        val current = Store.sources(ctx).toMutableList()
        val have = current.map { Store.normalizeUrl(it.url).lowercase() }.toMutableSet()
        val dismissed = Store.dismissed(ctx)
        var added = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = Store.normalizeUrl(o.optString("url"))
            val key = url.lowercase()
            if (o.optString("url").isBlank() || key in have || key in dismissed) continue
            val name = o.optString("name").ifBlank { url.substringAfter("://") }
            current.add(Source(name, url, true))
            have.add(key)
            added++
        }
        if (added > 0) Store.saveSources(ctx, current)
        Store.setLastSync(ctx, System.currentTimeMillis())
        RequestLog.add("public list: +$added")
        added
    } catch (e: Exception) {
        RequestLog.add("public list failed: ${e.javaClass.simpleName}")
        -1
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
