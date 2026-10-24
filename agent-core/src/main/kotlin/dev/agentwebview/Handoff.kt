package dev.agentwebview

import kotlinx.coroutines.CompletableDeferred

/** What kind of obstacle needs a person. */
enum class HandoffKind { CAPTCHA, LOGIN, TWO_FACTOR, PAYMENT, HTTP_AUTH, OTHER }

data class HandoffSignal(val kind: HandoffKind, val reason: String)

/**
 * Heuristic detector for pages that need a human. Reads only the snapshot, so it
 * is pure. It never tries to solve anything; it only says "a person is needed".
 */
object HandoffDetector {
    private val CAPTCHA_FRAMES = listOf(
        "recaptcha", "hcaptcha.com", "challenges.cloudflare.com", "arkoselabs", "funcaptcha",
        "geetest", "captcha-delivery", "perimeterx", "px-captcha",
    )
    private val CAPTCHA_TEXT = Regex(
        "captcha|verify (that )?you('| a)re (a )?human|i'm not a robot|i am not a robot|press (&|and) hold|security check",
        RegexOption.IGNORE_CASE,
    )
    private val OTP_TEXT = Regex(
        "verification code|one-time (code|password)|2fa|two-factor|authenticator app|security code sent",
        RegexOption.IGNORE_CASE,
    )
    private val PAYMENT_TEXT = Regex("card number|cvv|cvc|expiry date|security code \\(", RegexOption.IGNORE_CASE)

    fun detect(s: PageSnapshot): List<HandoffSignal> {
        val out = ArrayList<HandoffSignal>()
        val frameHit = s.frames.firstOrNull { f -> CAPTCHA_FRAMES.any { f.src.contains(it, ignoreCase = true) } }
        if (frameHit != null) out += HandoffSignal(HandoffKind.CAPTCHA, "CAPTCHA challenge on the page")
        val nodes = s.allNodes().toList()
        if (out.none { it.kind == HandoffKind.CAPTCHA } &&
            nodes.any { CAPTCHA_TEXT.containsMatchIn(it.name) }
        ) {
            out += HandoffSignal(HandoffKind.CAPTCHA, "page asks to verify you are human")
        }
        if (nodes.any { "password" in it.states }) out += HandoffSignal(HandoffKind.LOGIN, "password field on the page")
        if (nodes.any { "otp" in it.states } || nodes.any { it.isInteractive && OTP_TEXT.containsMatchIn(it.name) }) {
            out += HandoffSignal(HandoffKind.TWO_FACTOR, "one-time code requested")
        }
        if (nodes.any { "cc" in it.states } || nodes.any { it.isInteractive && PAYMENT_TEXT.containsMatchIn(it.name) }) {
            out += HandoffSignal(HandoffKind.PAYMENT, "payment card fields on the page")
        }
        return out
    }
}

/** When a handoff may end by itself. A person can always end it manually. */
sealed class ResumeWhen {
    /** Only the person ends it (taps Done). The safe default. */
    data object Manual : ResumeWhen()
    data class UrlChanges(val from: String) : ResumeWhen()
    data class UrlContains(val fragment: String) : ResumeWhen()
    data class SelectorGone(val css: String) : ResumeWhen()
    data class SelectorPresent(val css: String) : ResumeWhen()

    fun selectors(): List<String> = when (this) {
        is SelectorGone -> listOf(css)
        is SelectorPresent -> listOf(css)
        else -> emptyList()
    }

    fun isMet(p: ProbeResult): Boolean = when (this) {
        Manual -> false
        is UrlChanges -> p.url != from
        is UrlContains -> p.url.contains(fragment)
        is SelectorGone -> css !in p.presentSelectors
        is SelectorPresent -> css in p.presentSelectors
    }

    fun toWire(): Map<String, Any?> = when (this) {
        Manual -> mapOf("type" to "manual")
        is UrlChanges -> mapOf("type" to "url_changes", "from" to from)
        is UrlContains -> mapOf("type" to "url_contains", "fragment" to fragment)
        is SelectorGone -> mapOf("type" to "selector_gone", "selector" to css)
        is SelectorPresent -> mapOf("type" to "selector_present", "selector" to css)
    }

