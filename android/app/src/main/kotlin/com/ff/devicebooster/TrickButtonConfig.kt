package com.ff.devicebooster

import android.content.Context
import android.graphics.Color

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
) {
    /** 0..1 alpha applied to the whole button. */
    val alphaFraction: Float get() = (opacity.coerceIn(0, 100)) / 100f

    companion object {
        const val MIN_SIZE = 48
        const val MAX_SIZE = 180
        const val MAX_BORDER = 16
        const val MIN_PULL_SPEED = 0.25f
        const val MAX_PULL_SPEED = 1.0f

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
            .apply()
    }
}
