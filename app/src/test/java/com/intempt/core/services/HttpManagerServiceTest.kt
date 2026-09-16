@file:OptIn(com.intempt.core.internal.InternalIntemptApi::class)

package com.intempt.core.services

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * INT-3907 — `get()` used to answer a failed request with `throw Exception()`.
 *
 * That is a new, empty exception: no type, no message, no cause. The caller learned only that
 * "something went wrong", and the one place the real reason existed was a log line. The fix
 * rethrows the caught exception itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class HttpManagerServiceTest {
    @Test
    fun `get rethrows the original failure instead of a bare Exception`() {
        val config = ConfigManagerService(RuntimeEnvironment.getApplication())
        val logger = spy(LoggerManagerService(config))
        val http = HttpManagerService(config, logger)

        // Port 1 is never listening, so the request fails inside the try block with a real,
        // specific exception rather than a manufactured one.
        val thrown =
            try {
                runBlocking { http.get("http://127.0.0.1:1/never-listening") }
                null
            } catch (e: Exception) {
                e
            }

        assertNotNull("a failed GET must not be swallowed", thrown)
        assertNotEquals(
            "a bare `throw Exception()` is exactly java.lang.Exception — the cause's type must survive",
            Exception::class.java,
            thrown!!.javaClass,
        )
        assertNotNull("`throw Exception()` carries a null message — the cause's message must survive", thrown.message)
        assertTrue("the message must say something", thrown.message!!.isNotBlank())

        // Identity, not merely shape: the exception the caller caught is the same one the
        // service logged. A freshly constructed exception could still be non-null and typed.
        val logged = argumentCaptor<String>()
        verify(logger, atLeastOnce()).error(logged.capture())
        assertEquals(
            "the caller must receive the very exception that was logged",
            "HttpService get request error: ${thrown.message}",
            logged.allValues.last(),
        )
    }
}
