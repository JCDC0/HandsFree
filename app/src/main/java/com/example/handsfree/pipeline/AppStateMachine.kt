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
     * Transition to a new state. No-op if the transition is invalid.
     * @return true if the transition succeeded
     */
    fun transitionTo(newState: State): Boolean {
        // TODO: Validate transition is allowed from currentState
        // TODO: Update currentState
        // TODO: Notify all listeners
        return false
    }

    /**
     * Register a listener that receives (oldState, newState) on every transition.
     */
    fun addStateListener(listener: (State, State) -> Unit) {
        listeners.add(listener)
    }
}
