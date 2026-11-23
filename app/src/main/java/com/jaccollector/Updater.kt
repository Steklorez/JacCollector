package com.jaccollector

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update from GitHub Releases, without Google Play.
 *
 * Once a day (and when the app is opened) it asks GitHub which release is the latest: the
 * "releases/latest" page redirects to ".../tag/vX.Y.Z", so no API and no API limits. A newer
 * version is downloaded in the background right away; the main screen then shows a blinking
 * "update" badge, and one press opens the system installer (Android always asks the user).
 *
 * Safety: download over https from GitHub only; before offering it, the file must be our own
 * package, newer than the installed one and signed with the same key (Android would refuse
 * anything else anyway).
 */
object Updater {
    /** Repository page; overridable for tests (build.cmd ... -PupdateBase=http://...). */
    private val BASE: String = BuildConfig.UPDATE_BASE
    private const val CHECK_EVERY_MS = 24 * 60 * 60 * 1000L
    private const val MAX_APK = 30 * 1024 * 1024

    /** A newer version, already downloaded and checked, ready to install. */
    class Ready(val version: String, val file: File)

    @Volatile private var running = false

    private fun prefs(c: Context) = c.getSharedPreferences("update", Context.MODE_PRIVATE)
    private fun dir(c: Context) = File(c.filesDir, "update").apply { mkdirs() }

    /** The downloaded update, if it is still newer than what is installed. */
    fun ready(ctx: Context): Ready? {
        val v = prefs(ctx).getString("version", null) ?: return null
        val f = File(dir(ctx), "JacCollector-$v.apk")
        if (!f.exists() || compare(v, BuildConfig.VERSION_NAME) <= 0) return null
        return Ready(v, f)
    }

    /** Checks GitHub at most once per [everyMs]. Runs in the background, never throws. */
    fun checkInBackground(ctx: Context, everyMs: Long = CHECK_EVERY_MS) {
        val app = ctx.applicationContext
        val p = prefs(app)
        if (System.currentTimeMillis() - p.getLong("checkedAt", 0) < everyMs) return
        if (running) return
        running = true
        Thread({
            try {
                check(app)
            } catch (e: Exception) {
                RequestLog.add("update check failed: ${e.javaClass.simpleName}")
            } finally {
                running = false
            }
        }, "update").start()
    }

    /**
     * "Check for updates" button: checks right now and reports the result in plain words
     * (on a background thread; [done] is called on that thread).
     */
    fun checkNow(ctx: Context, done: (String) -> Unit) {
        val app = ctx.applicationContext
        Thread({
            val msg = try {
                when (val r = check(app)) {
                    null -> "GitHub не ответил, попробуйте позже"
                    else -> if (compare(r, BuildConfig.VERSION_NAME) <= 0)
                        "Установлена последняя версия ${BuildConfig.VERSION_NAME}"
                    else "Версия $r скачана — нажмите «Обновить» вверху справа"
                }
            } catch (e: Exception) {
                "Не удалось скачать обновление: ${e.message}"
            }
            done(msg)
        }, "update-now").start()
    }

    /** Returns the latest version on GitHub (downloading it if newer), or null if GitHub did not answer. */
    @Synchronized
    private fun check(ctx: Context): String? {
        val p = prefs(ctx)
        cleanup(ctx)
        val latest = latestVersion() ?: return null
        p.edit().putLong("checkedAt", System.currentTimeMillis()).apply()
        if (compare(latest, BuildConfig.VERSION_NAME) <= 0) return latest
        if (ready(ctx)?.version == latest) return latest
        RequestLog.add("update: $latest available, downloading")
        val tmp = File(dir(ctx), "download.tmp")
        download("$BASE/releases/download/v$latest/JacCollector.apk", tmp)
        val f = File(dir(ctx), "JacCollector-$latest.apk")
        verify(ctx, tmp)
        f.delete()
        if (!tmp.renameTo(f)) throw RuntimeException("rename failed")
        p.edit().putString("version", latest).apply()
        RequestLog.add("update: $latest downloaded")
        return latest
    }

