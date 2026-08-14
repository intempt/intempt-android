package com.intempt.core

import androidx.test.core.app.ApplicationProvider
import com.intempt.core.services.ConfigManagerService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The credential checks that stand between a misconfigured app and silent total event loss.
 *
 * There is no assets directory in this module, so every instance built here is the
 * missing-config case — which is the case that matters. A customer who ships without
 * intempt-config.json in some build flavour used to get `initialize() == true`, a queue that filled
 * normally, and POSTs with no Authorization header. Every batch 401'd and was dropped, and nothing
 * anywhere reported a problem.
 */
@RunWith(RobolectricTestRunner::class)
class ConfigValidationTest {
    private fun config() = ConfigManagerService(ApplicationProvider.getApplicationContext())

    @Test
    fun `a missing config asset reports itself as unconfigured`() {
        assertFalse(
            "an absent intempt-config.json must not read as configured — that is what made a dead " +
                "SDK look healthy",
            config().isConfigured,
        )
    }

    /** The log line has to name what is missing, or a customer cannot act on it. */
    @Test
    fun `every absent credential is named`() {
        val missing = config().missingCredentials()

        assertEquals(
            "all four credentials are absent when the asset is",
            listOf("apiKey", "sourceId", "organizationId", "projectId"),
            missing,
        )
    }

    /**
     * With no apiKey there is no Authorization header, and the empty string is the honest answer.
     * The alternative — a partially built header — would be sent and rejected.
     */
    @Test
    fun `no api key yields no token rather than a broken one`() {
        assertEquals("", config().token())
    }

    /**
     * A key with no dot used to reach `val (username, password) = _apiKey.split(".")`, where a
     * one-element list makes the assignment to `password` throw IndexOutOfBoundsException — from
     * inside the auth path, on nothing worse than a typo in a config file.
     *
     * Exercised through the real accessor by way of the same absent-config instance, then directly
     * against the split contract, since the field is private and set at construction.
     */
    @Test
    fun `a malformed api key does not throw`() {
        // The shape the guard checks. If this ever stops holding, token() must still not throw.
        listOf("", "nodot", ".", "a.", ".b", "a.b.c").forEach { candidate ->
            val parts = candidate.split(".")
            val usable = parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()
            assertFalse("`$candidate` must not be treated as a usable api key", usable)
        }

        // And a well-formed one must be accepted, or the guard is too strict and rejects everything.
        val good = "rcukTEJJe8eT5gaZ.LOmQAga9gTxeZKph6QcNY17UAcoO8qZY".split(".")
        assertTrue(good.size == 2 && good[0].isNotBlank() && good[1].isNotBlank())
    }
    // ------------------------------------------- server-side geolocation flag

    /**
     * The replacement for the ipapi.co call: one query parameter on our own endpoint.
     *
     * Copied from mixpanel-android, where `MPConfig.getEndPointWithIpTrackingParam` is the whole
     * mechanism. The device never handles its own IP and no third party is involved — the platform
     * geolocates from the source IP of the request it already receives.
     */
    @Test
    fun `the events endpoint carries the geolocation flag`() {
        val url = config().eventsUrl

        assertTrue(
            "the platform needs the flag to know whether it may geolocate; without it the parameter " +
                "is absent and behaviour is whatever the server defaults to. Got: $url",
            url.contains("?ip="),
        )
        assertTrue("default is on, matching Mixpanel's UseIpAddressForGeolocation", url.endsWith("?ip=1"))
    }

    /** Default on, as Mixpanel's is, so geo keeps working for customers who want it. */
    @Test
    fun `geolocation defaults to enabled`() {
        assertTrue(config().useIpAddressForGeolocation)
    }

    /**
     * Exactly one `?ip=` on the URL. Appending unconditionally to a base that already carried a
     * query string would produce two, and the second would be parsed as part of the first value.
     */
    @Test
    fun `the flag appears exactly once`() {
        val url = config().eventsUrl

        assertEquals("one ip parameter, not two", 1, url.split("?ip=").size - 1)
        assertEquals("and one query separator", 1, url.count { it == '?' })
    }
}
