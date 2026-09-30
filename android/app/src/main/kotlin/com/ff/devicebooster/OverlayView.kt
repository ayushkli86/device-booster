package com.ff.devicebooster

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The on-screen booster: a small draggable pill that expands into a panel with
 * live stats, tuning switches, a permission manager and a web search box.
 *
 * Built with plain Android views rather than a Flutter view - a service-hosted
 * Flutter engine would cost far more memory and start slower, which is the last
 * thing a performance overlay can afford.
 */
class OverlayView(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density

    /** Real display size in pixels, so dragged windows can be clamped on screen. */
    private fun displaySize(): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val p = android.graphics.Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(p)
            p.x to p.y
        }
    }

    private var root: LinearLayout? = null
    private var pill: View? = null
    private var hint: TextView? = null
    private var statsText: TextView? = null
    private var body: LinearLayout? = null
    private var searchRow: LinearLayout? = null
    private var searchInput: EditText? = null

    private val pollRunnable = object : Runnable {
        override fun run() {
            refreshStats()
            handler.postDelayed(this, POLL_MS)
        }
    }

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 16
        y = 220
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (root != null) return

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_PANEL)
        }

        val header = TextView(context).apply {
            text = "Booster"
            setTextColor(Color.WHITE)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(10), dp(8), dp(10), dp(6))
        }

        statsText = TextView(context).apply {
            setTextColor(COLOR_ACCENT)
            textSize = 11f
            setPadding(dp(10), 0, dp(10), dp(6))
            typeface = android.graphics.Typeface.MONOSPACE
        }

        val toggle = TextView(context).apply {
            text = "TAP TO EXPAND  -  DRAG TO MOVE"
            setTextColor(Color.parseColor("#9AA4B2"))
            textSize = 9f
            setPadding(dp(10), 0, dp(10), dp(6))
        }
        hint = toggle

        val collapsed = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_PANEL)
            addView(header)
            addView(statsText)
            addView(toggle)
        }

        val bodyView = buildBody().apply { visibility = View.GONE }
        body = bodyView

        container.addView(collapsed)
        container.addView(bodyView)

        root = container
        pill = collapsed

        makeDraggable(container, collapsed)

        windowManager.addView(container, params)
        handler.post(pollRunnable)
    }

    fun hide() {
        handler.removeCallbacks(pollRunnable)
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
        pill = null
        hint = null
        statsText = null
        body = null
        searchRow = null
        searchInput = null
    }

    // ---------------------------------------------------------------- panel

    private fun buildBody(): LinearLayout {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(10))
        }

        panel.addView(sectionTitle("PERFORMANCE"))

        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(260),
            )
        }
        val scrollInner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(scrollInner)
        panel.addView(scroll)

        if (!ShizukuHelper.isGranted()) {
            panel.addView(
                notice(
                    "Shizuku is not authorised, so tuning switches are hidden. " +
                        "Open the app to grant access.",
                ),
            )
        } else {
            val actions = Tuner.availableActions(context)
            if (actions.isEmpty()) {
                panel.addView(notice("This ROM exposes no tuning hooks to the shell user."))
            }
            for (action in actions) {
                // A one-shot has no off state, so a switch would promise a
                // reversal that does not exist; it gets a button instead.
                if (action.reversible) scrollInner.addView(tunerRow(action))
                else scrollInner.addView(oneShotRow(action))
            }

            val gpu = Tuner.gpuTuningAvailable(context)
            panel.addView(
                notice(
                    if (gpu) {
                        "GPU frequency control is writable on this device."
                    } else {
                        "GPU frequency control is root-only here - it is not " +
                            "offered rather than faked."
                    },
                ),
            )
        }

        panel.addView(sectionTitle("QUICK ACTIONS"))
        panel.addView(actionButton("Search the web") { showSearch() })
        panel.addView(buildSearchRow())
        panel.addView(actionButton("Clear memory") {
            val action = Tuner.ACTIONS.firstOrNull { it.id == "trim_cache" }
            if (action == null) {
                toast("Needs Shizuku")
            } else {
                report(Tuner.apply(context, action, enable = true))
            }
        })
        panel.addView(actionButton("Stop booster") { stopEverything() })

        return panel
    }

    private fun buildSearchRow(): LinearLayout {
        val input = EditText(context).apply {
            hint = "Search the web"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#8B94A3"))
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        searchInput = input

        val go = TextView(context).apply {
            text = "Go"
            setTextColor(Color.parseColor("#7EE787"))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener { submitSearch() }
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(COLOR_BUTTON)
            setPadding(dp(8), 0, 0, 0)
            visibility = View.GONE
            addView(
                input,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(go)
        }
        searchRow = row
        return row
    }

    private fun submitSearch() {
        val query = searchInput?.text?.toString()?.trim().orEmpty()
        closeSearch()
        if (query.isEmpty()) return
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://duckduckgo.com/?q=" + Uri.encode(query)),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { toast("No app can open that link") }
    }

    /**
     * The overlay is created with FLAG_NOT_FOCUSABLE so that while collapsed it
     * never swallows touches meant for the game underneath. An EditText cannot
     * take focus in such a window, so the flag is dropped only while the search
     * row is open, and restored as soon as it closes.
     */
    private fun setWindowFocusable(focusable: Boolean) {
        val root = root ?: return
        val notFocusable = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        params.flags =
            if (focusable) params.flags and notFocusable.inv()
            else params.flags or notFocusable
        runCatching { windowManager.updateViewLayout(root, params) }
            .onFailure { Log.w(TAG, "could not change window focus", it) }
    }

    private fun showSearch() {
        val row = searchRow ?: return
        row.visibility = View.VISIBLE
        setWindowFocusable(true)
        searchInput?.apply {
            requestFocus()
            post {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun closeSearch() {
        searchRow?.visibility = View.GONE
        setWindowFocusable(false)
        runCatching {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(searchInput?.windowToken, 0)
        }
    }

    private fun stopEverything() {
        closeSearch()
        context.startService(
            Intent(context, BoosterService::class.java)
                .setAction(BoosterService.ACTION_STOP),
        )
    }

    private fun labelBlock(action: TunerAction): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(
                TextView(context).apply {
                    text = action.label
                    setTextColor(Color.WHITE)
                    textSize = 12f
                },
            )
            addView(
                TextView(context).apply {
                    text = action.detail
                    setTextColor(Color.parseColor("#8B94A3"))
                    textSize = 9f
                },
            )
        }

    private fun tunerRow(action: TunerAction): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }

        val toggle = Switch(context).apply {
            // Reflect the session's actually applied state; drawing every
            // switch off made a booster restart look like all changes had
            // silently reverted.
            isChecked = Tuner.isApplied(action)
            setOnCheckedChangeListener { button, isChecked ->
                if (!button.isPressed) return@setOnCheckedChangeListener
                report(Tuner.apply(context, action, isChecked))
            }
        }

        row.addView(labelBlock(action))
        row.addView(toggle)
        return row
    }

    /** A one-shot action: run it with a button, since there is nothing to toggle off. */
    private fun oneShotRow(action: TunerAction): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val run = TextView(context).apply {
            text = "Run"
            setTextColor(COLOR_ACCENT)
            textSize = 12f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(COLOR_BUTTON)
            setOnClickListener { report(Tuner.apply(context, action, enable = true)) }
        }
        row.addView(labelBlock(action))
        row.addView(run)
        return row
    }

    // -------------------------------------------------------------- helpers

    private fun refreshStats() {
        val s = PerfMonitor.sample(context)
        val text = buildString {
            append(String.format("CPU %3.0f%%", s.cpuLoad * 100))
            if (s.cpuFreqMHz > 0) {
                append(String.format("  @%dMHz", s.cpuFreqMHz))
                if (s.cpuMaxMHz > 0) append("/${s.cpuMaxMHz}MHz")
            }
            append(String.format("\nRAM %3d%%  cores %d", s.ramUsedPercent, s.coreCount))
            s.batteryTempC?.let { append(String.format("  %.1fC", it)) }
            append(String.format("\nBAT %d%%%s", s.batteryPercent, if (s.charging) " charging" else ""))
        }
        statsText?.text = text
    }

    private fun makeDraggable(container: LinearLayout, header: View) {
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var maxW = 0
        var maxH = 0

        header.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    // Cache the clamp bounds at gesture start: the window
                    // carries FLAG_LAYOUT_NO_LIMITS, so an unclamped drag
                    // could park the panel off-screen and lose it. Querying
                    // window metrics per MOVE would put a binder call on the
                    // drag hot path.
                    val (dw, dh) = displaySize()
                    maxW = (dw - container.width).coerceAtLeast(0)
                    maxH = (dh - container.height).coerceAtLeast(0)
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!moved && (abs(dx) > touchSlop || abs(dy) > touchSlop)) moved = true
                    if (moved) {
                        params.x = (startX + dx.roundToInt()).coerceIn(0, maxW)
                        params.y = (startY + dy.roundToInt()).coerceIn(0, maxH)
                        runCatching { windowManager.updateViewLayout(container, params) }
                    }
                }

                MotionEvent.ACTION_UP -> if (!moved) toggleExpanded()
                MotionEvent.ACTION_CANCEL -> Unit
            }
            true
        }
    }

    private fun toggleExpanded() {
        val isOpen = body?.visibility == View.VISIBLE
        body?.visibility = if (isOpen) View.GONE else View.VISIBLE
        hint?.text =
            if (isOpen) "TAP TO COLLAPSE  -  DRAG TO MOVE" else "TAP TO EXPAND  -  DRAG TO MOVE"
        if (!isOpen) refreshStats()
    }

    private fun collapse() {
        body?.visibility = View.GONE
        hint?.text = "TAP TO EXPAND  -  DRAG TO MOVE"
    }

    private fun report(result: Result<*>) {
        result.fold(
            onSuccess = { toast("Applied") },
            onFailure = {
                toast("Failed: ${it.message}")
                // A switch that stayed on after the command failed would be a
                // lie, so tear the panel down and rebuild it from real state.
                rebuild()
            },
        )
    }

    private fun rebuild() {
        val wasOpen = body?.visibility == View.VISIBLE
        // The panel is re-added with the same LayoutParams object, but its
        // x/y are whatever the drag last wrote; keep the user's placement
        // instead of letting it snap or drift.
        val savedX = params.x
        val savedY = params.y
        hide()
        params.x = savedX
        params.y = savedY
        runCatching { show() }.onFailure {
            Log.e(TAG, "rebuild failed", it)
            toast("Panel could not be rebuilt")
            return
        }
        if (wasOpen) {
            body?.visibility = View.VISIBLE
            hint?.text = "TAP TO COLLAPSE  -  DRAG TO MOVE"
        }
    }

    private fun sectionTitle(text: String) = TextView(context).apply {
        this.text = text
        setTextColor(Color.parseColor("#5C8DFF"))
        textSize = 10f
        typeface = android.graphics.Typeface.MONOSPACE
        setPadding(0, dp(10), 0, dp(2))
    }

    private fun notice(text: String) = TextView(context).apply {
        this.text = text
        setTextColor(Color.parseColor("#C9A227"))
        textSize = 10f
        setPadding(0, dp(6), 0, dp(4))
    }

    private fun actionButton(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 12f
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setBackgroundColor(COLOR_BUTTON)
        setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        lp.topMargin = dp(4)
        layoutParams = lp
    }

    private fun toast(message: String) =
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int): Int = (value * density).toInt()

    companion object {
        private const val TAG = "DeviceBooster"
        private const val POLL_MS = 1000L
        private val COLOR_PANEL = Color.parseColor("#E61A1F26")
        private val COLOR_ACCENT = Color.parseColor("#7EE787")
        private val COLOR_BUTTON = Color.parseColor("#2D333B")
    }
}
