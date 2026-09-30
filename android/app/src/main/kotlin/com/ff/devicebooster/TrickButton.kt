package com.ff.devicebooster

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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

    // `pressed` is transient finger-down feedback only; the button has no
    // latched state, because a tap no longer does anything.
    private var pressed = false

    /** When true the position is pinned; see [setLocked]. */
    private var locked = false

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

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

    /**
     * Non-generic callbacks on purpose. A Kotlin `Function2<Int, Int, Unit>`
     * would box both arguments on every ACTION_MOVE; a plain interface gives a
     * direct call with primitive ints.
     */
    interface Listener {
        fun onMoved(x: Int, y: Int)
        fun onSettled(config: TrickButtonConfig)
    }

    var listener: Listener? = null

    fun applyConfig(next: TrickButtonConfig) {
        val previous = config
        config = next.clamped()
        val changed = previous.sizeDp != config.sizeDp || previous.borderDp != config.borderDp
        if (previous.locked != config.locked) locked = config.locked
        if (changed) refreshBounds()
        invalidate()
        if (changed) listener?.onSettled(config)
    }

    fun currentConfig(): TrickButtonConfig = config

    fun isLocked(): Boolean = locked

    fun setLocked(value: Boolean) {
        if (locked == value) return
        locked = value
        config = config.copy(locked = value)
        invalidate()
    }

    private fun updatePressed(value: Boolean) {
        if (pressed == value) return
        pressed = value
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

        dotPaint.color = applyAlpha(config.dotColor, alpha)
        val dotRadius = (outer * if (pressed) 0.28f else 0.22f).coerceAtLeast(1f)
        canvas.drawCircle(cx, cy, dotRadius, dotPaint)
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
                updatePressed(true)
            }

            // A second finger aborts the gesture cleanly, otherwise lifting one
            // finger can leave the button stuck mid-drag.
            MotionEvent.ACTION_POINTER_DOWN -> abortGesture()

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && TrickButtonMath.isDrag(dx, dy, touchSlop)) dragging = true
                if (dragging && !locked) {
                    var nx = TrickButtonMath.dragged(startX, dx, config.pullSpeed)
                    var ny = TrickButtonMath.dragged(startY, dy, config.pullSpeed)

                    if (config.preventOverPull) {
                        nx = TrickButtonMath.clampToScreen(nx, maxX)
                        ny = TrickButtonMath.clampToScreen(ny, maxY)
                    }
                    screenX = nx
                    screenY = ny
                    listener?.onMoved(nx, ny)
                }
            }

            // A tap that never became a drag is deliberately inert: arming and
            // disarming were removed so the touch can reach the app underneath.
            MotionEvent.ACTION_UP -> {
                updatePressed(false)
                dragging = false
                listener?.onSettled(config)
            }

            MotionEvent.ACTION_CANCEL -> {
                abortGesture()
                listener?.onSettled(config)
            }
        }
        // Always true. This only runs when unlocked: while locked the window
        // carries FLAG_NOT_TOUCHABLE and never receives touches at all, which
        // is how taps pass through to the app underneath.
        return true
    }

    private fun abortGesture() {
        updatePressed(false)
        dragging = false
    }

    /**
     * Cached `[minX, maxX, minY, maxY]` limits for the button's current size.
     * Recomputed on config change and rotation, so the ACTION_MOVE path reads
     * two ints instead of allocating an array per frame.
     */
    private var maxX = 0
    private var maxY = 0

    /** Recalculates the cached limits; call after a size or display change. */
    fun refreshBounds() {
        val size = (config.sizeDp * resources.displayMetrics.density).roundToInt()
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.currentWindowMetrics.bounds
        } else {
            val d = context.resources.displayMetrics
            android.graphics.Rect(0, 0, d.widthPixels, d.heightPixels)
        }
        maxX = (metrics.width() - size).coerceAtLeast(0)
        maxY = (metrics.height() - size).coerceAtLeast(0)
    }

    /** Current `[minX, maxX, minY, maxY]`; allocates, so not for the touch path. */
    fun screenBounds(): IntArray = intArrayOf(0, maxX, 0, maxY)
}
