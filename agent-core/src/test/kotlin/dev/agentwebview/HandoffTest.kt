package dev.agentwebview

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class HandoffTest {
    private class Rig(probe: HandoffProbe? = null) {
        val clock = FakeClock()
        val sched = FakeScheduler(clock)
        val log = EventLog(clock = clock)
        val state = StateMachine(log)
        var url = "https://a.test/"
        var finished: HandoffOutcome? = null
        val ctl = HandoffController(log, clock, sched, state, { url }, probe, pollMs = 100, onFinished = { finished = it })
        fun types() = log.since(0).records.map { it.event.type }
    }

    @Test fun `request moves to WAITING_FOR_HUMAN and user resume returns to IDLE`() {
        val r = Rig()
        val t = r.ctl.request("solve captcha", kind = HandoffKind.CAPTCHA)
        assertEquals(SessionState.WAITING_FOR_HUMAN, r.state.current)
        assertEquals(t, r.ctl.activeTicket)
        r.clock.now += 5_000; r.url = "https://a.test/after"
        assertTrue(r.ctl.resume())
        assertEquals(SessionState.IDLE, r.state.current)
        val out = r.finished as HandoffOutcome.Resumed
        assertEquals(ResumeSource.USER, out.by); assertEquals(5_000, out.durationMs)
        assertEquals(true, out.toWire()["urlChanged"])
        assertNull(r.ctl.activeTicket)
        assertTrue(r.types().containsAll(listOf("handoff_requested", "handoff_resumed")))
        assertFalse(r.ctl.resume()) // nothing active now
    }

    @Test fun `second request returns the active ticket`() {
        val r = Rig()
        assertSame(r.ctl.request("a"), r.ctl.request("b"))
        assertEquals(1, r.types().count { it == "handoff_requested" })
    }

    @Test fun `timeout ends the handoff and reports it`() {
        val r = Rig()
        r.ctl.request("x", timeoutMs = 60_000)
        r.sched.advance(59_999); assertEquals(SessionState.WAITING_FOR_HUMAN, r.state.current)
        r.sched.advance(2)
        assertEquals(SessionState.IDLE, r.state.current)
        assertTrue(r.finished is HandoffOutcome.TimedOut)
        assertTrue(r.types().contains("handoff_timed_out"))
        assertEquals(0, r.sched.pending)
    }

    @Test fun `auto resume when url changes`() {
        var url = "https://a.test/captcha"
        val probe = object : HandoffProbe {
            override fun sample(selectors: List<String>, callback: (ProbeResult?) -> Unit) = callback(ProbeResult(url, emptySet()))
        }
        val r = Rig(probe)
        r.ctl.request("x", ResumeWhen.UrlChanges("https://a.test/captcha"), timeoutMs = 60_000)
        r.sched.advance(300); assertEquals(SessionState.WAITING_FOR_HUMAN, r.state.current)
        url = "https://a.test/home"
        r.sched.advance(150)
        assertEquals(SessionState.IDLE, r.state.current)
        val out = r.finished as HandoffOutcome.Resumed
        assertEquals(ResumeSource.AUTO, out.by); assertEquals("https://a.test/home", out.urlAfter)
        assertEquals(0, r.sched.pending)
    }

    @Test fun `auto resume when selector disappears and probe failure is tolerated`() {
        var present = setOf(".cf"); var fail = true
        val probe = object : HandoffProbe {
            override fun sample(selectors: List<String>, callback: (ProbeResult?) -> Unit) =
                callback(if (fail) null else ProbeResult("u", present.filter { it in selectors }.toSet()))
        }
        val r = Rig(probe)
        r.ctl.request("x", ResumeWhen.SelectorGone(".cf"))
        r.sched.advance(300); assertEquals(SessionState.WAITING_FOR_HUMAN, r.state.current) // probe failing: keep waiting
        fail = false; r.sched.advance(150); assertEquals(SessionState.WAITING_FOR_HUMAN, r.state.current) // still present
        present = emptySet(); r.sched.advance(150)
        assertEquals(SessionState.IDLE, r.state.current)
    }

    @Test fun `awaitOutcome wakes exactly when control returns`() = runBlocking {
        val r = Rig()
        r.ctl.request("x")
        var got: HandoffOutcome? = null
        val job = launch { got = r.ctl.awaitOutcome() }
        kotlinx.coroutines.delay(50); assertNull(got)
        r.ctl.resume()
        withTimeout(2000) { job.join() }
        assertTrue(got is HandoffOutcome.Resumed)
        assertSame(got, r.ctl.awaitOutcome()) // later callers get the last outcome
    }

    @Test fun `cancel completes waiters`() = runBlocking {
        val r = Rig()
        r.ctl.request("x")
        var got: HandoffOutcome? = null
        val job = launch { got = r.ctl.awaitOutcome() }
        kotlinx.coroutines.delay(30)
        r.ctl.cancel()
        withTimeout(2000) { job.join() }
        assertTrue(got is HandoffOutcome.Cancelled)
    }

    @Test fun `resumeWhen wire round trip`() {
        for (w in listOf(ResumeWhen.Manual, ResumeWhen.UrlChanges("u"), ResumeWhen.UrlContains("f"),
            ResumeWhen.SelectorGone(".a"), ResumeWhen.SelectorPresent(".b"))) {
            assertEquals(w, ResumeWhen.fromWire(Json.parse(Json.stringify(w.toWire())) as Map<*, *>, "u"))
        }
        try { ResumeWhen.fromWire(mapOf("type" to "wat"), "u"); fail() } catch (e: AgentException) { assertEquals(ErrorCode.PROTOCOL, e.error.code) }
    }

    @Test fun `detector finds captcha login otp and payment`() {
        fun snap(vararg n: SnapNode, frames: List<FrameInfo> = emptyList()) = PageSnapshot("u", "t", n.toList(), frames = frames)
        val cap = HandoffDetector.detect(snap(frames = listOf(FrameInfo("https://www.google.com/recaptcha/api2/anchor", crossOrigin = true))))
        assertEquals(HandoffKind.CAPTCHA, cap.single().kind)
        assertEquals(HandoffKind.CAPTCHA, HandoffDetector.detect(snap(leaf("text", "Please verify you are human"))).single().kind)
        assertEquals(HandoffKind.LOGIN, HandoffDetector.detect(snap(SnapNode("textbox", "Pw", "@e1", states = setOf("password")))).single().kind)
        assertEquals(HandoffKind.TWO_FACTOR, HandoffDetector.detect(snap(SnapNode("textbox", "Code", "@e1", states = setOf("otp")))).single().kind)
        assertEquals(HandoffKind.PAYMENT, HandoffDetector.detect(snap(SnapNode("textbox", "Card", "@e1", states = setOf("cc")))).single().kind)
        assertTrue(HandoffDetector.detect(snap(btn("Save"), leaf("text", "Hello"))).isEmpty())
    }
}
