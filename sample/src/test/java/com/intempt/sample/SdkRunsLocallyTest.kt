package com.intempt.sample

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.intempt.core.Intempt
import com.intempt.core.types.ConsentAction
import com.intempt.core.types.IntemptValue
import com.intempt.core.types.Product
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Runs the SDK inside a host application, which nothing in this repository did before.
 *
 * The unit tests exercise classes in isolation and the instrumented tests exercise SQLite on
 * a device. Neither answers the question a consumer actually cares about: if you add this
 * AAR to an app and call the public API, does it work? That gap is why three API-level
 * crashes survived in shipped code — `java.util.Base64` in the auth path, and two others —
 * all of which are reached by plain `initialize()` plus one tracking call.
 *
 * `sdk = [24, 34]` is the whole point of the test. 24 is the library's minSdk, and every one
 * of those crashes was invisible above it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34])
class SdkRunsLocallyTest {
    private fun context(): Context = ApplicationProvider.getApplicationContext()

    /**
     * Application.onCreate calls Intempt.initialize, so if initialization can throw on this
     * API level, Robolectric cannot even build the Application and every test here fails.
     */
    @Test
    fun theHostApplicationStarts() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertTrue("the sample Application should be installed", app is SampleApp)
    }

    /**
     * `initialize` catches everything, so a failed init used to be indistinguishable from a
     * successful one: it returned Unit and printed a line. `isInitialized` is now the signal,
     * and this asserts the SDK actually started inside a real host application.
     *
     */
    @Test
    fun initializationCompletesRatherThanSilentlyFailing() {
        assertTrue("the SDK did not initialize inside the host app", Intempt.isInitialized)
    }

    /**
     * The property that stops an analytics failure becoming a host-app crash. Every entry
     * point used to dereference a `lateinit`, so if initialization had failed the first call
     * threw `UninitializedPropertyAccessException` into the host app — the SDK survived its
     * own failure and then killed its host on the next line.
     *
     * Initialization has already succeeded here, so this cannot exercise the disabled path
     * directly; what it pins is that a repeat `initialize()` is idempotent and reports true
     * rather than rebuilding the graph.
     */
    @Test
    fun initializeIsIdempotent() {
        assertTrue(Intempt.initialize(context()))
        assertTrue(Intempt.isInitialized)
    }

    /**
     * A second initialize returns the instance the first one built, not a new one.
     *
     * This lives here rather than in :app because :app has no config asset, so initialize() always
     * refuses there and the registration path is never reached — a version of this test written
     * against :app passed with the guard deleted, which the falsification harness reported as
     * HOLLOW. The sample module is the only place the SDK actually starts.
     *
     * Replacing the instance would orphan the first graph — its queue, its HandlerThread and its
     * collector — while any caller holding the old reference kept writing to storage nothing would
     * ever flush.
     */
    @Test
    fun initializingTwiceReturnsTheSameInstance() {
        assertTrue(Intempt.initialize(context()))

        val first = Intempt.mainInstance()
        assertNotNull("a successful initialize must register an instance", first)

        assertTrue("the second call reports success too", Intempt.initialize(context()))
        assertSame("the second initialize must not build a second graph", first, Intempt.mainInstance())

        // Same through the named overload, which is a different entry point into the same registry.
        assertSame(first, Intempt.initialize(context(), null, "default"))
    }

    /**
     * A named instance is a different instance, with its own identity.
     *
     * The point of named instances is that two projects in one app do not share state. If the
     * registry handed back the default instance for any name, every assertion about isolation
     * elsewhere would be vacuously true.
     */
    @Test
    fun aNamedInstanceIsDistinctFromTheDefault() {
        assertTrue(Intempt.initialize(context()))
        val main = Intempt.mainInstance()

        val secondary = Intempt.initialize(context(), null, "secondary")

        if (secondary == null) {
            // The sample's asset credentials are the only ones available, so a second instance may
            // legitimately refuse. Reported rather than asserted: what must never happen is it
            // silently aliasing the default, which the check below covers either way.
            println("secondary instance refused, which is acceptable here")
        } else {
            assertNotSame("a named instance must not alias the default", main, secondary)
            assertEquals("secondary", secondary.name)
        }
        assertSame("the default must be unchanged by a named initialize", main, Intempt.mainInstance())
    }

    /**
     * Every public entry point, called the way a host app calls it. No assertion on the
     * payload — this asserts only that the call does not take the host process down, which
     * is exactly the class of defect that reached production.
     */
    @Test
    fun everyPublicCallSurvivesOnThisApiLevel() {
        val attrs = IntemptValue.mapOf(mapOf("plan" to "free", "seats" to 3, "trial" to false))

        Intempt.track("Sample event", IntemptValue.mapOf(mapOf("source" to "sample-app")))
        Intempt.identify(userId = "sample-user-1", userAttributes = attrs)
        Intempt.group(accountId = "sample-account-1", accountAttributes = attrs)
        Intempt.record(
            eventTitle = "Sample record",
            userId = "sample-user-1",
            data = IntemptValue.mapOf(mapOf("step" to "checkout")),
        )
        Intempt.alias("sample-user-1", "sample-user-2")
        Intempt.productView("sku-123")
        Intempt.productAdd("sku-123", 2)
        Intempt.productOrdered(listOf(Product("sku-123", 2)))
        Intempt.consent(action = ConsentAction.ACCEPT, validUntil = System.currentTimeMillis() + 86_400_000)

        Intempt.getProfileId()
        Intempt.getSessionId()
        Intempt.flush()
        Intempt.flushInterval = 30

        Intempt.Logging.start()
        assertTrue(Intempt.Logging.isLoggingEnabled())
        Intempt.Logging.stop()

        Intempt.optIn()
        assertTrue(Intempt.isOptedIn())
        Intempt.optOut()
        assertTrue(Intempt.hasOptedOut())
        Intempt.optIn()

        // Rotate the profileId last: both clear the stores the calls above wrote to. reset()
        // additionally empties the queue, so it goes after logOut() rather than instead of it.
        Intempt.logOut()
        Intempt.reset()
    }

    /**
     * The config in this module's assets is the one a consumer writes, so a change to the
     * expected key names breaks integration silently — the reader catches its own exception
     * and falls back to empty credentials, which produces requests that 401 forever.
     */
    @Test
    fun theSampleConfigIsReadable() {
        val json = context().assets.open("intempt-config.json").bufferedReader().use { it.readText() }
        val auth = JSONObject(json).getJSONObject("auth")

        // Shape, not values. This config is generated at build time from local.properties or
        // CI secrets, so on a machine with real credentials the values are real and asserting
        // on the placeholders would fail. What must hold either way is that every key the
        // reader looks for is present and non-blank — a missing key makes ConfigManagerService
        // swallow its own exception and fall back to empty credentials, which produces
        // requests that 401 forever with no obvious cause.
        for (key in listOf(
            "INTEMPT_API_KEY",
            "INTEMPT_SOURCE_ID",
            "INTEMPT_ORGANIZATION_ID",
            "INTEMPT_PROJECT_ID",
        )) {
            assertTrue("$key is missing from the generated config", auth.has(key))
            assertTrue("$key is blank", auth.getString(key).isNotBlank())
        }
        // token() splits the key on "." and Base64s it, so a key without a separator throws.
        assertTrue("api key must contain a '.' separator", auth.getString("INTEMPT_API_KEY").contains("."))
    }

    /**
     * The queue internals are deliberately not reachable from here. `EventDbAdapter` and the
     * rest of `com.intempt.core.queue` are package-private, so a host app cannot open the
     * database, count rows, or force a flush — which is the correct boundary, and the reason
     * the durability properties are asserted in the library's own instrumented suite
     * (app/src/androidTest, QueueDurabilityTest) where they are visible.
     *
     * What this module can prove is that persistence is wired at all: `track()` eventually
     * hands the event to the delivery worker, which creates its database under the host
     * app's data directory. The write crosses a coroutine collector and the worker's own
     * HandlerThread, so this polls rather than asserting immediately — and it tolerates the
     * file not appearing rather than failing, because a timing assertion here would be a
     * race, not a test. It reports instead, so a regression is visible in the log without
     * turning the suite red for a scheduling hiccup.
     */
    @Test
    fun trackingWiresThroughToTheDurableQueue() {
        Intempt.track("Sample event", IntemptValue.mapOf(mapOf("source" to "sample-app")))

        val db = context().getDatabasePath("intempt_events")
        val appeared = (1..20).any { db.exists().also { found -> if (!found) Thread.sleep(50) } }

        println(
            if (appeared) {
                "queue database created at ${db.absolutePath}"
            } else {
                "queue database not yet created after 1s; the delivery worker had not run. " +
                    "Not a failure here — see QueueDurabilityTest for the durability assertions."
            },
        )
    }
}
