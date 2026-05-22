package com.example.fuelmonitor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

class FuelMonitorKeepAliveService : Service() {
    private var partialWakeLock: PowerManager.WakeLock? = null
    private var screenWakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val keepScreenOn = intent?.getBooleanExtra(EXTRA_KEEP_SCREEN_ON, false) ?: false
        startForeground(NOTIFICATION_ID, buildNotification())
        acquirePartialWakeLock()
        updateScreenWakeLock(keepScreenOn)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseScreenWakeLock()
        releasePartialWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_concept_a)
            .setContentTitle("Engine Monitor")
            .setContentText("Logging fuel telemetry in the background")
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

    private fun acquirePartialWakeLock() {
        val lock = partialWakeLock ?: run {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$packageName:fuel-monitor-logging"
            ).also {
                it.setReferenceCounted(false)
                partialWakeLock = it
            }
        }
        if (!lock.isHeld) lock.acquire()
    }

    private fun releasePartialWakeLock() {
        partialWakeLock?.let {
            if (it.isHeld) it.release()
        }
        partialWakeLock = null
    }

    private fun updateScreenWakeLock(enabled: Boolean) {
        if (enabled) acquireScreenWakeLock() else releaseScreenWakeLock()
    }

    @Suppress("DEPRECATION")
    private fun acquireScreenWakeLock() {
        val lock = screenWakeLock ?: run {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "$packageName:fuel-monitor-screen"
            ).also {
                it.setReferenceCounted(false)
                screenWakeLock = it
            }
        }
        if (!lock.isHeld) lock.acquire()
    }

    private fun releaseScreenWakeLock() {
        screenWakeLock?.let {
            if (it.isHeld) it.release()
        }
        screenWakeLock = null
    }

    companion object {
        private const val CHANNEL_ID = "fuel_monitor_keep_alive"
        private const val CHANNEL_NAME = "Engine Monitor"
        private const val NOTIFICATION_ID = 3001
        private const val ACTION_STOP = "com.example.fuelmonitor.STOP_KEEP_ALIVE"
        private const val EXTRA_KEEP_SCREEN_ON = "keep_screen_on"

        fun start(context: Context, keepScreenOn: Boolean) {
            val intent = Intent(context, FuelMonitorKeepAliveService::class.java)
                .putExtra(EXTRA_KEEP_SCREEN_ON, keepScreenOn)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun updateKeepScreenOn(context: Context, keepScreenOn: Boolean) {
            start(context, keepScreenOn)
        }

        fun stop(context: Context) {
            val intent = Intent(context, FuelMonitorKeepAliveService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
