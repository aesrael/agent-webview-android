package com.terminus.agentwebview

import org.junit.Assert.*
import org.junit.Test

class SnapshotTest {
    private val json = """{"url":"https://x.test/","title":"T","truncated":true,"scrollY":10,"scrollHeight":900,"viewportHeight":600,
      "nextIndex":4,"frames":[{"src":"https://www.google.com/recaptcha/api2/anchor","crossOrigin":true}],
      "nodes":[
        {"role":"heading","name":"Welcome","level":1,"children":[]},
        {"role":"button","name":"Save","ref":"@e1","states":["disabled"],"children":[]},
        {"role":"link","name":"Home","ref":"@e2","href":"https://x.test/h","children":[
          {"role":"textbox","name":"Search","ref":"@e3","value":"abc","children":[]}]}]}"""

    @Test fun `parses full snapshot`() {
        val s = SnapshotWire.parse(json)
        assertEquals("https://x.test/", s.url)
        assertTrue(s.truncated)
        assertEquals(4, s.nextIndex)
        assertEquals(3, s.nodes.size)
        assertEquals(setOf("disabled"), s.nodes[1].states)
        assertEquals("@e3", s.nodes[2].children[0].ref)
        assertEquals(1, s.frames.size)
        assertTrue(s.frames[0].crossOrigin)
    }

    @Test fun `legacy array form still parses`() {
        val s = SnapshotWire.parse("""[{"role":"button","name":"Save","ref":"@e1","children":[]}]""")
        assertEquals(SnapNode("button", "Save", "@e1"), s.nodes[0])
    }

    @Test fun `bad json throws`() {
        try { SnapshotWire.parse("not json"); fail() } catch (_: Exception) { }
        try { SnapshotWire.parse("42"); fail() } catch (_: Exception) { }
    }

    @Test fun `refs from an older snapshot never resolve to a different element`() {
        val t = RefTable()
        t.install(PageSnapshot("", "", listOf(btn("A", "@e1")), nextIndex = 2))
        assertEquals("A", t.resolve("@e1")?.name)
        // Next snapshot is numbered from nextIndex(), so @e1 is simply gone.
        assertEquals(2, t.nextIndex())
        t.install(PageSnapshot("", "", listOf(btn("B", "@e2")), nextIndex = 3))
        assertNull(t.resolve("@e1"))
        assertEquals("B", t.resolve("@e2")?.name)
    }

    @Test fun `invalidate drops everything and bumps generation`() {
        val t = RefTable()
        t.install(PageSnapshot("", "", listOf(btn("A")), nextIndex = 2))
        val g = t.generation
        t.invalidate()
        assertNull(t.resolve("@e1"))
        assertTrue(t.generation > g)
        assertEquals(2, t.nextIndex()) // counter never resets
    }

    @Test fun `only interactive nodes enter the table`() {
        val t = RefTable()
        t.install(PageSnapshot("", "", listOf(leaf("heading", "H"), btn("A", "@e1")), nextIndex = 2))
        assertEquals(setOf("@e1"), t.knownRefs())
    }

    @Test fun `text rendering is compact and bounded`() {
        val s = SnapshotWire.parse(json)
        val text = SnapshotText.render(s)
        assertTrue(text.contains("[@e1] button \"Save\" {disabled}"))
        assertTrue(text.contains("  [@e3] textbox \"Search\" value=\"abc\""))
        assertTrue(text.contains("heading(1) \"Welcome\""))
        assertTrue(text.contains("cross-origin frame"))
        assertTrue(text.contains("page truncated"))
        val clipped = SnapshotText.render(s, 60)
        assertTrue(clipped.length < 140)
        assertTrue(clipped.contains("omitted"))
    }
}
