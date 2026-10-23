package com.terminus.agentwebview

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Everything the browser can tell the harness. Each event has a stable [type]
 * and a flat [data] map so it can be shipped as JSON without reflection.
 */
sealed class TabEvent(val type: String) {
    abstract fun data(): Map<String, Any?>

    /** One line a model can read. */
    abstract fun describe(): String

    // ---- navigation / page lifecycle
    data class PageStarted(val url: String) : TabEvent("page_started") {
        override fun data() = mapOf("url" to url)
        override fun describe() = "loading $url"
    }

    data class PageFinished(val url: String, val title: String) : TabEvent("page_finished") {
        override fun data() = mapOf("url" to url, "title" to title)
        override fun describe() = "loaded $url"
    }

    /** URL changed; [spa] is true for in-page (history API) navigation. */
    data class Navigated(val url: String, val spa: Boolean) : TabEvent("navigated") {
        override fun data() = mapOf("url" to url, "spa" to spa)
        override fun describe() = "navigated to $url"
    }

    data class NavigationBlocked(val url: String, val code: ErrorCode, val reason: String) : TabEvent("navigation_blocked") {
        override fun data() = mapOf("url" to url, "code" to code.name, "reason" to reason)
        override fun describe() = "blocked navigation to $url ($reason)"
    }

    data class LoadError(val url: String, val code: Int, val description: String) : TabEvent("load_error") {
        override fun data() = mapOf("url" to url, "errorCode" to code, "description" to description)
        override fun describe() = "failed to load $url: $description"
    }

    data class HttpError(val url: String, val status: Int) : TabEvent("http_error") {
        override fun data() = mapOf("url" to url, "status" to status)
        override fun describe() = "HTTP $status for $url"
    }

    data class SslError(val url: String, val detail: String) : TabEvent("ssl_error") {
        override fun data() = mapOf("url" to url, "detail" to detail)
        override fun describe() = "TLS error for $url ($detail); request cancelled"
    }

    data class HttpAuthRequired(val host: String, val realm: String) : TabEvent("http_auth_required") {
        override fun data() = mapOf("host" to host, "realm" to realm)
        override fun describe() = "$host requested HTTP authentication ($realm); request cancelled"
    }

    // ---- page-initiated UI
    data class DialogOpened(val kind: String, val message: String, val defaultValue: String?) : TabEvent("dialog_opened") {
        override fun data() = mapOf("kind" to kind, "message" to message, "defaultValue" to defaultValue)
        override fun describe() = "$kind dialog open: \"${message.take(120)}\""
    }

    data class DialogClosed(val accepted: Boolean, val auto: Boolean) : TabEvent("dialog_closed") {
        override fun data() = mapOf("accepted" to accepted, "auto" to auto)
        override fun describe() = "dialog ${if (accepted) "accepted" else "dismissed"}${if (auto) " automatically" else ""}"
    }

    data class PopupOpened(val url: String, val handled: String) : TabEvent("popup_opened") {
        override fun data() = mapOf("url" to url, "handled" to handled)
        override fun describe() = "popup to $url ($handled)"
    }

    data class DownloadRequested(val url: String, val mimeType: String, val contentLength: Long) : TabEvent("download_requested") {
        override fun data() = mapOf("url" to url, "mimeType" to mimeType, "contentLength" to contentLength)
        override fun describe() = "download requested: $url ($mimeType); not downloaded"
    }

    data object FileChooserRequested : TabEvent("file_chooser_requested") {
        override fun data() = emptyMap<String, Any?>()
        override fun describe() = "page opened a file chooser; cancelled"
    }

    data class PermissionDenied(val origin: String, val what: String) : TabEvent("permission_denied") {
        override fun data() = mapOf("origin" to origin, "what" to what)
        override fun describe() = "denied $what permission for $origin"
    }

    data class RendererGone(val didCrash: Boolean) : TabEvent("renderer_gone") {
        override fun data() = mapOf("didCrash" to didCrash)
        override fun describe() = if (didCrash) "browser renderer crashed" else "browser renderer was killed"
    }

    data class ConsoleMessage(val level: String, val text: String) : TabEvent("console") {
        override fun data() = mapOf("level" to level, "text" to text)
        override fun describe() = "console.$level: ${text.take(120)}"
    }

    data class DriverNote(val message: String) : TabEvent("driver_note") {
        override fun data() = mapOf("message" to message)
        override fun describe() = message
    }

    // ---- agent activity
    /** [label] is a short human sentence for UIs ("Click “Search”"); it never contains typed text. */
    data class ActionStarted(val actionId: Long, val actionType: String, val label: String = actionType) : TabEvent("action_started") {
        override fun data() = mapOf("actionId" to actionId, "actionType" to actionType, "label" to label)
        override fun describe() = "started $label"
    }

