package com.terminus.agentwebview

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class SessionTest {
    private val sessions = ArrayList<AgentSession>()
    @After fun cleanup() { sessions.forEach { it.close() } }

    private val fast = SessionConfig(
        settle = SettleConfig(quietMs = 0, maxWaitMs = 500, pollMs = 5, minWaitMs = 0),
        heartbeatMs = 0,
        navigation = NavigationPolicy(),
    )

    private fun rig(
        config: SessionConfig = fast, setup: (FakeDriver) -> Unit = {},
    ): Pair<AgentSession, FakeDriver> {
        val d = FakeDriver()
        setup(d)
        val s = AgentSession(d, config)
        sessions += s
        return s to d
    }

    @Test fun `open then observe then click reports what happened`() = runBlocking {
        val (s, d) = rig { it.snapshotNodes = listOf(btn("Save"), leaf("text", "Hi")) }
        val open = s.open("https://shop.test/")
        assertTrue(open.ok); assertTrue(open.navigated); assertEquals("https://shop.test/", open.urlAfter)
        assertEquals(SettleReason.QUIET, open.settle?.reason)
        assertTrue(open.events.any { it.event is TabEvent.PageFinished })
        val obs = s.observe()
        assertTrue(obs.text.startsWith(AgentSession.UNTRUSTED_NOTICE))
        assertTrue(obs.text.contains("[@e1] button \"Save\""))
        val r = s.act(AgentAction.Click("@e1"))
        assertTrue(r.ok); assertFalse(r.navigated); assertFalse(r.refsInvalidated)
        assertEquals(AgentAction.Click("@e1"), d.performed.single())
        assertEquals(SessionState.IDLE, s.sessionState)
    }

    @Test fun `stale refs are refused before touching the page`() = runBlocking {
        val (s, d) = rig { it.snapshotNodes = listOf(btn("A")) }
        s.observe()
        val r = s.act(AgentAction.Click("@e99"))
        assertEquals(ActionStatus.REFUSED, r.status); assertEquals(ErrorCode.STALE_REF, r.error?.code)
        assertTrue(d.performed.isEmpty())
    }

    @Test fun `navigation invalidates refs and a second observe never reuses them`() = runBlocking {
        val (s, d) = rig { it.snapshotNodes = listOf(btn("A")) }
        s.observe()
        val nav = s.act(AgentAction.Navigate("https://b.test/"))
        assertTrue(nav.refsInvalidated)
        assertEquals(ErrorCode.STALE_REF, s.act(AgentAction.Click("@e1")).error?.code)
        val o2 = s.observe()
        assertTrue(o2.text.contains("[@e2]")) // counter moved on, @e1 is gone for good
        assertEquals(ErrorCode.STALE_REF, s.act(AgentAction.Click("@e1")).error?.code)
        assertTrue(s.act(AgentAction.Click("@e2")).ok)
    }

    @Test fun `driver reporting a missing element invalidates refs`() = runBlocking {
        val (s, d) = rig {
            it.snapshotNodes = listOf(btn("A"))
            it.performResult = { DriverResult.Failed(ErrorCode.STALE_REF, "gone") }
        }
        s.observe()
        val r = s.act(AgentAction.Click("@e1"))
        assertEquals(ActionStatus.FAILED, r.status); assertTrue(r.refsInvalidated)
        assertEquals(ErrorCode.STALE_REF, s.act(AgentAction.Click("@e1")).error?.code)
    }

    @Test fun `blocked navigation is refused and logged`() = runBlocking {
        val (s, d) = rig()
        val r = s.open("http://192.168.1.1/admin")
        assertEquals(ErrorCode.NAV_BLOCKED, r.error?.code)
        assertEquals("about:blank", d.currentUrl)
        assertTrue(s.eventsSince(0).records.any { it.event is TabEvent.NavigationBlocked })
        assertFalse(d.listener.allowNavigation("http://localhost:9/x", true)) // redirects / link clicks too
        assertTrue(d.listener.allowNavigation("https://cdn.test/a.js", false))
        assertFalse(d.listener.allowNavigation("http://10.0.0.1/p.png", false))
    }

    @Test fun `evaluate is gated`() = runBlocking {
        val (s, d) = rig()
        assertEquals(ErrorCode.EVAL_DISABLED, s.act(AgentAction.Evaluate("1")).error?.code)
        assertTrue(d.evaluated.isEmpty())
        val (s2, _) = rig(fast.copy(allowEvaluate = true))
        val r = s2.act(AgentAction.Evaluate("1+41"))
        assertTrue(r.ok); assertEquals("42", r.output)
    }

    @Test fun `secrets are substituted for the page and redacted everywhere else`() = runBlocking {
        val (s, d) = rig { it.snapshotNodes = listOf(SnapNode("textbox", "Password", "@e1", value = "hunter2!")) }
        s.vault.put("pw", "hunter2!")
        s.observe()
        val r = s.act(AgentAction.Fill("@e1", "<secret:pw>"))
        assertEquals("hunter2!", (d.performed.single() as AgentAction.Fill).text) // page gets the real value
        assertFalse(Json.stringify(r.toWire()).contains("hunter2!"))            // report does not
        val obs = s.observe()
        assertFalse(obs.text.contains("hunter2!")); assertTrue(obs.text.contains("<secret:pw>"))
        val unknown = s.act(AgentAction.Fill("@e2", "<secret:missing>"))
        assertEquals(ActionStatus.REFUSED, unknown.status)
    }

    @Test fun `captcha on the page starts a handoff and the agent is locked out until it ends`() = runBlocking {
        val (s, d) = rig {
            it.snapshotNodes = listOf(btn("Continue"))
            it.snapshotFrames = listOf(FrameInfo("https://www.google.com/recaptcha/api2/anchor", crossOrigin = true))
        }
        val obs = s.observe()
        assertEquals(SessionState.WAITING_FOR_HUMAN, obs.state.let { s.sessionState })
        assertNotNull(obs.handoff); assertEquals(HandoffKind.CAPTCHA, obs.handoff?.kind)
        // agent may not act, observe or screenshot while the person drives
        assertEquals(ErrorCode.HANDOFF_ACTIVE, s.act(AgentAction.Click("@e1")).error?.code)
        try { s.observe(); fail() } catch (e: AgentException) { assertEquals(ErrorCode.HANDOFF_ACTIVE, e.error.code) }
        try { s.screenshot(); fail() } catch (e: AgentException) { assertEquals(ErrorCode.HANDOFF_ACTIVE, e.error.code) }
        assertEquals(0, d.screenshotCalls); assertEquals(1, d.snapshotCalls)
        // agent suspends until the person is done
        var outcome: HandoffOutcome? = null
        val job = launch { outcome = s.awaitHandoff() }
        kotlinx.coroutines.delay(30); assertNull(outcome)
        d.snapshotFrames = emptyList()
        assertTrue(s.resumeFromHuman())
        withTimeout(2000) { job.join() }
        assertTrue(outcome is HandoffOutcome.Resumed)
        assertEquals(SessionState.IDLE, s.sessionState)
        // refs from before the handoff are dead; a fresh observe works and no longer triggers it
        assertEquals(ErrorCode.STALE_REF, s.act(AgentAction.Click("@e1")).error?.code)
        assertNull(s.observe().handoff)
    }

    @Test fun `explicit handoff with manual resume`() = runBlocking {
        val (s, _) = rig()
        val t = s.requestHandoff("log in with 2FA", kind = HandoffKind.TWO_FACTOR)
        assertEquals(t, s.status().handoff)
        assertEquals(SessionState.WAITING_FOR_HUMAN, s.status().state)
        assertTrue(s.resumeFromHuman())
        assertEquals(SessionState.IDLE, s.status().state)
        assertNull(s.status().handoff)
    }

    @Test fun `dialogs block the agent until answered and alerts auto accept`() = runBlocking {
        val (s, d) = rig()
        d.openDialog("confirm", "Delete everything?")
        assertEquals(SessionState.BLOCKED, s.sessionState)
        assertEquals(ErrorCode.DIALOG_OPEN, s.act(AgentAction.Reload).error?.code)
        assertEquals("Delete everything?", s.status().pendingDialog?.message)
        assertTrue(s.answerDialog(false))
        assertEquals(SessionState.IDLE, s.sessionState)
        assertTrue(s.act(AgentAction.Reload).ok)
        d.openDialog("alert", "Saved!")
        withTimeout(2000) { while (s.sessionState != SessionState.IDLE) kotlinx.coroutines.delay(5) }
        assertTrue(s.eventsSince(0).records.any { (it.event as? TabEvent.DialogClosed)?.auto == true })
    }

    @Test fun `renderer loss blocks until recovered`() = runBlocking {
        val (s, d) = rig { it.snapshotNodes = listOf(btn("A")) }
        s.observe()
        d.listener.onEvent(TabEvent.RendererGone(didCrash = true))
        assertEquals(SessionState.BLOCKED, s.sessionState)
        assertEquals(ErrorCode.RENDERER_GONE, s.act(AgentAction.Click("@e1")).error?.code)
        s.recoverRenderer()
        assertEquals(SessionState.IDLE, s.sessionState)
        assertEquals(ErrorCode.STALE_REF, s.act(AgentAction.Click("@e1")).error?.code)
    }

    @Test fun `batch stops on failure and when the page changes`() = runBlocking {
        val (s, d) = rig { it.snapshotNodes = listOf(btn("A"), btn("B")) }
        s.observe()
        val (r1, skipped1) = s.actBatch(listOf(AgentAction.Click("@e1"), AgentAction.Click("@e2")))
        assertEquals(2, r1.size); assertEquals(0, skipped1)
        val (r2, skipped2) = s.actBatch(listOf(AgentAction.Click("@e77"), AgentAction.Click("@e2")))
        assertEquals(1, r2.size); assertEquals(1, skipped2)
        s.observe()
        d.onPerform = { if (it is AgentAction.Click) d.load("https://next.test/") }
        val (r3, skipped3) = s.actBatch(listOf(AgentAction.Click("@e3"), AgentAction.Click("@e4")))
        assertEquals(1, r3.size); assertEquals(1, skipped3); assertTrue(r3[0].navigated)
    }

    @Test fun `confirmation gate asks before risky clicks`() = runBlocking {
        val asked = ArrayList<ConfirmationRequest>()
        var answer = false
        val cfg = fast.copy(confirmSensitive = ConfirmationHandler { asked += it; answer })
        val (s, d) = rig(cfg) { it.snapshotNodes = listOf(btn("Place order"), btn("Cancel")) }
        s.observe()
        val denied = s.act(AgentAction.Click("@e1"))
        assertEquals(ErrorCode.CONFIRMATION_DENIED, denied.error?.code)
        assertTrue(d.performed.isEmpty()); assertEquals(1, asked.size)
        assertTrue(s.act(AgentAction.Click("@e2")).ok); assertEquals(1, asked.size) // harmless: not asked
        answer = true
        assertTrue(s.act(AgentAction.Click("@e1")).ok)
    }

    @Test fun `commands time out instead of hanging`() = runBlocking {
        val cfg = fast.copy(commandTimeoutMs = 100)
        val d = FakeDriver().also { it.snapshotNodes = listOf(btn("A")) }
        // a driver whose actions never answer
        val hang = object : BrowserDriver by d {
            override suspend fun perform(action: AgentAction): DriverResult { kotlinx.coroutines.delay(10_000); return DriverResult.Ok }
        }
        val s = AgentSession(hang, cfg); sessions += s
        s.observe()
        val r = s.act(AgentAction.Click("@e1"))
        assertEquals(ActionStatus.FAILED, r.status)
        assertEquals(ErrorCode.TIMEOUT, r.error?.code)
        assertEquals(SessionState.IDLE, s.sessionState) // the tab is usable again afterwards
    }

    @Test fun `settling reports timeout when the page never goes quiet`() = runBlocking {
        val cfg = fast.copy(settle = SettleConfig(quietMs = 0, maxWaitMs = 60, pollMs = 5, minWaitMs = 0))
        val (s, _) = rig(cfg) { it.sample = PageSample("complete", 3, 0, "") }
        val r = s.open("https://busy.test/")
        assertTrue(r.ok); assertEquals(SettleReason.TIMEOUT, r.settle?.reason)
    }

    @Test fun `wire protocol round trip`() = runBlocking {
        val (s, _) = rig { it.snapshotNodes = listOf(btn("Go")) }
        fun call(json: String) = Json.parse(runBlocking { s.handleJson(json) }) as Map<*, *>
        val open = call("""{"id":1,"cmd":"open","url":"https://x.test/"}""")
        assertEquals(true, open["ok"]); assertEquals(1L, open["id"])
        assertEquals("ok", open.map("report")?.string("status"))
        val obs = call("""{"id":2,"cmd":"observe","nodes":true}""").map("observation")!!
        assertEquals("IDLE", obs.string("state")); assertNotNull(obs.list("nodes"))
        val act = call("""{"id":3,"cmd":"act","action":{"type":"click","ref":"@e1"}}""")
        assertEquals("ok", act.map("report")?.string("status"))
        val stale = call("""{"id":4,"cmd":"act","action":{"type":"click","ref":"@e50"}}""")
        assertEquals("refused", stale.map("report")?.string("status"))
        assertEquals("STALE_REF", stale.map("report")?.map("error")?.string("code"))
        val bad = call("""{"id":5,"cmd":"act","action":{"type":"nope"}}""")
        assertEquals(false, bad["ok"]); assertEquals("BAD_ACTION", bad.map("error")?.string("code"))
        assertEquals("PROTOCOL", call("""{"id":6,"cmd":"frobnicate"}""").map("error")?.string("code"))
        assertEquals("PROTOCOL", (Json.parse(s.handleJson("[1,2")) as Map<*, *>).map("error")?.string("code"))
        val ev = call("""{"id":7,"cmd":"events","since":0}""")
        assertTrue((ev.list("events")?.size ?: 0) > 3)
        val shot = call("""{"id":8,"cmd":"screenshot"}""")
        assertEquals("AQID", shot.string("data")) // base64 of 1,2,3
        val h = call("""{"id":9,"cmd":"handoff","reason":"solve it","kind":"captcha","resumeWhen":{"type":"manual"}}""")
        assertEquals("solve it", h.map("handoff")?.string("reason"))
        assertEquals("HANDOFF_ACTIVE", call("""{"id":10,"cmd":"observe"}""").map("error")?.string("code"))
        assertEquals(true, call("""{"id":11,"cmd":"resume"}""")["resumed"])
        assertEquals("resumed", call("""{"id":12,"cmd":"await_handoff"}""").map("result")?.string("outcome"))
        val batch = call("""{"id":13,"cmd":"batch","actions":[{"type":"wait","ms":1},{"type":"scroll","dir":"down"}]}""")
        assertEquals(2, batch.list("reports")?.size)
        call("""{"id":14,"cmd":"close"}""")
        assertEquals(SessionState.CLOSED, s.sessionState)
    }

    @Test fun `heartbeats flow and close is idempotent`() = runBlocking {
        val d = FakeDriver()
        val s = AgentSession(d, fast.copy(heartbeatMs = 20)); sessions += s
        val beats = ArrayList<EventRecord>()
        s.subscribe { if (it.event is TabEvent.Heartbeat) beats += it }
        withTimeout(3000) { while (beats.size < 2) kotlinx.coroutines.delay(10) }
        s.close(); s.close()
        assertTrue(d.closed); assertEquals(SessionState.CLOSED, s.sessionState)
        assertEquals(ErrorCode.SESSION_CLOSED, s.act(AgentAction.Reload).error?.code)
        assertEquals(1, s.eventsSince(0).records.count { it.event is TabEvent.SessionClosed })
    }
}
