@file:OptIn(com.intempt.core.internal.InternalIntemptApi::class)

package com.intempt.core

import com.intempt.core.types.FlagContext
import com.intempt.core.types.FlagErrorCode
import com.intempt.core.types.FlagReason
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * INT-7455 then S11: a key outside `^[a-zA-Z0-9_-]+$` never reaches the server, which would answer
 * 400. It used to throw; PLAN §3 makes every flag read fail safe, so it now returns the default with
 * reason ERROR and FLAG_NOT_FOUND, sending nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FlagKeyValidationTest {
    private val server = StubChooseServer(200 to """{"key":"a_b-1","value":"v","variationId":1}""")

    @After
    fun tearDown() = server.close()

    @Test
    fun `a key outside the server's pattern returns the default and sends nothing`(): Unit =
        runBlocking {
            val flags = flagsServiceAt(server.flagsBase)
            for (key in listOf("", "a.b", "a b", "a/b", "ключ")) {
                val detail = flags.detail(key, FlagContext(), "fallback")
                assertEquals(key, "fallback", detail.value)
                assertEquals(key, FlagReason.ERROR, detail.reason)
                assertEquals(key, FlagErrorCode.FLAG_NOT_FOUND, detail.errorCode)
            }
            assertTrue(server.paths.isEmpty())
        }

    @Test
    fun `a key inside the pattern is looked up`(): Unit =
        runBlocking {
            assertEquals("v", flagsServiceAt(server.flagsBase).detail("a_b-1", FlagContext(), "d").value)
            assertEquals(1, server.bodies.size)
        }
}
