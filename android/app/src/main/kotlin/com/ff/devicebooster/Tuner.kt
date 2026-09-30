package com.ff.devicebooster

import android.content.Context

/**
 * One performance action, plus the probe used to decide whether it is offered.
 *
 * [probe] returns null when the action is genuinely unavailable on this device,
 * which is what keeps the UI honest: an action that cannot work is hidden
 * rather than shown as a switch that silently does nothing.
 */
data class TunerAction(
    val id: String,
    val label: String,
    val detail: String,
    val needsShizuku: Boolean,
    val probe: String,
    val enable: String,
    val disable: String,
) {
    /** Some actions have no natural "off" state to verify (e.g. a cache drop). */
    val reversible: Boolean get() = disable.isNotBlank()
}

/** One selectable step of a [TunerTierAction]. */
data class TunerTier(
    val id: String,
    val label: String,
    val value: String,
)

/**
 * An action with several discrete settings rather than an on/off switch.
 *
 * [note] is shown in the UI and must state anything the user cannot see for
 * themselves, such as a setting that does not survive a reboot.
 */
data class TunerTierAction(
    val id: String,
    val label: String,
    val detail: String,
    val note: String,
    val needsShizuku: Boolean,
    val namespace: String,
    val key: String,
    val probe: String,
    val read: String,
    val tiers: List<TunerTier>,
) {
    fun commandFor(tierId: String): String? =
        tiers.firstOrNull { it.id == tierId }
            ?.let { "device_config put $namespace $key ${it.value}" }
}

object Tuner {

    /**
     * `cmd power set-fixed-performance-mode-enabled` pins every core at its
     * maximum frequency and stops thermal-aware downclocking. This is the one
     * genuinely effective CPU boost available to a non-rooted app, because it
     * goes through the platform power service rather than writing sysfs.
     */
    private val FIXED_PERF_ON = "cmd power set-fixed-performance-mode-enabled true"
    private val FIXED_PERF_OFF = "cmd power set-fixed-performance-mode-enabled false"

    /**
     * `cmd thermalservice override-status 0` reports "none" to the thermal
     * HAL, so the SoC does not throttle. Effective, but it also disables the
     * protection that keeps the phone from damaging itself, so it is labelled
     * as such in the UI.
     */
    private val THERMAL_ON = "cmd thermalservice override-status 0"
    private val THERMAL_OFF = "cmd thermalservice reset"

    val ACTIONS: List<TunerAction> = listOf(
        TunerAction(
            id = "fixed_perf",
            label = "Lock CPU to max frequency",
            detail = "Pins all cores at top clock and disables frequency scaling.",
            needsShizuku = true,
            probe = "cmd power help 2>/dev/null | grep -q fixed-performance",
            enable = FIXED_PERF_ON,
            disable = FIXED_PERF_OFF,
        ),
        TunerAction(
            id = "battery_saver",
            label = "Disable battery saver",
            detail = "Stops low-power mode from capping the SoC.",
            needsShizuku = true,
            probe = "settings get global low_power",
            enable = "settings put global low_power 0",
            disable = "settings put global low_power 1",
        ),
        TunerAction(
            id = "thermal",
            label = "Bypass thermal throttling",
            detail = "DANGER: stops the SoC protecting itself from overheating.",
            needsShizuku = true,
            probe = "cmd thermalservice help 2>/dev/null | grep -q override-status",
            enable = THERMAL_ON,
            disable = THERMAL_OFF,
        ),
        TunerAction(
            id = "process_limit",
            label = "Raise background process limit",
            detail = "Lets more apps stay resident instead of being killed.",
            needsShizuku = true,
            probe = "device_config get activity_manager max_apps_processes",
            enable = "device_config put activity_manager max_apps_processes 1000",
            disable = "device_config delete activity_manager max_apps_processes",
        ),
        TunerAction(
            id = "anim_scale",
            label = "Speed up UI animations",
            detail = "Shortens window, transition and animator scales.",
            needsShizuku = true,
            probe = "settings get global window_animation_scale",
            enable = (
                "settings put global window_animation_scale 0.5; " +
                    "settings put global transition_animation_scale 0.5; " +
                    "settings put global animator_duration_scale 0.5"
                ),
            disable = (
                "settings put global window_animation_scale 1.0; " +
                    "settings put global transition_animation_scale 1.0; " +
                    "settings put global animator_duration_scale 1.0"
                ),
        ),
        TunerAction(
            id = "stay_on",
            label = "Keep screen awake while charging",
            detail = "Stops the display sleeping while the device is plugged in.",
            needsShizuku = true,
            probe = "svc power stayon true 2>/dev/null || settings get global stay_on_while_plugged_in",
            enable = "settings put global stay_on_while_plugged_in 7",
            disable = "settings put global stay_on_while_plugged_in 0",
        ),
        TunerAction(
            id = "trim_cache",
            label = "Free memory now",
            detail = "Kills all cached background processes to reclaim RAM. One-shot.",
            needsShizuku = true,
            probe = "am get-current-user",
            // "am send-trim-memory" is deliberately not used: Android rejects a
            // request for a less memory-hungry level than the device is already
            // in, so it always failed with IllegalArgumentException.
            enable = "am kill-all",
            disable = "",
        ),
    )

