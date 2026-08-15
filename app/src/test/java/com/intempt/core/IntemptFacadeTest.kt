package com.intempt.core

import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.intempt.core.types.ConsentAction
import com.intempt.core.types.IntemptCredentials
import com.intempt.core.types.IntemptValue
import com.intempt.core.types.Product
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The single property a public SDK facade has to hold: **calling anything before `initialize()`
 * must never crash the host app.**
 *
 * This is what an SDK actually gets blamed for. A customer wires up a call site, ships, and the
 * initialize happens later than they thought — or not at all, because the config asset is missing
 * in a build flavour. Every entry point then runs against a null core. If any one of them throws,
 * it throws inside the host's Activity, and the crash report says Intempt.
 *
 * The facade measured 18.8% line coverage, so most of these entry points had never been called
 * from a test at all — including in the uninitialized state, which is the state a misconfigured
 * app spends its entire life in.
 *
 * `Intempt` is a Kotlin `object`, so its state outlives a test. Each test resets the private core
 * reference reflectively, otherwise a test that initializes it would silently make every later
 * test pass for the wrong reason.
 */
@RunWith(RobolectricTestRunner::class)
class IntemptFacadeTest {
    @Before
    fun setUp() = resetCore()

    @After
    fun tearDown() = resetCore()

    /**
     * Clears the singleton's core reference. Reflection is unavoidable here — there is no reset
     * on a public SDK facade, and there should not be one just to satisfy a test.
     */
    private fun resetCore() {
        val field = Intempt::class.java.getDeclaredField("intemptCore")
        field.isAccessible = true
        field.set(Intempt, null)
    }

    // ------------------------------------------------------------------- state

    @Test
    fun `an uninitialized sdk reports itself as uninitialized`() {
        assertFalse(
            "isInitialized is how a host app tells a working SDK from a dead one",
            Intempt.isInitialized,
        )
    }

    /**
     * A missing config asset must be reported, not swallowed.
     *
     * <p>This test used to assert the opposite, pinning the bug: credentials are read lazily, so
     * Dagger wired up happily against an intempt-config.json that does not exist and initialize()
     * returned true. An app shipped without the asset in some build flavour was told it was
     * healthy, queued events, and posted them with no Authorization header — every batch 401'd and
     * was dropped, with no failure anywhere.
     *
     * <p>The whole reason initialize() returns a Boolean is that it used to return Unit and a host
     * app had no way to tell a working SDK from a dead one. Returning true here made that signal a
     * lie. initialize() now checks that all four credentials were resolved.
     *
     * <p>There is no assets directory in this module, which is what makes this the real
     * misconfigured-customer case rather than a simulation of one.
     */
    @Test
    fun `initialize fails when the config asset is missing`() {
        val started = Intempt.initialize(ApplicationProvider.getApplicationContext())

        assertFalse("a missing config must be reported, not swallowed", started)
        assertFalse("a failed initialize must not leave the SDK looking ready", Intempt.isInitialized)
    }

