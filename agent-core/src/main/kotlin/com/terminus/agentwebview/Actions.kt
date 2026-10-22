package com.terminus.agentwebview

/** Agent actions. Pure Kotlin, no Android deps. */
sealed class AgentAction {
    abstract val type: String

    data class Click(val ref: String) : AgentAction() { override val type get() = "click" }

    /** [text] may contain `<secret:name>` placeholders resolved by the session's SecretVault. */
    data class Fill(val ref: String, val text: String) : AgentAction() { override val type get() = "fill" }

    data class Press(val key: String, val modifiers: Set<String> = emptySet()) : AgentAction() {
        override val type get() = "press"
    }

    data class Scroll(val dir: Dir, val amount: Double = 0.8) : AgentAction() { override val type get() = "scroll" }
    data class ScrollTo(val ref: String) : AgentAction() { override val type get() = "scroll_to" }
    data class Select(val ref: String, val value: String) : AgentAction() { override val type get() = "select" }
    data class Check(val ref: String, val checked: Boolean) : AgentAction() { override val type get() = "check" }
    data class Navigate(val url: String) : AgentAction() { override val type get() = "navigate" }
    data class Wait(val ms: Long) : AgentAction() { override val type get() = "wait" }

    /** Gated: refused unless SessionConfig.allowEvaluate. */
    data class Evaluate(val js: String) : AgentAction() { override val type get() = "evaluate" }

    data object Back : AgentAction() { override val type get() = "back" }
    data object Forward : AgentAction() { override val type get() = "forward" }
    data object Reload : AgentAction() { override val type get() = "reload" }

    enum class Dir { UP, DOWN }

    companion object {
        const val MAX_TEXT = 4000
        const val MAX_WAIT_MS = 30_000L
        const val MAX_EVAL_CHARS = 20_000

        val PRESS_KEYS = setOf(
            "Enter", "Tab", "Escape", "ArrowDown", "ArrowUp", "ArrowLeft", "ArrowRight",
            "Backspace", "Delete", "Home", "End", "PageUp", "PageDown", "Space",
        )
        val MODIFIERS = setOf("Shift", "Control", "Alt", "Meta")
    }
}

/** The ref an action targets, if any. */
val AgentAction.refOrNull: String?
    get() = when (this) {
        is AgentAction.Click -> ref
        is AgentAction.Fill -> ref
        is AgentAction.Select -> ref
        is AgentAction.Check -> ref
        is AgentAction.ScrollTo -> ref
        else -> null
    }

/** Wire form for transport to a remote brain / loop. Round-trips through [parseAction]. */
fun AgentAction.toWire(): Map<String, Any?> = when (this) {
    is AgentAction.Click -> mapOf("type" to type, "ref" to ref)
    is AgentAction.Fill -> mapOf("type" to type, "ref" to ref, "text" to text)
    is AgentAction.Press ->
        if (modifiers.isEmpty()) mapOf("type" to type, "key" to key)
        else mapOf("type" to type, "key" to key, "modifiers" to modifiers.sorted())
    is AgentAction.Scroll -> mapOf("type" to type, "dir" to dir.name.lowercase(), "amount" to amount)
    is AgentAction.ScrollTo -> mapOf("type" to type, "ref" to ref)
    is AgentAction.Select -> mapOf("type" to type, "ref" to ref, "value" to value)
    is AgentAction.Check -> mapOf("type" to type, "ref" to ref, "checked" to checked)
    is AgentAction.Navigate -> mapOf("type" to type, "url" to url)
    is AgentAction.Wait -> mapOf("type" to type, "ms" to ms)
    is AgentAction.Evaluate -> mapOf("type" to type, "js" to js)
    AgentAction.Back, AgentAction.Forward, AgentAction.Reload -> mapOf("type" to type)
}

