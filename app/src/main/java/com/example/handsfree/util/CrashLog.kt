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
 * only until cleared). [reset] wipes it — call this at the start of each fresh
 * pipeline run so the post-mortem log reflects only the most recent attempt.
 */
object CrashLog {

    private const val TAG = "CrashLog"
    private const val FILE_NAME = "crash.log"

    private val tsFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /**
     * Wipe any previous log. Call before the first [step] of a fresh run so the
     * post-mortem log reflects only the latest attempt.
     */
    fun reset(context: Context) {
        runCatching {
            File(context.filesDir, FILE_NAME).writeText("")
        }.onFailure { Log.w(TAG, "reset failed", it) }
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
    fun read(context: Context): String {
        return runCatching {
            val f = File(context.filesDir, FILE_NAME)
            if (!f.exists()) "(no crash log yet)"
            else f.readText().ifEmpty { "(crash log empty)" }
        }.getOrElse { e ->
            Log.e(TAG, "read failed", e)
            "(failed to read crash log: ${e.message})"
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