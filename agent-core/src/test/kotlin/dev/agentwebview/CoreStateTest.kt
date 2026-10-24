package dev.agentwebview

import org.junit.Assert.*
import org.junit.Test

class CoreStateTest {
    @Test fun `event log numbers events and replays`() {
        val log = EventLog(capacity = 3, clock = FakeClock())
        repeat(5) { log.append(TabEvent.DriverNote("n$it")) }
        assertEquals(5, log.lastSeq())
        val r = log.since(0)
        assertEquals(listOf(3L, 4L, 5L), r.records.map { it.seq })
        assertEquals(2, r.missed)
        assertEquals(0, log.since(3).missed)
        assertEquals(listOf(5L), log.since(4).records.map { it.seq })
        assertEquals(1, log.since(0, limit = 1).records.size)
    }

    @Test fun `heartbeats are broadcast but not stored or numbered`() {
        val log = EventLog(clock = FakeClock())
        val seen = ArrayList<EventRecord>()
        log.subscribe { seen += it }
        log.append(TabEvent.DriverNote("a"))
        log.append(TabEvent.Heartbeat(SessionState.IDLE, "u", 1, null, 0))
        assertEquals(2, seen.size)
        assertEquals(1, log.lastSeq())
        assertEquals(1, log.since(0).records.size)
    }

    @Test fun `a throwing subscriber does not break others`() {
        val log = EventLog(clock = FakeClock())
        var got = 0
        log.subscribe { throw RuntimeException("boom") }
        log.subscribe { got++ }
        log.append(TabEvent.DriverNote("x"))
        assertEquals(1, got)
    }

    @Test fun `unsubscribe stops delivery and wire form is flat`() {
        val log = EventLog(clock = FakeClock(5))
        var got = 0
        val s = log.subscribe { got++ }
        val rec = log.append(TabEvent.PageFinished("https://a", "T"))
        s.cancel(); log.append(TabEvent.DriverNote("y"))
        assertEquals(1, got)
        assertEquals(mapOf("seq" to 1L, "time" to 5L, "type" to "page_finished", "url" to "https://a", "title" to "T"), rec.toWire())
    }

    @Test fun `state machine only allows legal transitions and logs them`() {
        val log = EventLog(clock = FakeClock())
        val sm = StateMachine(log)
        assertFalse(sm.transition(SessionState.SETTLING, "x")) // IDLE → SETTLING illegal
        assertTrue(sm.transition(SessionState.ACTING, "a"))
        assertTrue(sm.transition(SessionState.SETTLING, "s"))
        assertFalse(sm.transition(SessionState.IDLE, "i", onlyFrom = setOf(SessionState.BLOCKED)))
        assertTrue(sm.transition(SessionState.IDLE, "i"))
        assertTrue(sm.transition(SessionState.CLOSED, "c"))
        assertFalse(sm.transition(SessionState.IDLE, "reopen")) // CLOSED is terminal
        assertEquals(4, log.since(0).records.count { it.event is TabEvent.StateChanged })
    }

    @Test fun `command gate`() {
        fun g(s: SessionState, k: CommandKind, d: Boolean = false, r: Boolean = false) = CommandGate.check(s, k, d, r)?.code
        assertNull(g(SessionState.IDLE, CommandKind.ACT))
        assertEquals(ErrorCode.HANDOFF_ACTIVE, g(SessionState.WAITING_FOR_HUMAN, CommandKind.OBSERVE))
        assertEquals(ErrorCode.HANDOFF_ACTIVE, g(SessionState.WAITING_FOR_HUMAN, CommandKind.SCREENSHOT))
        assertNull(g(SessionState.WAITING_FOR_HUMAN, CommandKind.RESUME))
        assertEquals(ErrorCode.DIALOG_OPEN, g(SessionState.BLOCKED, CommandKind.ACT, d = true))
        assertEquals(ErrorCode.RENDERER_GONE, g(SessionState.BLOCKED, CommandKind.ACT, r = true))
        assertNull(g(SessionState.BLOCKED, CommandKind.ANSWER_DIALOG, d = true))
        assertEquals(ErrorCode.SESSION_CLOSED, g(SessionState.CLOSED, CommandKind.ACT))
        assertNull(g(SessionState.CLOSED, CommandKind.STATUS))
    }

    @Test fun `settle detector`() {
        val cfg = SettleConfig(quietMs = 300, maxWaitMs = 5000, minWaitMs = 100)
        val d = SettleDetector(cfg, startMs = 0)
        val quiet = PageSample("complete", 0, 1000, "u")
        assertNull(d.update(50, quiet, false))                                   // too early
        assertNull(d.update(200, quiet, true))                                   // navigating
        assertNull(d.update(200, null, false))                                   // not evaluable
        assertNull(d.update(200, quiet.copy(pendingRequests = 1), false))        // network busy
        assertNull(d.update(200, quiet.copy(sinceLastMutationMs = 50), false))   // DOM busy
        assertNull(d.update(200, quiet.copy(readyState = "loading"), false))
        assertEquals(SettleReason.QUIET, d.update(200, quiet, false)?.reason)
        assertEquals(SettleReason.TIMEOUT, d.update(5000, quiet.copy(pendingRequests = 1), false)?.reason)
        assertEquals(SettleReason.BLOCKED, d.update(10, null, false, blocked = true)?.reason)
    }

    @Test fun `secret vault substitutes and redacts`() {
        val v = SecretVault()
        v.putForAnyOrigin("pw", "hunter2!"); v.putForAnyOrigin("short", "ab")
        assertEquals("pass=hunter2!", v.substitute("pass=<secret:pw>", "https://a.test"))
        assertEquals("no placeholders", v.substitute("no placeholders", null))
        try { v.substitute("<secret:nope>", null); fail() } catch (e: AgentException) { assertEquals(ErrorCode.UNKNOWN_SECRET, e.error.code) }
        assertEquals("typed <secret:pw> ok", v.redact("typed hunter2! ok"))
        assertEquals("ab stays", v.redact("ab stays")) // too short to redact safely
        try { v.putForAnyOrigin("bad name", "x"); fail() } catch (_: IllegalArgumentException) { }
        v.remove("pw"); assertEquals(setOf("short"), v.names())
    }

    @Test fun `key tables`() {
        assertEquals(66, KeyTable.lookup("Enter")?.androidKeyCode)
        assertNull(KeyTable.lookup("F13"))
        assertEquals(9, KeyTable.cdpModifiers(setOf("Alt", "Shift")).let { it }) // Alt=1 + Shift=8
        assertEquals(0x1001, KeyTable.androidMetaState(setOf("Shift", "Control")))
    }
}
