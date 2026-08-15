package com.intempt.core.services

import com.intempt.core.types.IntemptError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

/**
 * The error listener, and the two ways a listener like this is usually wrong.
 *
 * It exists because `Boolean` answers *whether* a call was refused and never *why*: a `false` from
 * `track()` is equally an opt-out, a reserved event name, a NaN in the attributes and a full disk.
 */
class ErrorReporterTest {
    private fun reporter(): Pair<ErrorReporter, MutableList<IntemptError>> {
        val received = mutableListOf<IntemptError>()
        val reporter = ErrorReporter(mock(LoggerManagerService::class.java))
        reporter.setListener { received += it }
        return reporter to received
    }

    @Test
    fun `a reported error reaches the listener`() {
        val (reporter, received) = reporter()

        reporter.report(IntemptError.OptedOut("track"))

        assertEquals(1, received.size)
        assertTrue(received.first() is IntemptError.OptedOut)
    }

    /**
     * A listener that throws must not propagate.
     *
     * The listener runs inside `track()`, so an exception escaping here turns "we could not record
     * your event" into a crash in someone's checkout flow. An analytics SDK taking down its host is
     * the failure this whole facade is built to avoid, and a host app's own faulty callback is the
     * most likely way in.
     */
    @Test
    fun `a listener that throws does not propagate`() {
        val reporter = ErrorReporter(mock(LoggerManagerService::class.java))
        reporter.setListener { throw IllegalStateException("host app callback is broken") }

        reporter.report(IntemptError.Terminal(401))
    }

    @Test
    fun `clearing the listener stops delivery`() {
        val (reporter, received) = reporter()

        reporter.setListener(null)
        reporter.report(IntemptError.Transport("offline"))

        assertTrue("a cleared listener must receive nothing", received.isEmpty())
    }

    @Test
    fun `reporting with no listener set does not throw`() {
        ErrorReporter(mock(LoggerManagerService::class.java)).report(IntemptError.EncodingFailed("bad"))
    }

    /**
     * No error message may carry key material.
     *
     * `malformedAPIKey` reports a length rather than the key across every Intempt SDK, because an
     * error that quotes the credential puts it wherever the error goes — a crash reporter, a
     * support ticket, a screenshot. Asserted on the message rather than assumed from the type,
     * since the type could be given a `key` field tomorrow and nothing else would notice.
     */
    @Test
    fun `no error message contains key material`() {
        val secret = "sk-live-9d2f-DO-NOT-PRINT"
        val errors =
            listOf(
                IntemptError.MalformedApiKey(length = "keyid.$secret".length),
                IntemptError.MissingConfiguration("apiKey"),
                IntemptError.Terminal(401),
                IntemptError.Retryable(429, 30_000L),
                IntemptError.Transport("connection reset"),
                IntemptError.StorageUnavailable("disk full"),
                IntemptError.Server(422, listOf("bad payload")),
                IntemptError.InvalidPropertyValue("score"),
                IntemptError.MissingIdentity("userId"),
                IntemptError.EncodingFailed("cycle"),
                IntemptError.OptedOut("track"),
                IntemptError.ForbiddenEventName("identify"),
            )

        errors.forEach { error ->
            assertFalse("${error::class.java.simpleName} leaked the key: ${error.message}", error.message.contains(secret))
            assertFalse("${error::class.java.simpleName} leaked the key via toString", "$error".contains(secret))
        }

        // The length still has to be reported, or the case carries no information at all and the
        // assertion above would pass on an empty message.
        assertTrue(IntemptError.MalformedApiKey(31).message.contains("31"))
    }

    /**
     * Retryable states its delay in the same unit the retry scheduler uses.
     *
     * `Retry-After` is seconds on the wire and milliseconds everywhere inside this SDK. A host app
     * comparing the two would be off by a factor of a thousand in whichever direction nobody
     * tested — which is exactly how the header came to be discarded on every 5xx before.
     */
    @Test
    fun `retryable reports milliseconds`() {
        assertTrue(IntemptError.Retryable(503, 30_000L).message.contains("30000ms"))
        assertTrue(
            "no Retry-After must still say something actionable",
            IntemptError.Retryable(503, null).message.contains("backoff"),
        )
    }
}
