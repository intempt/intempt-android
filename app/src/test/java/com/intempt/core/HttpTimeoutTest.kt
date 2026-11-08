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
        val plugin = svc.client.plugin(HttpTimeout)
        // Read what the installed plugin actually holds, not the constants it was built from, so a
        // client wired with other values (or none) fails here.
        assertEquals(HttpManagerService.REQUEST_TIMEOUT_MS, longField(plugin, "requestTimeoutMillis"))
        assertEquals(HttpManagerService.CONNECT_TIMEOUT_MS, longField(plugin, "connectTimeoutMillis"))
        assertEquals(HttpManagerService.SOCKET_TIMEOUT_MS, longField(plugin, "socketTimeoutMillis"))
        // And pin the numbers themselves, so a change to a constant is a conscious one.
        assertEquals(30_000L, HttpManagerService.REQUEST_TIMEOUT_MS)
        assertEquals(10_000L, HttpManagerService.CONNECT_TIMEOUT_MS)
        assertEquals(30_000L, HttpManagerService.SOCKET_TIMEOUT_MS)
    }

    private fun longField(
        plugin: Any,
        name: String,
    ): Long? {
        val field =
            generateSequence<Class<*>>(plugin.javaClass) { it.superclass }
                .flatMap { it.declaredFields.asSequence() }
                .firstOrNull { it.name == name || it.name == "_$name" }
                ?: error("no field $name on ${plugin.javaClass}")
        field.isAccessible = true
        return field.get(plugin) as Long?
    }
}
