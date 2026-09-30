package com.ff.devicebooster

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The draggable trick button: a ring with a dot in the middle, over a
 * high-contrast backing so it reads clearly against any wallpaper.
 *
 * Drawing lives here rather than in Flutter because a service-hosted Flutter
 * engine would cost far more memory than a single custom View, and the button
 * has to appear over a running game.
 */
class TrickButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var config = TrickButtonConfig()
    private var active = false

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val bounds = RectF()

    private var touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    /**
     * The button's position on the display. A View's own x/y are layout
     * offsets, so the window position is tracked explicitly and reported
     * upwards through [onMoved].
     */
    var screenX = 0
    var screenY = 0

    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false

    /** Reports the new position so the service can move the window. */
    var onMoved: ((x: Int, y: Int) -> Unit)? = null
    var onTapped: (() -> Unit)? = null
    var onSettled: ((TrickButtonConfig) -> Unit)? = null

    fun applyConfig(next: TrickButtonConfig) {
        val previous = config
        config = next.clamped()
        val changed = previous.sizeDp != config.sizeDp || previous.borderDp != config.borderDp
        invalidate()
        if (changed) onSettled?.invoke(config)
    }

    fun currentConfig(): TrickButtonConfig = config

    fun isActive(): Boolean = active

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val density = resources.displayMetrics.density
        val cx = w / 2f
        val cy = h / 2f
        val outer = minOf(w, h) / 2f
        val alpha = (255 * config.alphaFraction).roundToInt().coerceIn(0, 255)

        // High-contrast backing so the button stays visible on light or busy
        // backgrounds; it fades with the user's opacity setting.
        fillPaint.color = applyAlpha(config.backColor, alpha)
        canvas.drawCircle(cx, cy, outer, fillPaint)

        // A thin light edge keeps the circle readable even at low opacity.
        ringPaint.color = applyAlpha(Color.WHITE, (alpha * 0.35f).roundToInt())
        ringPaint.strokeWidth = 1f * density
        canvas.drawCircle(cx, cy, outer - ringPaint.strokeWidth / 2f, ringPaint)

        val ringWidth = (config.borderDp * density).coerceAtLeast(1f)
        ringPaint.color = applyAlpha(config.ringColor, alpha)
        ringPaint.strokeWidth = ringWidth
        val ringRadius = outer - ringWidth / 2f - 2f * density
        if (ringRadius > 0f) canvas.drawCircle(cx, cy, ringRadius, ringPaint)

        if (active) {
            // Armed: the dot becomes a cross, so the state is unmistakable.
            crossPaint.color = applyAlpha(config.dotColor, alpha)
            crossPaint.strokeWidth = (config.borderDp * density).coerceAtLeast(2f)
            val arm = ringRadius * 0.55f
            canvas.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, crossPaint)
            canvas.drawLine(cx - arm, cy + arm, cx + arm, cy - arm, crossPaint)
        } else {
            dotPaint.color = applyAlpha(config.dotColor, alpha)
            canvas.drawCircle(cx, cy, (outer * 0.22f).coerceAtLeast(1f), dotPaint)
        }
    }

    private fun applyAlpha(color: Int, alpha: Int) =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                startX = screenX
                startY = screenY
                dragging = false
                setActive(true)
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    dragging = true
                }
                if (dragging) {
                    // pullSpeed < 1 makes the button trail the finger, so small
                    // corrections stay precise instead of overshooting.
                    val scale = config.pullSpeed.coerceIn(
                        TrickButtonConfig.MIN_PULL_SPEED,
                        TrickButtonConfig.MAX_PULL_SPEED,
                    )
                    var nx = startX + (dx * scale).roundToInt()
                    var ny = startY + (dy * scale).roundToInt()

                    if (config.preventOverPull) {
                        val (minX, maxX, minY, maxY) = boundsForCurrentSize()
                        nx = nx.coerceIn(minX, maxX)
                        ny = ny.coerceIn(minY, maxY)
                    }
                    screenX = nx
                    screenY = ny
                    onMoved?.invoke(nx, ny)
                }
            }

            MotionEvent.ACTION_UP -> {
                if (!dragging) onTapped?.invoke()
                setActive(false)
                onSettled?.invoke(config)
            }

            MotionEvent.ACTION_CANCEL -> {
                setActive(false)
                onSettled?.invoke(config)
            }
        }
        return true
    }

    private fun boundsForCurrentSize(): IntArray {
        val size = (config.sizeDp * resources.displayMetrics.density).roundToInt()
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.currentWindowMetrics.bounds
        } else {
            val d = context.resources.displayMetrics
            android.graphics.Rect(0, 0, d.widthPixels, d.heightPixels)
        }
        val maxX = (metrics.width() - size).coerceAtLeast(0)
        val maxY = (metrics.height() - size).coerceAtLeast(0)
        return intArrayOf(0, maxX, 0, maxY)
    }

    override fun performClick(): Boolean = super.performClick()
}
