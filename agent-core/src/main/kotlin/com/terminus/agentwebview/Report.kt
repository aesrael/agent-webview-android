package com.terminus.agentwebview

enum class ActionStatus { OK, REFUSED, FAILED }

/**
 * What happened when the agent acted. Every action returns one of these, so the
 * harness learns not just "ok" but what changed and what the browser said.
 */
data class ActionReport(
    val id: Long,
    val action: Map<String, Any?>,
    val status: ActionStatus,
    val error: AgentError?,
    val durationMs: Long,
    val urlBefore: String,
    val urlAfter: String,
    val navigated: Boolean,
    val settle: SettleOutcome?,
    /** Browser events that occurred during the action (heartbeats excluded). */
    val events: List<EventRecord>,
    /** True if all refs died; observe again before the next ref-based action. */
    val refsInvalidated: Boolean,
    val state: SessionState,
    /** Result of an Evaluate action, as JSON text. */
    val output: String? = null,
) {
    val ok: Boolean get() = status == ActionStatus.OK

    /** One line for the model's context. */
    fun summary(): String {
        val sb = StringBuilder()
        sb.append(action["type"]).append(" → ").append(status.name.lowercase())
        if (error != null) sb.append(" (").append(error.code).append(": ").append(error.message).append(')')
        if (navigated && urlAfter != urlBefore) sb.append("; now at ").append(urlAfter)
        val notable = events.map { it.event }.filter {
            it is TabEvent.DialogOpened || it is TabEvent.PopupOpened || it is TabEvent.DownloadRequested ||
                it is TabEvent.NavigationBlocked || it is TabEvent.LoadError || it is TabEvent.HttpError ||
                it is TabEvent.RendererGone || it is TabEvent.HandoffRequested || it is TabEvent.FileChooserRequested ||
                it is TabEvent.PermissionDenied || it is TabEvent.SslError || it is TabEvent.HttpAuthRequired
        }
        for (e in notable.take(4)) sb.append("; ").append(e.describe())
        if (refsInvalidated) sb.append("; refs reset")
        if (state != SessionState.IDLE) sb.append("; state=").append(state.name)
        return sb.toString()
    }

    fun toWire(): Map<String, Any?> = mapOf(
        "id" to id,
        "action" to action,
        "status" to status.name.lowercase(),
        "error" to error?.toWire(),
        "durationMs" to durationMs,
        "urlBefore" to urlBefore,
        "urlAfter" to urlAfter,
        "navigated" to navigated,
        "settle" to settle?.toWire(),
        "refsInvalidated" to refsInvalidated,
        "state" to state.name,
        "summary" to summary(),
        "output" to output,
        "events" to events.map { it.toWire() },
    )
}

/** A snapshot plus everything the agent needs to decide what to do next. */
data class Observation(
    val snapshot: PageSnapshot,
    /** Model-ready text rendering (secrets redacted, length-capped). */
    val text: String,
    val state: SessionState,
    val signals: List<HandoffSignal>,
    val handoff: HandoffTicket?,
    val pendingDialog: DialogInfo?,
    val lastEventSeq: Long,
    val refGeneration: Int,
    /** Heuristic alerts, e.g. text that looks like instructions aimed at an AI. */
    val warnings: List<String> = emptyList(),
) {
    fun toWire(includeNodes: Boolean = false): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["url"] = snapshot.url
        m["title"] = snapshot.title
        m["text"] = text
        m["state"] = state.name
        m["truncated"] = snapshot.truncated
        m["warnings"] = warnings
        m["signals"] = signals.map { mapOf("kind" to it.kind.name, "reason" to it.reason) }
        m["handoff"] = handoff?.toWire()
        m["pendingDialog"] = pendingDialog?.toWire()
        m["lastEventSeq"] = lastEventSeq
        m["refGeneration"] = refGeneration
        if (includeNodes) m["nodes"] = snapshot.nodes.map(::nodeWire)
        return m
    }

    private fun nodeWire(n: SnapNode): Map<String, Any?> = linkedMapOf(
        "role" to n.role, "name" to n.name, "ref" to n.ref.ifEmpty { null },
        "value" to n.value, "href" to n.href, "states" to n.states.sorted(), "level" to n.level,
        "options" to n.options, "children" to n.children.map(::nodeWire),
    )
}

data class SessionStatus(
    val state: SessionState,
    val url: String,
    val navigating: Boolean,
    val lastEventSeq: Long,
    val uptimeMs: Long,
    val lastActionAgoMs: Long?,
    val handoff: HandoffTicket?,
    val pendingDialog: DialogInfo?,
    val rendererGone: Boolean,
) {
    fun toWire(): Map<String, Any?> = mapOf(
        "state" to state.name, "url" to url, "navigating" to navigating,
        "lastEventSeq" to lastEventSeq, "uptimeMs" to uptimeMs, "lastActionAgoMs" to lastActionAgoMs,
        "handoff" to handoff?.toWire(), "pendingDialog" to pendingDialog?.toWire(), "rendererGone" to rendererGone,
    )
}
