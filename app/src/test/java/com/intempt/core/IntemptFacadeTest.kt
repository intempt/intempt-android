package com.intempt.core

import android.view.View
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
     * A finding, pinned rather than fixed here because changing it changes public API behaviour.
     *
     * There is no intempt-config.json anywhere in this module — no assets directory at all — and
     * `initialize` still returns true. The credentials are read lazily on first use, so Dagger
     * wires up a core object successfully against a config that does not exist.
     *
     * The contract says "true when the SDK is running", and the reason it returns a Boolean at all
     * is that it previously returned Unit and a host app had no way to tell a working SDK from a
     * dead one. That signal is still not reliable: an app shipped without the config asset in some
     * build flavour gets `true`, reports itself healthy, and drops every event.
     *
     * Pinned as-is so the current behaviour is at least stated somewhere. Making initialize
     * validate the config eagerly is the fix, and it is an API behaviour change that belongs in
     * its own PR with Beso's call on it — not folded into a coverage branch.
     */
    @Test
    fun `initialize succeeds even with no config asset present`() {
        val started = Intempt.initialize(ApplicationProvider.getApplicationContext())

        assertTrue(
            "documenting current behaviour: initialize does not validate that credentials exist",
            started,
        )
        assertTrue("and the SDK then reports itself ready", Intempt.isInitialized)
    }

    /** Initializing twice must be a no-op rather than rebuilding the object graph. */
    @Test
    fun `initialize is safe to call more than once`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        assertTrue(Intempt.initialize(context))
        assertTrue("a repeated call must not report a different state", Intempt.initialize(context))
        assertTrue(Intempt.isInitialized)
    }

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
        Intempt.identify("user@example.com")
        Intempt.identify("user@example.com", "Signed up", mapOf("plan" to "pro"), mapOf("k" to "v"))
        Intempt.group("acct-1")
        Intempt.group("acct-1", "Joined", mapOf("tier" to "enterprise"))
        Intempt.track("Viewed", mapOf("screen" to "home"))
        Intempt.record("Custom")
        Intempt.record("Custom", "acct-1", "user@example.com", mapOf("a" to "b"), mapOf("c" to "d"), mapOf("e" to "f"))
        Intempt.alias("user@example.com", "user-2")
        Intempt.consent("granted", 1_800_000_000_000L)
        Intempt.consent("granted", 1_800_000_000_000L, "user@example.com", "why", "marketing")
        Intempt.productAdd("21", 2)
        Intempt.productOrdered(listOf(mapOf("productId" to "21", "quantity" to 1)))
        Intempt.productView("21")
        Intempt.logOut()
        Intempt.doNotCaptureText(View(ApplicationProvider.getApplicationContext()))
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
        Intempt.Tracking.start()
        Intempt.Tracking.stop()
    }

    /**
     * A query against a dead SDK has to answer something. False is the safe answer for both: a
     * host app branching on these would otherwise be told a feature is on while nothing is
     * recording it.
     */
    @Test
    fun `the toggles report disabled rather than throwing when uninitialized`() {
        assertFalse(Intempt.Logging.isLoggingEnabled())
        assertFalse(Intempt.Tracking.isTrackingEnabled())
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
        Intempt.track(huge, mapOf(huge to huge))
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
        Intempt.track("购买 🎉", mapOf("категория" to "тест"))
        Intempt.group("مجموعة")
    }

    /**
     * A consent timestamp in the past is a legitimate call — a customer expiring consent
     * immediately — and must not be treated as an error at the facade.
     */
    @Test
    fun `a past or zero consent expiry does not throw when uninitialized`() {
        Intempt.consent("revoked", 0L)
        Intempt.consent("revoked", -1L)
    }
}
