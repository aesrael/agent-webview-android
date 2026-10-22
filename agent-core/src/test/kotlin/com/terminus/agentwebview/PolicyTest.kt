package com.terminus.agentwebview

import org.junit.Assert.*
import org.junit.Test

class PolicyTest {
    private val p = NavigationPolicy()
    private fun ok(u: String) = assertTrue("should allow $u", p.checkNavigation(u).allowed)
    private fun no(u: String, policy: NavigationPolicy = p) =
        assertFalse("should block $u", policy.checkNavigation(u).allowed)

    @Test fun `public web is allowed`() {
        ok("https://example.com/a?b=1#c"); ok("http://example.com"); ok("https://sub.example.co.uk:8443/x")
        ok("https://8.8.8.8/"); ok("https://[2606:4700:4700::1111]/"); ok("about:blank")
    }

    @Test fun `non web schemes are blocked`() {
        for (u in listOf("file:///etc/passwd", "javascript:alert(1)", "intent://x#Intent;scheme=http;end", "content://x/y",
            "data:text/html,hi", "ftp://example.com", "market://details?id=x", "tel:123", "about:srcdoc", "chrome://settings"))
            no(u)
    }

    @Test fun `loopback and private ranges are blocked`() {
        for (u in listOf("http://localhost/", "http://127.0.0.1/", "http://127.1/", "http://0.0.0.0/", "http://10.0.0.5/",
            "http://172.16.0.1/", "http://172.31.255.255/", "http://192.168.1.1/", "http://169.254.169.254/latest/meta-data",
            "http://100.64.0.1/", "http://[::1]/", "http://[fe80::1]/", "http://[fc00::1]/", "http://224.0.0.1/",
            "http://foo.localhost/", "http://printer.local/", "http://nas.internal/", "http://router/"))
            no(u)
        ok("http://172.32.0.1/"); ok("http://11.0.0.1/")
    }

    @Test fun `numeric IPv4 tricks are blocked`() {
        for (u in listOf("http://2130706433/", "http://0x7f.0.0.1/", "http://0x7f000001/", "http://0177.0.0.1/", "http://127.0.1/",
            "http://3232235777/", "http://0xc0.0xa8.1.1/"))
            no(u)
    }

    @Test fun `mapped and embedded IPv6 are blocked`() {
        for (u in listOf("http://[::ffff:127.0.0.1]/", "http://[::ffff:7f00:1]/", "http://[::ffff:10.0.0.1]/",
            "http://[64:ff9b::7f00:1]/", "http://[2002:7f00:1::1]/", "http://[::]/"))
            no(u)
        ok("http://[::ffff:8.8.8.8]/")
    }

    @Test fun `trailing dot case and credentials`() {
        no("http://LOCALHOST./"); no("http://127.0.0.1./")
        no("https://user:pw@example.com/")
        assertTrue(NavigationPolicy(allowCredentialsInUrl = true).checkNavigation("https://user@example.com/").allowed)
    }

    @Test fun `malformed or ambiguous URLs fail closed`() {
        for (u in listOf("", " ", "http://", "http:///x", "https://exa mple.com", "https://example.com\\@127.0.0.1/",
            "http://exa\nmple.com", "//example.com", "example.com", "http://[::1", "http://[zz]/"))
            no(u)
    }

    @Test fun `allowlist and denylist`() {
        val a = NavigationPolicy(allowHosts = listOf("example.com", "*.shop.test"))
        assertTrue(a.checkNavigation("https://example.com/").allowed)
        assertTrue(a.checkNavigation("https://a.shop.test/").allowed)
        no("https://evil.com/", a); no("https://notexample.com/", a); no("https://shop.test.evil.com/", a)
        val d = NavigationPolicy(denyHosts = listOf("*.ads.test"))
        no("https://x.ads.test/", d); assertTrue(d.checkNavigation("https://example.com").allowed)
    }

    @Test fun `private network protection can be disabled for tests`() {
        val open = NavigationPolicy(blockPrivateNetworks = false)
        assertTrue(open.checkNavigation("http://localhost:8080/").allowed)
        assertTrue(open.checkSubresource("http://192.168.1.1/x").allowed)
    }

    @Test fun `subresources only block private networks`() {
        assertTrue(p.checkSubresource("https://cdn.example.net/x.js").allowed)
        assertTrue(p.checkSubresource("data:image/png;base64,AAAA").allowed)
        assertTrue(p.checkSubresource("blob:https://example.com/uuid").allowed)
        assertFalse(p.checkSubresource("http://192.168.0.1/admin").allowed)
        assertFalse(p.checkSubresource("http://localhost:3000/x").allowed)
    }

    @Test fun `decision converts to error`() {
        assertEquals(ErrorCode.NAV_BLOCKED, p.checkNavigation("file:///x").toError()?.code)
        assertNull(p.checkNavigation("https://example.com").toError())
    }
}
