package com.example.handsfree.pipeline

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Returns the pipeline to IDLE when one activation runs too long.
 *
 * Two caps, one per phase:
 *   LISTENING                    : [listenTimeoutMs], the mic window plus a
 *                                  short grace. OverlayService's own listen
 *                                  timer normally ends this phase first; this
 *                                  cap only catches the case where it did not.
 *   PREPROCESSING to EXECUTING   : [inferenceTimeoutMs], default
 *                                  [INFERENCE_TIMEOUT_MS]. Counted from the end
 *                                  of listening, so the tester never waits more
 *                                  than 10 s for a transcript.
 *
 * Each timer is tied to the session it was armed for. A timer that fires after
 * its activation already ended does nothing to the next one.
 *
 * The watchdog recovers the state, not the work. A native ONNX call cannot be
 * stopped from Kotlin; its late result is refused by the session check in
 * [AppStateMachine.transitionIfSession].
 */
class PipelineWatchdog(
    private val stateMachine: AppStateMachine,
    private val listenTimeoutMs: () -> Long,
    private val inferenceTimeoutMs: () -> Long = { INFERENCE_TIMEOUT_MS },
    private val scheduler: Scheduler = ExecutorScheduler(),
    private val onExpiry: (AppStateMachine.State) -> Unit
) {
    interface Scheduler {
        fun schedule(delayMs: Long, task: Runnable): Cancellable
        fun shutdown() {}
    }

    fun interface Cancellable {
        fun cancel()
    }

    class ExecutorScheduler(
        private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    ) : Scheduler {
        override fun schedule(delayMs: Long, task: Runnable): Cancellable {
            val future = executor.schedule(task, delayMs, TimeUnit.MILLISECONDS)
            return Cancellable { future.cancel(false) }
        }

        override fun shutdown() {
            executor.shutdownNow()
        }
    }

    private var pending: Cancellable? = null

    private val listener: (AppStateMachine.State, AppStateMachine.State) -> Unit = { oldState, newState ->
        when {
            newState == AppStateMachine.State.IDLE -> cancel()
            oldState == AppStateMachine.State.IDLE -> arm(listenTimeoutMs())
            oldState == AppStateMachine.State.LISTENING -> arm(inferenceTimeoutMs())
        }
    }

    init {
        stateMachine.addStateListener(listener)
    }

    @Synchronized
    fun arm(timeoutMs: Long) {
        pending?.cancel()
        val session = stateMachine.session
        pending = scheduler.schedule(timeoutMs, Runnable {
            val stuckState = stateMachine.resetIfSession(session)
            if (stuckState != null) {
                onExpiry(stuckState)
            }
        })
    }

    @Synchronized
    fun cancel() {
        pending?.cancel()
        pending = null
    }

    fun release() {
        stateMachine.removeStateListener(listener)
        cancel()
        scheduler.shutdown()
    }

    companion object {
        const val INFERENCE_TIMEOUT_MS = 10000L
    }
}