/** Parses the wire form. Throws [AgentException] (BAD_ACTION) on anything malformed. */
fun parseAction(m: Map<*, *>): AgentAction {
    fun bad(msg: String): Nothing = throw AgentException(ErrorCode.BAD_ACTION, msg)
    fun need(key: String): String = m.string(key) ?: bad("missing string field '$key'")
    return when (val t = m.string("type") ?: bad("missing 'type'")) {
        "click" -> AgentAction.Click(need("ref"))
        "fill" -> AgentAction.Fill(need("ref"), need("text"))
        "press" -> {
            val mods = (m.list("modifiers") ?: emptyList<Any?>()).map { it as? String ?: bad("modifiers must be strings") }
            AgentAction.Press(need("key"), mods.toSet())
        }
        "scroll" -> {
            val dir = when (need("dir").lowercase()) {
                "up" -> AgentAction.Dir.UP
                "down" -> AgentAction.Dir.DOWN
                else -> bad("dir must be 'up' or 'down'")
            }
            AgentAction.Scroll(dir, m.double("amount") ?: 0.8)
        }
        "scroll_to" -> AgentAction.ScrollTo(need("ref"))
        "select" -> AgentAction.Select(need("ref"), need("value"))
        "check" -> AgentAction.Check(need("ref"), m.bool("checked") ?: bad("missing boolean field 'checked'"))
        "navigate" -> AgentAction.Navigate(need("url"))
        "wait" -> AgentAction.Wait(m.long("ms") ?: bad("missing number field 'ms'"))
        "evaluate" -> AgentAction.Evaluate(need("js"))
        "back" -> AgentAction.Back
        "forward" -> AgentAction.Forward
        "reload" -> AgentAction.Reload
        else -> bad("unknown action type '$t'")
    }
}

/**
 * Validate against live refs. Returns an error, or null when the action may proceed.
 * Never throws. Policy checks (navigation, evaluate) live in the session.
 */
fun AgentAction.validate(refs: RefTable): AgentError? {
    fun stale(ref: String) = AgentError(ErrorCode.STALE_REF, "stale ref $ref — observe again first")
    return when (this) {
        is AgentAction.Click -> if (refs.resolve(ref) == null) stale(ref) else null
        is AgentAction.Fill -> when {
            refs.resolve(ref) == null -> stale(ref)
            text.length > AgentAction.MAX_TEXT ->
                AgentError(ErrorCode.TEXT_TOO_LONG, "text too long (max ${AgentAction.MAX_TEXT} chars)")
            else -> null
        }
        is AgentAction.Select -> if (refs.resolve(ref) == null) stale(ref) else null
        is AgentAction.Check -> if (refs.resolve(ref) == null) stale(ref) else null
        is AgentAction.ScrollTo -> if (refs.resolve(ref) == null) stale(ref) else null
        is AgentAction.Press -> when {
            key !in AgentAction.PRESS_KEYS && key.codePointCount(0, key.length) != 1 ->
                AgentError(ErrorCode.UNSUPPORTED_KEY, "unsupported key '$key'")
            !AgentAction.MODIFIERS.containsAll(modifiers) ->
                AgentError(ErrorCode.UNSUPPORTED_KEY, "unsupported modifier in $modifiers")
            else -> null
        }
        is AgentAction.Scroll ->
            if (amount.isNaN() || amount <= 0.0 || amount > 5.0)
                AgentError(ErrorCode.BAD_ACTION, "scroll amount must be in (0, 5]") else null
        is AgentAction.Wait ->
            if (ms < 0 || ms > AgentAction.MAX_WAIT_MS)
                AgentError(ErrorCode.BAD_ACTION, "wait must be 0..${AgentAction.MAX_WAIT_MS} ms") else null
        is AgentAction.Navigate ->
            if (url.isBlank()) AgentError(ErrorCode.BAD_ACTION, "empty url") else null
        is AgentAction.Evaluate ->
            if (js.isBlank() || js.length > AgentAction.MAX_EVAL_CHARS)
                AgentError(ErrorCode.BAD_ACTION, "js must be 1..${AgentAction.MAX_EVAL_CHARS} chars") else null
        AgentAction.Back, AgentAction.Forward, AgentAction.Reload -> null
    }
}
