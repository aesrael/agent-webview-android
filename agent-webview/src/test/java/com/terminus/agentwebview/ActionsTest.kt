package com.terminus.agentwebview

import org.junit.Assert.*
import org.junit.Test

class ActionsTest {
    private fun refs(vararg r: String): RefTable {
        val t = RefTable()
        t.install(r.map { SnapNode("button", it, it) })
        return t
    }

    @Test fun `valid actions pass`() {
        val r = refs("@e1")
        assertNull(AgentAction.Click("@e1").validate(r))
        assertNull(AgentAction.Fill("@e1", "hi").validate(r))
        assertNull(AgentAction.Press("Enter").validate(r))
        assertNull(AgentAction.Scroll(AgentAction.Dir.DOWN).validate(r))
    }

    @Test fun `stale refs refused`() {
        val r = refs("@e1")
        assertNotNull(AgentAction.Click("@e2").validate(r))
        assertNotNull(AgentAction.Fill("@e9", "x").validate(r))
    }

    @Test fun `bad keys and long text refused`() {
        val r = refs("@e1")
        assertNotNull(AgentAction.Press("F13").validate(r))
        assertNotNull(AgentAction.Fill("@e1", "x".repeat(4001)).validate(r))
    }

    @Test fun `wire format stable`() {
        assertEquals(
            mapOf("type" to "fill", "ref" to "@e1", "text" to "hi"),
            AgentAction.Fill("@e1", "hi").toWire(),
        )
        assertEquals(
            mapOf("type" to "scroll", "dir" to "down"),
            AgentAction.Scroll(AgentAction.Dir.DOWN).toWire(),
        )
    }
}
