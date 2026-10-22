package com.terminus.agentwebview

import org.junit.Assert.*
import org.junit.Test

class SnapshotTest {
    @Test fun `parses nested snapshot`() {
        val json = """[{"role":"button","name":"Save","ref":"@e1","children":[]},
            {"role":"link","name":"Home","ref":"@e2","children":[
              {"role":"textbox","name":"Search","ref":"@e3","children":[]}]}]"""
        val nodes = SnapshotWire.parse(json)
        assertEquals(2, nodes.size)
        assertEquals(SnapNode("button", "Save", "@e1"), nodes[0])
        assertEquals(1, nodes[1].children.size)
        assertEquals("@e3", nodes[1].children[0].ref)
    }

    @Test fun `refs die on reinstall`() {
        val t = RefTable()
        t.install(listOf(SnapNode("button", "A", "@e1")))
        assertNotNull(t.resolve("@e1"))
        t.install(listOf(SnapNode("button", "B", "@e1")))
        // Same ref string, new generation: resolves to the NEW node.
        assertEquals("B", t.resolve("@e1")?.name)
        assertNull(t.resolve("@e9"))
    }

    @Test fun `bad json throws`() {
        try {
            SnapshotWire.parse("not json")
            fail("expected exception")
        } catch (_: Exception) { /* expected */ }
    }
}
