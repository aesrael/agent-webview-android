package com.terminus.agentwebview

import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps credentials out of the model's context. The model writes
 * `<secret:github_password>` in a Fill action; the session substitutes the real
 * value just before it reaches the page, and [redact] scrubs any occurrence of a
 * secret value from everything that flows back (snapshots, events, traces).
 */
class SecretVault {
    private val secrets = ConcurrentHashMap<String, String>()

    fun put(name: String, value: String) {
        require(NAME.matches(name)) { "secret name must match [A-Za-z0-9_.-]+" }
        secrets[name] = value
    }

    fun remove(name: String) { secrets.remove(name) }

    fun names(): Set<String> = secrets.keys.toSet()

    /** Replaces `<secret:name>` placeholders. Unknown names throw UNKNOWN_SECRET. */
    fun substitute(text: String): String {
        if (!text.contains("<secret:")) return text
        return PLACEHOLDER.replace(text) { m ->
            val name = m.groupValues[1]
            secrets[name] ?: throw AgentException(ErrorCode.UNKNOWN_SECRET, "no secret named '$name'")
        }
    }

    /** Replaces literal secret values (length >= 4) with their placeholder. */
    fun redact(text: String): String {
        if (secrets.isEmpty() || text.isEmpty()) return text
        var out = text
        for ((name, value) in secrets.entries.sortedByDescending { it.value.length }) {
            if (value.length >= MIN_REDACT_LEN) out = out.replace(value, "<secret:$name>")
        }
        return out
    }

    companion object {
        private const val MIN_REDACT_LEN = 4
        private val NAME = Regex("[A-Za-z0-9_.-]+")
        private val PLACEHOLDER = Regex("<secret:([A-Za-z0-9_.-]+)>")
    }
}
