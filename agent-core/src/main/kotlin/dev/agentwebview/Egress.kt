package dev.agentwebview

import java.net.URI

/** Web origins (`scheme://host[:port]`) as used for secret binding and frame checks. */
object Origins {
    /** Normalised origin of an http(s) URL, or null. Default ports are dropped. */
    fun of(url: String): String? {
        val u = try { URI(url.trim()) } catch (_: Exception) { return null }
        val scheme = u.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = u.host?.lowercase()?.trimEnd('.') ?: return null
        val def = if (scheme == "https") 443 else 80
        return if (u.port == -1 || u.port == def) "$scheme://$host" else "$scheme://$host:${u.port}"
    }

    /**
     * Pattern is an exact origin (`https://github.com`) or a subdomain wildcard
     * (`https://` followed by `*.github.com`, which does not match `https://github.com` itself).
     */
    fun matches(pattern: String, origin: String): Boolean {
        val p = pattern.trim().lowercase().trimEnd('/')
        val o = origin.trim().lowercase()
        if (!p.contains('*')) return normalize(p) == o
        val m = WILDCARD.matchEntire(p) ?: return false
        val (scheme, host, port) = m.destructured
        val oo = try { URI(o) } catch (_: Exception) { return false }
        if (oo.scheme != scheme) return false
        val oh = oo.host ?: return false
        val def = if (scheme == "https") 443 else 80
        val oPort = if (oo.port == -1) def else oo.port
        val pPort = if (port.isEmpty()) def else port.drop(1).toInt()
        return oPort == pPort && oh.endsWith(".$host")
    }

    fun matchesAny(patterns: Collection<String>, origin: String): Boolean = patterns.any { matches(it, origin) }

    /** Canonical form of an exact-origin pattern; wildcards are returned lower-cased as written. */
    fun normalize(pattern: String): String {
        val p = pattern.trim().lowercase().trimEnd('/')
        return if (p.contains('*')) p else (of(p) ?: p)
    }

    private val WILDCARD = Regex("^(https?)://\\*\\.([^/*:]+)(:\\d{1,5})?$")
}

enum class UrlQueryPolicy {
    /** Send URLs unchanged. */
    KEEP,
    /** Replace the value of credential-looking parameters (token, code, session, key, ...). */
    REDACT_SENSITIVE,
    /** Drop query strings and fragments entirely. */
    STRIP_ALL,
}

/**
 * What may leave the device. A cloud model sees everything the library returns, so every outgoing
 * string passes through [EgressFilter]. Defaults favour privacy over fidelity.
 */
data class EgressConfig(
    val urlQueryPolicy: UrlQueryPolicy = UrlQueryPolicy.REDACT_SENSITIVE,
    /** Luhn-valid 13-19 digit numbers become `[card]`. */
    val redactCardNumbers: Boolean = true,
    /** US-SSN-shaped numbers (ddd-dd-dddd) become `[id]`. */
    val redactNationalIds: Boolean = true,
    val redactEmails: Boolean = false,
    /**
     * Hosts the agent may never read: banking, health, email, anything you would not paste into a
     * chat. `example.com` or `*.example.com`. On these hosts `observe()` withholds the page and
     * hands control to the person; screenshots are refused.
     */
    val humanOnlyHosts: List<String> = emptyList(),
    /** Query parameter names (lower-case) treated as credentials, besides names containing token/secret/password/... */
    val sensitiveQueryKeys: Set<String> = setOf(
        "code", "auth", "authorization", "key", "sig", "sid", "jwt", "otp", "csrf", "xsrf", "session", "sessionid", "pwd",
    ),
)

class EgressFilter(private val cfg: EgressConfig, private val vault: SecretVault) {

    /** True for hosts listed in [EgressConfig.humanOnlyHosts]. */
    fun isHumanOnly(url: String): Boolean {
        if (cfg.humanOnlyHosts.isEmpty()) return false
        val host = try { URI(url.trim()).host?.lowercase()?.trimEnd('.') } catch (_: Exception) { null } ?: return false
        return cfg.humanOnlyHosts.any { hostMatches(it, host) }
    }

