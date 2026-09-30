package com.ff.devicebooster

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.lang.reflect.Method

/**
 * Thin wrapper around the Shizuku API.
 *
 * Shizuku runs our commands as the `shell` user (uid 2000), which is what makes
 * privileged `cmd` / `settings` / `device_config` calls possible without root.
 * Every call returns a [Result] carrying the failure reason, so callers can
 * surface it instead of silently no-opping.
 */
object ShizukuHelper {

    private const val TAG = "DeviceBooster"
    const val REQUEST_CODE = 9001

    /** Invoked after the user answers the Shizuku permission dialog. */
    @Volatile
    var onPermissionChanged: (() -> Unit)? = null

    /**
     * `Shizuku.newProcess` is private in the 13.x API and is the only way to
     * reach a shell process. The supported alternative - binding a user
     * service - is asynchronous and needs a listener on every call, which does
     * not fit the synchronous MethodChannel handlers. Reflection keeps the
     * bridge simple; see the keep rule in proguard-rules.pro, without which a
     * release build would rename the method out from under us.
     */
    private val newProcess: Method? by lazy {
        runCatching {
            Shizuku::class.java
                .getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java,
                )
                .apply { isAccessible = true }
        }.onFailure { Log.w(TAG, "newProcess not found in Shizuku API", it) }
            .getOrNull()
    }

    fun isRunning(): Boolean = Shizuku.pingBinder()

    fun isGranted(): Boolean =
        isRunning() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    fun statusText(): String = when {
        !isRunning() -> "Shizuku is not running - start it, then re-check"
        isGranted() -> "Shizuku is running and authorised"
        else -> "Shizuku is running - permission not granted yet"
    }

    fun attach() {
        Shizuku.addRequestPermissionResultListener(
            object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    if (requestCode == REQUEST_CODE) onPermissionChanged?.invoke()
                }
            },
        )
    }

    fun requestPermission() {
        if (isRunning() && !isGranted()) Shizuku.requestPermission(REQUEST_CODE)
    }

    /** Runs [script] through `sh -c` at shell privilege and returns its stdout. */
    fun shell(context: Context, script: String): Result<String> {
        if (!isGranted()) {
            return Result.failure(IllegalStateException("Shizuku not authorised"))
        }
        val method = newProcess
            ?: return Result.failure(IllegalStateException("Shizuku API too old or incompatible"))

        val process = try {
            method.invoke(null, arrayOf("sh", "-c", script), null, null) as Process
        } catch (e: Exception) {
            return Result.failure(e.cause ?: e)
        }

        return try {
            val output = StringBuilder()
            process.inputStream.bufferedReader().use { reader: BufferedReader ->
                reader.forEachLine { output.append(it).append('\n') }
            }
            val stdout = output.toString().trim()
            if (process.waitFor() == 0) {
                Result.success(stdout)
            } else {
                Result.failure(
                    IllegalStateException(
                        "exit ${process.exitValue()}${if (stdout.isEmpty()) "" else ": $stdout"}",
                    ),
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            process.destroy()
        }
    }
}
