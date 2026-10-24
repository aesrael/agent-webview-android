package dev.agentwebview

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Take-over / give-back behaviour, step labels and the faster settle. */
class ExperienceTest {
    private val sessions = ArrayList<AgentSession>()
    @After fun cleanup() { sessions.forEach { it.close() } }

    private val fast = SessionConfig(
        settle = SettleConfig(quietMs = 0, maxWaitMs = 500, maxWaitIdleMs = 200, pollMs = 5, minWaitMs = 0),
        heartbeatMs = 0,
        awaitHandoffPollMs = 80,
    )

    private fun rig(setup: (FakeDriver) -> Unit = {}): Pair<AgentSession, FakeDriver> {
        val d = FakeDriver(); setup(d)
        val s = AgentSession(d, fast); sessions += s
        return s to d
    }

    @Test fun `steps get human labels and never contain typed text`() = runBlocking {
        val (s, _) = rig { it.snapshotNodes = listOf(btn("Search flights", "@e1"), SnapNode("textbox", "From", "@e2")) }
        s.observe()
        s.act(AgentAction.Click("@e1"))
        s.act(AgentAction.Fill("@e2", "hunter2-secret"))
        s.act(AgentAction.Navigate("https://www.google.com/flights"))
        val labels = s.eventsSince(0, 500).records.map { it.event }.filterIsInstance<TabEvent.ActionStarted>().map { it.label }
        assertEquals(listOf("Click “Search flights”", "Type into “From”", "Open google.com"), labels)
        assertTrue(labels.none { it.contains("hunter2") })
    }

    @Test fun `an agent supplied label wins`() = runBlocking {
        val (s, _) = rig()
        s.act(AgentAction.Wait(1), label = "Checking the baggage policy")
        val e = s.eventsSince(0, 50).records.map { it.event }.filterIsInstance<TabEvent.ActionStarted>().single()
        assertEquals("Checking the baggage policy", e.label)
    }

    @Test fun `note command lands in the event log`() = runBlocking {
        val (s, _) = rig()
        s.handle(mapOf("id" to 1, "cmd" to "note", "text" to "Comparing 10-17 April"))
        assertTrue(s.eventsSince(0, 50).records.any { (it.event as? TabEvent.AgentNote)?.text == "Comparing 10-17 April" })
    }

    @Test fun `await_handoff long-polls instead of blocking forever`() = runBlocking {
        val (s, _) = rig()
        s.requestHandoff("you took over")
        val t0 = System.currentTimeMillis()
        val r = s.handle(mapOf("id" to 1, "cmd" to "await_handoff"))
        assertEquals("still_waiting", r["outcome"])
        assertTrue(r["hint"].toString().contains("again"))
        assertTrue(System.currentTimeMillis() - t0 < 2_000)
        assertTrue(s.resumeFromHuman())
        val done = s.handle(mapOf("id" to 2, "cmd" to "await_handoff"))
        assertEquals("resumed", (done["result"] as Map<*, *>)["outcome"])
    }

    @Test fun `waiting agent wakes the moment the person gives back`() = runBlocking {
        val (s, _) = rig()
        s.requestHandoff("captcha")
        val job = launch { s.handle(mapOf("id" to 1, "cmd" to "await_handoff", "timeoutMs" to 30_000)) }
        kotlinx.coroutines.delay(30)
        s.resumeFromHuman()
        withTimeout(2_000) { job.join() }
        assertEquals(SessionState.IDLE, s.sessionState)
    }

    @Test fun `refusal during a handoff tells the model what to do`() = runBlocking {
        val (s, _) = rig()
        s.requestHandoff("login")
        val r = s.handle(mapOf("id" to 1, "cmd" to "observe"))
        assertEquals(false, r["ok"])
        assertTrue(((r["error"] as Map<*, *>)["message"]).toString().contains("await_handoff"))
    }

