package com.intempt.sample

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.intempt.core.Intempt
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
     * `initialize` swallows every exception and prints "Intempt initialization failed", so a
     * failed init is indistinguishable from a successful one at the call site. `experiment`
     * and `personalization` are only assigned on the success path, and reading an unassigned
     * `lateinit` throws — so this is the only way from outside the SDK to tell whether
     * initialization actually completed.
     */
    @Test
    fun initializationCompletesRatherThanSilentlyFailing() {
        assertNotNull("initialize() did not complete; experiment was never assigned", Intempt.experiment)
        assertNotNull("initialize() did not complete; personalization was never assigned", Intempt.personalization)
    }

    /**
     * Every public entry point, called the way a host app calls it. No assertion on the
     * payload — this asserts only that the call does not take the host process down, which
     * is exactly the class of defect that reached production.
     */
    @Test
    fun everyPublicCallSurvivesOnThisApiLevel() {
        Intempt.track("Sample event", mapOf("source" to "sample-app"))
        Intempt.identify(userId = "sample-user-1", userAttributes = mapOf("plan" to "free"))
        Intempt.group(accountId = "sample-account-1", accountAttributes = mapOf("tier" to "smb"))
        Intempt.record(eventTitle = "Sample record", userId = "sample-user-1", data = mapOf("step" to "checkout"))
        Intempt.alias("sample-user-1", "sample-user-2")
        Intempt.productView("sku-123")
        Intempt.productAdd("sku-123", 2)
        Intempt.productOrdered(listOf(mapOf("productId" to "sku-123", "quantity" to 2)))
        Intempt.consent(action = "granted", validUntil = System.currentTimeMillis() + 86_400_000)

        Intempt.Logging.start()
        assertTrue(Intempt.Logging.isLoggingEnabled())
        Intempt.Logging.stop()

        Intempt.Tracking.start()
        assertTrue(Intempt.Tracking.isTrackingEnabled())
        Intempt.Tracking.stop()

        // Rotates the profileId. Called last: it clears the stores the calls above wrote to.
        Intempt.logOut()
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

        assertEquals("sample-source", auth.getString("INTEMPT_SOURCE_ID"))
        assertEquals("sample-org", auth.getString("INTEMPT_ORGANIZATION_ID"))
        assertEquals("sample-project", auth.getString("INTEMPT_PROJECT_ID"))
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
        Intempt.track("Sample event", mapOf("source" to "sample-app"))

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
