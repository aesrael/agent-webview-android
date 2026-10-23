package com.terminus.agentwebview

/**
 * One node of the accessibility-style snapshot tree.
 * Interactive nodes carry a ref (`@e12`); text, headings and landmarks do not.
 */
data class SnapNode(
    val role: String,
    val name: String,
    val ref: String = "",
    val children: List<SnapNode> = emptyList(),
    val value: String? = null,
    val href: String? = null,
    /** e.g. disabled, checked, expanded, required, focused, offscreen, password, otp, cc, sensitive */
    val states: Set<String> = emptySet(),
    val level: Int? = null,
    val options: List<String> = emptyList(),
) {
    val isInteractive: Boolean get() = ref.isNotEmpty()
}

data class FrameInfo(val src: String, val title: String = "", val crossOrigin: Boolean = false)

data class PageSnapshot(
    val url: String,
    val title: String,
    val nodes: List<SnapNode>,
    val truncated: Boolean = false,
    val scrollY: Int = 0,
    val scrollHeight: Int = 0,
    val viewportHeight: Int = 0,
    val frames: List<FrameInfo> = emptyList(),
    /** First ref index the next snapshot may use. Refs are never reused within a tab. */
    val nextIndex: Int = 1,
) {
    fun allNodes(): Sequence<SnapNode> = sequence {
        suspend fun SequenceScope<SnapNode>.walk(n: SnapNode) {
            yield(n)
            n.children.forEach { walk(it) }
        }
        for (n in nodes) walk(n)
    }
}

/**
 * Ref table. Refs (`@e1`, `@e2`, ...) are allocated from a counter that never
 * resets, so a ref from an older snapshot can never silently resolve to a
 * different element in a newer one: it is simply unknown, and acting on it is
 * refused with STALE_REF. [invalidate] drops all refs (navigation, handoff).
 */
class RefTable {
    private val lock = Any()
    private var nodes: Map<String, SnapNode> = emptyMap()
    private var next = 1
    private var gen = 0

    /** Bumped on every install/invalidate; reports use it to tell whether refs died. */
    val generation: Int get() = synchronized(lock) { gen }

    /** Index the next snapshot must start numbering from. */
    fun nextIndex(): Int = synchronized(lock) { next }

    fun install(snapshot: PageSnapshot): PageSnapshot = synchronized(lock) {
        val map = HashMap<String, SnapNode>()
        fun walk(n: SnapNode) {
            if (n.isInteractive) map[n.ref] = n
            n.children.forEach(::walk)
        }
        snapshot.nodes.forEach(::walk)
        nodes = map
        next = maxOf(next, snapshot.nextIndex)
        gen += 1
        snapshot
    }

    /** Test/legacy helper: install bare roots. */
    fun install(roots: List<SnapNode>): List<SnapNode> {
        install(PageSnapshot("", "", roots, nextIndex = roots.maxOfOrNull { highestIndex(it) + 1 } ?: 1))
        return roots
    }

    fun invalidate() = synchronized(lock) {
        nodes = emptyMap()
        gen += 1
    }

    fun resolve(ref: String): SnapNode? = synchronized(lock) { nodes[ref] }

    fun knownRefs(): Set<String> = synchronized(lock) { nodes.keys.toSet() }

    private fun highestIndex(n: SnapNode): Int {
        val own = n.ref.removePrefix("@e").toIntOrNull() ?: 0
        return maxOf(own, n.children.maxOfOrNull { highestIndex(it) } ?: 0)
    }
}

/** Decoding of the snapshot wire format produced by `snapshot.js`. */
object SnapshotWire {
    fun parse(json: String): PageSnapshot = fromValue(Json.parse(json))

    fun fromValue(v: Any?): PageSnapshot = when (v) {
        is Map<*, *> -> fromMap(v)
        is List<*> -> PageSnapshot("", "", v.mapNotNull { (it as? Map<*, *>)?.let(::node) })
        else -> throw IllegalArgumentException("snapshot must be a JSON object or array")
    }

