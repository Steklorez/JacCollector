package com.jaccollector

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/** Last requests, shown in the UI so you can see what the player actually asks for. */
object RequestLog {
    private val lines = ArrayDeque<String>()
    fun add(s: String) = synchronized(lines) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        lines.addFirst("$t  $s")
        while (lines.size > 30) lines.removeLast()
    }
    fun all(): List<String> = synchronized(lines) { lines.toList() }
}

/**
 * Tiny HTTP server that looks like Jackett / JacRed to the player:
 *  - JSON:    /api/v2.0/indexers/{any}/results?Query=...           (jac.red, NUM, TorrStream, Lampa)
 *  - Torznab: /api/v2.0/indexers/{any}/results/torznab/api?t=...&q=...
 *  - list:    /api/v2.0/indexers?configured=true
 * The api key is accepted and ignored.
 */
class HttpServer(private val ctx: Context, private val port: Int) {
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    @Volatile var error: String? = null
        private set

    fun start() {
        Thread({
            try {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress(port))
                server = s
                error = null
                while (!s.isClosed) {
                    val client = try { s.accept() } catch (e: Exception) { break }
                    pool.execute { handle(client) }
                }
            } catch (e: Exception) {
                error = "port $port: ${e.message}"
                RequestLog.add("SERVER ERROR: $error")
            }
        }, "http-server").start()
    }

    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    val running get() = server?.isClosed == false

    private fun handle(sock: Socket) {
        sock.use { s ->
            try {
                s.soTimeout = 15000
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                val requestLine = reader.readLine() ?: return
                while (true) {
                    val h = reader.readLine() ?: break
                    if (h.isEmpty()) break
                }
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val target = parts[1]
                val path = target.substringBefore('?')
                val query = parseQuery(target.substringAfter('?', ""))
                val out = s.getOutputStream()

                if (method == "OPTIONS") {
                    out.write(response(204, "text/plain", ByteArray(0)))
                    return
                }
                val (code, type, body) = route(path, query)
                out.write(response(code, type, body))
                out.flush()
            } catch (e: Exception) {
                RequestLog.add("error: ${e.javaClass.simpleName} ${e.message}")
            }
        }
    }

    private fun route(path: String, query: List<Pair<String, String>>): Triple<Int, String, ByteArray> {
        val lower = path.lowercase()
        val noKey = query.filter { !it.first.equals("apikey", true) }
        return when {
            lower.contains("/torznab") -> {
                val t = noKey.firstOrNull { it.first == "t" }?.second ?: "search"
                if (t == "caps") {
                    RequestLog.add("torznab caps")
                    Triple(200, "application/xml; charset=utf-8", Torznab.caps().toByteArray())
                } else {
                    val params = Torznab.toJacredParams(noKey)
                    val t0 = System.currentTimeMillis()
                    val ans = Aggregator.search(ctx, params)
                    RequestLog.add("torznab ${describe(params)} -> ${ans.items.size} in ${System.currentTimeMillis() - t0} ms")
                    Triple(200, "application/rss+xml; charset=utf-8", Torznab.rss(ans.items).toByteArray())
                }
            }
            lower.matches(Regex(".*/api/v2\\.0/indexers/[^/]+/results/?")) -> {
                val t0 = System.currentTimeMillis()
                val ans = Aggregator.search(ctx, noKey)
                RequestLog.add("search ${describe(noKey)} -> ${ans.items.size} in ${System.currentTimeMillis() - t0} ms")
                val json = JSONObject()
                    .put("Results", JSONArray(ans.items))
                    .put("Indexers", JSONArray(ans.report))
                Triple(200, "application/json; charset=utf-8", json.toString().toByteArray())
            }
            lower.matches(Regex(".*/api/v2\\.0/indexers/?")) -> {
                RequestLog.add("indexers list")
                val a = JSONArray()
                a.put(JSONObject().put("id", "all").put("name", "JacCollector").put("configured", true)
                    .put("type", "public").put("language", "ru-RU"))
                Triple(200, "application/json; charset=utf-8", a.toString().toByteArray())
            }
            lower.matches(Regex(".*/api/v2\\.0/server/config/?")) -> {
                // NUM asks for this before searching; answer like Jackett does.
                val cfg = JSONObject()
                    .put("port", Store.PORT)
                    .put("external", true)
                    .put("api_key", "")
                    .put("app_version", "0.22.0")
                    .put("blackholedir", "")
                    .put("updatedisabled", true)
                    .put("prerelease", false)
                    .put("logging", false)
                    .put("cache_enabled", true)
                    .put("cache_ttl", 1800)
                    .put("cache_max_results_per_indexer", 1000)
                    .put("flaresolverrurl", "")
                    .put("proxy_type", -1)
                Triple(200, "application/json; charset=utf-8", cfg.toString().toByteArray())
            }
            lower == "/" || lower.isEmpty() -> {
                Triple(200, "text/plain; charset=utf-8", "JacCollector is running".toByteArray())
            }
            else -> {
                RequestLog.add("unknown $path")
                Triple(404, "text/plain", "not found".toByteArray())
            }
        }
    }

    private fun describe(p: List<Pair<String, String>>) =
        p.joinToString(" ") { "${it.first}=${it.second}" }.take(80)

    private fun response(code: Int, type: String, body: ByteArray): ByteArray {
        val reason = when (code) { 200 -> "OK"; 204 -> "No Content"; 404 -> "Not Found"; else -> "Error" }
        val head = "HTTP/1.1 $code $reason\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Access-Control-Allow-Methods: GET, OPTIONS\r\n" +
            "Access-Control-Allow-Headers: *\r\n" +
            "Connection: close\r\n\r\n"
        return head.toByteArray() + body
    }

    private fun parseQuery(q: String): List<Pair<String, String>> {
        if (q.isEmpty()) return emptyList()
        return q.split('&').filter { it.isNotEmpty() }.map {
            val k = it.substringBefore('=')
            val v = it.substringAfter('=', "")
            dec(k) to dec(v)
        }
    }

    private fun dec(s: String) = try { URLDecoder.decode(s, "UTF-8") } catch (e: Exception) { s }
}

