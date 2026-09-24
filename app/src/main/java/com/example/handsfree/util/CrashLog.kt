package com.example.handsfree.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Survivable crash diagnostics for the HandsFree pipeline.
 *
 * The app currently dies silently when "Start Mic & VOSK" is pressed — no Java
 * exception, no red error text in the debug UI, just process death. The leading
 * hypothesis is a native crash inside `libvosk.so` triggered by a 16KB-page
 * alignment incompatibility on newer devices. Such crashes tear the process
 * down at the kernel level *before* any Kotlin try-catch can fire, so the only
 * way to locate the killing instruction is to leave breadcrumbs in a file that
 * outlives the process.
 *
 * [step] is called *before* each pipeline action executes and flushes
 * synchronously (with `fsync`-style force via append + explicit sync) so the
 * breadcrumb is on disk even if the very next native call kills the process.
 * After a crash, [read] returns the full log; the last recorded `STEP_x` line
 * is the one that was about to run when the process died.
 *
 * The log lives at `context.filesDir/crash.log` (app-private, survives uninstall
 * only until cleared). [rotate] runs once per service start. It moves the
 * current file to `crash_prev.log` and opens a new one with a header line, so
 * a restart keeps the evidence of whatever ended the previous instance. It
 * used to be wiped at every start, which erased exactly that evidence.
 */
object CrashLog {

    private const val TAG = "CrashLog"
    private const val FILE_NAME = "crash.log"
    private const val PREV_FILE_NAME = "crash_prev.log"
    private const val START_MARK = "=== SERVICE START"
    private const val STOP_MARK = "=== SERVICE STOP"

    private val tsFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /**
     * Start a new log for a new service instance. The previous log becomes
     * `crash_prev.log`; any older one is dropped, so the two files stay small.
     *
     * The header names the reason when it can be told from the previous log:
     * a previous instance with no [stopped] line after its last start did not
     * reach `onDestroy`, which means the process was killed.
     */
    fun rotate(context: Context, buildStamp: String) {
        runCatching {
            val current = File(context.filesDir, FILE_NAME)
            val previous = File(context.filesDir, PREV_FILE_NAME)
            var reason = "first start"
            if (current.exists() && current.length() > 0L) {
                val text = current.readText()
                val lastStart = text.lastIndexOf(START_MARK)
                val lastStop = text.lastIndexOf(STOP_MARK)
                reason = when {
                    lastStart < 0 -> "previous log had no start header"
                    lastStop > lastStart -> "previous instance stopped normally"
                    else -> "previous instance ended without onDestroy (process killed)"
                }
                previous.delete()
                if (!current.renameTo(previous)) {
                    previous.writeText(text)
                }
            }
            current.writeText("")
            writeLine(
                context,
                "$START_MARK ${dateFormat.format(Date())} build $buildStamp, reason: $reason"
            )
        }.onFailure { Log.w(TAG, "rotate failed", it) }
    }

    fun stopped(context: Context) {
        writeLine(context, "$STOP_MARK ${dateFormat.format(Date())}")
    }

    /**
     * Record a pipeline breadcrumb *before* the action identified by [tag]
     * executes. The write is flushed to disk synchronously so it survives a
     * native crash that kills the process on the very next instruction.
     *
     * Convention: `tag` follows the `STEP_N: description` form documented in
     * [com.example.handsfree.service.OverlayService]. The caller invokes
     * [step] *first*, then executes the action it describes.
     */
    fun step(context: Context, tag: String) {
        writeLine(context, "$tag // ${tsFormat.format(Date())}")
    }

    /**
     * Record a free-form informational line (e.g. "VOSK onReady fired").
     */
    fun info(context: Context, message: String) {
        writeLine(context, "INFO: $message // ${tsFormat.format(Date())}")
    }

    /**
     * Record a thrown exception with full stack trace. Used by the global
     * [Thread.setDefaultUncaughtExceptionHandler] installed in MainActivity
     * and by pipeline catch blocks that want the trace persisted.
     */
    fun exception(context: Context, where: String, t: Throwable) {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        writeLine(context, "EXCEPTION @ $where // ${tsFormat.format(Date())}\n$sw")
    }

    /**
     * Record a marker indicating the pipeline reached a stable state — used so
     * the post-mortem log distinguishes "crashed mid-step" from "completed
     * cleanly then something else killed the process".
     */
    fun ok(context: Context, tag: String) {
        writeLine(context, "$tag OK // ${tsFormat.format(Date())}")
    }

    /**
     * Read the full crash log, or a placeholder string if absent/empty.
     */
    fun read(context: Context): String = readFile(context, FILE_NAME, "crash log")

    fun readPrevious(context: Context): String =
        readFile(context, PREV_FILE_NAME, "previous crash log")

    private fun readFile(context: Context, name: String, label: String): String {
        return runCatching {
            val f = File(context.filesDir, name)
            if (!f.exists()) "(no $label yet)"
            else f.readText().ifEmpty { "($label empty)" }
        }.getOrElse { e ->
            Log.e(TAG, "read failed", e)
            "(failed to read $label: ${e.message})"
        }
    }

    private fun writeLine(context: Context, line: String) {
        runCatching {
            val f = File(context.filesDir, FILE_NAME)
            f.appendText(line + "\n")
            // Force the append to reach disk before returning so a native
            // crash on the next instruction cannot lose the breadcrumb.
            java.io.FileOutputStream(f, true).use { fd ->
                fd.fd.sync()
            }
        }.onFailure { Log.w(TAG, "writeLine failed", it) }
    }
}