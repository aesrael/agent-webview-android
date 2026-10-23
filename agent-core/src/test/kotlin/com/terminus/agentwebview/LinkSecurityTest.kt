package com.terminus.agentwebview

import org.junit.Assert.*
import org.junit.Test

class LinkSecurityTest {
    private fun ok(u: String) = LinkSecurity.check(u).allowed
    @Test fun `https anywhere and plain http on private networks are fine`() {
        assertTrue(ok("https://terminus.example.com"))
        assertTrue(ok("http://10.0.2.2:9009"))
        assertTrue(ok("http://192.168.1.20:9009"))
        assertTrue(ok("http://100.101.102.103:9009")) // Tailscale CGNAT
        assertTrue(ok("http://my-box.tail1234.ts.net:9009"))
        assertTrue(ok("http://localhost:9009"))
        assertTrue(ok("http://[fd7a:115c:a1e0::1]:9009"))
    }
    @Test fun `plain http over the public internet is refused`() {
        assertFalse(ok("http://203.0.113.9:9009".replace("203.0.113", "8.8.8")))
        assertFalse(ok("http://example.com"))
        assertEquals(LinkSecurity.Verdict.REFUSE_PUBLIC_PLAINTEXT, LinkSecurity.check("http://example.com").verdict)
    }
    @Test fun `garbage is invalid`() {
        assertEquals(LinkSecurity.Verdict.INVALID, LinkSecurity.check("ftp://x").verdict)
        assertEquals(LinkSecurity.Verdict.INVALID, LinkSecurity.check("").verdict)
    }
    @Test fun `denied hosts stay denied for subresources too`() {
        val p = NavigationPolicy(denyHosts = listOf("my-box.tail1234.ts.net"))
        assertFalse(p.checkSubresource("https://my-box.tail1234.ts.net/api/x").allowed)
        assertTrue(p.checkSubresource("https://cdn.example.com/a.js").allowed)
        assertFalse(p.checkNavigation("https://my-box.tail1234.ts.net/").allowed)
    }
}
