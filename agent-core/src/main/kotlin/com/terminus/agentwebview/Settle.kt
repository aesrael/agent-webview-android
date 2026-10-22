package com.terminus.agentwebview

data class SettleConfig(
    /** Page must be mutation-free and request-free for this long. */
    val quietMs: Long = 400,
    /** Hard cap; the action still reports success, with reason TIMEOUT. */
    val maxWaitMs: Long = 8_000,
    val pollMs: Long = 100,
    /** Always wait at least this long, so synchronous side effects have started. */
    val minWaitMs: Long = 150,
)

/** One reading of the page's activity, sampled in-page by `sample.js`. */
data class PageSample(
    val readyState: String,
    val pendingRequests: Int,
    val sinceLastMutationMs: Long,
    val url: String,
)

enum class SettleReason { QUIET, TIMEOUT, BLOCKED }

data class SettleOutcome(val reason: SettleReason, val waitedMs: Long) {
    fun toWire(): Map<String, Any?> = mapOf("reason" to reason.name, "waitedMs" to waitedMs)
}

/**
 * Decides when the page has "settled" after an action: loaded, no network in
 * flight, DOM quiet. Pure logic; the caller supplies samples and time.
 */
class SettleDetector(private val cfg: SettleConfig, private val startMs: Long) {
    /**
     * Feed one observation. Returns an outcome when settling is over, null to keep waiting.
     * [navigating] is true between a page start and its finish. A null [sample] means the
     * page could not be evaluated (e.g. mid-navigation).
     */
    fun update(nowMs: Long, sample: PageSample?, navigating: Boolean, blocked: Boolean = false): SettleOutcome? {
        val waited = nowMs - startMs
        if (blocked) return SettleOutcome(SettleReason.BLOCKED, waited)
        if (waited >= cfg.maxWaitMs) return SettleOutcome(SettleReason.TIMEOUT, waited)
        if (waited < cfg.minWaitMs || navigating || sample == null) return null
        val quiet = sample.readyState == "complete" &&
            sample.pendingRequests == 0 &&
            sample.sinceLastMutationMs >= cfg.quietMs
        return if (quiet) SettleOutcome(SettleReason.QUIET, waited) else null
    }
}