    /** A line the agent wants the person to see ("Comparing prices for 10-17 April"). */
    data class AgentNote(val text: String) : TabEvent("agent_note") {
        override fun data() = mapOf("text" to text)
        override fun describe() = "note: $text"
    }

    data class ActionFinished(val actionId: Long, val status: String, val summary: String) : TabEvent("action_finished") {
        override fun data() = mapOf("actionId" to actionId, "status" to status, "summary" to summary)
        override fun describe() = summary
    }

    data class StateChanged(val from: SessionState, val to: SessionState, val reason: String) : TabEvent("state_changed") {
        override fun data() = mapOf("from" to from.name, "to" to to.name, "reason" to reason)
        override fun describe() = "state $from → $to ($reason)"
    }

    // ---- human handoff
    data class HandoffRequested(
        val id: Long, val reason: String, val kind: String?, val auto: Boolean,
        val resumeWhen: Map<String, Any?>, val timeoutMs: Long,
    ) : TabEvent("handoff_requested") {
        override fun data() = mapOf(
            "id" to id, "reason" to reason, "kind" to kind, "auto" to auto,
            "resumeWhen" to resumeWhen, "timeoutMs" to timeoutMs,
        )
        override fun describe() = "waiting for a human: $reason"
    }

    data class HandoffResumed(
        val id: Long, val by: String, val durationMs: Long, val urlBefore: String, val urlAfter: String,
    ) : TabEvent("handoff_resumed") {
        override fun data() = mapOf(
            "id" to id, "by" to by, "durationMs" to durationMs, "urlBefore" to urlBefore, "urlAfter" to urlAfter,
        )
        override fun describe() = "human handed control back (${durationMs / 1000}s)"
    }

    data class HandoffTimedOut(val id: Long, val durationMs: Long) : TabEvent("handoff_timed_out") {
        override fun data() = mapOf("id" to id, "durationMs" to durationMs)
        override fun describe() = "handoff timed out after ${durationMs / 1000}s"
    }

    // ---- liveness
    data class Heartbeat(
        val state: SessionState, val url: String, val lastEventSeq: Long,
        val lastActionAgoMs: Long?, val uptimeMs: Long,
    ) : TabEvent("heartbeat") {
        override fun data() = mapOf(
            "state" to state.name, "url" to url, "lastEventSeq" to lastEventSeq,
            "lastActionAgoMs" to lastActionAgoMs, "uptimeMs" to uptimeMs,
        )
        override fun describe() = "heartbeat ($state)"
    }

    data class SessionClosed(val reason: String) : TabEvent("session_closed") {
        override fun data() = mapOf("reason" to reason)
        override fun describe() = "session closed ($reason)"
    }
}

data class EventRecord(val seq: Long, val timeMs: Long, val event: TabEvent) {
    fun toWire(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["seq"] = seq
        m["time"] = timeMs
        m["type"] = event.type
        m.putAll(event.data())
        return m
    }
}

fun interface Subscription {
    fun cancel()
}

/**
 * Append-only, numbered event log with a bounded replay buffer.
 *
 * A harness that reconnects asks `since(lastSeq)` and gets everything it
 * missed; if the buffer wrapped, [Replay.missed] says how many events were lost.
 * Heartbeats are broadcast to subscribers but never stored or numbered, so
 * they cannot push real events out of the buffer.
 */
class EventLog(private val capacity: Int = 2000, private val clock: Clock = WallClock) {
    private val lock = Any()
    private val buffer = ArrayDeque<EventRecord>()
    private var seq = 0L
    private val listeners = CopyOnWriteArrayList<(EventRecord) -> Unit>()

    data class Replay(val records: List<EventRecord>, val missed: Long)

    fun append(event: TabEvent): EventRecord {
        val rec: EventRecord
        synchronized(lock) {
            if (event is TabEvent.Heartbeat) {
                rec = EventRecord(seq, clock.nowMs(), event)
            } else {
                seq += 1
                rec = EventRecord(seq, clock.nowMs(), event)
                buffer.addLast(rec)
                while (buffer.size > capacity) buffer.removeFirst()
            }
        }
        for (l in listeners) {
            try { l(rec) } catch (_: Throwable) { /* a bad subscriber must not break the pipeline */ }
        }
        return rec
    }

    fun lastSeq(): Long = synchronized(lock) { seq }

    fun since(afterSeq: Long, limit: Int = Int.MAX_VALUE): Replay = synchronized(lock) {
        val oldest = buffer.firstOrNull()?.seq
        val missed = if (oldest != null && oldest > afterSeq + 1) oldest - (afterSeq + 1) else 0L
        Replay(buffer.filter { it.seq > afterSeq }.take(limit), missed)
    }

    fun subscribe(listener: (EventRecord) -> Unit): Subscription {
        listeners.add(listener)
        return Subscription { listeners.remove(listener) }
    }
}
