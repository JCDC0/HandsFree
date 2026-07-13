package com.example.handsfree.pipeline

/**
 * Finite State Machine governing the HandsFree application lifecycle.
 *
 * Six states (as defined in the paper's Conceptual Framework):
 *   IDLE          — Waiting for wake word or trigger
 *   LISTENING     — Actively capturing microphone input
 *   PREPROCESSING — Running VAD and noise reduction on captured audio
 *   TRANSLATING   — Normalizing transcribed text via TranslationLayer
 *   PROCESSING    — Intent classification + DFS node traversal
 *   EXECUTING     — Dispatching simulated gesture to OS
 *
 * Transitions are rigidly enforced to prevent concurrent command processing.
 * State change events are emitted so the overlay UI can show feedback.
 */
class AppStateMachine {

    enum class State {
        IDLE, LISTENING, PREPROCESSING, TRANSLATING, PROCESSING, EXECUTING
    }

    var currentState: State = State.IDLE
        private set

    private val listeners = mutableListOf<(State, State) -> Unit>()

    /**
     * Valid state transitions. Any transition not listed here is rejected.
     *
     * Pipeline flow:
     *   IDLE → LISTENING → PREPROCESSING → TRANSLATING → PROCESSING → EXECUTING → IDLE
     *
     * Error / reset paths allow returning to IDLE from any state.
     */
    private val validTransitions: Map<State, Set<State>> = mapOf(
        State.IDLE          to setOf(State.LISTENING),
        State.LISTENING     to setOf(State.PREPROCESSING, State.IDLE),
        State.PREPROCESSING to setOf(State.TRANSLATING, State.IDLE),
        State.TRANSLATING   to setOf(State.PROCESSING, State.IDLE),
        State.PROCESSING    to setOf(State.EXECUTING, State.IDLE),
        State.EXECUTING     to setOf(State.IDLE)
    )

    /**
     * Transition to a new state. No-op if the transition is invalid.
     *
     * @return true if the transition succeeded; false if it was rejected
     *         (either same state or not in the allowed set).
     */
    fun transitionTo(newState: State): Boolean {
        if (newState == currentState) return false

        val allowed = validTransitions[currentState] ?: return false
        if (newState !in allowed) return false

        val oldState = currentState
        currentState = newState

        // Notify all registered listeners on the calling thread.
        // Callers responsible for dispatching to UI thread if needed.
        for (listener in listeners) {
            listener(oldState, newState)
        }

        return true
    }

    /**
     * Register a listener that receives (oldState, newState) on every transition.
     */
    fun addStateListener(listener: (State, State) -> Unit) {
        listeners.add(listener)
    }

    /**
     * Remove a previously registered listener.
     */
    fun removeStateListener(listener: (State, State) -> Unit) {
        listeners.remove(listener)
    }

    /**
     * Force-reset to IDLE regardless of current state (for error recovery).
     * Notifies listeners with (currentState, IDLE).
     */
    fun reset() {
        val oldState = currentState
        currentState = State.IDLE
        if (oldState != State.IDLE) {
            for (listener in listeners) {
                listener(oldState, State.IDLE)
            }
        }
    }
}
