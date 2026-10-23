package com.terminus.agentwebview

import java.net.URI

/**
 * Checks the URL an app uses to reach its agent server (the "remote link"). The link carries a bearer
 * token and every page the agent reads, so cleartext over the public internet is never acceptable.
 * Everything else is allowed: HTTPS anywhere, and plain HTTP where the network itself is private
 * (loopback, the emulator host, RFC1918 LANs, Tailscale/CGNAT, `.ts.net`, `.local`), because a
 * VPN/tailnet or home LAN is how most self-hosted setups run.
 */
object LinkSecurity {
    enum class Verdict { OK, OK_PRIVATE_PLAINTEXT, REFUSE_PUBLIC_PLAINTEXT, INVALID }

    data class Result(val verdict: Verdict, val message: String) {
        /** Whether the app may connect. */
        val allowed: Boolean get() = verdict == Verdict.OK || verdict == Verdict.OK_PRIVATE_PLAINTEXT
    }

    fun check(serverUrl: String): Result {
        val uri = try { URI(serverUrl.trim()) } catch (_: Exception) { null }
        val scheme = uri?.scheme?.lowercase()
        val host = uri?.host?.lowercase()?.trimEnd('.')
        if (uri == null || host == null || (scheme != "http" && scheme != "https")) {
            return Result(Verdict.INVALID, "Server URL must start with http:// or https://")
        }
        if (scheme == "https") return Result(Verdict.OK, "Encrypted connection")
        return if (isPrivateNetworkHost(host)) {
            Result(Verdict.OK_PRIVATE_PLAINTEXT, "Plain HTTP on a private network ($host); fine on a LAN or tailnet, not elsewhere")
        } else {
            Result(
                Verdict.REFUSE_PUBLIC_PLAINTEXT,
                "Refusing plain HTTP to $host: the access token and page contents would cross the internet unencrypted. Use https:// or your tailnet address.",
            )
        }
    }

    /** True when traffic to [host] stays on a network the user controls or an encrypted overlay. */
    fun isPrivateNetworkHost(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".ts.net") ||
            h.endsWith(".lan") || h.endsWith(".internal") || h.endsWith(".home.arpa")
        ) return true
        if (h == "10.0.2.2") return true // Android emulator -> host machine
        if (h.contains(':')) {
            val b = Hosts.parseIpv6(h.removePrefix("[").removeSuffix("]")) ?: return false
            val tailscaleV6 = b[0] == 0xFD.toByte() && b[1] == 0x7A.toByte() && b[2] == 0x11.toByte() && b[3] == 0x5C.toByte() &&
                b[4] == 0xA1.toByte() && b[5] == 0xE0.toByte() // fd7a:115c:a1e0::/48
            return tailscaleV6 || Hosts.privateAddressReason(b) != null
        }
        val v4 = Hosts.parseIpv4Lenient(h) ?: return !h.contains('.') // single-label names are intranet names
        val ok = Hosts.privateAddressReason(
            byteArrayOf((v4 shr 24).toByte(), (v4 shr 16).toByte(), (v4 shr 8).toByte(), v4.toByte()),
        )
        return ok != null // loopback, RFC1918, link-local, 100.64.0.0/10 (Tailscale) all count as private
    }
}
