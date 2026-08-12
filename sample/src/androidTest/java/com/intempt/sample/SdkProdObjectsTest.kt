package com.intempt.sample

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.intempt.core.Intempt
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The methods that need real objects from a live project to mean anything.
 *
 * Fixtures arrive as BuildConfig constants, sourced from gitignored local.properties locally
 * or repository secrets in CI, under one name in both places:
 *
 *   INTEMPT_E2E_USER_ID, INTEMPT_E2E_PRODUCT_ID, INTEMPT_E2E_FEED_ID,
 *   INTEMPT_E2E_FEED_FIELDS (defaults to "id")
 *
 * Each test skips when its fixture is missing rather than failing. A red suite because a
 * contributor has no credentials teaches people to ignore the suite; a fabricated id gives a
 * green run that proves nothing.
 *
 * Experiments and personalizations are deliberately absent: they are an intemptjs capability
 * and are not part of the Android or iOS SDKs, so there is nothing here to test.
 *
 * The only identity fixture is userId. group() creates its own account, and the SDK's public
 * API accepts no internal profile identifier, so a test reaching for one would be asserting
 * against something the SDK is not allowed to know.
 */
@RunWith(AndroidJUnit4::class)
class SdkProdObjectsTest {
    private fun context(): Context = ApplicationProvider.getApplicationContext()

    @Before
    fun launch() {
        ActivityScenario.launch(MainActivity::class.java)
        assertTrue("the SDK must be running before these tests mean anything", Intempt.isInitialized)
    }

    private fun requireFixture(
        name: String,
        value: String,
    ): String {
        Assume.assumeTrue(
            "$name is not set; supply it in local.properties or as a CI secret to run this test",
            value.isNotBlank(),
        )
        return value
    }

    /** Rows currently in the durable queue. */
    private fun rows(): List<JSONObject> {
        val file = context().getDatabasePath("intempt_events")
        if (!file.exists()) return emptyList()
        val out = mutableListOf<JSONObject>()
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT data FROM events ORDER BY _id", null).use { c ->
                while (c.moveToNext()) runCatching { out.add(JSONObject(c.getString(0))) }
            }
        }
        return out
    }

    private fun awaitEvent(
        what: String,
        predicate: (JSONObject) -> Boolean,
    ): JSONObject {
        val deadline = System.currentTimeMillis() + 15_000L
        while (System.currentTimeMillis() < deadline) {
            rows().firstOrNull(predicate)?.let { return it }
            Thread.sleep(250)
        }
        throw AssertionError("timed out waiting for $what. Queued: ${rows().map { it.optString("name") }}")
    }

    /**
     * The same profile every run, which is what keeps results comparable: a new anonymous
     * user per run would leave a trail of throwaway profiles in a real project.
     */
    @Test
    fun identifyAttachesToTheStableUser() {
        val userId = requireFixture("INTEMPT_E2E_USER_ID", BuildConfig.INTEMPT_E2E_USER_ID)

        Intempt.identify(userId = userId, userAttributes = mapOf("source" to "android-sdk-e2e"))

        val event = awaitEvent("an identify for $userId") { it.optString("type") == "identify" }
        val payload = event.getJSONArray("payload").getJSONObject(0)
        assertEquals("the identify must carry the userId it was given", userId, payload.optString("userId"))
        // profileId is generated and persisted on the device, so it must be present and
        // prefixed — it is what ties this event to the anonymous profile before identify runs.
        assertTrue("profileId missing", payload.optString("profileId").startsWith("prof_"))
    }

    /**
     * group() needs no pre-existing account: creating a group creates the account and puts the
     * user in it, so any id works and this needs no fixture.
     */
    @Test
    fun groupCreatesTheAccountAndQueuesTheEvent() {
        val accountId = "androidtest-account-${System.nanoTime()}"

        Intempt.group(accountId = accountId, accountAttributes = mapOf("source" to "android-sdk-e2e"))

        val event = awaitEvent("a group for $accountId") { it.optString("type") == "group" }
        assertEquals("Group", event.optString("name"))
        assertEquals(accountId, event.getJSONArray("payload").getJSONObject(0).optString("accountId"))
    }

    /**
     * All three commerce calls with a product that exists in the catalog. A productId that is
     * not in the catalog is the case worth worrying about: the gateway answers 400 "Data not
     * matching with collection schema", HttpStatusPolicy drops the batch by design, and the
     * queue looks healthy while the events are gone.
     */
    @Test
    fun productCallsUseACatalogProduct() {
        val productId = requireFixture("INTEMPT_E2E_PRODUCT_ID", BuildConfig.INTEMPT_E2E_PRODUCT_ID)

        Intempt.productView(productId)
        awaitEvent("a product view") { it.optString("name") == "Product viewed" }

        Intempt.productAdd(productId, 2)
        awaitEvent("an add to cart") { it.optString("name") == "Added to cart" }

        Intempt.productOrdered(listOf(mapOf("productId" to productId, "quantity" to 1)))
        awaitEvent("a product order") {
            it.optString("type") == "product" &&
                it.optString("name") != "Product viewed" &&
                it.optString("name") != "Added to cart"
        }
    }

    /**
     * `recommendation`'s first argument is the feed id: it goes straight into
     * `/v1/{org}/projects/{project}/feeds/{feedId}/data`. Unlike the tracking calls this is a
     * synchronous read, so a null result means the request genuinely failed rather than being
     * queued for later.
     *
     * The profile has to exist server-side first, which is the part worth knowing. The SDK
     * sends `id = storage.getProfileId()` with `type = "profile"` — a device-generated
     * `prof_<uuid>` the platform has never heard of on a fresh install. Until events for that
     * profile have been ingested the feed answers:
     *
     *     400 {"errors":[{"message":"USER with id prof_… is not found"}]}
     *
     * So this identifies first and waits for the queue to drain, which is what a real app does
     * before it asks for recommendations. Verified by hand against linea_shop: after an
     * identify was ingested for the profile, the same feed returned 200 {"products":[]}.
     *
     * Note the error text names USER even when the id is a profile and even when the *feed id*
     * is the wrong part — a nonexistent feed returns the identical message. That makes a wrong
     * feed id and a wrong profile indistinguishable from the client, so a failure here means
     * "one of the two", not "the feed is broken".
     */
    @Test
    fun recommendationReturnsFromTheFeed() {
        val feedId = requireFixture("INTEMPT_E2E_FEED_ID", BuildConfig.INTEMPT_E2E_FEED_ID)
        val userId = requireFixture("INTEMPT_E2E_USER_ID", BuildConfig.INTEMPT_E2E_USER_ID)
        val fields =
            BuildConfig.INTEMPT_E2E_FEED_FIELDS
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

        // Give the platform a profile to answer about, then let it reach the gateway.
        Intempt.identify(userId = userId, userAttributes = mapOf("source" to "android-sdk-e2e"))
        awaitEvent("the identify to be queued") { it.optString("type") == "identify" }
        val delivered =
            awaitDrained(timeoutMs = 120_000) {
                rows().none { row -> row.optString("type") == "identify" }
            }
        assertTrue(
            "the identify never left the queue, so the platform cannot know this profile yet",
            delivered,
        )

        val result = runBlocking { Intempt.recommendation(feedId, 3, fields, null) }

        assertNotNull(
            "feed $feedId returned nothing. Either the feed id is wrong or this device's " +
                "profile is still unknown to the platform — the API cannot tell those apart",
            result,
        )
    }

    private fun awaitDrained(
        timeoutMs: Long,
        predicate: () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(1_000)
        }
        return false
    }
}
