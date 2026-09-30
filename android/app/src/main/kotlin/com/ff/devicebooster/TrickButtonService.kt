package com.ff.devicebooster

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
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
 *
 * The window is sized to the button and carries FLAG_NOT_TOUCH_MODAL, which is
 * what lets touches outside the button reach the app underneath. While unlocked
 * touches on the button are consumed here for dragging; while locked the window
 * also carries FLAG_NOT_TOUCHABLE, so taps pass through to the app underneath
 * (see [applyTouchability]). Enlarging this window to full screen would break
 * that split and swallow the whole screen.
 */
class TrickButtonService : Service(), TrickButton.Listener, LockControl.Listener {

    private var button: TrickButton? = null
    private var params: WindowManager.LayoutParams? = null

    /** True only between a successful addView and its matching removeView. */
    private var attached = false

    private var lock: LockControl? = null
    private var lockParams: WindowManager.LayoutParams? = null
    private var lockAttached = false

    /** Set in onDestroy so in-flight touch callbacks no-op instead of crashing. */
    private var dead = false

    private val wm: WindowManager get() = getSystemService(WINDOW_SERVICE) as WindowManager

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

        if (!android.provider.Settings.canDrawOverlays(this)) {
            Log.w(TAG, "overlay permission is not granted; not showing")
            stopSelf()
            return
        }

        val sizePx = (config.sizeDp * resources.displayMetrics.density).roundToInt()
        val view = TrickButton(this).apply {
            applyConfig(config)
            refreshBounds()
            screenX = config.x
            screenY = config.y
            listener = this@TrickButtonService
        }
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

        try {
            wm.addView(view, p)
            attached = true
        } catch (t: Throwable) {
            // BadTokenException when the overlay grant was revoked, or
            // IllegalArgumentException on a bad layout param.
            Log.e(TAG, "could not add the trick button window", t)
            button = null
            stopSelf()
            return
        }

