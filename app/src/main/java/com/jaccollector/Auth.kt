package com.jaccollector

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Anonymous Firebase sign-in over plain REST (no Firebase SDK, no Google Play services).
 *
 * Google gives this install an anonymous account id (uid) without email or any input.
 * Unlike a number the app makes up itself, the uid is signed by Google, and the database rules
 * check it: a box can only write records filed under its own uid. Google also limits how many
 * new anonymous accounts one IP address can create, so ids cannot be mass-produced.
 * Reinstalling or clearing app data gives a new uid.
 */
object Auth {
    // Firebase web API key. It is public by design: it only names the project, access is
    // decided by the database rules.
    private const val API_KEY = "AIzaSyCuIFvOGD1v2oAzWcfD4eCJwGoBL0C8dng"
    private const val SIGN_UP_URL = "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$API_KEY"
    private const val REFRESH_URL = "https://securetoken.googleapis.com/v1/token?key=$API_KEY"
    private const val PREFS = "auth"

    /** uid + ID token for one request. */
    class Session(val uid: String, val token: String)

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Current anonymous session: signs up on first use, refreshes the token (valid 1 hour)
     * when needed. Null when Google cannot be reached. Call from a background thread.
     */
    @Synchronized
    fun session(ctx: Context): Session? {
        val p = prefs(ctx)
        val uid = p.getString("uid", null)
        val token = p.getString("token", null)
        val refresh = p.getString("refresh", null)
        // reuse the token while it has at least 5 minutes left
        if (uid != null && token != null && System.currentTimeMillis() < p.getLong("exp", 0) - 300_000) {
            return Session(uid, token)
        }
        if (refresh != null) {
            val (code, r) = post(REFRESH_URL, "application/x-www-form-urlencoded",
                "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(refresh, "UTF-8"))
            if (code == 200 && r != null) {
                return save(ctx, r.optString("user_id"), r.optString("id_token"),
                    r.optString("refresh_token"), r.optString("expires_in"))
            }
            // A network problem: try later with the same account. Anything else (account deleted,
            // refresh token revoked) means the account is gone: get a new one below.
            if (code !in 400..499) return null
            RequestLog.add("auth: account lost (HTTP $code), new one")
        }
        val (code, r) = post(SIGN_UP_URL, "application/json", "{\"returnSecureToken\":true}")
        if (code != 200 || r == null) {
            RequestLog.add("auth: sign-up HTTP $code")
            return null
        }
        return save(ctx, r.optString("localId"), r.optString("idToken"),
            r.optString("refreshToken"), r.optString("expiresIn"))
    }

    /** Forget the token (kept: uid and refresh token) so the next call refreshes it. */
    fun invalidate(ctx: Context) = prefs(ctx).edit().remove("token").apply()

    private fun save(ctx: Context, uid: String, token: String, refresh: String, expiresIn: String): Session? {
        if (uid.isBlank() || token.isBlank() || refresh.isBlank()) return null
        val exp = System.currentTimeMillis() + (expiresIn.toLongOrNull() ?: 3600L) * 1000
        prefs(ctx).edit().putString("uid", uid).putString("token", token)
            .putString("refresh", refresh).putLong("exp", exp).apply()
        return Session(uid, token)
    }

    private fun post(url: String, type: String, body: String): Pair<Int, JSONObject?> = try {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000
            c.readTimeout = 15000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "$type; charset=utf-8")
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            val text = try {
                (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }
            } catch (_: Exception) { null }
            code to (if (code == 200 && text != null) JSONObject(text) else null)
        } finally {
            c.disconnect()
        }
    } catch (e: Exception) {
        -1 to null
    }
}
