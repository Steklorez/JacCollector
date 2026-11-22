package com.jaccollector

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** Keeps the HTTP server alive in the background (foreground service with a notification). */
class CollectorService : Service() {

    companion object {
        @Volatile var server: HttpServer? = null
        @Volatile private var syncThread: Thread? = null
        private const val CHANNEL = "collector"

        fun start(ctx: Context) {
            val i = Intent(ctx, CollectorService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Tls.init(applicationContext) // Let's Encrypt root for Android < 7.1.1
        goForeground()
        synchronized(CollectorService::class.java) {
            // onStartCommand comes from the screen, boot and updates, often several times in a row:
            // start one server only, and a new one only if the old one really failed.
            if (server?.alive != true) {
                server?.stop()
                server = HttpServer(applicationContext, Store.PORT).also { it.start() }
            }
        }
        startSyncLoop()
        return START_STICKY
    }

    /** Pulls the public source list once a day (checked every 3 hours). */
    private fun startSyncLoop() {
        if (syncThread?.isAlive == true) return
        val app = applicationContext
        syncThread = Thread({
            try {
                Thread.sleep(15_000) // let the network come up after boot
                while (true) {
                    Community.syncIfDue(app)
                    Thread.sleep(3 * 60 * 60 * 1000L)
                }
            } catch (_: InterruptedException) {}
        }, "public-list").also { it.isDaemon = true; it.start() }
    }

    override fun onDestroy() {
        syncThread?.interrupt()
        syncThread = null
        server?.stop()
        server = null
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun goForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "JacCollector", NotificationManager.IMPORTANCE_LOW))
            Notification.Builder(this, CHANNEL)
        } else {
            Notification.Builder(this)
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val n = builder
            .setContentTitle("JacCollector")
            .setContentText("Search server on port ${Store.PORT}")
            .setSmallIcon(R.drawable.icon)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }
}

/** Starts the service after the box boots or the app is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try { CollectorService.start(context) } catch (_: Exception) {}
    }
}
