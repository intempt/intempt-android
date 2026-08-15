package com.intempt.core.types

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The small contract types: [Product], [ConsentAction], [IntemptError] and the capture options.
 *
 * All four sat at **zero mutation coverage** — 7, 3, 11 and 0 live mutants respectively. Small
 * types are the ones that get written and never tested, and these four decide, in order: whether a
 * completed order is sent, whether a consent decision is understood, what an integrator is told
 * when something is refused, and how many events the SDK emits unasked.
 *
 * Deliberately free of Android and of the filesystem so `:mutation` can run it.
 */
class ContractTypesTest {
    // ------------------------------------------------------------------- Product

    @Test
    fun `a well-formed product line has no problems`() {
        assertTrue(Product("sku-1", 2).problems().isEmpty())
    }

    /** A view has no quantity, which is why the field is nullable rather than defaulted to 1. */
    @Test
    fun `a product with no quantity is valid`() {
        assertTrue(Product("sku-1").problems().isEmpty())
        assertNull(Product("sku-1").quantity)
    }

    @Test
    fun `a blank product id is refused`() {
        assertTrue(Product("", 1).problems().any { it.contains("productId") })
        assertTrue(Product("   ", 1).problems().any { it.contains("productId") })
    }

    /**
     * Zero and negative quantities are refused, and the message says what was passed.
     *
     * Zero especially: it reads as "none ordered", which is not an order, and the old
     * `List<Map<String, Any>>` shape silently dropped the whole call for it.
     */
    @Test
    fun `a non-positive quantity is refused and quoted`() {
        assertTrue(Product("sku-1", 0).problems().any { it.contains("0") })
        assertTrue(Product("sku-1", -3).problems().any { it.contains("-3") })
        assertTrue(Product("sku-1", 1).problems().isEmpty())
    }

    @Test
    fun `both problems are reported together`() {
        assertEquals(2, Product("", 0).problems().size)
    }

    // ------------------------------------------------------------- ConsentAction

    /**
     * The wire values are what `/consents/data` expects and must not follow the constant names.
     *
     * Renaming `ACCEPT` would otherwise change the payload silently, which for a consent record is
     * a compliance failure with no error attached.
     */
    @Test
    fun `wire values are stable and lowercase`() {
        assertEquals("accept", ConsentAction.ACCEPT.wireValue)
        assertEquals("reject", ConsentAction.REJECT.wireValue)
        assertEquals(2, ConsentAction.entries.size)
    }

    @Test
    fun `parsing accepts the wire values in any case, with padding`() {
        assertEquals(ConsentAction.ACCEPT, ConsentAction.fromWireValue("accept"))
        assertEquals(ConsentAction.ACCEPT, ConsentAction.fromWireValue("ACCEPT"))
        assertEquals(ConsentAction.ACCEPT, ConsentAction.fromWireValue("  Accept  "))
        assertEquals(ConsentAction.REJECT, ConsentAction.fromWireValue("reject"))
    }

    /**
     * An unrecognised value is null, not a default.
     *
     * Defaulting either way is the failure: to `ACCEPT` and a typo silently grants consent, to
     * `REJECT` and it silently withdraws it. Null makes the caller decide.
     */
    @Test
    fun `an unrecognised action does not parse`() {
        assertNull(ConsentAction.fromWireValue("granted"))
        assertNull(ConsentAction.fromWireValue(""))
        assertNull(ConsentAction.fromWireValue("  "))
        assertNull(ConsentAction.fromWireValue("accepted"))
    }

    // -------------------------------------------------------------- IntemptError

    /** Every case says something specific. A message that is only a class name helps nobody. */
    @Test
    fun `every error case carries a distinct, non-empty message`() {
        val errors =
            listOf(
                IntemptError.MalformedApiKey(31),
                IntemptError.MissingConfiguration("apiKey"),
                IntemptError.InvalidPropertyValue("score"),
                IntemptError.MissingIdentity("userId"),
                IntemptError.EncodingFailed("cycle"),
                IntemptError.Terminal(401),
                IntemptError.Retryable(429, 30_000L),
                IntemptError.Transport("connection reset"),
                IntemptError.StorageUnavailable("disk full"),
                IntemptError.Server(422, listOf("bad payload")),
                IntemptError.OptedOut("track"),
                IntemptError.ForbiddenEventName("identify"),
            )

        errors.forEach { assertTrue("${it::class.java.simpleName} has an empty message", it.message.isNotBlank()) }
        assertEquals("messages must not be interchangeable", errors.size, errors.map { it.message }.toSet().size)
    }

