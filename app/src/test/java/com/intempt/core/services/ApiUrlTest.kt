package com.intempt.core.services

import com.intempt.core.types.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * INT-5178. `apiUrl` used to be read from intempt-config.json only, and any string at all was
 * accepted: a typo'd scheme or a plain-http production host went straight into every request
 * URL. These pin the rule: https anywhere, http only to a loopback host, anything else refused
 * at initialize, and never a silent fall back to production.
 */
class ApiUrlTest {
    @Test
    fun `an https url is accepted and its trailing slash dropped`() {
        assertEquals("https://api.staging.intempt.com", ApiUrl.validate("https://api.staging.intempt.com/"))
        assertEquals("HTTPS://example.com:8443/prefix", ApiUrl.validate("HTTPS://example.com:8443/prefix/"))
    }

    @Test
    fun `http is accepted for every loopback host`() {
        listOf(
            "http://localhost:8080",
            "http://127.0.0.1:3000",
            "http://[::1]:3000",
            "http://10.0.2.2:8080",
            "HTTP://LOCALHOST",
        ).forEach { url -> assertEquals(url.trimEnd('/'), ApiUrl.validate(url)) }
    }

    @Test
    fun `http to a non-loopback host is refused`() {
        listOf(
            "http://api.intempt.com",
            "http://localhost.evil.example",
            "http://192.168.1.10:8080",
            "http://127.0.0.1.evil.example",
        ).forEach { url ->
            assertThrows("$url must be refused", IllegalArgumentException::class.java) { ApiUrl.validate(url) }
        }
    }

    @Test
    fun `garbage and other schemes are refused`() {
        listOf(
            "api.intempt.com",
            "not a url",
            "ftp://api.intempt.com",
            "https://",
            "https:///v1",
            "javascript:alert(1)",
            "https://user:pass@api.intempt.com",
            "https://api.intempt.com?x=1",
        ).forEach { url ->
            assertThrows("$url must be refused", IllegalArgumentException::class.java) { ApiUrl.validate(url) }
        }
    }

    @Test
    fun `the runtime option overrides the config file`() {
        assertEquals(
            "https://runtime.example",
            ApiUrl.resolve(runtime = "https://runtime.example", asset = "https://asset.example"),
        )
    }

    @Test
    fun `the config file applies when no runtime option is set`() {
        assertEquals("https://asset.example", ApiUrl.resolve(runtime = null, asset = "https://asset.example"))
        assertEquals("https://asset.example", ApiUrl.resolve(runtime = "  ", asset = "https://asset.example"))
    }

    @Test
    fun `production is the default only when nothing is set`() {
        assertEquals(Constants.API_URL, ApiUrl.resolve(runtime = null, asset = null))
        assertEquals(Constants.API_URL, ApiUrl.resolve(runtime = "", asset = ""))
    }

    @Test
    fun `an invalid value never falls back to production`() {
        assertThrows(IllegalArgumentException::class.java) {
            ApiUrl.resolve(runtime = "http://evil.example", asset = "https://asset.example")
        }
        assertThrows(IllegalArgumentException::class.java) { ApiUrl.resolve(runtime = null, asset = "nonsense") }
    }
}