    /**
     * Phantom processes are child processes an app forks that do not get their
     * own UID: terminal emulators, toolchains, and anything spawning helpers.
     * The platform caps how many may exist at once, and the newest is killed
     * past the cap, which is what makes heavy multi-process tools die in the
     * background.
     *
     * The tier ladder is deliberately capped at 1024, which is what this vendor
     * build ships. Higher values are accepted by `device_config` without
     * complaint - a probe on this device took 2147483647 - but a per-process
     * heap growth limit of 256MB means an unbounded cap only buys OOM kills.
     */
    private val PHANTOM_TIERS = TunerTierAction(
        id = "phantom_processes",
        label = "Phantom process limit",
        detail = "How many unowned child processes may exist before the " +
            "platform starts killing the newest. Raise it to keep terminal " +
            "sessions, emulators and build tools alive in the background.",
        note = "Held in memory only - this build's device_config has no " +
            "persistent store, so the value returns to the system default " +
            "(1024) after a reboot.",
        needsShizuku = true,
        namespace = "activity_manager",
        key = "max_phantom_processes",
        probe = "device_config get activity_manager max_phantom_processes",
        read = "device_config get activity_manager max_phantom_processes",
        tiers = listOf(
            TunerTier("low", "Low - 32 (Android default)", "32"),
            TunerTier("mid", "Mid - 128", "128"),
            TunerTier("high", "High - 512", "512"),
            TunerTier("max", "Max - 1024 (this build's default)", "1024"),
        ),
    )

    val TIER_ACTIONS: List<TunerTierAction> = listOf(PHANTOM_TIERS)

    /** Returns only tier actions this device actually supports. */
    fun availableTierActions(context: Context): List<TunerTierAction> {
        if (!ShizukuHelper.isGranted()) return emptyList()
        return TIER_ACTIONS.filter { ShizukuHelper.shell(context, it.probe).isSuccess }
    }

    /** The tier matching the device's current value, or null if unrecognised. */
    fun currentTier(context: Context, action: TunerTierAction): TunerTier? {
        val raw = ShizukuHelper.shell(context, action.read).getOrNull()?.trim()
        return action.tiers.firstOrNull { it.value == raw }
    }

    fun applyTier(context: Context, action: TunerTierAction, tierId: String) {
        val cmd = action.commandFor(tierId) ?: return
        ShizukuHelper.shell(context, cmd)
    }

    /** Runs the probe for each action and returns only those this device supports. */
    fun availableActions(context: Context): List<TunerAction> {
        if (!ShizukuHelper.isGranted()) return emptyList()
        return ACTIONS.filter { action ->
            ShizukuHelper.shell(context, action.probe).isSuccess
        }
    }

    fun apply(context: Context, action: TunerAction, enable: Boolean) =
        ShizukuHelper.shell(context, if (enable) action.enable else action.disable)

    /**
     * GPU frequency control is exposed under vendor sysfs trees (Adreno `kgsl`,
     * Mali `/sys/kernel/gpu`) that are normally root-only. It is listed here so
     * the UI can state plainly that it is unavailable, rather than implying the
     * app can do it.
     */
    /**
     * The vendor's own performance tier, read-only.
     *
     * `persist.sys.computility.cpulevel` / `.gpulevel` do exist on HyperOS
     * (alongside `computility.version`) and are readable, but they are refused
     * to uid 2000: measured on an MT6769, `setprop` fails for the `persist.*`
     * class as a whole, while a control `debug.*` prop writes fine. Shizuku
     * runs as uid 2000 in `u:r:shell:s0`, so it inherits the same refusal, and
     * only root could change these. The app therefore reports the level it is
     * actually running at instead of offering a switch that cannot move it.
     */
    fun computilityLevels(context: Context): Map<String, Any> {
        fun read(prop: String): String? =
            ShizukuHelper.shell(context, "getprop $prop").getOrNull()?.trim()?.ifEmpty { null }

        val cpu = read("persist.sys.computility.cpulevel")
        val gpu = read("persist.sys.computility.gpulevel")
        val version = read("persist.sys.computility.version")
        return mapOf(
            "cpuLevel" to (cpu ?: ""),
            "gpuLevel" to (gpu ?: ""),
            "version" to (version ?: ""),
            "present" to (cpu != null || gpu != null),
            "writable" to false,
        )
    }

    fun gpuTuningAvailable(context: Context): Boolean {
        if (!ShizukuHelper.isGranted()) return false
        val probe = ShizukuHelper.shell(
            context,
            "for d in /sys/class/kgsl/kgsl-3d0/devfreq /sys/kernel/gpu; do " +
                "[ -w \"\$d/max_freq\" ] && echo ok; done",
        )
        return probe.getOrNull()?.contains("ok") == true
    }
}