        attachLockWindow(config)
        applyTouchability()
    }

    /**
     * Adds the padlock as a second, independently draggable window.
     *
     * It is separate so it can sit anywhere on screen, and so the button window
     * stays sized to the button: a larger window would take touches that belong
     * to the app underneath. A failure here is not fatal, because the button is
     * still usable and the notification can still lock it.
     */
    private fun attachLockWindow(config: TrickButtonConfig) {
        val sizePx =
            (TrickButtonConfig.LOCK_SIZE * resources.displayMetrics.density).roundToInt()
        val view = LockControl(this).apply {
            setLocked(config.locked)
            refreshBounds()
            val b = screenBounds()
            // First run: park it clear of the button rather than at the raw
            // default, which would leave it jammed against the top edge. Either
            // way it is clamped, since a saved position can come from a larger
            // display.
            screenX = TrickButtonMath.clampToScreen(
                if (config.lockX < 0) config.x - sizePx * 2 else config.lockX,
                b[1],
            )
            screenY = TrickButtonMath.clampToScreen(
                if (config.lockY < 0) config.y else config.lockY,
                b[3],
            )
            listener = this@TrickButtonService
        }
        lock = view

        val p = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = view.screenX
            y = view.screenY
        }
        lockParams = p

        try {
            wm.addView(view, p)
            lockAttached = true
        } catch (t: Throwable) {
            Log.e(TAG, "could not add the lock control window", t)
            lock = null
            lockParams = null
        }
    }

    /**
     * Pulls the padlock back inside the display after a rotation or a settings
     * change. A lock that drifted off screen could not be tapped to unlock, so it
     * is never left outside the bounds.
     */
    private fun rePlaceLock(config: TrickButtonConfig) {
        val view = lock ?: return
        val p = lockParams ?: return
        view.refreshBounds()
        val b = view.screenBounds()
        val nx = TrickButtonMath.clampToScreen(view.screenX, b[1])
        val ny = TrickButtonMath.clampToScreen(view.screenY, b[3])
        view.screenX = nx
        view.screenY = ny
        p.x = nx
        p.y = ny
        if (!lockAttached) return
        try {
            wm.updateViewLayout(view, p)
        } catch (t: Throwable) {
            Log.w(TAG, "could not re-place the lock after a display change", t)
        }
        TrickButtonStore.save(
            this,
            (button?.currentConfig() ?: config).copy(
                x = button?.screenX ?: config.x,
                y = button?.screenY ?: config.y,
                lockX = nx,
                lockY = ny,
            ),
        )
    }

    /**
     * A locked button must not take touches: the tap has to reach the app
     * underneath, and the button must not arm. `FLAG_NOT_TOUCHABLE` is what
     * makes the window transparent to input, which also pins it, since a drag
     * can no longer reach it. Pull speed and over-pull prevention still apply
     * whenever it is unlocked.
     */
    private fun applyTouchability() {
        val p = params ?: return
        val view = button ?: return
        val base = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        val target = if (view.isLocked()) {
            base or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            base
        }
        if (p.flags == target) return
        p.flags = target
        if (!attached) return
        try {
            wm.updateViewLayout(view, p)
        } catch (t: Throwable) {
            Log.w(TAG, "could not change the button's touchability", t)
        }
    }

    // --- LockControl.Listener -------------------------------------------------

    override fun onLockMoved(x: Int, y: Int) {
        if (dead || !lockAttached) return
        val p = lockParams ?: return
        val view = lock ?: return
        p.x = x
        p.y = y
        try {
            wm.updateViewLayout(view, p)
        } catch (t: Throwable) {
            Log.w(TAG, "could not move the lock window", t)
        }
    }

    override fun onLockSettled(x: Int, y: Int) {
        if (dead) return
        val view = lock ?: return
        val button = button ?: return
        TrickButtonStore.save(
            this,
            button.currentConfig().copy(
                x = button.screenX,
                y = button.screenY,
                locked = view.isLocked(),
                lockX = x,
                lockY = y,
            ),
        )
    }

    override fun onLockToggled(locked: Boolean) {
        if (dead) return
        val view = lock ?: return
        val button = button ?: return
        // Keep both windows showing the same state, whichever one was touched.
        button.setLocked(locked)
        applyTouchability()
        TrickButtonStore.save(
            this,
            button.currentConfig().copy(
                x = button.screenX,
                y = button.screenY,
                locked = locked,
                lockX = view.screenX,
                lockY = view.screenY,
            ),
        )
        notify(buildNotification(stateText(button)))
    }

    // --- TrickButton.Listener -------------------------------------------------
    // Each callback is a no-op once the service is dead or the view is gone, so
    // a touch that lands mid-teardown cannot touch a detached window.

    override fun onMoved(x: Int, y: Int) {
        if (dead || !attached) return
        val p = params ?: return
        val view = button ?: return
        p.x = x
        p.y = y
        try {
            wm.updateViewLayout(view, p)
        } catch (t: Throwable) {
            Log.w(TAG, "could not move the button window", t)
        }
    }

    override fun onSettled(config: TrickButtonConfig) {
        if (dead) return
        val view = button ?: return
        TrickButtonStore.save(
            this,
            config.copy(
                x = view.screenX,
                y = view.screenY,
                // The padlock owns these; the button has no say in them.
                lockX = lock?.screenX ?: config.lockX,
                lockY = lock?.screenY ?: config.lockY,
            ),
        )
    }

    private fun stateText(view: TrickButton): String =
        if (view.isLocked()) "Trick button: locked in place" else "Trick button: drag to move"


    private fun notify(n: Notification) {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
        } catch (t: Throwable) {
            Log.w(TAG, "could not update the notification", t)
        }
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
        // Escape hatches: a locked or off-screen button must always be
        // recoverable without opening the app.
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, TrickButtonService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val locked = button?.isLocked() ?: TrickButtonStore.load(this).locked
        val lock = PendingIntent.getService(
            this,
            2,
            Intent(this, TrickButtonService::class.java)
                .setAction(ACTION_SET_LOCK)
                .putExtra(EXTRA_LOCKED, !locked),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val b = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Trick button")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(launch)
            .setOngoing(true)
        b.addAction(
            Notification.Action.Builder(
                null,
                if (locked) "Unlock" else "Lock",
                lock,
            ).build(),
        )
        b.addAction(Notification.Action.Builder(null, "Stop", stop).build())
        return b.build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_SET_LOCK -> {
                val view = button
                if (view != null && !dead) {
                    val target = intent.getBooleanExtra(EXTRA_LOCKED, false)
                    view.setLocked(target)
                    lock?.setLocked(target)
                    applyTouchability()
                    TrickButtonStore.save(
                        this,
                        view.currentConfig().copy(
                            x = view.screenX,
                            y = view.screenY,
                            locked = target,
                            lockX = lock?.screenX ?: view.currentConfig().lockX,
                            lockY = lock?.screenY ?: view.currentConfig().lockY,
                        ),
                    )
                    notify(buildNotification(stateText(view)))
                }
                return START_NOT_STICKY
            }

            ACTION_RELOAD -> {
                if (dead) return START_NOT_STICKY
                val view = button
                val p = params
                if (view != null && p != null) {
                    val next = TrickButtonStore.load(this)
                    val sizePx =
                        (next.sizeDp * resources.displayMetrics.density).roundToInt()
                    view.applyConfig(next)
                    view.refreshBounds()
                    p.width = sizePx
                    p.height = sizePx
                    // A size change can leave the button hanging off screen.
                    val b = view.screenBounds()
                    p.x = TrickButtonMath.clampToScreen(view.screenX, b[1])
                    p.y = TrickButtonMath.clampToScreen(view.screenY, b[3])
                    view.screenX = p.x
                    view.screenY = p.y
                    if (attached) {
                        try {
                            wm.updateViewLayout(view, p)
                        } catch (t: Throwable) {
                            Log.w(TAG, "resize failed", t)
                        }
                    }
                    view.setLocked(next.locked)
                    lock?.setLocked(next.locked)
                    applyTouchability()
                    rePlaceLock(next)
                    notify(buildNotification(stateText(view)))
                }
                return START_STICKY
            }
        }
        return START_STICKY
    }

    /**
     * Rotation or a display change can shrink the screen out from under a
     * pinned button. Pull it back into view, and unlock if it had to move, so
     * the user is never left with a button they cannot reach.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (dead || !attached) return
        val view = button ?: return
        val p = params ?: return

        view.refreshBounds()
        val b = view.screenBounds()
        val nx = TrickButtonMath.clampToScreen(view.screenX, b[1])
        val ny = TrickButtonMath.clampToScreen(view.screenY, b[3])
        // A pinned button that got pushed off screen must be unlocked so the
        // user can reach it again - but a button that stayed in view keeps its
        // lock, and so must the padlock that mirrors the state.
        val wasLocked = view.isLocked()
        val rescued = nx != view.screenX || ny != view.screenY
        if (rescued && wasLocked) {
            Log.i(TAG, "display changed; unlocking and pulling the button back into view")
            view.setLocked(false)
            lock?.setLocked(false)
        }
        view.screenX = nx
        view.screenY = ny
        p.x = nx
        p.y = ny
        applyTouchability()
        try {
            wm.updateViewLayout(view, p)
        } catch (t: Throwable) {
            Log.w(TAG, "could not re-place the button after a display change", t)
        }
        rePlaceLock(view.currentConfig())
    }

    override fun onDestroy() {
        dead = true
        val view = button
        if (view != null) {
            TrickButtonStore.save(
                this,
                view.currentConfig().copy(x = view.screenX, y = view.screenY),
            )
            // removeView on a view that was never added throws
            // IllegalArgumentException, hence the attached guard.
            if (attached) {
                try {
                    wm.removeView(view)
                } catch (t: Throwable) {
                    Log.w(TAG, "trick button removal failed", t)
                }
                attached = false
            }
        }
        val lockView = lock
        if (lockView != null) {
            TrickButtonStore.save(
                this,
                (button?.currentConfig() ?: TrickButtonConfig()).copy(
                    x = button?.screenX ?: 0,
                    y = button?.screenY ?: 0,
                    locked = lockView.isLocked(),
                    lockX = lockView.screenX,
                    lockY = lockView.screenY,
                ),
            )
            if (lockAttached) {
                try {
                    wm.removeView(lockView)
                } catch (t: Throwable) {
                    Log.w(TAG, "lock control removal failed", t)
                }
                lockAttached = false
            }
        }
        lock = null
        lockParams = null

        button = null
        params = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.ff.devicebooster.TRICK_STOP"
        const val ACTION_RELOAD = "com.ff.devicebooster.TRICK_RELOAD"
        const val ACTION_SET_LOCK = "com.ff.devicebooster.TRICK_SET_LOCK"
        const val EXTRA_LOCKED = "locked"
        private const val TAG = "DeviceBooster"
        private const val CHANNEL_ID = "trick_button"
        private const val NOTIFICATION_ID = 2003
    }
}
