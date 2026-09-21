package com.example.handsfree.pipeline

/**
 * Finite State Machine governing the HandsFree application lifecycle.
 *
 * Six states (as defined in the paper's Conceptual Framework):
 *   IDLE          : Waiting for wake word or trigger
 *   LISTENING     : Actively capturing microphone input
 *   PREPROCESSING : Running VAD and noise reduction on captured audio
 *   NORMALIZING   : Normalizing transcribed text via TranslationLayer
 *   PROCESSING    : Intent classification + DFS node traversal
 *   EXECUTING     : Dispatching simulated gesture to OS
 *
 * Transitions are rigidly enforced to prevent concurrent command processing.
 * All state mutations and queries are serialized on an internal monitor.
 */
class AppStateMachine {

    enum class State {
        IDLE, LISTENING, PREPROCESSING, NORMALIZING, PROCESSING, EXECUTING
    }

    private var _currentState: State = State.IDLE
    val currentState: State
        get() = synchronized(this) { _currentState }

    val isIdle: Boolean
        get() = synchronized(this) { _currentState == State.IDLE }

    val isListening: Boolean
        get() = synchronized(this) { _currentState == State.LISTENING }

    val isPipelineRunning: Boolean
        get() = synchronized(this) { _currentState != State.IDLE }

    private var _isKwsListening: Boolean = false
    val isKwsListening: Boolean
        get() = synchronized(this) { _isKwsListening }

    private var _isExplicitActivation: Boolean = false
    val isExplicitActivation: Boolean
        get() = synchronized(this) { _isExplicitActivation }

    private var _isAwaitingCommand: Boolean = false
    val isAwaitingCommand: Boolean
        get() = synchronized(this) { _isAwaitingCommand }

    private val listeners = mutableListOf<(State, State) -> Unit>()

    private val validTransitions: Map<State, Set<State>> = mapOf(
        State.IDLE          to setOf(State.LISTENING),
        State.LISTENING     to setOf(State.PREPROCESSING, State.IDLE),
        State.PREPROCESSING to setOf(State.NORMALIZING, State.IDLE),
        State.NORMALIZING   to setOf(State.PROCESSING, State.IDLE),
        State.PROCESSING    to setOf(State.EXECUTING, State.IDLE),
        State.EXECUTING     to setOf(State.IDLE)
    )

    fun transitionTo(newState: State): Boolean {
        val (oldState, targets) = synchronized(this) {
            if (newState == _currentState) return false

            val allowed = validTransitions[_currentState] ?: return false
            if (newState !in allowed) return false

            val old = _currentState
            _currentState = newState
            Pair(old, listeners.toList())
        }

        for (listener in targets) {
            listener(oldState, newState)
        }

        return true
    }

    fun tryActivate(explicit: Boolean, awaitingCommand: Boolean = false): Boolean {
        val (oldState, targets) = synchronized(this) {
            if (_currentState != State.IDLE) {
                return false
            }
            val old = _currentState
            _currentState = State.LISTENING
            _isKwsListening = false
            _isExplicitActivation = explicit
            _isAwaitingCommand = awaitingCommand
            Pair(old, listeners.toList())
        }

        for (listener in targets) {
            listener(oldState, State.LISTENING)
        }

        return true
    }

    fun startKws(): Boolean = synchronized(this) {
        if (_currentState != State.IDLE || _isKwsListening) return false
        _isKwsListening = true
        return true
    }

    fun stopKws() = synchronized(this) {
        _isKwsListening = false
    }

    fun setAwaitingCommand(value: Boolean = true) = synchronized(this) {
        _isAwaitingCommand = value
    }

    fun consumeAwaitingCommand(): Boolean = synchronized(this) {
        val was = _isAwaitingCommand
        _isAwaitingCommand = false
        was
    }

    fun consumeExplicitActivation(): Boolean = synchronized(this) {
        val was = _isExplicitActivation
        _isExplicitActivation = false
        was
    }

    fun addStateListener(listener: (State, State) -> Unit) = synchronized(this) {
        listeners.add(listener)
    }

    fun removeStateListener(listener: (State, State) -> Unit) = synchronized(this) {
        listeners.remove(listener)
    }

    fun reset() {
        val (oldState, targets) = synchronized(this) {
            val old = _currentState
            _currentState = State.IDLE
            _isKwsListening = false
            _isExplicitActivation = false
            _isAwaitingCommand = false
            Pair(old, listeners.toList())
        }

        if (oldState != State.IDLE) {
            for (listener in targets) {
                listener(oldState, State.IDLE)
            }
        }
    }
}
