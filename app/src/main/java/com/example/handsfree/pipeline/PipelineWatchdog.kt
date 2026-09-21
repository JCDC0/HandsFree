package com.example.handsfree.pipeline

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class PipelineWatchdog(
    private val stateMachine: AppStateMachine,
    private val timeoutSupplier: () -> Long,
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(),
    private val onExpiry: (AppStateMachine.State) -> Unit
) {
    private var scheduledFuture: ScheduledFuture<*>? = null
    private val listener: (AppStateMachine.State, AppStateMachine.State) -> Unit = { oldState, newState ->
        if (oldState == AppStateMachine.State.IDLE && newState != AppStateMachine.State.IDLE) {
            arm()
        } else if (newState == AppStateMachine.State.IDLE) {
            cancel()
        }
    }

    init {
        stateMachine.addStateListener(listener)
    }

    @Synchronized
    fun arm() {
        scheduledFuture?.cancel(false)
        val timeout = timeoutSupplier()
        scheduledFuture = executor.schedule({
            val stuckState = synchronized(this) {
                if (!stateMachine.isIdle) {
                    val state = stateMachine.currentState
                    stateMachine.reset()
                    state
                } else {
                    null
                }
            }
            if (stuckState != null) {
                onExpiry(stuckState)
            }
        }, timeout, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    fun cancel() {
        scheduledFuture?.cancel(false)
        scheduledFuture = null
    }

    fun release() {
        stateMachine.removeStateListener(listener)
        cancel()
        executor.shutdownNow()
    }
}
