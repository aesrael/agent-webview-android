package com.terminus.agentwebview

/** Accessibility-style snapshot model. Pure Kotlin, no Android deps. */
data class SnapNode(
    val role: String,
    val name: String,
    val ref: String,
    val children: List<SnapNode> = emptyList(),
)

/** Ref table: refs (@e1…) are valid for exactly one snapshot generation.
 *  A ref from an older snapshot resolves to null — stale refs error instead
 *  of acting on the wrong element. */
class RefTable {
    private var generation = 0
    private var nodes: Map<String, SnapNode> = emptyMap()

    /** Install a fresh snapshot; returns the nodes. Old refs die here. */
    fun install(roots: List<SnapNode>): List<SnapNode> {
        generation += 1
        val map = HashMap<String, SnapNode>()
        fun walk(n: SnapNode) {
            map[n.ref] = n
            n.children.forEach(::walk)
        }
        roots.forEach(::walk)
        nodes = map
        return roots
    }

    fun resolve(ref: String): SnapNode? = nodes[ref]

    fun knownRefs(): Set<String> = nodes.keys
}

/** Minimal JSON parsing for the snapshot wire format (no org.json on JVM
 *  unit tests — hand-rolled for the flat shape the JS emits). */
object SnapshotWire {
    // [{"role":"button","name":"Save","ref":"@e1","children":[...]}]
    fun parse(json: String): List<SnapNode> {
        val t = Tokenizer(json.trim())
        if (t.peek() != '[') throw IllegalArgumentException("snapshot must be a JSON array")
        return t.array().map { tNode(it as Map<*, *>) }
    }

    private fun tNode(m: Map<*, *>): SnapNode {
        @Suppress("UNCHECKED_CAST")
        val kids = (m["children"] as? List<Map<*, *>> ?: emptyList()).map(::tNode)
        return SnapNode(
            role = m["role"] as? String ?: "unknown",
            name = m["name"] as? String ?: "",
            ref = m["ref"] as? String ?: "",
            children = kids,
        )
    }

    /** Tiny JSON reader: objects, arrays, strings, enough for snapshots. */
    private class Tokenizer(val s: String) {
        var i = 0
        fun peek(): Char = s[i]
        fun parse(): Any? {
            skip()
            return when (s[i]) {
                '{' -> obj()
                '[' -> array()
                '"' -> str()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> num()
            }
        }

        fun array(): List<Any?> {
            i++ // [
            val out = mutableListOf<Any?>()
            skip()
            if (s[i] == ']') { i++; return out }
            while (true) {
                out += parse()
                skip()
                if (s[i] == ']') { i++; return out }
                expect(",")
            }
        }

        private fun obj(): Map<String, Any?> {
            i++ // {
            val out = LinkedHashMap<String, Any?>()
            skip()
            if (s[i] == '}') { i++; return out }
            while (true) {
                skip()
                val k = str()
                skip(); expect(":")
                out[k] = parse()
                skip()
                if (s[i] == '}') { i++; return out }
                expect(",")
            }
        }

        private fun str(): String {
            i++ // "
            val sb = StringBuilder()
            while (s[i] != '"') {
                if (s[i] == '\\') {
                    i++
                    sb.append(when (s[i]) {
                        'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'
                        'u' -> { val h = s.substring(i + 1, i + 5); i += 4; h.toInt(16).toChar() }
                        else -> s[i]
                    })
                } else sb.append(s[i])
                i++
            }
            i++
            return sb.toString()
        }

        private fun num(): Double {
            val st = i
            while (i < s.length && s[i] !in ",:]} \n\r\t") i++
            return s.substring(st, i).toDouble()
        }

        private fun expect(lit: String) {
            if (!s.startsWith(lit, i)) throw IllegalArgumentException("bad snapshot json at $i")
            i += lit.length
        }

        private fun skip() { while (i < s.length && s[i].isWhitespace()) i++ }
    }
}
