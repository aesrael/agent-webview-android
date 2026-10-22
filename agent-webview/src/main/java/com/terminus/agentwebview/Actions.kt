package com.terminus.agentwebview

/** Agent actions. Pure Kotlin, no Android deps. Refs come from the latest
 *  snapshot; anything else is rejected before it touches the page. */
sealed class AgentAction {
    data class Click(val ref: String) : AgentAction()
    data class Fill(val ref: String, val text: String) : AgentAction()
    data class Press(val key: String) : AgentAction()
    data class Scroll(val dir: Dir) : AgentAction()

    enum class Dir { UP, DOWN }

    companion object {
        val PRESS_KEYS = setOf(
            "Enter", "Tab", "Escape", "ArrowDown", "ArrowUp",
            "ArrowLeft", "ArrowRight", "Backspace", "Go", "Search",
        )
    }
}

/** Wire form for transport to a remote brain / loop. */
fun AgentAction.toWire(): Map<String, String> = when (this) {
    is AgentAction.Click -> mapOf("type" to "click", "ref" to ref)
    is AgentAction.Fill -> mapOf("type" to "fill", "ref" to ref, "text" to text)
    is AgentAction.Press -> mapOf("type" to "press", "key" to key)
    is AgentAction.Scroll -> mapOf("type" to "scroll", "dir" to dir.name.lowercase())
}

/** Validate against live refs. Returns an error string, or null when
 *  the action may proceed. Never throws. */
fun AgentAction.validate(refs: RefTable): String? = when (this) {
    is AgentAction.Click -> if (refs.resolve(ref) == null) "stale ref $ref — re-snapshot first" else null
    is AgentAction.Fill -> when {
        refs.resolve(ref) == null -> "stale ref $ref — re-snapshot first"
        text.length > 4000 -> "text too long (max 4000 chars)"
        else -> null
    }
    is AgentAction.Press -> if (key !in AgentAction.PRESS_KEYS) "unsupported key $key" else null
    is AgentAction.Scroll -> null
}