    /** The detail each case exists to carry actually reaches the message. */
    @Test
    fun `each case reports its own detail`() {
        assertTrue(IntemptError.MalformedApiKey(31).message.contains("31"))
        assertTrue(IntemptError.MissingConfiguration("sourceId").message.contains("sourceId"))
        assertTrue(IntemptError.InvalidPropertyValue("score").message.contains("score"))
        assertTrue(IntemptError.MissingIdentity("userId").message.contains("userId"))
        assertTrue(IntemptError.EncodingFailed("cycle").message.contains("cycle"))
        assertTrue(IntemptError.Terminal(401).message.contains("401"))
        assertTrue(IntemptError.Transport("reset").message.contains("reset"))
        assertTrue(IntemptError.StorageUnavailable("disk full").message.contains("disk full"))
        assertTrue(IntemptError.OptedOut("track").message.contains("track"))
        assertTrue(IntemptError.ForbiddenEventName("identify").message.contains("identify"))
    }

    @Test
    fun `server errors list every message the server sent`() {
        val message = IntemptError.Server(422, listOf("first problem", "second problem")).message

        assertTrue(message.contains("first problem"))
        assertTrue(message.contains("second problem"))
        assertTrue(message.contains("422"))
    }

    /**
     * `Retry-After` is seconds on the wire and milliseconds everywhere inside this SDK.
     *
     * A host app comparing the two would be off by a factor of a thousand in whichever direction
     * nobody tested — which is how the header came to be discarded on every 5xx before.
     */
    @Test
    fun `retryable reports milliseconds, and says something useful without them`() {
        assertTrue(IntemptError.Retryable(503, 30_000L).message.contains("30000"))
        assertTrue(IntemptError.Retryable(503, null).message.contains("backoff"))
        assertEquals(30_000L, IntemptError.Retryable(503, 30_000L).retryAfterMillis)
        assertNull(IntemptError.Retryable(503).retryAfterMillis)
    }

    /** `toString` names the case, so a log line is readable without unwrapping it. */
    @Test
    fun `toString names the case and includes the message`() {
        val error = IntemptError.Terminal(401)

        assertTrue("$error".contains("Terminal"))
        assertTrue("$error".contains(error.message))
    }

    // ------------------------------------------------------------ capture options

    /**
     * The contract's defaults, pinned.
     *
     * The SDK emitted all three families unconditionally, so an app that wanted sessions also got
     * an event on every foreground/background transition. Flipping either of the two `false`
     * defaults back to `true` is an event-volume change nobody would review as one.
     */
    @Test
    fun `automatic event defaults are sessions only`() {
        val defaults = AutomaticEventsOptions()

        assertTrue("sessions are the one family on by default", defaults.sessions)
        assertFalse("version changes must be opt-in", defaults.versionChanges)
        assertFalse("app state changes must be opt-in", defaults.appStateChanges)
    }

    @Test
    fun `autocapture defaults are on, since starting it is already the opt-in`() {
        val defaults = AutocaptureOptions()

        assertTrue(defaults.screenViews)
        assertTrue(defaults.controlInteractions)
        assertTrue(defaults.captureText)
    }

    @Test
    fun `each capture option is independently settable`() {
        val onlyScreens = AutocaptureOptions(screenViews = true, controlInteractions = false, captureText = false)

        assertTrue(onlyScreens.screenViews)
        assertFalse(onlyScreens.controlInteractions)
        assertFalse(onlyScreens.captureText)

        val onlyVersions = AutomaticEventsOptions(sessions = false, versionChanges = true, appStateChanges = false)

        assertFalse(onlyVersions.sessions)
        assertTrue(onlyVersions.versionChanges)
        assertFalse(onlyVersions.appStateChanges)
    }

    /**
     * The default feed field set is compact, and that is load-bearing.
     *
     * An unfielded request returns every catalog column including raw ML embedding vectors —
     * 222,919 bytes against 503 for the same 10 products. A default that grew to "everything" would
     * be a 443x payload nobody asked for.
     */
    @Test
    fun `the default feed field set is neither empty nor everything`() {
        assertTrue("empty means the server picks, which is 'all'", FeedFields.DEFAULT.isNotEmpty())
        assertTrue("the default must stay compact", FeedFields.DEFAULT.size <= 5)
    }
}
