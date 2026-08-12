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
import java.util.concurrent.TimeUnit

/**
 * The methods that need real objects to mean anything: an existing profile, an existing
 * account, a catalog product, a product feed, and a published experiment and personalization.
 *
 * Every test here skips when its fixture is missing rather than failing. A red suite because
 * a contributor has no credentials teaches people to ignore the suite; a fabricated id gives
 * a green run that proves nothing. Skipped and reported is the only honest third option.
 *
 * Fixtures arrive as BuildConfig constants, sourced from gitignored local.properties locally
 * or repository secrets in CI, under one name in both places:
 *
 *   INTEMPT_E2E_USER_ID, INTEMPT_E2E_ACCOUNT_ID, INTEMPT_E2E_PRODUCT_ID,
 *   INTEMPT_E2E_FEED_ID, INTEMPT_E2E_FEED_FIELDS,
 *   INTEMPT_E2E_EXPERIMENT_NAME, INTEMPT_E2E_EXPERIMENT_GROUP,
 *   INTEMPT_E2E_PERSONALIZATION_NAME, INTEMPT_E2E_PERSONALIZATION_GROUP
 *
 * The identity fixtures are deliberately only userId and accountId. Those are the only
 * identifiers the SDK's public API accepts, so a test that reached for an internal profile id
 * would be asserting against something the SDK is not allowed to know about.
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

    @Test
    fun groupAttachesToTheExistingAccount() {
        val accountId = requireFixture("INTEMPT_E2E_ACCOUNT_ID", BuildConfig.INTEMPT_E2E_ACCOUNT_ID)

        Intempt.group(accountId = accountId, accountAttributes = mapOf("source" to "android-sdk-e2e"))

        val event = awaitEvent("a group for $accountId") { it.optString("type") == "group" }
        assertEquals("Group", event.optString("name"))
        val payload = event.getJSONArray("payload").getJSONObject(0)
        assertEquals(accountId, payload.optString("accountId"))
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
        awaitEvent("a product order") { it.optString("type") == "product" && it.optString("name") != "Product viewed" }
    }

    /**
     * `recommendation`'s first argument is the feed id: it goes straight into
     * `/v1/{org}/projects/{project}/feeds/{feedId}/data`. Unlike the tracking calls this is a
     * synchronous read, so a null result means the request genuinely failed rather than being
     * queued for later.
     */
    @Test
    fun recommendationReturnsFromTheFeed() {
        val feedId = requireFixture("INTEMPT_E2E_FEED_ID", BuildConfig.INTEMPT_E2E_FEED_ID)
        val fields =
            requireFixture("INTEMPT_E2E_FEED_FIELDS", BuildConfig.INTEMPT_E2E_FEED_FIELDS)
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

        val result = runBlocking { Intempt.recommendation(feedId, 3, fields, null) }

        assertNotNull(
            "the feed returned nothing. Either the feed id is wrong, the feed is empty, or the " +
                "request was rejected — check logcat for the response from /feeds/$feedId/data",
            result,
        )
    }

    /**
     * Experiments and personalizations both go to `/optimization/choose-api`; the only
     * difference is `optimizationType`, which the SDK hardcodes to "experiment" and
     * "personalization". A *draft* returns nothing, which is indistinguishable from a broken
     * SDK call, so these fixtures must name published objects.
     */
    @Test
    fun experimentReturnsAPublishedModification() {
        val name = requireFixture("INTEMPT_E2E_EXPERIMENT_NAME", BuildConfig.INTEMPT_E2E_EXPERIMENT_NAME)

        val byName = Intempt.experiment.getByNameAsync(listOf(name)).get(20, TimeUnit.SECONDS)

        assertNotNull(
            "experiment '$name' returned nothing. If it is a draft rather than published, this " +
                "is expected and the fixture needs changing rather than the SDK",
            byName,
        )
    }

    @Test
    fun experimentReturnsByGroup() {
        val group = requireFixture("INTEMPT_E2E_EXPERIMENT_GROUP", BuildConfig.INTEMPT_E2E_EXPERIMENT_GROUP)

        val byGroup = Intempt.experiment.getByGroupAsync(listOf(group)).get(20, TimeUnit.SECONDS)

        assertNotNull("experiment group '$group' returned nothing", byGroup)
    }

    @Test
    fun personalizationReturnsAPublishedModification() {
        val name =
            requireFixture(
                "INTEMPT_E2E_PERSONALIZATION_NAME",
                BuildConfig.INTEMPT_E2E_PERSONALIZATION_NAME,
            )

        val byName = Intempt.personalization.getByNameAsync(listOf(name)).get(20, TimeUnit.SECONDS)

        assertNotNull("personalization '$name' returned nothing", byName)
    }

    @Test
    fun personalizationReturnsByGroup() {
        val group =
            requireFixture(
                "INTEMPT_E2E_PERSONALIZATION_GROUP",
                BuildConfig.INTEMPT_E2E_PERSONALIZATION_GROUP,
            )

        val byGroup = Intempt.personalization.getByGroupAsync(listOf(group)).get(20, TimeUnit.SECONDS)

        assertNotNull("personalization group '$group' returned nothing", byGroup)
    }
}
