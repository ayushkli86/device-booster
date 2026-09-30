package com.ff.devicebooster

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.lang.reflect.Method
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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

    /** How long to wait for the stream pumps after the child exits. */
    private const val STREAM_JOIN_MS = 2_000L

    /**
     * Serialised off-main-thread worker for shell callers. The shells block on
     * `waitFor`, which on the main thread once froze the overlay's panel for as
     * long as a hung `cmd` took to time out.
     */
    val shellExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "shizuku-shell").apply { isDaemon = true }
    }

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

    private var attachedListener: Shizuku.OnRequestPermissionResultListener? = null

    /**
     * Idempotent: the activity can be recreated any number of times, and each
     * re-attach used to stack another listener, so one permission answer
     * notified the UI once per registration.
     */
    fun attach() {
        if (attachedListener != null) return
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode == REQUEST_CODE) onPermissionChanged?.invoke()
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        attachedListener = listener
    }

    fun requestPermission() {
        if (isRunning() && !isGranted()) Shizuku.requestPermission(REQUEST_CODE)
    }

    /**
     * Runs [script] through `sh -c` at shell privilege and returns its stdout.
     *
     * Always call from a background thread - see [shellExecutor]. Stdout and
     * stderr are drained in parallel before `waitFor`: reading them back to
     * back used to let a chatty command fill the 64 KiB stderr pipe and block
     * the child forever.
     */
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
            // Both pipes must be drained concurrently or one can fill while we
            // are still reading the other; that deadlocked waitFor on probe
            // commands that log to stderr. Plain threads, not a shared pool: a
            // shared worker that is still draining stdout cannot also drain
            // stderr, which re-creates exactly this deadlock.
            var stdout = ""
            var stderr = ""
            val stdoutPump = Thread {
                val output = StringBuilder()
                runCatching {
                    process.inputStream.bufferedReader().use { reader: BufferedReader ->
                        reader.forEachLine { output.append(it).append('\n') }
                    }
                }
                stdout = output.toString().trim()
            }.apply { start() }
            val stderrPump = Thread {
                stderr = runCatching {
                    process.errorStream.bufferedReader().use { it.readText() }
                }.getOrDefault("")
            }.apply { start() }

            val exit = process.waitFor()
            stdoutPump.join(STREAM_JOIN_MS)
            stderrPump.join(STREAM_JOIN_MS)
            if (exit == 0) {
                Result.success(stdout)
            } else {
                Result.failure(
                    IllegalStateException(
                        "exit $exit" +
                            (if (stdout.isEmpty()) "" else ": $stdout") +
                            (if (stderr.isEmpty()) "" else ". $stderr"),
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
