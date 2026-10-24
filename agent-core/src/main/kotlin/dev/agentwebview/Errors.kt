package dev.agentwebview

/** Machine-readable failure reasons. The harness can branch on these. */
enum class ErrorCode {
    /** Ref is unknown: from an older snapshot, or the page navigated. Re-observe. */
    STALE_REF,
    /** More than one element carries the ref (page tampering). Re-observe. */
    AMBIGUOUS_REF,
    /** Malformed or unsupported action. */
    BAD_ACTION,
    TEXT_TOO_LONG,
    UNSUPPORTED_KEY,
    /** Navigation refused by NavigationPolicy. */
    NAV_BLOCKED,
    TIMEOUT,
    /** A JS dialog is open; answer it first. */
    DIALOG_OPEN,
    /** A human has control; wait for resume. */
    HANDOFF_ACTIVE,
    /** The WebView render process died; rebind a fresh WebView. */
    RENDERER_GONE,
    /** Element is hidden, covered, disabled or of the wrong kind. */
    NOT_ACTIONABLE,
    EVAL_DISABLED,
    /** A sensitive action was not confirmed by the person. */
    CONFIRMATION_DENIED,
    /** A secret may not be typed into this origin (or frame). */
    SECRET_ORIGIN_MISMATCH,
    /** The session's action budget or rate limit is used up. */
    LIMIT_EXCEEDED,
    /** The page is on a human-only site; its content is withheld from the agent. */
    WITHHELD,
    SESSION_CLOSED,
    UNKNOWN_SECRET,
    PROTOCOL,
    INTERNAL,
}

data class AgentError(val code: ErrorCode, val message: String) {
    fun toWire(): Map<String, Any?> = mapOf("code" to code.name, "message" to message)
}

class AgentException(val error: AgentError) : RuntimeException(error.message) {
    constructor(code: ErrorCode, message: String) : this(AgentError(code, message))
}
