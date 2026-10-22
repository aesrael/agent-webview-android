package com.terminus.agentwebview

import java.net.URI

/**
 * What the agent's browser may reach. The WebView runs on the phone's own
 * network, so by default we refuse non-web schemes and anything that is not on
 * the public internet (loopback, home/office LAN, link-local, cloud metadata):
 * a prompt-injected page must not be able to steer the agent into the router's
 * admin page or an intranet service.
 *
 * An allowlist is optional and off by default.
 *
 * Limitation: this inspects the URL, not DNS. A public hostname that resolves
 * to a private address is not caught here.
 */
data class NavigationPolicy(
    val allowedSchemes: Set<String> = setOf("http", "https"),
    val blockPrivateNetworks: Boolean = true,
    /** Exact hosts or `*.suffix` patterns. null = any public host. */
    val allowHosts: List<String>? = null,
    val denyHosts: List<String> = emptyList(),
    /** Single-label hosts like `router` or `nas` are intranet names; blocked by default. */
    val allowSingleLabelHosts: Boolean = false,
    val allowAboutBlank: Boolean = true,
    val allowCredentialsInUrl: Boolean = false,
) {
    data class Decision(val allowed: Boolean, val code: ErrorCode? = null, val reason: String? = null) {
        fun toError(): AgentError? = if (allowed) null else AgentError(code ?: ErrorCode.NAV_BLOCKED, reason ?: "blocked")
    }

    /** Main-frame navigation. */
    fun checkNavigation(url: String): Decision {
        val parsed = parse(url) ?: return deny("malformed or unsafe URL")
        if (parsed.scheme == "about") {
            return if (allowAboutBlank && url.trim().equals("about:blank", ignoreCase = true)) ALLOW
            else deny("scheme 'about' not allowed")
        }
        if (parsed.scheme !in allowedSchemes) return deny("scheme '${parsed.scheme}' not allowed")
        if (parsed.host == null) return deny("URL has no host")
        if (parsed.hasCredentials && !allowCredentialsInUrl) return deny("credentials in URL not allowed")
        val host = parsed.host
        if (blockPrivateNetworks) privateReason(host)?.let { return deny(it) }
        if (denyHosts.any { matches(it, host) }) return deny("host '$host' is denied")
        val allow = allowHosts
        if (allow != null && allow.none { matches(it, host) }) return deny("host '$host' is not on the allowlist")
        return ALLOW
    }

    /**
     * Subresource requests (images, fetch, XHR, iframes...). Only the private-network
     * rule applies: pages legitimately load third-party content, but a page must not be
     * able to poke at the user's LAN.
     */
    fun checkSubresource(url: String): Decision {
        if (!blockPrivateNetworks) return ALLOW
        val parsed = parse(url) ?: return ALLOW // data:, blob:, etc. are not network reachability
        if (parsed.scheme != "http" && parsed.scheme != "https") return ALLOW
        val host = parsed.host ?: return deny("URL has no host")
        privateReason(host)?.let { return deny(it) }
        return ALLOW
    }

    private fun deny(reason: String) = Decision(false, ErrorCode.NAV_BLOCKED, reason)

    private fun privateReason(host: String): String? = Hosts.privateReason(host, allowSingleLabelHosts)

    private fun matches(pattern: String, host: String): Boolean {
        val p = pattern.trim().lowercase().trimEnd('.')
        return if (p.startsWith("*.")) host.endsWith(p.substring(1)) else host == p
    }

    private class Parsed(val scheme: String, val host: String?, val hasCredentials: Boolean)

    // Fail closed: anything java.net.URI rejects (spaces, backslashes, control chars) is blocked,
    // which also removes most parser-differential tricks against the WebView's own URL parser.
    private fun parse(url: String): Parsed? {
        if (url.isBlank() || url.any { it.isISOControl() || it == ' ' || it == '\\' }) return null
        val uri = try { URI(url) } catch (_: Exception) { return null }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme == "about") return Parsed(scheme, null, false)
        val rawHost = uri.host ?: if (uri.rawAuthority != null) return null else null
        val host = rawHost?.lowercase()?.removePrefix("[")?.removeSuffix("]")?.trimEnd('.')
        return Parsed(scheme, host, uri.rawUserInfo != null)
    }

    companion object {
        private val ALLOW = Decision(true)
    }
}

/** Host classification: is this name or address on the public internet? */
object Hosts {
    private val PRIVATE_SUFFIXES = listOf(
        ".localhost", ".local", ".localdomain", ".internal", ".lan", ".home.arpa", ".intranet", ".corp", ".private",
    )

    /** Returns why [host] is not public, or null if it looks public. */
    fun privateReason(host: String, allowSingleLabel: Boolean = false): String? {
        val h = host.lowercase().trimEnd('.')
        if (h.isEmpty()) return "empty host"
        if (h.contains(':')) {
            val bytes = parseIpv6(h) ?: return "invalid IPv6 address"
            return if (isPrivateV6(bytes)) "private or reserved IPv6 address" else null
        }
        val lastLabel = h.substringAfterLast('.')
        if (isNumericLabel(lastLabel)) {
            val v4 = parseIpv4Lenient(h) ?: return "invalid IPv4 address"
            return if (isPrivateV4(v4)) "private or reserved IPv4 address" else null
        }
        if (h == "localhost" || PRIVATE_SUFFIXES.any { h.endsWith(it) }) return "local hostname"
        if (!h.contains('.') && !allowSingleLabel) return "single-label (intranet) hostname"
        return null
    }

    private fun isNumericLabel(s: String) =
        s.isNotEmpty() && (s.all { it.isDigit() } || (s.startsWith("0x") && s.length > 2 && s.drop(2).all { it.isHexDigit() }))

