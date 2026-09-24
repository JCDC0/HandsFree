package com.example.handsfree.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W1b items 1 and 2: the 10 s inference cap and the session-number drop.
 * The watchdog runs on [FakeScheduler], so no test waits on a real clock.
 */
class PipelineWatchdogTest {

    private class FakeScheduler : PipelineWatchdog.Scheduler {
        class Job(val delayMs: Long, val task: Runnable) {
            var cancelled = false
        }

        val jobs = mutableListOf<Job>()

        val live: List<Job> get() = jobs.filter { !it.cancelled }

        override fun schedule(delayMs: Long, task: Runnable): PipelineWatchdog.Cancellable {
            val job = Job(delayMs, task)
            jobs.add(job)
            return PipelineWatchdog.Cancellable { job.cancelled = true }
        }

        fun fire(job: Job) = job.task.run()
    }

    private fun watchdog(
        fsm: AppStateMachine,
        scheduler: FakeScheduler,
        expired: MutableList<AppStateMachine.State>
    ) = PipelineWatchdog(
        stateMachine = fsm,
        listenTimeoutMs = { LISTEN_MS },
        scheduler = scheduler,
        onExpiry = { expired.add(it) }
    )

    @Test
    fun inferenceCapIsTenSeconds() {
        assertEquals(10000L, PipelineWatchdog.INFERENCE_TIMEOUT_MS)
    }

    @Test
    fun listeningArmsListenCapAndPreprocessingArmsInferenceCap() {
        val fsm = AppStateMachine()
        val scheduler = FakeScheduler()
        val dog = watchdog(fsm, scheduler, mutableListOf())

        assertNotNull(fsm.activate(explicit = true))
        assertEquals(1, scheduler.live.size)
        assertEquals(LISTEN_MS, scheduler.live[0].delayMs)

        assertTrue(fsm.transitionTo(AppStateMachine.State.PREPROCESSING))
        assertEquals(1, scheduler.live.size)
        assertEquals(PipelineWatchdog.INFERENCE_TIMEOUT_MS, scheduler.live[0].delayMs)

        assertTrue(fsm.transitionTo(AppStateMachine.State.NORMALIZING))
        assertTrue(fsm.transitionTo(AppStateMachine.State.PROCESSING))
        assertEquals(
            "later inference states keep the one cap counted from the end of listening",
            1,
            scheduler.jobs.count { it.delayMs == PipelineWatchdog.INFERENCE_TIMEOUT_MS }
        )

        dog.release()
    }

    @Test
    fun inferenceCapExpiryReturnsToIdleAndReportsStuckState() {
        val fsm = AppStateMachine()
        val scheduler = FakeScheduler()
        val expired = mutableListOf<AppStateMachine.State>()
        val dog = watchdog(fsm, scheduler, expired)

        fsm.activate(explicit = true)
        fsm.transitionTo(AppStateMachine.State.PREPROCESSING)
        scheduler.fire(scheduler.live.single())

        assertEquals(listOf(AppStateMachine.State.PREPROCESSING), expired)
        assertTrue(fsm.isIdle)
        assertTrue(fsm.tryActivate(explicit = true))

        dog.release()
    }

    @Test
    fun reachingIdleCancelsTheCap() {
        val fsm = AppStateMachine()
        val scheduler = FakeScheduler()
        val expired = mutableListOf<AppStateMachine.State>()
        val dog = watchdog(fsm, scheduler, expired)

        fsm.activate(explicit = true)
        fsm.transitionTo(AppStateMachine.State.PREPROCESSING)
        fsm.transitionTo(AppStateMachine.State.IDLE)

        assertTrue(scheduler.live.isEmpty())
        dog.release()
    }

    @Test
    fun capFromAnOldSessionDoesNotTouchTheNewOne() {
        val fsm = AppStateMachine()
        val scheduler = FakeScheduler()
        val expired = mutableListOf<AppStateMachine.State>()
        val dog = watchdog(fsm, scheduler, expired)

        fsm.activate(explicit = true)
        fsm.transitionTo(AppStateMachine.State.PREPROCESSING)
        val oldCap = scheduler.live.single()
        fsm.transitionTo(AppStateMachine.State.IDLE)

        fsm.activate(explicit = true)
        scheduler.fire(oldCap)

        assertTrue(expired.isEmpty())
        assertEquals(AppStateMachine.State.LISTENING, fsm.currentState)
        dog.release()
    }

    @Test
    fun lateResultFromTimedOutSessionIsDropped() {
        val fsm = AppStateMachine()
        val scheduler = FakeScheduler()
        val expired = mutableListOf<AppStateMachine.State>()
        val dog = watchdog(fsm, scheduler, expired)

        val first = fsm.activate(explicit = true)!!
        assertTrue(fsm.transitionIfSession(first, AppStateMachine.State.PREPROCESSING))
        scheduler.fire(scheduler.live.single())
        assertTrue(fsm.isIdle)
        assertFalse(fsm.isCurrentSession(first))

        val second = fsm.activate(explicit = true)!!
        assertNotEquals(first, second)
        assertTrue(fsm.transitionIfSession(second, AppStateMachine.State.PREPROCESSING))

        assertFalse(fsm.transitionIfSession(first, AppStateMachine.State.NORMALIZING))
        assertFalse(fsm.transitionIfSession(first, AppStateMachine.State.IDLE))
        assertEquals(AppStateMachine.State.PREPROCESSING, fsm.currentState)

        assertTrue(fsm.transitionIfSession(second, AppStateMachine.State.NORMALIZING))
        dog.release()
    }

    @Test
    fun lateResultAfterResetWithNoNewActivationIsDropped() {
        val fsm = AppStateMachine()
        val session = fsm.activate(explicit = true)!!
        fsm.transitionIfSession(session, AppStateMachine.State.PREPROCESSING)

        fsm.reset()

        assertFalse(fsm.isCurrentSession(session))
        assertFalse(fsm.transitionIfSession(session, AppStateMachine.State.NORMALIZING))
        assertTrue(fsm.isIdle)
    }

    @Test
    fun resetIfSessionIgnoresStaleOrIdle() {
        val fsm = AppStateMachine()
        val session = fsm.activate(explicit = true)!!
        assertNull(fsm.resetIfSession(session + 1))
        assertEquals(AppStateMachine.State.LISTENING, fsm.currentState)

        assertEquals(AppStateMachine.State.LISTENING, fsm.resetIfSession(session))
        assertNull(fsm.resetIfSession(fsm.session))
    }

    @Test
    fun rejectedActivationDoesNotChangeSession() {
        val fsm = AppStateMachine()
        val session = fsm.activate(explicit = true)!!
        assertNull(fsm.activate(explicit = true))
        assertTrue(fsm.isCurrentSession(session))
    }

    private companion object {
        const val LISTEN_MS = 6000L
    }
}
