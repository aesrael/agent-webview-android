package com.terminus.agentwebview

import org.junit.Assert.*
import org.junit.Test

class ActionsTest {
    private fun refs(vararg r: String): RefTable {
        val t = RefTable()
        t.install(PageSnapshot("", "", r.map { btn(it, it) }, nextIndex = 100))
        return t
    }

    @Test fun `valid actions pass`() {
        val r = refs("@e1")
        listOf(
            AgentAction.Click("@e1"), AgentAction.Fill("@e1", "hi"), AgentAction.Press("Enter"),
            AgentAction.Press("a", setOf("Control")), AgentAction.Scroll(AgentAction.Dir.DOWN),
            AgentAction.ScrollTo("@e1"), AgentAction.Select("@e1", "v"), AgentAction.Check("@e1", true),
            AgentAction.Navigate("https://a.test"), AgentAction.Wait(100), AgentAction.Evaluate("1+1"),
            AgentAction.Back, AgentAction.Forward, AgentAction.Reload,
        ).forEach { assertNull("$it", it.validate(r)) }
    }

    @Test fun `stale refs refused with code`() {
        val r = refs("@e1")
        assertEquals(ErrorCode.STALE_REF, AgentAction.Click("@e2").validate(r)?.code)
        assertEquals(ErrorCode.STALE_REF, AgentAction.Fill("@e9", "x").validate(r)?.code)
        assertEquals(ErrorCode.STALE_REF, AgentAction.Select("@e9", "x").validate(r)?.code)
        assertEquals(ErrorCode.STALE_REF, AgentAction.ScrollTo("@e9").validate(r)?.code)
    }

    @Test fun `bad input refused`() {
        val r = refs("@e1")
        assertEquals(ErrorCode.UNSUPPORTED_KEY, AgentAction.Press("F13").validate(r)?.code)
        assertEquals(ErrorCode.UNSUPPORTED_KEY, AgentAction.Press("Enter", setOf("Hyper")).validate(r)?.code)
        assertEquals(ErrorCode.TEXT_TOO_LONG, AgentAction.Fill("@e1", "x".repeat(4001)).validate(r)?.code)
        assertEquals(ErrorCode.BAD_ACTION, AgentAction.Wait(-1).validate(r)?.code)
        assertEquals(ErrorCode.BAD_ACTION, AgentAction.Wait(31_000).validate(r)?.code)
        assertEquals(ErrorCode.BAD_ACTION, AgentAction.Scroll(AgentAction.Dir.UP, Double.NaN).validate(r)?.code)
        assertEquals(ErrorCode.BAD_ACTION, AgentAction.Navigate(" ").validate(r)?.code)
        assertEquals(ErrorCode.BAD_ACTION, AgentAction.Evaluate("").validate(r)?.code)
    }

    @Test fun `wire round trip for every action`() {
        val all = listOf(
            AgentAction.Click("@e1"), AgentAction.Fill("@e1", "hi \"there\" <secret:pw>"),
            AgentAction.Press("Enter"), AgentAction.Press("a", setOf("Control", "Shift")),
            AgentAction.Scroll(AgentAction.Dir.DOWN, 0.5), AgentAction.Scroll(AgentAction.Dir.UP),
            AgentAction.ScrollTo("@e3"), AgentAction.Select("@e2", "opt"), AgentAction.Check("@e2", false),
            AgentAction.Navigate("https://a.test/x?y=1"), AgentAction.Wait(250), AgentAction.Evaluate("document.title"),
            AgentAction.Back, AgentAction.Forward, AgentAction.Reload,
        )
        for (a in all) {
            val json = Json.stringify(a.toWire())
            val back = parseAction(Json.parse(json) as Map<*, *>)
            assertEquals(a, back)
        }
    }

    @Test fun `wire format is stable`() {
        assertEquals("""{"type":"fill","ref":"@e1","text":"hi"}""", Json.stringify(AgentAction.Fill("@e1", "hi").toWire()))
        assertEquals("""{"type":"back"}""", Json.stringify(AgentAction.Back.toWire()))
    }

    @Test fun `parseAction rejects malformed input with BAD_ACTION`() {
        val bad = listOf(
            "{}", """{"type":"click"}""", """{"type":"nope"}""", """{"type":"scroll","dir":"sideways"}""",
            """{"type":"check","ref":"@e1"}""", """{"type":"wait","ms":"x"}""", """{"type":"press","key":"a","modifiers":[1]}""",
        )
        for (b in bad) {
            try {
                parseAction(Json.parse(b) as Map<*, *>)
                fail("expected failure: $b")
            } catch (e: AgentException) {
                assertEquals(ErrorCode.BAD_ACTION, e.error.code)
            }
        }
    }
}
