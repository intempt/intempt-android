@file:OptIn(com.intempt.core.internal.InternalIntemptApi::class)

package com.intempt.core

import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.intempt.core.types.ConsentAction
import com.intempt.core.types.FeedFields
import com.intempt.core.types.IntemptCredentials
import com.intempt.core.types.IntemptRuntimeOptions
import com.intempt.core.types.IntemptValue
import com.intempt.core.types.Product
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        // `instances`, not `intemptCore`: the facade holds a registry of named instances as of
        // 3.0, not a single core reference. Reflection is still unavoidable — there is no reset on
        // a public SDK facade and there should not be one just to satisfy a test — but the field
        // name is now load-bearing, so it is asserted rather than assumed. A silently missing
        // field would make every test in this class fail in setUp, which is what it did.
        val field = Intempt::class.java.getDeclaredField("instances")
        field.isAccessible = true
        (field.get(Intempt) as MutableMap<*, *>).clear()
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
     * Concurrent initialize() calls produce at most one instance.
     *
     * The registry is a ConcurrentHashMap, which makes the map safe and says nothing about what is
     * built to put in it. An earlier version constructed the whole Dagger graph before taking any
     * lock, so a losing thread had already started a `DeliveryMessages` HandlerThread and an event
     * collector before discovering it had lost — and then dropped them on the floor. Construction
     * now happens under the lock.
     *
     * Asserted on the registry's size rather than on threads, because a thread leak is not
     * observable from here; a second registration would be, and it is the same race.
     */
    @Test
    fun `concurrent initialize calls register at most one instance`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val threads = 8
        val start = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(threads)
        val results = java.util.Collections.synchronizedList(mutableListOf<Boolean>())

        repeat(threads) {
            Thread {
                start.await()
                results += Intempt.initialize(context)
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue("threads did not finish", done.await(30, java.util.concurrent.TimeUnit.SECONDS))

        // There is no config asset in this module, so every call must refuse — identically, from
        // every thread. A race that produced a half-built instance would show up as a mixed result.
        assertEquals("every concurrent call must agree", 1, results.toSet().size)
        assertFalse("no instance can exist without credentials", Intempt.isInitialized)
        assertNull(Intempt.mainInstance())
    }

    /**
     * A second initialize for the same name returns the first instance rather than replacing it.
     *
     * Replacing it would orphan the first graph — its queue, its HandlerThread and its collector —
     * while callers holding the old reference kept writing to storage nothing would ever flush.
     */
    @Test
    fun `initializing the same name twice returns the same instance`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        // Both refuse here (no config asset), which is still the property under test: the second
        // call must not leave a different registry state than the first.
        val first = Intempt.initialize(context, null, "tenant-a")
        val second = Intempt.initialize(context, null, "tenant-a")

        assertEquals(first, second)
        assertNull("a refused initialize must register nothing", Intempt.instance("tenant-a"))
    }

    /**
     * The runtime options bag must survive every hop from the public overload to the config.
     *
     * The four tests that cover this option construct ConfigManagerService directly, so they
     * prove the option is *read* and prove nothing about it being *delivered*. Between
     * `initialize(context, credentials, name, options)` and that constructor sit start, build,
     * buildTraced and the Dagger module -- four places to drop an argument, none of them covered.
     * Dropping it at any one of them leaves all four passing.
     *
     * This asserts the opposite of the default. `geolocation defaults to enabled` pins that an
     * unconfigured instance reads true, so false here can only come from the bag actually
     * arriving -- the assertion cannot be satisfied by the value that was already there.
     *
     * Runtime credentials, not an asset, because this module has no assets directory: they
     * populate the same four fields isConfigured reads, which is what lets an instance start here
     * at all.
     */
    @Test
    fun `runtime options reach the config through every hop of initialize`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        val instance =
            Intempt.initialize(
                context,
                IntemptCredentials("id.secret", "org", "proj", "src"),
                "geo-forwarding",
                IntemptRuntimeOptions(useIpAddressForGeolocation = false),
            )

        assertNotNull("valid runtime credentials must produce an instance without an asset", instance)
        assertFalse(
            "the option was dropped somewhere between initialize and the config; the instance is " +
                "running with geolocation on while the caller asked for it off",
            instance!!.core.config.useIpAddressForGeolocation,
        )
        assertTrue(
            "and it must reach the wire, not just the config object",
            instance.core.config.eventsUrl.endsWith("?ip=0"),
        )
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
    fun `products returns null rather than throwing when uninitialized`() =
        runBlocking {
            assertNull(
                "a feed lookup with no SDK behind it is absent, not an error",
                Intempt.products("feed-5292", 4, listOf("id"), null),
            )

            // The defaulted arities too — @JvmOverloads generates them, and each is a separate
            // entry point that has its own chance to have missed the null-core guard.
            assertNull(Intempt.products("feed-5292"))
            assertNull(Intempt.products("feed-5292", 4))
        }

    /**
     * The default field set is compact, and that is load-bearing rather than tidy.
     *
     * An unfielded feed request returns every catalog column including raw ML embedding vectors —
     * 222,919 bytes against 503 for the same 10 products. A default that grew to "everything"
     * would be a 443x payload nobody asked for, so the contract forbids it and this pins it.
     */
    @Test
    fun `the default feed field set is not everything`() {
        assertTrue("a default of no fields means the server picks, which is 'all'", FeedFields.DEFAULT.isNotEmpty())
        assertTrue("the default must stay compact", FeedFields.DEFAULT.size <= 5)
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
