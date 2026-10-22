package com.terminus.agentwebview

/**
 * What the tab is doing right now. The harness reads this (via status,
 * heartbeats and StateChanged events) to know whether the browser is working,
 * waiting for a person, or stuck.
 */
enum class SessionState {
    /** Ready for commands. */
    IDLE,
    /** Executing an action. */
    ACTING,
    /** Action done; waiting for the page to go quiet. */
    SETTLING,
    /** A human has control (CAPTCHA, login, ...). Agent commands are refused. */
    WAITING_FOR_HUMAN,
    /** A JS dialog is open or the renderer died. */
    BLOCKED,
    CLOSED,
}

enum class CommandKind { OPEN, OBSERVE, ACT, SCREENSHOT, ANSWER_DIALOG, REQUEST_HANDOFF, RESUME, AWAIT_RESUME, STATUS, EVENTS, CLOSE }

/** Validated state transitions; every change is logged as a StateChanged event. */
class StateMachine(private val log: EventLog) {
    private val lock = Any()
    @Volatile private var state = SessionState.IDLE

    val current: SessionState get() = state

    /**
     * Moves to [to] if the transition is legal and, when [onlyFrom] is given,
     * the current state is one of those. Returns whether the state is now [to].
     */
    fun transition(to: SessionState, reason: String, onlyFrom: Set<SessionState>? = null): Boolean {
        val from: SessionState
        synchronized(lock) {
            from = state
            if (from == to) return true
            if (onlyFrom != null && from !in onlyFrom) return false
            if (to !in LEGAL.getValue(from)) return false
            state = to
        }
        log.append(TabEvent.StateChanged(from, to, reason))
        return true
    }

    companion object {
        private val LEGAL: Map<SessionState, Set<SessionState>> = mapOf(
            SessionState.IDLE to setOf(
                SessionState.ACTING, SessionState.WAITING_FOR_HUMAN, SessionState.BLOCKED, SessionState.CLOSED,
            ),
            SessionState.ACTING to setOf(
                SessionState.SETTLING, SessionState.IDLE, SessionState.BLOCKED,
                SessionState.WAITING_FOR_HUMAN, SessionState.CLOSED,
            ),
            SessionState.SETTLING to setOf(
                SessionState.IDLE, SessionState.BLOCKED, SessionState.WAITING_FOR_HUMAN, SessionState.CLOSED,
            ),
            SessionState.WAITING_FOR_HUMAN to setOf(SessionState.IDLE, SessionState.BLOCKED, SessionState.CLOSED),
            SessionState.BLOCKED to setOf(SessionState.IDLE, SessionState.WAITING_FOR_HUMAN, SessionState.CLOSED),
            SessionState.CLOSED to emptySet(),
        )
    }
}

/** Which commands may run in which state. */
object CommandGate {
    fun check(state: SessionState, kind: CommandKind, dialogOpen: Boolean, rendererGone: Boolean): AgentError? {
        val alwaysOk = kind == CommandKind.STATUS || kind == CommandKind.EVENTS || kind == CommandKind.CLOSE
        if (alwaysOk) return null
        return when (state) {
            SessionState.CLOSED -> AgentError(ErrorCode.SESSION_CLOSED, "session is closed")
            SessionState.WAITING_FOR_HUMAN -> when (kind) {
                // Observing or screenshotting while a person types would expose what they enter.
                CommandKind.REQUEST_HANDOFF, CommandKind.RESUME, CommandKind.AWAIT_RESUME -> null
                else -> AgentError(ErrorCode.HANDOFF_ACTIVE, "a human has control; wait for resume")
            }
            SessionState.BLOCKED -> when {
                kind == CommandKind.ANSWER_DIALOG || kind == CommandKind.REQUEST_HANDOFF ||
                    kind == CommandKind.RESUME || kind == CommandKind.AWAIT_RESUME -> null
                rendererGone -> AgentError(ErrorCode.RENDERER_GONE, "browser renderer is gone; rebind a WebView")
                dialogOpen -> AgentError(ErrorCode.DIALOG_OPEN, "a dialog is open; answer it first")
                else -> AgentError(ErrorCode.DIALOG_OPEN, "browser is blocked")
            }
            else -> null
        }
    }
}
