package com.ff.devicebooster

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    private lateinit var channel: MethodChannel
    private var bootWatchdog: Runnable? = null

    /**
     * Set once the UI is known to be up. A plain flag rather than relying on
     * handler bookkeeping: the activity can be recreated, which posts a second
     * watchdog and leaves the first one untracked, and a late second post would
     * otherwise cover a perfectly good screen.
     */
    private val bootOk = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        armBootWatchdog()
    }

    /**
     * If the Dart side never produces a frame the window would otherwise stay
     * blank white with nothing to indicate a failure. After [BOOT_TIMEOUT_MS]
     * we draw a native explanation on top so the cause is visible.
     */
    private fun armBootWatchdog() {
        if (bootOk.get()) return
        val watchdog = Runnable {
            if (bootOk.get()) return@Runnable
            Log.w(TAG, "boot watchdog fired: Dart never signalled that the UI was up")
            showBootFailure("The interface did not finish starting.")
        }
        bootWatchdog = watchdog
        Handler(Looper.getMainLooper()).postDelayed(watchdog, BOOT_TIMEOUT_MS)
    }

    private fun disarmBootWatchdog() {
        bootOk.set(true)
        bootWatchdog?.let {
            Handler(Looper.getMainLooper()).removeCallbacks(it)
            Log.i(TAG, "boot watchdog disarmed")
        }
        bootWatchdog = null
    }

    override fun onFlutterUiDisplayed() {
        super.onFlutterUiDisplayed()
        disarmBootWatchdog()
    }
    private fun showBootFailure(reason: String) {
        if (bootOk.get() || isFinishing || isDestroyed) return
        runOnUiThread {
            if (bootOk.get() || isFinishing || isDestroyed) return@runOnUiThread
            val message = TextView(this).apply {
                setBackgroundColor(Color.parseColor("#101418"))
                setTextColor(Color.parseColor("#FF6B6B"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setPadding(48, 48, 48, 48)
                gravity = Gravity.CENTER
                text = buildString {
                    appendLine("Device Booster could not start.")
                    appendLine()
                    appendLine(reason)
                    appendLine()
                    appendLine("This is a bug in the app, not your device.")
                    appendLine("Logcat tag: $TAG")
                }
            }
            addContentView(
                message,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_POST_NOTIFICATIONS,
            )
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        runCatching { ShizukuHelper.attach() }.onFailure {
            Log.w(TAG, "Shizuku attach failed", it)
        }
        ShizukuHelper.onPermissionChanged = {
            runOnUiThread {
                runCatching { channel.invokeMethod("permissionChanged", null) }
                    .onFailure { Log.w(TAG, "permissionChanged dispatch failed", it) }
            }
        }

        channel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            CHANNEL,
        )

        channel.setMethodCallHandler { call, result ->
            // Every bridge call is guarded: a throw here would otherwise
            // surface on the Dart side as a channel error with no detail, or
            // not at all.
            try {
                handle(call.method, call, result)
            } catch (t: Throwable) {
                Log.e(TAG, "unhandled failure in ${call.method}", t)
                result.error(
                    "native_error",
                    t.message ?: t::class.java.simpleName,
                    Log.getStackTraceString(t),
                )
            }
        }
    }

    private fun handle(
        method: String,
        call: MethodCall,
        result: MethodChannel.Result,
    ) {
        when (method) {
            // The only reliable "Dart is alive and painting" signal. The native
            // onFlutterUiDisplayed callback does not fire consistently, and
            // relying on it made the watchdog cover a working UI.
            "booted" -> {
                disarmBootWatchdog()
                result.success(null)
            }

            "shizukuStatus" -> result.success(
                mapOf(
                    "running" to ShizukuHelper.isRunning(),
                    "granted" to ShizukuHelper.isGranted(),
                    "text" to ShizukuHelper.statusText(),
                ),
            )

            "requestShizuku" -> {
                if (!ShizukuHelper.isRunning()) {
                    result.error(
                        "shizuku_not_running",
                        "Start Shizuku first, then grant access.",
                        null,
                    )
                    return
                }
                ShizukuHelper.requestPermission()
                result.success(null)
            }

            "canDrawOverlays" -> result.success(Settings.canDrawOverlays(this))

            "openOverlaySettings" -> {
                startOverlaySettings()
                result.success(null)
            }

            "openShizukuApp" -> {
                if (!launchShizuku()) {
                    result.error(
                        "shizuku_missing",
                        "Shizuku is not installed on this device.",
                        null,
                    )
                    return
                }
                result.success(null)
            }

            "startBooster" -> {
                if (!Settings.canDrawOverlays(this)) {
                    result.error(
                        "no_overlay",
                        "Overlay permission has not been granted",
                        null,
                    )
                    return
                }
                startForegroundService(Intent(this, BoosterService::class.java))
                result.success(null)
            }

            "stopBooster" -> {
                startService(
                    Intent(this, BoosterService::class.java)
                        .setAction(BoosterService.ACTION_STOP),
                )
                result.success(null)
            }

            "probeActions" -> {
                val actions = Tuner.availableActions(this).map { action ->
                    mapOf(
                        "id" to action.id,
                        "label" to action.label,
                        "detail" to action.detail,
                        "reversible" to action.reversible,
                    )
                }
                result.success(
                    mapOf(
                        "actions" to actions,
                        "gpuTuning" to Tuner.gpuTuningAvailable(this),
                    ),
                )
            }

            "runAction" -> {
                val id = call.argument<String>("id")
                val enable = call.argument<Boolean>("enable") ?: true
                val action = Tuner.ACTIONS.firstOrNull { it.id == id }
                if (action == null) {
                    result.error("unknown_action", "No action with id $id", null)
                    return
                }
                Tuner.apply(this, action, enable).fold(
                    onSuccess = { result.success("ok") },
                    onFailure = {
                        Log.w(TAG, "action $id failed", it)
                        result.error(
                            "action_failed",
                            it.message ?: "The command was rejected.",
                            null,
                        )
                    },
                )
            }

            "trickConfig" -> result.success(
                runCatching { trickConfigToMap(TrickButtonStore.load(this)) }
                    .getOrElse {
                        Log.e(TAG, "could not read the trick button config", it)
                        mapOf("error" to (it.message ?: "unknown"))
                    },
            )

            "trickSave" -> {
                val error = runCatching {
                    TrickButtonStore.save(this, trickConfigFromCall(call))
                }.exceptionOrNull()
                if (error != null) {
                    Log.e(TAG, "could not save the trick button config", error)
                    result.error(
                        "trick_save_failed",
                        error.message ?: "Could not save the settings.",
                        null,
                    )
                    return
                }
                // Resize the live button so the change is visible immediately.
                if (isTrickButtonRunning()) {
                    runCatching {
                        startService(
                            Intent(this, TrickButtonService::class.java)
                                .setAction(TrickButtonService.ACTION_RELOAD),
                        )
                    }.onFailure { Log.w(TAG, "live reload failed", it) }
                }
                result.success(true)
            }

            "trickRunning" -> result.success(isTrickButtonRunning())

            "trickStart" -> {
                if (!Settings.canDrawOverlays(this)) {
                    result.error(
                        "no_overlay",
                        "Overlay permission has not been granted",
                        null,
                    )
                    return
                }
                if (!TrickButtonStore.load(this).enabled) {
                    result.error(
                        "trick_disabled",
                        "The trick button is switched off in the app.",
                        null,
                    )
                    return
                }
                runCatching { startForegroundService(Intent(this, TrickButtonService::class.java)) }
                    .onFailure {
                        Log.e(TAG, "trick button service refused to start", it)
                        result.error(
                            "trick_start_failed",
                            it.message ?: "The overlay could not be started.",
                            null,
                        )
                        return
                    }
                result.success(true)
            }

            "trickStop" -> {
                runCatching {
                    startService(
                        Intent(this, TrickButtonService::class.java)
                            .setAction(TrickButtonService.ACTION_STOP),
                    )
                }.onFailure { Log.w(TAG, "trick stop failed", it) }
                result.success(true)
            }

            "sample" -> {
                val s = PerfMonitor.sample(this)
                result.success(
                    mapOf(
                        "cpuLoad" to s.cpuLoad.toDouble(),
                        "cpuFreqMHz" to s.cpuFreqMHz,
                        "cpuMaxMHz" to s.cpuMaxMHz,
                        "ramPercent" to s.ramUsedPercent,
                        "batteryTempC" to (s.batteryTempC?.toDouble() ?: -1.0),
                        "batteryPercent" to s.batteryPercent,
                        "charging" to s.charging,
                        "cores" to s.coreCount,
                    ),
                )
            }

            else -> result.notImplemented()
        }
    }

    // Deprecated, but since Android 8 it only reports this app's own services,
    // which is precisely what is being asked here.
    @Suppress("DEPRECATION")
    private fun isTrickButtonRunning(): Boolean =
        getSystemService(android.app.ActivityManager::class.java)
            .getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == TrickButtonService::class.java.name }

    private fun trickConfigToMap(c: TrickButtonConfig) = mapOf(
        "enabled" to c.enabled,
        "sizeDp" to c.sizeDp,
        "borderDp" to c.borderDp,
        "opacity" to c.opacity,
        "preventOverPull" to c.preventOverPull,
        "pullSpeed" to c.pullSpeed.toDouble(),
        "ringColor" to c.ringColor,
        "dotColor" to c.dotColor,
        "backColor" to c.backColor,
    )

    private fun trickConfigFromCall(call: MethodCall): TrickButtonConfig {
        val base = TrickButtonStore.load(this)
        val args = call.arguments as? Map<*, *> ?: return base
        fun bool(key: String, fallback: Boolean) = (args[key] as? Boolean) ?: fallback
        fun int(key: String, fallback: Int) = (args[key] as? Number)?.toInt() ?: fallback
        fun flt(key: String, fallback: Float) = (args[key] as? Number)?.toFloat() ?: fallback
        return TrickButtonConfig(
            enabled = bool("enabled", base.enabled),
            sizeDp = int("sizeDp", base.sizeDp),
            borderDp = int("borderDp", base.borderDp),
            opacity = int("opacity", base.opacity),
            preventOverPull = bool("preventOverPull", base.preventOverPull),
            pullSpeed = flt("pullSpeed", base.pullSpeed),
            ringColor = int("ringColor", base.ringColor),
            dotColor = int("dotColor", base.dotColor),
            backColor = int("backColor", base.backColor),
            x = base.x,
            y = base.y,
        ).clamped()
    }

    private fun startOverlaySettings() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
        }.onFailure {
            Log.w(TAG, "overlay settings intent failed, falling back", it)
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    /** Shizuku exposes no launcher activity, so open its package directly. */
    private fun launchShizuku(): Boolean {
        val intent = context.packageManager
            .getLaunchIntentForPackage(SHIZUKU_PACKAGE)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent == null) {
            Log.w(TAG, "no launch intent for $SHIZUKU_PACKAGE")
            return false
        }
        return runCatching { startActivity(intent) }
            .onFailure { Log.w(TAG, "could not open Shizuku", it) }
            .isSuccess
    }

    companion object {
        private const val TAG = "DeviceBooster"
        private const val CHANNEL = "com.ff.devicebooster/bridge"
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val BOOT_TIMEOUT_MS = 10_000L
        private const val REQUEST_POST_NOTIFICATIONS = 1001
    }
}
