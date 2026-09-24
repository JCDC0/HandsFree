package com.example.handsfree.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class AppStateMachineTest {

    @Test
    fun testInitialStateIsIdle() {
        val fsm = AppStateMachine()
        assertEquals(AppStateMachine.State.IDLE, fsm.currentState)
        assertTrue(fsm.isIdle)
        assertFalse(fsm.isListening)
        assertFalse(fsm.isPipelineRunning)
        assertFalse(fsm.isKwsListening)
    }

    @Test
    fun testFullPipelineTransitionSequence() {
        val fsm = AppStateMachine()
        assertTrue(fsm.transitionTo(AppStateMachine.State.LISTENING))
        assertEquals(AppStateMachine.State.LISTENING, fsm.currentState)
        assertTrue(fsm.isListening)
        assertTrue(fsm.isPipelineRunning)

        assertTrue(fsm.transitionTo(AppStateMachine.State.PREPROCESSING))
        assertEquals(AppStateMachine.State.PREPROCESSING, fsm.currentState)
        assertFalse(fsm.isListening)
        assertTrue(fsm.isPipelineRunning)

        assertTrue(fsm.transitionTo(AppStateMachine.State.NORMALIZING))
        assertEquals(AppStateMachine.State.NORMALIZING, fsm.currentState)

        assertTrue(fsm.transitionTo(AppStateMachine.State.PROCESSING))
        assertEquals(AppStateMachine.State.PROCESSING, fsm.currentState)

        assertTrue(fsm.transitionTo(AppStateMachine.State.EXECUTING))
        assertEquals(AppStateMachine.State.EXECUTING, fsm.currentState)

        assertTrue(fsm.transitionTo(AppStateMachine.State.IDLE))
        assertEquals(AppStateMachine.State.IDLE, fsm.currentState)
        assertTrue(fsm.isIdle)
    }

    @Test
    fun testInvalidTransitionsAreRejected() {
        val fsm = AppStateMachine()
        assertFalse(fsm.transitionTo(AppStateMachine.State.PREPROCESSING))
        assertFalse(fsm.transitionTo(AppStateMachine.State.NORMALIZING))
        assertFalse(fsm.transitionTo(AppStateMachine.State.PROCESSING))
        assertFalse(fsm.transitionTo(AppStateMachine.State.EXECUTING))
        assertEquals(AppStateMachine.State.IDLE, fsm.currentState)

        assertTrue(fsm.transitionTo(AppStateMachine.State.LISTENING))
        assertFalse(fsm.transitionTo(AppStateMachine.State.NORMALIZING))
        assertFalse(fsm.transitionTo(AppStateMachine.State.PROCESSING))
        assertFalse(fsm.transitionTo(AppStateMachine.State.EXECUTING))
        assertEquals(AppStateMachine.State.LISTENING, fsm.currentState)
    }

    @Test
    fun testTryActivateOnlySucceedsWhenIdle() {
        val fsm = AppStateMachine()
        assertTrue(fsm.tryActivate(explicit = true))
        assertEquals(AppStateMachine.State.LISTENING, fsm.currentState)
        assertTrue(fsm.consumeExplicitActivation())
        assertFalse(fsm.consumeExplicitActivation())

        assertFalse(fsm.tryActivate(explicit = true))
        assertEquals(AppStateMachine.State.LISTENING, fsm.currentState)

        assertTrue(fsm.transitionTo(AppStateMachine.State.PREPROCESSING))
        assertFalse(fsm.tryActivate(explicit = true))
        assertEquals(AppStateMachine.State.PREPROCESSING, fsm.currentState)
    }

    @Test
    fun testKwsStartAndStop() {
        val fsm = AppStateMachine()
        assertTrue(fsm.startKws())
        assertTrue(fsm.isKwsListening)
        assertTrue(fsm.isIdle)

        assertFalse(fsm.startKws())

        fsm.stopKws()
        assertFalse(fsm.isKwsListening)

        assertTrue(fsm.startKws())
        assertTrue(fsm.tryActivate(explicit = true))
        assertFalse(fsm.isKwsListening)
        assertEquals(AppStateMachine.State.LISTENING, fsm.currentState)
    }

    @Test
    fun testResetRestoresIdleFromAnyState() {
        val fsm = AppStateMachine()
        fsm.tryActivate(explicit = true, awaitingCommand = true)
        fsm.transitionTo(AppStateMachine.State.PREPROCESSING)
        assertEquals(AppStateMachine.State.PREPROCESSING, fsm.currentState)

        fsm.reset()
        assertEquals(AppStateMachine.State.IDLE, fsm.currentState)
        assertTrue(fsm.isIdle)
        assertFalse(fsm.isExplicitActivation)
        assertFalse(fsm.isAwaitingCommand)
        assertFalse(fsm.isKwsListening)
    }

    @Test
    fun testListenerInvokedOutsideLock() {
        val fsm = AppStateMachine()
        var lockHeldInListener = true
        fsm.addStateListener { _, _ ->
            lockHeldInListener = Thread.holdsLock(fsm)
        }
        assertTrue(fsm.tryActivate(explicit = true))
        assertFalse(lockHeldInListener)

        assertTrue(fsm.transitionTo(AppStateMachine.State.PREPROCESSING))
        assertFalse(lockHeldInListener)

        fsm.reset()
        assertFalse(lockHeldInListener)
    }

    @Test
    fun testWatchdogRecoversStuckStateOutsideIdle() {
        val fsm = AppStateMachine()
        val recoveredLatch = CountDownLatch(1)
        val stuckStateRef = AtomicReference<AppStateMachine.State>()

        val watchdog = PipelineWatchdog(
            stateMachine = fsm,
            listenTimeoutMs = { 50L },
            inferenceTimeoutMs = { 50L },
            onExpiry = { state ->
                stuckStateRef.set(state)
                recoveredLatch.countDown()
            }
        )

        assertTrue(fsm.tryActivate(explicit = true))
        assertTrue(fsm.transitionTo(AppStateMachine.State.PREPROCESSING))
        assertEquals(AppStateMachine.State.PREPROCESSING, fsm.currentState)

        assertTrue(recoveredLatch.await(500, TimeUnit.MILLISECONDS))
        assertEquals(AppStateMachine.State.PREPROCESSING, stuckStateRef.get())
        assertEquals(AppStateMachine.State.IDLE, fsm.currentState)
        assertTrue(fsm.isIdle)

        assertTrue(fsm.tryActivate(explicit = true))
        assertEquals(AppStateMachine.State.LISTENING, fsm.currentState)

        watchdog.release()
    }

    @Test
    fun testConcurrentActivationsAtomicSingleWinner() {
        val fsm = AppStateMachine()
        val threadCount = 10
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val successCount = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(threadCount)

        for (i in 0 until threadCount) {
            executor.submit {
                try {
                    startLatch.await()
                    if (fsm.tryActivate(explicit = true)) {
                        successCount.incrementAndGet()
                    }
                } finally {
                    doneLatch.doneLatchCountDown()
                }
            }
        }

        startLatch.countDown()
        doneLatch.await()
        executor.shutdown()

        assertEquals(1, successCount.get())
        assertEquals(AppStateMachine.State.LISTENING, fsm.currentState)
    }

    private fun CountDownLatch.doneLatchCountDown() {
        this.countDown()
    }
}