/** Torznab (XML) flavour of the same answer, for players that speak it. */
object Torznab {
    fun toJacredParams(q: List<Pair<String, String>>): List<Pair<String, String>> {
        val m = q.toMap()
        val text = listOfNotNull(m["q"], m["season"]?.let { "s$it" }).joinToString(" ").trim()
        val out = ArrayList<Pair<String, String>>()
        out.add("Query" to text)
        q.filter { it.first.equals("cat", true) }.forEach { c ->
            c.second.split(',').filter { it.isNotBlank() }.forEach { out.add("Category[]" to it) }
        }
        return out
    }

    fun caps(): String = """<?xml version="1.0" encoding="UTF-8"?>
<caps>
  <server title="JacCollector"/>
  <limits default="1000" max="1000"/>
  <searching>
    <search available="yes" supportedParams="q"/>
    <tv-search available="yes" supportedParams="q,season,ep"/>
    <movie-search available="yes" supportedParams="q"/>
  </searching>
  <categories>
    <category id="2000" name="Movies"/>
    <category id="5000" name="TV"/>
  </categories>
</caps>"""

    fun rss(items: List<JSONObject>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<rss version="2.0" xmlns:torznab="http://torznab.com/schemas/2015/feed"><channel><title>JacCollector</title>""")
        for (it in items) {
            val magnet = it.optString("MagnetUri", "").takeIf { s -> s.isNotEmpty() && s != "null" }
            val link = magnet ?: it.optString("Link", "")
            if (link.isEmpty()) continue
            val size = it.optLong("Size", 0)
            sb.append("<item>")
            sb.append("<title>").append(x(it.optString("Title"))).append("</title>")
            sb.append("<guid>").append(x(it.optString("Guid", it.optString("Details", link)))).append("</guid>")
            sb.append("<link>").append(x(link)).append("</link>")
            sb.append("<comments>").append(x(it.optString("Details"))).append("</comments>")
            sb.append("<pubDate>").append(x(rfc822(it.optString("PublishDate")))).append("</pubDate>")
            sb.append("<size>").append(size).append("</size>")
            sb.append("<jackettindexer>").append(x(it.optString("Tracker"))).append("</jackettindexer>")
            sb.append("<enclosure url=\"").append(x(link)).append("\" length=\"").append(size)
                .append("\" type=\"application/x-bittorrent\"/>")
            attr(sb, "seeders", it.optInt("Seeders", 0).toString())
            attr(sb, "peers", it.optInt("Peers", 0).toString())
            if (magnet != null) {
                attr(sb, "magneturl", magnet)
                Aggregator.infoHash(magnet)?.let { h -> attr(sb, "infohash", h) }
            }
            val cats = it.optJSONArray("Category")
            if (cats != null) for (i in 0 until cats.length()) attr(sb, "category", cats.opt(i).toString())
            sb.append("</item>")
        }
        sb.append("</channel></rss>")
        return sb.toString()
    }

    private fun attr(sb: StringBuilder, n: String, v: String) {
        sb.append("<torznab:attr name=\"").append(n).append("\" value=\"").append(x(v)).append("\"/>")
    }

    private fun x(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun rfc822(iso: String): String {
        val out = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US)
        out.timeZone = TimeZone.getTimeZone("UTC")
        for (p in listOf("yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd")) {
            try {
                val f = SimpleDateFormat(p, Locale.US)
                f.timeZone = TimeZone.getTimeZone("UTC")
                return out.format(f.parse(iso.take(19))!!)
            } catch (_: Exception) {}
        }
        return out.format(Date())
    }
}
