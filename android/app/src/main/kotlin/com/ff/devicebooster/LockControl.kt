package com.ff.devicebooster

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.os.Build
import kotlin.math.roundToInt

/**
 * A free-floating padlock that pins the trick button's position.
 *
 * It lives in its own window, not inside the button, for two reasons. The user
 * asked for it to be movable away from the button; and folding it into the
 * button would force the button's window to grow, and a window bigger than the
 * button would swallow touches meant for the app underneath. Two small windows
 * each consume only their own footprint, which keeps that rule intact.
 *
 * Green means the position is pinned, red means it can still be dragged. Tapping
 * the padlock is the on/off switch; dragging it moves it anywhere on screen.
 */
class LockControl @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        fun onLockMoved(x: Int, y: Int)
        fun onLockSettled(x: Int, y: Int)
        fun onLockToggled(locked: Boolean)
    }

    var listener: Listener? = null

    /** Window position; tracked explicitly because a View's x/y are layout offsets. */
    var screenX = 0
    var screenY = 0

    private var locked = false
    private var pressed = false
    private var dragging = false

    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var maxX = 0
    private var maxY = 0

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val rect = RectF()

    private val lockedColor = 0xFF22C55E.toInt()
    private val unlockedColor = 0xFFEF4444.toInt()

    fun isLocked(): Boolean = locked

    fun setLocked(value: Boolean) {
        if (locked == value) return
        locked = value
        invalidate()
    }

    fun setPressedState(value: Boolean) {
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
        val state = if (locked) lockedColor else unlockedColor
        val strength = if (pressed) 0.7f else 1f
        val stroke = (2f * density).coerceAtLeast(1f)

        // A dark disc keeps the padlock readable over a light or busy backdrop.
        fill.color = 0xE0101418.toInt()
        canvas.drawCircle(cx, cy, outer, fill)

        ring.color = state
        ring.strokeWidth = stroke
        canvas.drawCircle(cx, cy, outer - stroke / 2f, ring)

        val w2 = outer * 0.86f
        val left = cx - w2 / 2f
        val top = cy - w2 * 0.32f

        glyph.color = state
        glyph.strokeWidth = (1.6f * density).coerceAtLeast(1f)
        glyph.alpha = (255 * strength).roundToInt().coerceIn(0, 255)

        // Shackle: the top half of a circle, inset so it reads as a U.
        val inset = w2 * 0.22f
        rect.set(left + inset, top, left + w2 - inset, top + (w2 - inset * 2f))
        canvas.drawArc(rect, 180f, 180f, false, glyph)

        // Body.
        rect.set(left, top + w2 * 0.36f, left + w2, top + w2 * 0.88f)
        canvas.drawRoundRect(rect, w2 * 0.14f, w2 * 0.14f, glyph)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                startX = screenX
                startY = screenY
                dragging = false
                setPressedState(true)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                dragging = false
                setPressedState(false)
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && TrickButtonMath.isDrag(dx, dy, touchSlop)) dragging = true
                if (dragging) {
                    // Full tracking: this control is meant to be placed precisely,
                    // so the button's damped pull speed does not apply.
                    val nx = TrickButtonMath.clampToScreen(startX + dx.roundToInt(), maxX)
                    val ny = TrickButtonMath.clampToScreen(startY + dy.roundToInt(), maxY)
                    screenX = nx
                    screenY = ny
                    listener?.onLockMoved(nx, ny)
                }
            }

            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    listener?.onLockSettled(screenX, screenY)
                } else {
                    performClick()
                    locked = !locked
                    invalidate()
                    listener?.onLockToggled(locked)
                }
                setPressedState(false)
                dragging = false
            }

            MotionEvent.ACTION_CANCEL -> {
                setPressedState(false)
                dragging = false
            }
        }
        // Always true: returning false would hand this touch to the app below.
        return true
    }

    /** Recalculates the cached on-screen limits; call after a display change. */
    fun refreshBounds() {
        val size = (TrickButtonConfig.LOCK_SIZE * resources.displayMetrics.density).roundToInt()
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