    companion object {
        fun fromWire(m: Map<*, *>?, currentUrl: String): ResumeWhen {
            if (m == null) return Manual
            fun need(k: String) = m.string(k) ?: throw AgentException(ErrorCode.PROTOCOL, "resumeWhen missing '$k'")
            return when (val t = m.string("type") ?: throw AgentException(ErrorCode.PROTOCOL, "resumeWhen missing 'type'")) {
                "manual" -> Manual
                "url_changes" -> UrlChanges(m.string("from") ?: currentUrl)
                "url_contains" -> UrlContains(need("fragment"))
                "selector_gone" -> SelectorGone(need("selector"))
                "selector_present" -> SelectorPresent(need("selector"))
                else -> throw AgentException(ErrorCode.PROTOCOL, "unknown resumeWhen type '$t'")
            }
        }
    }
}

/** What the page-side probe reports during a handoff: only the URL and selector presence. */
data class ProbeResult(val url: String, val presentSelectors: Set<String>)

/** Lets the controller watch the page for auto-resume conditions without reading its content. */
interface HandoffProbe {
    /** Must call [callback] exactly once; null means the probe failed (treated as "not met yet"). */
    fun sample(selectors: List<String>, callback: (ProbeResult?) -> Unit)
}

enum class ResumeSource { USER, AUTO, API }

data class HandoffTicket(
    val id: Long,
    val reason: String,
    val kind: HandoffKind?,
    val auto: Boolean,
    val resumeWhen: ResumeWhen,
    val timeoutMs: Long,
    val startedMs: Long,
    val urlAtStart: String,
) {
    fun toWire(): Map<String, Any?> = mapOf(
        "id" to id, "reason" to reason, "kind" to kind?.name, "auto" to auto,
        "resumeWhen" to resumeWhen.toWire(), "timeoutMs" to timeoutMs, "startedMs" to startedMs,
    )
}

sealed class HandoffOutcome {
    abstract val ticket: HandoffTicket
    abstract fun toWire(): Map<String, Any?>

    data class Resumed(
        override val ticket: HandoffTicket, val by: ResumeSource, val durationMs: Long, val urlAfter: String,
    ) : HandoffOutcome() {
        override fun toWire() = mapOf(
            "outcome" to "resumed", "handoffId" to ticket.id, "by" to by.name.lowercase(),
            "durationMs" to durationMs, "urlBefore" to ticket.urlAtStart, "urlAfter" to urlAfter,
            "urlChanged" to (urlAfter != ticket.urlAtStart),
        )
    }

    data class TimedOut(override val ticket: HandoffTicket, val durationMs: Long) : HandoffOutcome() {
        override fun toWire() = mapOf("outcome" to "timed_out", "handoffId" to ticket.id, "durationMs" to durationMs)
    }

    data class Cancelled(override val ticket: HandoffTicket) : HandoffOutcome() {
        override fun toWire() = mapOf("outcome" to "cancelled", "handoffId" to ticket.id)
    }
}

/**
 * Runs the human handoff protocol: request → WAITING_FOR_HUMAN → resume (by the
 * person, by an auto condition, or by timeout). The agent loop can suspend in
 * [awaitOutcome] and is woken exactly when control returns, so it never has to
 * guess whether the CAPTCHA has been solved.
 */
