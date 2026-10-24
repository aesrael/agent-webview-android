package dev.agentwebview

import org.junit.Assert.*
import org.junit.Test

class JsonTest {
    @Test fun `parses nested structures`() {
        val v = Json.parse("""{"a":[1,2.5,"x",true,null],"b":{"c":-3}}""") as Map<*, *>
        assertEquals(listOf(1L, 2.5, "x", true, null), v["a"])
        assertEquals(-3L, (v["b"] as Map<*, *>)["c"])
    }

    @Test fun `decodes unicode escapes emitted by WebView for angle brackets`() {
        // evaluateJavascript returns "\u003CBack" for the string "<Back"
        assertEquals("<Back", Js.decodeResult("\"\\u003CBack\""))
        assertEquals("a\"b\\c\nd", Js.decodeResult("\"a\\\"b\\\\c\\nd\""))
        assertEquals("\u2028", Js.decodeResult("\"\\u2028\""))
    }

    @Test fun `decodeResult handles null undefined and objects`() {
        assertNull(Js.decodeResult(null))
        assertNull(Js.decodeResult("null"))
        assertNull(Js.decodeResult(""))
        assertEquals(mapOf("k" to 1L), Js.decodeResult("{\"k\":1}"))
    }

    @Test fun `round trips tricky strings`() {
        val s = "quote\" back\\slash <tag> \u0001 \u2028 emoji \uD83D\uDE00 tab\t nl\n"
        assertEquals(s, Json.parse(Json.stringify(s)))
        assertEquals(mapOf("k" to listOf(1L, "v", null)), Json.parse(Json.stringify(mapOf("k" to listOf(1, "v", null)))))
    }

    @Test fun `stringify numbers enums and non finite`() {
        assertEquals("[1,2,1.5,null]", Json.stringify(listOf(1, 2.0, 1.5, Double.NaN)))
        assertEquals("\"DOWN\"", Json.stringify(AgentAction.Dir.DOWN))
    }

    @Test fun `rejects malformed input`() {
        for (bad in listOf("", "{", "[1,", "{\"a\"}", "{\"a\":1,}", "[1 2]", "\"abc", "tru", "01x", "{1:2}", "[1]x", "\"\\q\"", "\"\\u12\"")) {
            try {
                Json.parse(bad)
                fail("expected failure for: $bad")
            } catch (_: JsonException) {
            }
        }
    }

    @Test fun `deep nesting is rejected not a stack overflow`() {
        val deep = "[".repeat(5000) + "]".repeat(5000)
        try { Json.parse(deep); fail() } catch (_: JsonException) { }
    }

    @Test fun `large integers fall back to double`() {
        assertEquals(1e30, Json.parse("1e30"))
        assertTrue(Json.parse("123456789012345678901234567890") is Double)
    }
}