    private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f'

    /** WHATWG-style IPv4 parsing: 1-4 parts, each decimal, octal (leading 0) or hex (0x). */
    fun parseIpv4Lenient(host: String): Long? {
        val parts = host.split('.')
        if (parts.size > 4 || parts.any { it.isEmpty() }) return null
        val nums = ArrayList<Long>()
        for (p in parts) {
            val n = when {
                p.startsWith("0x") -> p.drop(2).toLongOrNull(16)
                p.length > 1 && p.startsWith("0") -> p.drop(1).toLongOrNull(8)
                else -> p.toLongOrNull(10)
            } ?: return null
            if (n < 0) return null
            nums += n
        }
        for (i in 0 until nums.size - 1) if (nums[i] > 255) return null
        val last = nums.last()
        var limit = 1L
        repeat(5 - nums.size) { limit *= 256 }
        if (last >= limit) return null
        var addr = last
        for (i in 0 until nums.size - 1) {
            var shift = 1L
            repeat(3 - i) { shift *= 256 }
            addr += nums[i] * shift
        }
        return addr
    }

    private fun isPrivateV4(a: Long): Boolean {
        fun inRange(base: Long, bits: Int): Boolean {
            val mask = (0xFFFFFFFFL shl (32 - bits)) and 0xFFFFFFFFL
            return (a and mask) == (base and mask)
        }
        fun ip(a1: Int, b: Int, c: Int, d: Int) = (a1.toLong() shl 24) or (b.toLong() shl 16) or (c.toLong() shl 8) or d.toLong()
        return inRange(ip(0, 0, 0, 0), 8) ||
            inRange(ip(10, 0, 0, 0), 8) ||
            inRange(ip(100, 64, 0, 0), 10) ||
            inRange(ip(127, 0, 0, 0), 8) ||
            inRange(ip(169, 254, 0, 0), 16) ||
            inRange(ip(172, 16, 0, 0), 12) ||
            inRange(ip(192, 0, 0, 0), 24) ||
            inRange(ip(192, 0, 2, 0), 24) ||
            inRange(ip(192, 88, 99, 0), 24) ||
            inRange(ip(192, 168, 0, 0), 16) ||
            inRange(ip(198, 18, 0, 0), 15) ||
            inRange(ip(198, 51, 100, 0), 24) ||
            inRange(ip(203, 0, 113, 0), 24) ||
            a >= ip(224, 0, 0, 0)
    }

    /** Parses an IPv6 literal (no brackets, no zone id) to 16 bytes, or null. */
    fun parseIpv6(s: String): ByteArray? {
        if (s.contains('%')) return null
        var text = s
        var tail: ByteArray? = null
        if (text.contains('.')) {
            val idx = text.lastIndexOf(':')
            if (idx < 0) return null
            val v4 = parseIpv4Lenient(text.substring(idx + 1)) ?: return null
            tail = byteArrayOf((v4 shr 24).toByte(), (v4 shr 16).toByte(), (v4 shr 8).toByte(), v4.toByte())
            text = text.substring(0, idx + 1) + "0:0"
        }
        val dbl = text.indexOf("::")
        if (dbl >= 0 && text.indexOf("::", dbl + 1) >= 0) return null
        fun groups(part: String): List<Int>? {
            if (part.isEmpty()) return emptyList()
            return part.split(':').map { g ->
                if (g.isEmpty() || g.length > 4) return null
                g.toIntOrNull(16) ?: return null
            }
        }
        val groups: List<Int> = if (dbl >= 0) {
            val head = groups(text.substring(0, dbl)) ?: return null
            val rest = groups(text.substring(dbl + 2)) ?: return null
            val missing = 8 - head.size - rest.size
            if (missing < 1) return null
            head + List(missing) { 0 } + rest
        } else {
            groups(text) ?: return null
        }
        if (groups.size != 8) return null
        val out = ByteArray(16)
        for (i in 0 until 8) {
            out[i * 2] = (groups[i] shr 8).toByte()
            out[i * 2 + 1] = groups[i].toByte()
        }
        if (tail != null) System.arraycopy(tail, 0, out, 12, 4)
        return out
    }

    private fun isPrivateV6(b: ByteArray): Boolean {
        fun u(i: Int) = b[i].toInt() and 0xFF
        val allZeroHead = (0 until 10).all { b[it].toInt() == 0 }
        val embeddedV4 = (u(12).toLong() shl 24) or (u(13).toLong() shl 16) or (u(14).toLong() shl 8) or u(15).toLong()
        if ((0 until 15).all { b[it].toInt() == 0 } && (u(15) == 0 || u(15) == 1)) return true // :: and ::1
        if (allZeroHead && u(10) == 0xFF && u(11) == 0xFF) return isPrivateV4(embeddedV4) // ::ffff:a.b.c.d
        if (u(0) == 0x00 && u(1) == 0x64 && u(2) == 0xFF && u(3) == 0x9B && (4 until 12).all { b[it].toInt() == 0 }) {
            return isPrivateV4(embeddedV4) // 64:ff9b::/96 NAT64
        }
        if (u(0) == 0x20 && u(1) == 0x02) { // 6to4 embeds the v4 address in bytes 2..5
            val v4 = (u(2).toLong() shl 24) or (u(3).toLong() shl 16) or (u(4).toLong() shl 8) or u(5).toLong()
            return isPrivateV4(v4)
        }
        if ((u(0) and 0xFE) == 0xFC) return true // fc00::/7 unique local
        if (u(0) == 0xFE && (u(1) and 0xC0) == 0x80) return true // fe80::/10 link-local
        if (u(0) == 0xFF) return true // multicast
        return false
    }
}
