package com.ff.devicebooster

import android.content.Context
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * User-tunable appearance and behaviour of the trick button.
 *
 * Appearance defaults to a ring with a dot in the middle, drawn over a
 * high-contrast backing so it stays readable on any wallpaper or game.
 */
data class TrickButtonConfig(
    val enabled: Boolean = true,
    val sizeDp: Int = 76,
    val borderDp: Int = 4,
    val opacity: Int = 85,
    val preventOverPull: Boolean = true,
    val pullSpeed: Float = 1.0f,
    val ringColor: Int = DEFAULT_RING,
    val dotColor: Int = DEFAULT_DOT,
    val backColor: Int = DEFAULT_BACK,
    val x: Int = 0,
    val y: Int = 0,
    /**
     * When true the button is pinned where it is and transparent to input:
     * taps pass through to the app underneath. The service applies
     * FLAG_NOT_TOUCHABLE while this is set, so the view never sees a touch.
     */
    val locked: Boolean = false,
    /**
     * Where the free-floating lock control sits, in screen pixels. It is a
     * separate window so it can be parked anywhere, including well away from
     * the button. Negative means it has never been placed, which is distinct
     * from a real 0 coordinate the user could drag it to.
     */
    val lockX: Int = -1,
    val lockY: Int = -1,
) {
    /** 0..1 alpha applied to the whole button. */
    val alphaFraction: Float get() = (opacity.coerceIn(0, 100)) / 100f

    companion object {
        const val MIN_SIZE = 48
        const val MAX_SIZE = 180
        const val MAX_BORDER = 16
        const val MIN_PULL_SPEED = 0.25f
        const val MAX_PULL_SPEED = 1.0f

        /** Diameter of the floating lock control, in dp. */
        const val LOCK_SIZE = 40

        val DEFAULT_RING = Color.parseColor("#7EE787")
        val DEFAULT_DOT = Color.parseColor("#FF6B6B")
        val DEFAULT_BACK = Color.parseColor("#101418")

        const val PREFS = "trick_button"
    }

    fun clamped(): TrickButtonConfig = copy(
        sizeDp = sizeDp.coerceIn(MIN_SIZE, MAX_SIZE),
        borderDp = borderDp.coerceIn(1, MAX_BORDER),
        opacity = opacity.coerceIn(0, 100),
        pullSpeed = pullSpeed.coerceIn(MIN_PULL_SPEED, MAX_PULL_SPEED),
    )
}

/**
 * The drag maths, kept free of Android types so it can be unit tested on the
 * JVM. Every function here runs on the ACTION_MOVE hot path, so none of them
 * allocate.
 */
object TrickButtonMath {

    /** Distance the finger must travel before a touch counts as a drag. */
    fun isDrag(dx: Float, dy: Float, touchSlop: Int): Boolean =
        abs(dx) > touchSlop || abs(dy) > touchSlop

    /**
     * Where the button should sit for a finger delta of [delta] starting at
     * [start]. A [pullSpeed] of 1 tracks the finger exactly; lower values make
     * the button trail it, which damps overshoot.
     */
    fun dragged(start: Int, delta: Float, pullSpeed: Float): Int {
        val speed = pullSpeed.coerceIn(
            TrickButtonConfig.MIN_PULL_SPEED,
            TrickButtonConfig.MAX_PULL_SPEED,
        )
        return start + (delta * speed).roundToInt()
    }

    /**
     * Keeps the button fully on screen. [max] is `screenSize - buttonSize` and
     * is floored at 0 so a screen smaller than the button cannot invert the
     * range and throw the button off the display.
     */
    fun clampToScreen(value: Int, max: Int): Int = value.coerceIn(0, max.coerceAtLeast(0))
}

/** Persists the config so the button reopens the way the user left it. */
object TrickButtonStore {
    private fun prefs(context: Context) =
        context.getSharedPreferences(TrickButtonConfig.PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): TrickButtonConfig {
        val p = prefs(context)
        val d = TrickButtonConfig()
        return TrickButtonConfig(
            enabled = p.getBoolean("enabled", d.enabled),
            sizeDp = p.getInt("sizeDp", d.sizeDp),
            borderDp = p.getInt("borderDp", d.borderDp),
            opacity = p.getInt("opacity", d.opacity),
            preventOverPull = p.getBoolean("preventOverPull", d.preventOverPull),
            pullSpeed = p.getFloat("pullSpeed", d.pullSpeed),
            ringColor = p.getInt("ringColor", d.ringColor),
            dotColor = p.getInt("dotColor", d.dotColor),
            backColor = p.getInt("backColor", d.backColor),
            x = p.getInt("x", d.x),
            y = p.getInt("y", d.y),
            locked = p.getBoolean("locked", d.locked),
            lockX = p.getInt("lockX", d.lockX),
            lockY = p.getInt("lockY", d.lockY),
        ).clamped()
    }

    fun save(context: Context, c: TrickButtonConfig) {
        val v = c.clamped()
        prefs(context).edit()
            .putBoolean("enabled", v.enabled)
            .putInt("sizeDp", v.sizeDp)
            .putInt("borderDp", v.borderDp)
            .putInt("opacity", v.opacity)
            .putBoolean("preventOverPull", v.preventOverPull)
            .putFloat("pullSpeed", v.pullSpeed)
            .putInt("ringColor", v.ringColor)
            .putInt("dotColor", v.dotColor)
            .putInt("backColor", v.backColor)
            .putInt("x", v.x)
            .putInt("y", v.y)
            .putBoolean("locked", v.locked)
            .putInt("lockX", v.lockX)
            .putInt("lockY", v.lockY)
            .apply()
    }
}
