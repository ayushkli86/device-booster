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

    // `armed` is the persistent user state (toggled by a tap and shown as a
    // cross). `pressed` is transient finger-down feedback only. Keeping them
    // separate stops ACTION_DOWN from clobbering the armed state on ACTION_UP.
    private var armed = false
    private var pressed = false

    /** When true the position is pinned; see [setLocked]. */
    private var locked = false

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val lockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
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

    /** True once a long press has fired, so ACTION_UP does not also tap. */
    private var longPressFired = false

    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

    // Allocated once: constructing a Runnable per ACTION_DOWN would put garbage
    // on the touch path.
    private val longPressRunnable = Runnable {
        if (!dragging) {
            longPressFired = true
            toggleLock()
        }
    }

    /**
     * Non-generic callbacks on purpose. A Kotlin `Function2<Int, Int, Unit>`
     * would box both arguments on every ACTION_MOVE; a plain interface gives a
     * direct call with primitive ints.
     */
    interface Listener {
        fun onMoved(x: Int, y: Int)
        fun onSettled(config: TrickButtonConfig)
        fun onTap()
        fun onLockChanged(locked: Boolean)
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

    fun isActive(): Boolean = armed

    fun setActive(value: Boolean) {
        if (armed == value) return
        armed = value
        invalidate()
    }

    fun isLocked(): Boolean = locked

    fun setLocked(value: Boolean) {
        if (locked == value) return
        locked = value
        config = config.copy(locked = value)
        invalidate()
    }

    private fun toggleLock() {
        setLocked(!locked)
        listener?.onLockChanged(locked)
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

        if (armed) {
            // Armed: the dot becomes a cross, so the state is unmistakable.
            crossPaint.color = applyAlpha(config.dotColor, alpha)
            crossPaint.strokeWidth = (config.borderDp * density).coerceAtLeast(2f)
            val arm = ringRadius * 0.55f
            canvas.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, crossPaint)
            canvas.drawLine(cx - arm, cy + arm, cx + arm, cy - arm, crossPaint)
        } else {
            dotPaint.color = applyAlpha(config.dotColor, alpha)
            val dotRadius = (outer * if (pressed) 0.28f else 0.22f).coerceAtLeast(1f)
            canvas.drawCircle(cx, cy, dotRadius, dotPaint)
        }

        if (locked) drawLock(canvas, cx, outer, ringWidth, alpha, density)
    }

    /** A small padlock at the top of the face, so a pinned button is obvious. */
    private fun drawLock(
        canvas: Canvas,
        cx: Float,
        outer: Float,
        ringWidth: Float,
        alpha: Int,
        density: Float,
    ) {
        val w = outer * 0.30f
        val top = outer * 0.12f + ringWidth * 0.5f
        lockPaint.color = applyAlpha(Color.WHITE, alpha)
        lockPaint.strokeWidth = (1.5f * density).coerceAtLeast(1f)

        // Shackle: the top half of a circle, inset to look like a U.
        val shackleInset = w * 0.22f
        bounds.set(
            cx - w / 2f + shackleInset,
            top,
            cx + w / 2f - shackleInset,
            top + (w - shackleInset * 2f) / 2f * 2f,
        )
        canvas.drawArc(bounds, 180f, 180f, false, lockPaint)

        // Body.
        bounds.set(cx - w / 2f, top + w * 0.30f, cx + w / 2f, top + w * 0.30f + w * 0.52f)
        canvas.drawRoundRect(bounds, w * 0.12f, w * 0.12f, lockPaint)
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
                longPressFired = false
                updatePressed(true)
                // A long press pins the button in place.
                postDelayed(longPressRunnable, longPressTimeout)
            }

            // A second finger aborts the gesture cleanly, otherwise lifting one
            // finger can leave the button stuck mid-drag.
            MotionEvent.ACTION_POINTER_DOWN -> abortGesture()

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && TrickButtonMath.isDrag(dx, dy, touchSlop)) {
                    dragging = true
                    removeCallbacks(longPressRunnable)
                }
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

            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                // A long press already consumed this gesture as a lock toggle.
                if (!dragging && !longPressFired) {
                    performClick()
                    listener?.onTap()
                }
                updatePressed(false)
                dragging = false
                longPressFired = false
                listener?.onSettled(config)
            }

            MotionEvent.ACTION_CANCEL -> {
                abortGesture()
                listener?.onSettled(config)
            }
        }
        // Always true, in every branch including locked and cancel: returning
        // false would hand this touch to the app underneath.
        return true
    }

    private fun abortGesture() {
        removeCallbacks(longPressRunnable)
        updatePressed(false)
        dragging = false
        longPressFired = false
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
