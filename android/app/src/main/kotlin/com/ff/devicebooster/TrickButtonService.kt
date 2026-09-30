package com.ff.devicebooster

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import kotlin.math.roundToInt

/**
 * Hosts the trick button as a standalone overlay so it can sit over a game
 * without the rest of the booster panel.
 */
class TrickButtonService : Service() {

    private var button: TrickButton? = null
    private var params: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            startInForeground()
        } catch (t: Throwable) {
            Log.e(TAG, "trick button could not enter the foreground", t)
            stopSelf()
            return
        }

        val config = TrickButtonStore.load(this)
        if (!config.enabled) {
            Log.i(TAG, "trick button is disabled in settings; not showing")
            stopSelf()
            return
        }

        val sizePx = (config.sizeDp * resources.displayMetrics.density).roundToInt()
        val view = TrickButton(this).apply { applyConfig(config) }
        button = view

        val p = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = config.x
            y = config.y
        }
        params = p

        view.screenX = config.x
        view.screenY = config.y

        view.onMoved = { x, y ->
            p.x = x
            p.y = y
            runCatching {
                (getSystemService(WINDOW_SERVICE) as WindowManager).updateViewLayout(view, p)
            }.onFailure { Log.w(TAG, "could not move the button window", it) }
        }

        view.onSettled = { settled ->
            TrickButtonStore.save(
                this,
                settled.copy(x = view.screenX, y = view.screenY),
            )
        }

        view.onTapped = { toggleActive(view) }

        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(view, p)
        } catch (t: Throwable) {
            Log.e(TAG, "could not add the trick button window", t)
            button = null
            stopSelf()
        }
    }

    private fun toggleActive(view: TrickButton) {
        val nowActive = !view.isActive()
        view.setActive(nowActive)
        val notification = getSystemService(NotificationManager::class.java)
        notification.notify(
            NOTIFICATION_ID,
            buildNotification(if (nowActive) "Trick button: armed" else "Trick button: idle"),
        )
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Trick button",
                NotificationManager.IMPORTANCE_MIN,
            ).apply { setShowBadge(false) },
        )
        startForegroundCompat(buildNotification("Trick button: idle"))
    }

    private fun startForegroundCompat(notification: Notification) {
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

    private fun buildNotification(text: String): Notification {
        val launch = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Trick button")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(launch)
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RELOAD) {
            val view = button
            val p = params
            if (view != null && p != null) {
                val next = TrickButtonStore.load(this)
                val sizePx = (next.sizeDp * resources.displayMetrics.density).roundToInt()
                view.applyConfig(next)
                p.width = sizePx
                p.height = sizePx
                runCatching {
                    (getSystemService(WINDOW_SERVICE) as WindowManager)
                        .updateViewLayout(view, p)
                }.onFailure { Log.w(TAG, "resize failed", it) }
            }
            return START_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        button?.let { view ->
            TrickButtonStore.save(
                this,
                view.currentConfig().copy(x = view.screenX, y = view.screenY),
            )
            runCatching {
                (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view)
            }.onFailure { Log.w(TAG, "trick button removal failed", it) }
        }
        button = null
        params = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.ff.devicebooster.TRICK_STOP"
        const val ACTION_RELOAD = "com.ff.devicebooster.TRICK_RELOAD"
        private const val TAG = "DeviceBooster"
        private const val CHANNEL_ID = "trick_button"
        private const val NOTIFICATION_ID = 2003
    }
}