    private fun hostMatches(pattern: String, host: String): Boolean {
        val p = pattern.trim().lowercase().trimEnd('.')
        return if (p.startsWith("*.")) host.endsWith(p.substring(1)) else host == p
    }

    /** A URL with credentials removed according to [UrlQueryPolicy]; also strips `user:pass@`. */
    fun url(raw: String): String {
        if (raw.isEmpty()) return raw
        var s = vault.redact(raw)
        s = USERINFO.replace(s) { it.groupValues[1] }
        if (cfg.urlQueryPolicy == UrlQueryPolicy.KEEP) return s
        val hash = s.indexOf('#')
        val frag = if (hash >= 0) s.substring(hash + 1) else null
        val noFrag = if (hash >= 0) s.substring(0, hash) else s
        val q = noFrag.indexOf('?')
        val query = if (q >= 0) noFrag.substring(q + 1) else null
        val base = if (q >= 0) noFrag.substring(0, q) else noFrag
        val sb = StringBuilder(base)
        if (cfg.urlQueryPolicy == UrlQueryPolicy.STRIP_ALL) {
            if (query != null || frag != null) sb.append(if (query != null && frag != null) "?…#…" else if (query != null) "?…" else "#…")
            return sb.toString()
        }
        if (query != null) sb.append('?').append(redactPairs(query))
        if (frag != null) sb.append('#').append(if (frag.contains('=')) redactPairs(frag) else frag)
        return sb.toString()
    }

    private fun redactPairs(s: String): String =
        s.split('&').joinToString("&") { pair ->
            val eq = pair.indexOf('=')
            if (eq <= 0) pair else {
                val key = pair.substring(0, eq)
                if (sensitiveKey(key)) "$key=REDACTED" else pair
            }
        }

    private fun sensitiveKey(key: String): Boolean {
        val k = key.lowercase()
        return k in cfg.sensitiveQueryKeys || SENSITIVE_PARTS.any { k.contains(it) }
    }

    /** Free text: vault secrets, URLs inside the text, card numbers, national ids, optionally emails. */
    fun text(s: String): String {
        if (s.isEmpty()) return s
        var out = vault.redact(s)
        if (cfg.urlQueryPolicy != UrlQueryPolicy.KEEP || out.contains('@')) {
            out = URL_IN_TEXT.replace(out) { url(it.value) }
        }
        if (cfg.redactCardNumbers) out = CARD.replace(out) { if (luhnOk(it.value)) "[card]" else it.value }
        if (cfg.redactNationalIds) out = SSN.replace(out, "[id]")
        if (cfg.redactEmails) out = EMAIL.replace(out, "[email]")
        return out
    }

    fun snapshot(s: PageSnapshot): PageSnapshot {
        fun node(n: SnapNode): SnapNode = n.copy(
            name = text(n.name),
            value = n.value?.let(::text),
            href = n.href?.let(::url),
            options = n.options.map(::text),
            children = n.children.map(::node),
        )
        return s.copy(
            url = url(s.url), title = text(s.title), nodes = s.nodes.map(::node),
            frames = s.frames.map { it.copy(src = url(it.src), title = text(it.title)) },
        )
    }

    /** Sanitises every string in a JSON-ready structure. Keys in [skipKeys] are left alone (ids, base64). */
    fun wire(v: Any?, skipKeys: Set<String> = setOf("id", "data")): Any? = when (v) {
        is String -> text(v)
        is Map<*, *> -> v.entries.associate { (k, x) -> k.toString() to (if (k.toString() in skipKeys) x else wire(x, skipKeys)) }
        is List<*> -> v.map { wire(it, skipKeys) }
        else -> v
    }

