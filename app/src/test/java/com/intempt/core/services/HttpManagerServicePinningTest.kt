@file:OptIn(com.intempt.core.internal.InternalIntemptApi::class)

package com.intempt.core.services

import io.ktor.client.engine.android.AndroidEngineConfig
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.net.ssl.HttpsURLConnection

/**
 * INT-3945. `certificatePins` used to reach only the event queue's transport. The Ktor client in
 * [HttpManagerService] — consent, optimization, feeds and the push webhook, all carrying the
 * Basic write key — was built with no engine config at all, so a host app that turned pinning on
 * got it for events and silently not for anything else. Pinning stays opt-in: with no pins the
 * client must not touch the connection's TLS setup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class HttpManagerServicePinningTest {
    private fun clientFor(pins: List<String>): AndroidEngineConfig {
        val config = mock<ConfigManagerService>()
        whenever(config.certificatePins).thenReturn(pins)
        val http = HttpManagerService(config, mock())
        return http.client.engine.config as AndroidEngineConfig
    }

    @Test
    fun `the Ktor client applies configured certificate pins to every HTTPS connection`() {
        val engine = clientFor(listOf(PIN))
        val connection = mock<HttpsURLConnection>()

        engine.sslManager(connection)

        verify(connection).sslSocketFactory = any()
    }

    @Test
    fun `with no pins the Ktor client keeps platform-default TLS`() {
        val engine = clientFor(emptyList())
        val connection = mock<HttpsURLConnection>()

        engine.sslManager(connection)

        verifyNoInteractions(connection)
        assertTrue(true)
    }

    private companion object {
        const val PIN = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    }
}
