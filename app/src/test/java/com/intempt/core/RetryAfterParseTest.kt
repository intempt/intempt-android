package com.intempt.core

import com.intempt.core.internal.InternalIntemptApi
import com.intempt.core.services.parseRetryAfterMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(InternalIntemptApi::class)
class RetryAfterParseTest {
    private val now = 1_700_000_000_000L // Tue, 14 Nov 2023 22:13:20 GMT

    @Test
    fun `seconds become milliseconds`() = assertEquals(120_000L, parseRetryAfterMs(" 120 ", now))

    @Test
    fun `an HTTP-date is the time until then`() {
        val ms = parseRetryAfterMs("Tue, 14 Nov 2023 22:14:00 GMT", now)
        assertEquals(40_000L, ms)
    }

    @Test
    fun `a date in the past is zero`() {
        val ms = parseRetryAfterMs("Tue, 14 Nov 2023 22:00:00 GMT", now)
        assertEquals(0L, ms)
    }

    @Test
    fun `absent or unparseable is null`() {
        assertNull(parseRetryAfterMs(null, now))
        assertNull(parseRetryAfterMs("", now))
        assertNull(parseRetryAfterMs("soon", now))
    }

    @Test
    fun `a negative number of seconds is zero`() = assertEquals(0L, parseRetryAfterMs("-5", now))
}