    /** A failed initialize must be repeatable without throwing, and must keep reporting failure. */
    @Test
    fun `a failed initialize can be retried and still reports failure`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        assertFalse(Intempt.initialize(context))
        assertFalse("the second attempt must not report success", Intempt.initialize(context))
        assertFalse(Intempt.isInitialized)
    }

    // The once-only guard and the toggle round-trip both need a genuinely initialized SDK, which
    // needs a real config asset. They live in sample/src/androidTest/SdkOnDeviceTest.kt, where the
    // host app has one — see `initializeIsIdempotent` and `theTogglesReflectRealStateOnDevice`.
    // Asserting them here against an SDK that cannot initialize would pass vacuously: assertSame
    // on two nulls succeeds, and assertFalse on a toggle is true whether the guard works or the
    // value is hardcoded.

    // ------------------------------------------------- every entry point, unarmed

    /**
     * The whole surface, called against a null core. Any throw here is a crash in the customer's
     * app, so the assertion is simply that the block completes.
     *
     * Listed one call per line rather than driven from a loop on purpose: a loop over reflection
     * would keep passing when a new method is added, and a new method is exactly the thing most
     * likely to forget the guard.
     */
    @Test
    fun `no entry point throws when the sdk was never initialized`() {
        val attrs = IntemptValue.mapOf(mapOf("plan" to "pro", "seats" to 5, "trial" to false))

        Intempt.identify("user@example.com")
        Intempt.identify("user@example.com", "Signed up", attrs, attrs)
        Intempt.group("acct-1")
        Intempt.group("acct-1", "Joined", attrs)
        Intempt.track("Viewed")
        Intempt.track("Viewed", attrs)
        Intempt.record("Custom")
        Intempt.record("Custom", "user@example.com", "acct-1", attrs, attrs, attrs)
        Intempt.alias("user@example.com", "user-2")
        Intempt.consent(ConsentAction.ACCEPT, 1_800_000_000_000L)
        Intempt.consent(ConsentAction.REJECT, 1_800_000_000_000L, "user@example.com", "why", "marketing")
        Intempt.productAdd("21", 2)
        Intempt.productOrdered(listOf(Product("21", 1)))
        Intempt.productView("21")
        Intempt.logOut()
        Intempt.reset()
        Intempt.optIn()
        Intempt.optOut()
        Intempt.flush()
        Intempt.flush { }
        Intempt.doNotCaptureText(View(ApplicationProvider.getApplicationContext()))
    }

    /**
     * The readers added in 3.0. Each has to answer something rather than throw, and the answer has
     * to be the one a host app can branch on safely: "" for an identifier it does not have, false
     * for a capability that is not running, 0 for a timer that is not scheduled.
     */
    @Test
    fun `the 3_0 readers answer safely when the sdk was never initialized`() {
        assertEquals("", Intempt.getProfileId())
        assertEquals("", Intempt.getSessionId())
        assertFalse("a dead SDK is not opted in", Intempt.isOptedIn())
        assertFalse("hasOptedOut must not claim an opt-out that was never recorded", Intempt.hasOptedOut())
        assertEquals(0, Intempt.flushInterval)

        // The setter has the same obligation as the getter, and is the half most likely to have
        // dereferenced the core without a guard.
        Intempt.flushInterval = 30
        assertEquals("a write against a dead SDK must not be reported as stored", 0, Intempt.flushInterval)
    }

    /**
     * Runtime credentials are refused before Dagger is touched when they are malformed.
     *
     * A key without its `<id>.<secret>` separator used to reach the auth path and throw
     * IndexOutOfBoundsException from inside it — a typo in a credential crashing the host app.
     */
    @Test
    fun `initialize refuses malformed runtime credentials without throwing`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        assertFalse(Intempt.initialize(context, IntemptCredentials("no-separator", "org", "proj", "src")))
        assertFalse(Intempt.initialize(context, IntemptCredentials("", "org", "proj", "src")))
        assertFalse(Intempt.initialize(context, IntemptCredentials("id.secret", "", "proj", "src")))
        assertFalse(Intempt.isInitialized)
    }

    /** The suspend entry point has the same obligation, and must return null rather than throw. */
    @Test
    fun `recommendation returns null rather than throwing when uninitialized`() =
        runBlocking {
            assertNull(
                "a recommendation with no SDK behind it is absent, not an error",
                Intempt.recommendation("feed-5292", 4, listOf("id"), null),
            )
        }

    /**
     * The nested toggles are a separate object each, so they have their own guard and their own
     * chance to have missed it.
     */
    @Test
    fun `the logging and tracking toggles are no-ops when uninitialized`() {
        Intempt.Logging.start()
        Intempt.Logging.stop()
        Intempt.optIn()
        Intempt.optOut()
    }

    /**
     * A query against a dead SDK has to answer something. False is the safe answer for both: a
     * host app branching on these would otherwise be told a feature is on while nothing is
     * recording it.
     *
     * <p>On its own this cannot distinguish the guard from a hardcoded `false`; the initialized
     * case above is what supplies that half.
     */
    @Test
    fun `the toggles report disabled rather than throwing when uninitialized`() {
        assertFalse(Intempt.Logging.isLoggingEnabled())
        assertFalse(Intempt.isOptedIn())
    }

    // ------------------------------------------------------------- hostile input

    /**
     * Empty and oversized identifiers reach the facade from customer code, and the guard has to
     * hold for them too — a null core plus a strange argument is still a null core.
     */
    @Test
    fun `empty and oversized arguments do not throw when uninitialized`() {
        val huge = "x".repeat(100_000)

        Intempt.identify("")
        Intempt.identify(huge)
        Intempt.track("", emptyMap())
        Intempt.track(huge, IntemptValue.mapOf(mapOf(huge to huge)))
        Intempt.group("")
        Intempt.alias("", "")
        Intempt.productAdd("", 0)
        Intempt.productAdd("21", -1)
        Intempt.productOrdered(emptyList())
        Intempt.productView("")
    }

    /** Unicode identifiers must not throw either — email addresses and account names carry them. */
    @Test
    fun `unicode arguments do not throw when uninitialized`() {
        Intempt.identify("ünïcødé@example.com")
        Intempt.track("购买 🎉", IntemptValue.mapOf(mapOf("категория" to "тест")))
        Intempt.group("مجموعة")
    }

    /**
     * A consent timestamp in the past is a legitimate call — a customer expiring consent
     * immediately — and must not be treated as an error at the facade.
     */
    @Test
    fun `a past or zero consent expiry does not throw when uninitialized`() {
        Intempt.consent(ConsentAction.REJECT, 0L)
        Intempt.consent(ConsentAction.REJECT, -1L)
    }
}
