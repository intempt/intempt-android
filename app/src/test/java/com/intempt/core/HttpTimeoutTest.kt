package com.intempt.core

import com.intempt.core.internal.InternalIntemptApi
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.HttpManagerService
import com.intempt.core.services.LoggerManagerService
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.plugin
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** INT-3902: the shared ktor client must carry connect/request/socket timeouts. */
@OptIn(InternalIntemptApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HttpTimeoutTest {
    @Test
    fun clientInstallsHttpTimeoutWithExpectedLimits() {
        val svc = HttpManagerService(mock<ConfigManagerService>(), mock<LoggerManagerService>())
        // plugin() throws IllegalStateException if HttpTimeout is not installed.
        svc.client.plugin(HttpTimeout)
        assertEquals(30_000L, HttpManagerService.REQUEST_TIMEOUT_MS)
        assertEquals(10_000L, HttpManagerService.CONNECT_TIMEOUT_MS)
        assertEquals(30_000L, HttpManagerService.SOCKET_TIMEOUT_MS)
    }
}
