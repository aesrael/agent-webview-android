package dev.agentwebview

import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps credentials out of the model's context AND out of the wrong website.
 *
 * The model writes `<secret:github_password>` in a Fill action; the session substitutes the real
 * value just before it reaches the page, and [redact] scrubs any occurrence of a secret value from
 * everything that flows back (snapshots, events, reports).
 *
 * Every secret is bound to the origins it may be typed into. Without that, a page that tricks the
 * agent (prompt injection) could have it type the password into an attacker's form.
 */
class SecretVault {
    private class Entry(val value: String, val origins: Set<String>?)

    private val secrets = ConcurrentHashMap<String, Entry>()

    /**
     * Registers [value] for [name], usable only when the page (and the field's frame) is one of
     * [origins]: exact (`https://github.com`) or subdomain wildcard (`https://` followed by `*.github.com`).
     */
    fun put(name: String, value: String, origins: Set<String>) {
        require(NAME.matches(name)) { "secret name must match [A-Za-z0-9_.-]+" }
        require(origins.isNotEmpty()) { "give at least one origin, or call putForAnyOrigin() on purpose" }
        require(origins.all { it.startsWith("https://") || it.startsWith("http://") }) { "origins must start with http:// or https://" }
        secrets[name] = Entry(value, origins.map(Origins::normalize).toSet())
    }

    /** Explicit opt-out of origin binding. Only for secrets that are harmless if typed anywhere. */
    fun putForAnyOrigin(name: String, value: String) {
        require(NAME.matches(name)) { "secret name must match [A-Za-z0-9_.-]+" }
        secrets[name] = Entry(value, null)
    }

    fun remove(name: String) { secrets.remove(name) }

    fun names(): Set<String> = secrets.keys.toSet()

    /** Names of placeholders appearing in [text]. */
    fun referenced(text: String): List<String> = PLACEHOLDER.findAll(text).map { it.groupValues[1] }.toList()

    /**
     * Replaces `<secret:name>` placeholders. Throws UNKNOWN_SECRET for unknown names and
     * SECRET_ORIGIN_MISMATCH when [origin] (the page or frame being typed into) is not allowed for
     * a secret used in [text]. A null [origin] fails every origin-bound secret.
     */
    fun substitute(text: String, origin: String?): String {
        if (!text.contains("<secret:")) return text
        return PLACEHOLDER.replace(text) { m ->
            val name = m.groupValues[1]
            val e = secrets[name] ?: throw AgentException(ErrorCode.UNKNOWN_SECRET, "no secret named '$name'")
            val allowed = e.origins
            if (allowed != null && (origin == null || !Origins.matchesAny(allowed, origin))) {
                throw AgentException(
                    ErrorCode.SECRET_ORIGIN_MISMATCH,
                    "secret '$name' may not be used on ${origin ?: "this page"}",
                )
            }
            e.value
        }
    }

    /** Replaces literal secret values (length >= 4) with their placeholder. */
    fun redact(text: String): String {
        if (secrets.isEmpty() || text.isEmpty()) return text
        var out = text
        for ((name, e) in secrets.entries.sortedByDescending { it.value.value.length }) {
            if (e.value.length >= MIN_REDACT_LEN) out = out.replace(e.value, "<secret:$name>")
        }
        return out
    }

    companion object {
        private const val MIN_REDACT_LEN = 4
        private val NAME = Regex("[A-Za-z0-9_.-]+")
        private val PLACEHOLDER = Regex("<secret:([A-Za-z0-9_.-]+)>")
    }
}
