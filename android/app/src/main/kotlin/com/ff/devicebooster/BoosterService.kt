package com.ff.devicebooster

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Hosts the overlay for as long as the booster is switched on.
 *
 * Every step that can fail on a real device - starting in the foreground, and
 * adding the overlay window - is guarded. A service that cannot put up its
 * window has nothing to offer, so it stops itself instead of lingering in the
 * notification shade doing nothing.
 */
class BoosterService : Service() {

    private var overlay: OverlayView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            startInForeground()
        } catch (t: Throwable) {
            Log.e(TAG, "could not enter the foreground", t)
            stopSelf()
            return
        }

        val view = OverlayView(this)
        overlay = view
        try {
            view.show()
        } catch (t: Throwable) {
            Log.e(TAG, "could not add the overlay window", t)
            overlay = null
            view.hide()
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // Removing a window that Android has already torn down throws, and
        // that must not take the process down.
        runCatching { overlay?.hide() }
            .onFailure { Log.w(TAG, "overlay removal failed", it) }
        overlay = null
        super.onDestroy()
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Device booster",
                NotificationManager.IMPORTANCE_MIN,
            ).apply { setShowBadge(false) },
        )

        val launch = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Booster running")
            .setContentText(ShizukuHelper.statusText())
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(launch)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val ACTION_STOP = "com.ff.devicebooster.STOP"
        private const val TAG = "DeviceBooster"
        private const val CHANNEL_ID = "device_booster"
        private const val NOTIFICATION_ID = 2002
    }
}