    /** "1.1.4" from the redirect of releases/latest to .../tag/v1.1.4, or null. */
    private fun latestVersion(): String? {
        val c = URL("$BASE/releases/latest").openConnection() as HttpURLConnection
        try {
            c.instanceFollowRedirects = false
            c.connectTimeout = 8000
            c.readTimeout = 15000
            c.setRequestProperty("User-Agent", "JacCollector")
            val loc = c.getHeaderField("Location") ?: return null
            val v = loc.substringAfterLast("/tag/", "").removePrefix("v")
            return if (v.matches(Regex("[0-9]+(\\.[0-9]+){1,3}"))) v else null
        } finally {
            c.disconnect()
        }
    }

    private fun download(url: String, to: File) {
        var u = url
        repeat(5) { // GitHub redirects to its file storage (another host)
            val c = URL(u).openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = 10000
                c.readTimeout = 30000
                c.setRequestProperty("User-Agent", "JacCollector")
                val code = c.responseCode
                if (code in 300..399) {
                    u = URL(URL(u), c.getHeaderField("Location") ?: throw RuntimeException("no Location")).toString()
                    if (!u.startsWith("https://") && !BASE.startsWith("http://")) throw RuntimeException("not https")
                    return@repeat
                }
                if (code != 200) throw RuntimeException("HTTP $code")
                c.inputStream.use { inp ->
                    to.outputStream().use { out ->
                        val buf = ByteArray(16 * 1024)
                        var total = 0
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_APK) throw RuntimeException("too big")
                            out.write(buf, 0, n)
                        }
                    }
                }
                return
            } finally {
                c.disconnect()
            }
        }
        throw RuntimeException("too many redirects")
    }

    /** Our package, newer, same signing key; throws otherwise (and deletes the file). */
    @Suppress("DEPRECATION")
    private fun verify(ctx: Context, apk: File) {
        try {
            val pm = ctx.packageManager
            val got = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNATURES)
                ?: throw RuntimeException("not an APK")
            val mine = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES)
            if (got.packageName != ctx.packageName) throw RuntimeException("other package")
            if (versionCode(got) <= versionCode(mine)) throw RuntimeException("not newer")
            val a = got.signatures?.map { it.toCharsString() }?.toSet() ?: emptySet()
            val b = mine.signatures?.map { it.toCharsString() }?.toSet() ?: emptySet()
            if (a.isEmpty() || a != b) throw RuntimeException("other signature")
        } catch (e: Exception) {
            apk.delete()
            throw e
        }
    }

    @Suppress("DEPRECATION")
    private fun versionCode(p: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()

    /** Removes downloads that are no longer newer than the installed version. */
    private fun cleanup(ctx: Context) {
        val keep = ready(ctx)?.file
        dir(ctx).listFiles()?.forEach { if (it != keep) it.delete() }
        if (keep == null) prefs(ctx).edit().remove("version").apply()
    }

    /**
     * Opens the system installer for the downloaded update. Returns a hint for the user when
     * Android first needs the "install unknown apps" permission for JacCollector.
     */
    fun install(ctx: Context): String? {
        val r = ready(ctx) ?: return "Обновление ещё скачивается, попробуйте через минуту"
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            return try {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "Разрешите JacCollector устанавливать приложения, вернитесь и нажмите «Обновить» ещё раз"
            } catch (e: Exception) {
                "Разрешите JacCollector устанавливать приложения: Настройки → Настройки устройства → " +
                    "Безопасность и ограничения → Неизвестные источники"
            }
        }
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("base.apk", 0, r.file.length()).use { out ->
                r.file.inputStream().use { it.copyTo(out) }
                s.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val status = PendingIntent.getBroadcast(ctx, id,
                Intent(ctx, UpdateReceiver::class.java), flags)
            s.commit(status.intentSender)
        }
        RequestLog.add("update: installing ${r.version}")
        return null
    }

    /** -1 / 0 / 1 for "1.1.3" vs "1.1.10" style versions. */
    fun compare(a: String, b: String): Int {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }
}

/** Result of the installer session: shows the system confirmation screen when Android asks for it. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> RequestLog.add("update: installed")
            else -> RequestLog.add("update failed: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        }
    }
}
