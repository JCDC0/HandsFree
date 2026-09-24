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
 *
 * Every activation gets a new session number, and so does every [reset]. A
 * native ONNX call cannot be killed, so a transcript can arrive after the
 * watchdog has given up on it. Callers that finish work on another thread
 * pass the session they started with to [transitionIfSession]; a stale number
 * is refused, and the late result is dropped rather than executed.
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

    private var _session: Long = 0L
    val session: Long
        get() = synchronized(this) { _session }

    private val listeners = mutableListOf<(State, State) -> Unit>()

    private val validTransitions: Map<State, Set<State>> = mapOf(
        State.IDLE          to setOf(State.LISTENING),
        State.LISTENING     to setOf(State.PREPROCESSING, State.IDLE),
        State.PREPROCESSING to setOf(State.NORMALIZING, State.IDLE),
        State.NORMALIZING   to setOf(State.PROCESSING, State.IDLE),
        State.PROCESSING    to setOf(State.EXECUTING, State.IDLE),
        State.EXECUTING     to setOf(State.IDLE)
    )

    fun transitionTo(newState: State): Boolean = transitionChecked(null, newState)

    fun transitionIfSession(session: Long, newState: State): Boolean =
        transitionChecked(session, newState)

    fun isCurrentSession(session: Long): Boolean = synchronized(this) { _session == session }

    private fun transitionChecked(session: Long?, newState: State): Boolean {
        val (oldState, targets) = synchronized(this) {
            if (session != null && session != _session) return false
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

    fun tryActivate(explicit: Boolean, awaitingCommand: Boolean = false): Boolean =
        activate(explicit, awaitingCommand) != null

    fun activate(explicit: Boolean, awaitingCommand: Boolean = false): Long? {
        val (session, targets) = synchronized(this) {
            if (_currentState != State.IDLE) {
                return null
            }
            _currentState = State.LISTENING
            _session++
            _isKwsListening = false
            _isExplicitActivation = explicit
            _isAwaitingCommand = awaitingCommand
            Pair(_session, listeners.toList())
        }

        for (listener in targets) {
            listener(State.IDLE, State.LISTENING)
        }

        return session
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
        resetChecked(null)
    }

    /**
     * Reset only if [session] is still the current one and the pipeline is not
     * idle. Returns the state that was abandoned, or null if nothing was reset.
     * Lets a timer armed for one activation fire late without touching the next.
     */
    fun resetIfSession(session: Long): State? = resetChecked(session)

    private fun resetChecked(session: Long?): State? {
        val (oldState, targets) = synchronized(this) {
            if (session != null && (session != _session || _currentState == State.IDLE)) {
                return null
            }
            val old = _currentState
            _currentState = State.IDLE
            _session++
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
        return oldState
    }
}