    @Test fun `a dialog that opens while the person has control is theirs to answer`() = runBlocking {
        val (s, d) = rig()
        s.requestHandoff("take over")
        d.openDialog("confirm", "Delete this?")
        assertEquals(SessionState.WAITING_FOR_HUMAN, s.sessionState) // still the person's turn
        assertEquals("confirm", s.status().pendingDialog?.kind)
        assertTrue(s.answerDialogAsHuman(false))
        assertNull(s.status().pendingDialog)
        assertTrue(s.resumeFromHuman())
        assertEquals(SessionState.IDLE, s.sessionState)
    }

    @Test fun `giving back with a dialog still open leaves the agent a way forward`() = runBlocking {
        val (s, d) = rig()
        s.requestHandoff("take over")
        d.openDialog("confirm", "Leave page?")
        s.resumeFromHuman()
        assertEquals(SessionState.BLOCKED, s.sessionState)
        assertTrue(s.answerDialog(true))
        assertEquals(SessionState.IDLE, s.sessionState)
    }

    @Test fun `act with observe returns the page in the same reply`() = runBlocking {
        val (s, _) = rig { it.snapshotNodes = listOf(btn("Go")) }
        val r = s.handle(mapOf("id" to 1, "cmd" to "act", "observe" to true, "action" to mapOf("type" to "wait", "ms" to 1)))
        assertEquals(true, r["ok"])
        assertNotNull(r["observation"])
        val plain = s.handle(mapOf("id" to 2, "cmd" to "act", "action" to mapOf("type" to "wait", "ms" to 1)))
        assertNull(plain["observation"])
    }

    @Test fun `observe flag reports a refusal instead of failing the whole action`() = runBlocking {
        val (s, _) = rig()
        s.requestHandoff("x")
        val r = s.handle(mapOf("id" to 1, "cmd" to "note", "text" to "hi"))
        assertEquals(true, r["ok"]) // notes are allowed while the person has control
    }

    @Test fun `a click on a page that never goes quiet stops waiting at the short cap`() = runBlocking {
        val (s, d) = rig { it.sample = PageSample("complete", 3, 0, "") } // always busy
        val t0 = System.currentTimeMillis()
        val r = s.act(AgentAction.Wait(1))
        // wait is instant; use a perform-based action to exercise settle
        val r2 = s.act(AgentAction.Scroll(AgentAction.Dir.DOWN))
        assertEquals(SettleReason.TIMEOUT, r2.settle?.reason)
        assertTrue("took ${System.currentTimeMillis() - t0}ms", System.currentTimeMillis() - t0 < 450)
        assertTrue(d.performed.isNotEmpty() && r.ok)
    }

    @Test fun `detector uses the long cap only after a navigation`() {
        val cfg = SettleConfig(quietMs = 0, maxWaitMs = 6000, maxWaitIdleMs = 2000, minWaitMs = 0)
        val busy = PageSample("complete", 5, 0, "")
        val idle = SettleDetector(cfg, 0)
        assertNull(idle.update(1_900, busy, navigating = false))
        assertEquals(SettleReason.TIMEOUT, idle.update(2_000, busy, navigating = false)?.reason)
        val nav = SettleDetector(cfg, 0)
        assertNull(nav.update(100, busy, navigating = true))
        assertNull(nav.update(2_500, busy, navigating = false))
        assertEquals(SettleReason.TIMEOUT, nav.update(6_000, busy, navigating = false)?.reason)
    }

    @Test fun `a captcha frame that stays after the person solves it does not bounce control straight back`() = runBlocking {
        val (s, d) = rig {
            it.snapshotNodes = listOf(btn("Continue"))
            it.snapshotFrames = listOf(FrameInfo("https://www.google.com/recaptcha/api2/anchor", crossOrigin = true))
        }
        d.load("https://shop.test/login")
        assertNotNull(s.observe().handoff)           // auto handoff on first sight
        assertTrue(s.resumeFromHuman())              // person solves and gives back; the frame is still in the DOM
        val again = s.observe()
        assertNull(again.handoff)
        assertEquals(SessionState.IDLE, s.sessionState)
        assertTrue(again.text.contains("just handled"))
        // a different page with a new challenge still triggers one
        d.load("https://shop.test/checkout")
        assertNotNull(s.observe().handoff)
    }
}