    fun fromMap(m: Map<*, *>): PageSnapshot = PageSnapshot(
        url = m.string("url") ?: "",
        title = m.string("title") ?: "",
        nodes = (m.list("nodes") ?: emptyList<Any?>()).mapNotNull { (it as? Map<*, *>)?.let(::node) },
        truncated = m.bool("truncated") ?: false,
        scrollY = m.int("scrollY") ?: 0,
        scrollHeight = m.int("scrollHeight") ?: 0,
        viewportHeight = m.int("viewportHeight") ?: 0,
        frames = (m.list("frames") ?: emptyList<Any?>()).mapNotNull { f ->
            (f as? Map<*, *>)?.let {
                FrameInfo(it.string("src") ?: "", it.string("title") ?: "", it.bool("crossOrigin") ?: false)
            }
        },
        nextIndex = m.int("nextIndex") ?: 1,
    )

    private fun node(m: Map<*, *>): SnapNode = SnapNode(
        role = m.string("role") ?: "unknown",
        name = m.string("name") ?: "",
        ref = m.string("ref") ?: "",
        children = (m.list("children") ?: emptyList<Any?>()).mapNotNull { (it as? Map<*, *>)?.let(::node) },
        value = m.string("value"),
        href = m.string("href"),
        states = (m.list("states") ?: emptyList<Any?>()).mapNotNull { it as? String }.toSet(),
        level = m.int("level"),
        options = (m.list("options") ?: emptyList<Any?>()).mapNotNull { it as? String },
    )
}

/** Compact text rendering of a snapshot: what the model actually reads. */
object SnapshotText {
    fun render(s: PageSnapshot, maxChars: Int = Int.MAX_VALUE): String {
        val lines = ArrayList<String>()
        lines += "url: ${s.url}"
        lines += "title: ${s.title}"
        if (s.scrollHeight > 0) {
            lines += "scroll: ${s.scrollY}/${s.scrollHeight} (viewport ${s.viewportHeight})"
        }
        for (f in s.frames.filter { it.crossOrigin }) {
            lines += "note: cross-origin frame not readable (${f.src.take(80)})"
        }
        val origin = ORIGIN.find(s.url)?.value ?: ""
        var lastText = ""
        fun walk(n: SnapNode, depth: Int) {
            // Token savers, no capability lost: separator-only text ("|", "·")
            // and immediately repeated text carry nothing the model can use.
            if (n.role == "text") {
                if (n.name.none { it.isLetterOrDigit() } || n.name == lastText) return
                lastText = n.name
            } else lastText = ""
            lines += "  ".repeat(depth) + line(n, origin)
            n.children.forEach { walk(it, depth + 1) }
        }
        s.nodes.forEach { walk(it, 0) }
        if (s.truncated) lines += "… page truncated; scroll or narrow down to see more"
        return clip(lines, maxChars)
    }

    private val ORIGIN = Regex("^https?://[^/?#]+")

    /** Same-origin links print as paths; bare `#` and javascript: links print nothing. */
    private fun shortHref(href: String, origin: String): String? = when {
        href == "#" || href.startsWith("javascript:", ignoreCase = true) -> null
        origin.isNotEmpty() && href.startsWith(origin) -> href.removePrefix(origin).ifEmpty { "/" }
        else -> href
    }

    private fun line(n: SnapNode, origin: String = ""): String {
        if (n.role == "text") return "\"${n.name}\""
        val sb = StringBuilder()
        if (n.isInteractive) sb.append('[').append(n.ref).append("] ")
        sb.append(n.role)
        if (n.level != null) sb.append('(').append(n.level).append(')')
        if (n.name.isNotEmpty()) sb.append(" \"").append(n.name).append('"')
        if (n.value != null) sb.append(" value=\"").append(n.value).append('"')
        n.href?.let { h -> shortHref(h, origin)?.let { sb.append(" href=").append(it) } }
        if (n.options.isNotEmpty()) sb.append(" options=").append(n.options.joinToString("|", "[", "]"))
        if (n.states.isNotEmpty()) sb.append(" {").append(n.states.sorted().joinToString(",")).append('}')
        return sb.toString()
    }

    private fun clip(lines: List<String>, maxChars: Int): String {
        val sb = StringBuilder()
        var used = 0
        for ((i, l) in lines.withIndex()) {
            if (used + l.length + 1 > maxChars) {
                sb.append("… ${lines.size - i} more lines omitted (output limit)")
                return sb.toString()
            }
            sb.append(l).append('\n')
            used += l.length + 1
        }
        return sb.toString().trimEnd('\n')
    }
}