    companion object {
        private val USERINFO = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*://)[^/@?#\\s]*@")
        private val URL_IN_TEXT = Regex("https?://[^\\s\"'<>)\\]]+")
        private val CARD = Regex("(?<!\\d)(?:\\d[ -]?){12,18}\\d(?!\\d)")
        private val SSN = Regex("(?<!\\d)\\d{3}-\\d{2}-\\d{4}(?!\\d)")
        private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
        private val SENSITIVE_PARTS = listOf("token", "secret", "password", "passwd", "apikey", "api_key", "signature")

        fun luhnOk(s: String): Boolean {
            val d = s.filter { it.isDigit() }
            if (d.length < 13 || d.length > 19) return false
            var sum = 0
            var alt = false
            for (i in d.length - 1 downTo 0) {
                var n = d[i] - '0'
                if (alt) { n *= 2; if (n > 9) n -= 9 }
                sum += n
                alt = !alt
            }
            return sum % 10 == 0
        }
    }
}

/**
 * Heuristic scan for text that looks aimed at an AI agent rather than a reader. It is a tripwire,
 * not a defence: attackers can rephrase. It exists so the harness can raise its guard.
 */
object InjectionHeuristics {
    private val PATTERNS = listOf(
        Regex("ignore (all |any |the )?(previous|prior|above|earlier) (instructions|prompts|messages)", RegexOption.IGNORE_CASE),
        Regex("disregard (all |any |the )?(previous|prior|above|earlier|your) (instructions|prompts|rules)", RegexOption.IGNORE_CASE),
        Regex("(new|updated|override) (system )?(instructions|prompt)\\s*:", RegexOption.IGNORE_CASE),
        Regex("you are now (an? |the )?(ai|assistant|agent|admin|root|dan|jailbroken|unrestricted|in developer mode)", RegexOption.IGNORE_CASE),
        Regex("(do not|don't|never) (tell|inform|mention|reveal|show)( (this|that|it))?( to)? the user", RegexOption.IGNORE_CASE),
        Regex("(send|forward|email|post|upload|exfiltrate) (all |the |your )?(cookies|credentials|passwords?|tokens?|secrets?|conversation|chat history|system prompt)", RegexOption.IGNORE_CASE),
        Regex("<\\s*/?\\s*(system|assistant|instructions?)\\s*>", RegexOption.IGNORE_CASE),
        Regex("\\bsystem prompt\\b", RegexOption.IGNORE_CASE),
        Regex("<secret:", RegexOption.IGNORE_CASE),
    )

    /** Up to three short excerpts of suspicious text found in [s]. */
    fun scan(s: PageSnapshot): List<String> {
        val hits = ArrayList<String>()
        for (n in s.allNodes()) {
            if (n.name.isEmpty()) continue
            for (p in PATTERNS) {
                val m = p.find(n.name) ?: continue
                hits += n.name.substring(maxOf(0, m.range.first - 10), minOf(n.name.length, m.range.last + 30)).trim()
                break
            }
            if (hits.size >= 3) break
        }
        return hits
    }
}

/** Sliding-window cap on how fast and how long an agent may act. Bounds a runaway or hijacked model. */
class ActionLimiter(private val maxTotal: Int, private val maxPerMinute: Int, private val clock: Clock) {
    private val lock = Any()
    private val recent = ArrayDeque<Long>()
    private var total = 0

    /** Returns an error if one more action would exceed a limit; otherwise records it. */
    fun tryAcquire(): AgentError? = synchronized(lock) {
        val now = clock.nowMs()
        while (recent.isNotEmpty() && now - recent.first() >= 60_000) recent.removeFirst()
        if (maxTotal > 0 && total >= maxTotal) {
            return AgentError(ErrorCode.LIMIT_EXCEEDED, "this session has used its $maxTotal-action budget")
        }
        if (maxPerMinute > 0 && recent.size >= maxPerMinute) {
            return AgentError(ErrorCode.LIMIT_EXCEEDED, "too many actions: limit is $maxPerMinute per minute")
        }
        recent.addLast(now)
        total++
        null
    }
}