class HandoffController(
    private val log: EventLog,
    private val clock: Clock,
    private val scheduler: Scheduler,
    private val state: StateMachine,
    private val urlProvider: () -> String,
    private val probe: HandoffProbe? = null,
    private val pollMs: Long = 500,
    private val onFinished: (HandoffOutcome) -> Unit = {},
) {
    private val lock = Any()
    private var nextId = 1L
    private var active: HandoffTicket? = null
    private var waiter: CompletableDeferred<HandoffOutcome>? = null
    private var timeoutTimer: Cancellable? = null
    private var pollTimer: Cancellable? = null
    private var probeInFlight = false
    @Volatile private var last: HandoffOutcome? = null

    val activeTicket: HandoffTicket? get() = synchronized(lock) { active }
    val lastOutcome: HandoffOutcome? get() = last

    /** Starts a handoff, or returns the one already running. */
    fun request(
        reason: String,
        resumeWhen: ResumeWhen = ResumeWhen.Manual,
        timeoutMs: Long = 10 * 60_000L,
        kind: HandoffKind? = null,
        auto: Boolean = false,
    ): HandoffTicket {
        val ticket: HandoffTicket
        synchronized(lock) {
            active?.let { return it }
            if (!state.transition(SessionState.WAITING_FOR_HUMAN, "handoff: $reason")) {
                throw AgentException(ErrorCode.SESSION_CLOSED, "cannot hand off from state ${state.current}")
            }
            ticket = HandoffTicket(nextId++, reason, kind, auto, resumeWhen, timeoutMs, clock.nowMs(), urlProvider())
            active = ticket
            waiter = CompletableDeferred()
            timeoutTimer = scheduler.schedule(timeoutMs) { finish(null, timedOut = true) }
            if (resumeWhen != ResumeWhen.Manual && probe != null) {
                pollTimer = scheduler.scheduleRepeating(pollMs) { poll(ticket) }
            }
        }
        log.append(
            TabEvent.HandoffRequested(
                ticket.id, ticket.reason, kind?.name, auto, ticket.resumeWhen.toWire(), timeoutMs,
            ),
        )
        return ticket
    }

    /** Called when the person taps Done, or by the API. Returns false if nothing was active. */
    fun resume(by: ResumeSource = ResumeSource.USER, urlAfter: String? = null): Boolean =
        finish(by, timedOut = false, urlAfter = urlAfter)

    /** Suspends until the active handoff ends. Returns the last outcome if none is active. */
    suspend fun awaitOutcome(): HandoffOutcome? {
        val w = synchronized(lock) { waiter } ?: return last
        return w.await()
    }

    /** Abandons any active handoff (session closing). */
    fun cancel() {
        val outcome: HandoffOutcome
        val w: CompletableDeferred<HandoffOutcome>?
        synchronized(lock) {
            val t = active ?: return
            outcome = HandoffOutcome.Cancelled(t)
            w = clear()
        }
        last = outcome
        w?.complete(outcome)
    }

    private fun poll(ticket: HandoffTicket) {
        val p = probe ?: return
        synchronized(lock) {
            if (active?.id != ticket.id || probeInFlight) return
            probeInFlight = true
        }
        try {
            p.sample(ticket.resumeWhen.selectors()) { r ->
                val met: Boolean
                synchronized(lock) {
                    probeInFlight = false
                    met = r != null && active?.id == ticket.id && ticket.resumeWhen.isMet(r)
                }
                if (met && r != null) finish(ResumeSource.AUTO, timedOut = false, urlAfter = r.url)
            }
        } catch (t: Throwable) {
            synchronized(lock) { probeInFlight = false }
        }
    }

    private fun finish(by: ResumeSource?, timedOut: Boolean, urlAfter: String? = null): Boolean {
        val ticket: HandoffTicket
        val w: CompletableDeferred<HandoffOutcome>?
        synchronized(lock) {
            ticket = active ?: return false
            w = clear()
        }
        val now = clock.nowMs()
        val duration = now - ticket.startedMs
        val outcome: HandoffOutcome
        if (timedOut) {
            outcome = HandoffOutcome.TimedOut(ticket, duration)
            log.append(TabEvent.HandoffTimedOut(ticket.id, duration))
        } else {
            val after = urlAfter ?: urlProvider()
            outcome = HandoffOutcome.Resumed(ticket, by ?: ResumeSource.API, duration, after)
            log.append(
                TabEvent.HandoffResumed(ticket.id, (by ?: ResumeSource.API).name.lowercase(), duration, ticket.urlAtStart, after),
            )
        }
        last = outcome
        // Control returns to the agent: the page may have changed arbitrarily, so the
        // owner (the session) invalidates refs in onFinished before the agent wakes.
        state.transition(SessionState.IDLE, if (timedOut) "handoff timed out" else "handoff finished", setOf(SessionState.WAITING_FOR_HUMAN))
        try { onFinished(outcome) } catch (_: Throwable) { }
        w?.complete(outcome)
        return true
    }

    // Must be called with `lock` held.
    private fun clear(): CompletableDeferred<HandoffOutcome>? {
        timeoutTimer?.cancel(); timeoutTimer = null
        pollTimer?.cancel(); pollTimer = null
        val w = waiter
        waiter = null
        active = null
        probeInFlight = false
        return w
    }
}
