package com.intempt.sample

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The SDK, driven through its public API, inside a real host app, on a real Android image,
 * with assertions read back out of the durable queue on disk.
 *
 * This exists because the checks that actually caught defects on this branch were run by
 * hand — adb taps and typed sqlite3 queries. Manual evidence gates nothing: the finding is
 * true on the day and silently regresses afterwards while CI stays green. Every assertion
 * below replaces one of those manual checks.
 *
 * Reading the queue goes through the database file rather than the queue classes, which are
 * package-private on purpose. That is also the more honest test: it asserts what a row on
 * disk contains, which is what survives process death and what eventually goes on the wire.
 *
 * Events cross a coroutine collector and the delivery worker's own thread, so every read
 * polls with a timeout rather than asserting immediately.
 */
@RunWith(AndroidJUnit4::class)
class SdkOnDeviceTest {
    companion object {
        private const val DB = "intempt_events"
        private const val CONFIG = "intempt-config.json"
        private const val TIMEOUT_MS = 10_000L

        /**
         * One cold start for the whole class. The SDK is a singleton initialized from
         * Application.onCreate, so it cannot be torn down and rebuilt between tests; sharing
         * one launch and asserting on distinct event names is the honest shape.
         */
        @JvmStatic
        @BeforeClass
        fun launchOnce() {
            ActivityScenario.launch(MainActivity::class.java)
            // "View screen" and not "Session start". A session start is only emitted for a
            // *new* session, and the app's data survives between runs on a device, so a
            // second run inside the session window legitimately produces no session start.
            // Gating the whole class on it made the suite depend on whatever state the
            // device happened to be left in — it failed here with `Queued: [View screen]`.
            // Screen views are emitted on every resume, so they are the safe barrier.
            awaitEventNamed("View screen")
        }

        private fun context(): Context = ApplicationProvider.getApplicationContext()

        /** Every queued row, newest last. Empty when the queue file does not exist yet. */
        private fun rows(): List<JSONObject> {
            val file = context().getDatabasePath(DB)
            if (!file.exists()) return emptyList()
            val out = mutableListOf<JSONObject>()
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT data FROM events ORDER BY _id", null).use { c ->
                    while (c.moveToNext()) {
                        runCatching { out.add(JSONObject(c.getString(0))) }
                    }
                }
            }
            return out
        }

        /**
         * Every row this suite has ever observed, keyed by eventId.
         *
         * The queue is a moving target once delivery works: a row is deleted as soon as the
         * gateway confirms it. Before CI had credentials every POST 401'd, rows piled up, and
         * sampling the table was reliable by accident. With real credentials
         * aliasQueuesBothIdentifiers failed on API 23 with `Queued: [Change event]` — the row
         * had been queued, delivered and deleted inside the poll window.
         *
         * Accumulating means a row only has to exist at some instant, which is the property
         * actually being asserted. Cross-test collisions are not a risk because every
         * predicate matches an identifier its own test generated.
         */
        private val observed = LinkedHashMap<String, JSONObject>()

        private fun sample(): Collection<JSONObject> {
            synchronized(observed) {
                rows().forEach { row ->
                    val id =
                        row.optJSONArray("payload")?.optJSONObject(0)?.optString("eventId")
                            ?: row.optString("name") + row.optString("type")
                    observed.putIfAbsent(id, row)
                }
                return observed.values.toList()
            }
        }

        private fun awaitEvent(
            what: String,
            predicate: (JSONObject) -> Boolean,
        ): JSONObject {
            val deadline = System.currentTimeMillis() + TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                sample().firstOrNull(predicate)?.let { return it }
                // 50ms, not 250ms: delivery can remove a row within a few hundred
                // milliseconds, so the sampler has to run faster than the queue drains.
                Thread.sleep(50)
            }
            throw AssertionError(
                "timed out after ${TIMEOUT_MS}ms waiting for $what. Observed: " +
                    sample().map { it.optString("name") },
            )
        }

        private fun awaitEventNamed(name: String): JSONObject = awaitEvent("an event named '$name'") { it.optString("name") == name }
    }

    /**
     * The bug class that took the app down before its first frame at API 24: a dependency
     * referencing a JDK class absent below API 26, surfacing as NoClassDefFoundError — an
     * Error, so it escaped a `catch (e: Exception)`. Robolectric cannot see it, because on
     * the JVM that class exists.
     */
    @Test
    fun theSdkInitializesOnThisApiLevel() {
        assertTrue(
            "Intempt.initialize() did not complete on API ${android.os.Build.VERSION.SDK_INT}",
            com.intempt.core.Intempt.isInitialized,
        )
    }

    /** Autocapture, unprompted: starting the app must produce a screen view. */
    @Test
    fun startingTheAppQueuesAScreenView() {
        assertEquals("screenView", awaitEventNamed("View screen").optString("type"))
    }

    /**
     * A new session must be queued when one begins, without depending on what session state
     * the device was left in — relying on a cold launch is what made the first version of
     * this suite fail on its second run.
     */
    @Test
    fun aNewSessionQueuesASessionStart() {
        // logOut() alone is not enough, which the first run of this suite proved: it clears
        // the stored session but emits nothing, because the session tracker only runs on a
        // lifecycle transition. Relaunching the activity supplies that transition, so the
        // sequence is logOut-then-resume rather than logOut alone.
        com.intempt.core.Intempt.logOut()
        ActivityScenario.launch(MainActivity::class.java).use {
            val event =
                awaitEvent("a session start after logOut() and a relaunch") {
                    it.optString("type") == "sessionStart"
                }
            assertEquals("Session start", event.optString("name"))
        }
    }

    @Test
    fun trackQueuesTheEvent() {
        val name = "on-device track ${System.nanoTime()}"
        com.intempt.core.Intempt.track(name, mapOf("source" to "androidTest"))
        assertEquals("track", awaitEventNamed(name).optString("type"))
    }

    /**
     * record() needs no project objects — any userId string queues — so there was no reason
     * for it to sit in the smoke-test bucket where the only assertion was "did not crash".
     * That bucket would not have caught identify() silently dropping every titleless call,
     * and record() takes the same shape of optional arguments.
     */
    @Test
    fun recordQueuesTheEventWithItsIdentifiers() {
        val name = "on-device record ${System.nanoTime()}"
        com.intempt.core.Intempt.record(
            eventTitle = name,
            userId = "androidtest-record-user",
            data = mapOf("step" to "checkout"),
        )

        val event = awaitEventNamed(name)
        assertEquals("record", event.optString("type"))
        val payload = event.getJSONArray("payload").getJSONObject(0)
        assertEquals("androidtest-record-user", payload.optString("userId"))
    }

    /** alias() likewise needs nothing from the project to prove it reaches the queue. */
    @Test
    fun aliasQueuesBothIdentifiers() {
        val from = "androidtest-alias-a-${System.nanoTime()}"
        val to = "androidtest-alias-b-${System.nanoTime()}"

        com.intempt.core.Intempt.alias(from, to)

        // Matched on the generated ids, not merely on type == "alias".
        // everyPublicCallSurvivesOnThisApiLevel also calls alias(), and with no ordering
        // guarantee between test methods this found that one instead and compared against
        // "u-survive". Sharing one app process across tests means every predicate has to be
        // specific enough to identify its own event.
        val event =
            awaitEvent("the alias event this test emitted") { row ->
                row.optString("type") == "alias" &&
                    row.optJSONArray("payload")?.optJSONObject(0)?.optString("userId") == from
            }
        val payload = event.getJSONArray("payload").getJSONObject(0)
        assertEquals(from, payload.optString("userId"))
        assertEquals(to, payload.optString("anotherUserId"))
    }

    /**
     * identify() used to reject this exact call — userAttributes with no eventTitle — log an
     * error, and return normally having queued nothing. The event is named "Identify" by
     * default, which is what makes the titleless form work at all.
     */
    @Test
    fun identifyWithoutAnEventTitleStillQueues() {
        com.intempt.core.Intempt.identify(
            userId = "androidtest-user",
            userAttributes = mapOf("plan" to "free"),
        )
        val event =
            awaitEvent("this test's titleless identify") { row ->
                row.optString("type") == "identify" &&
                    row.optJSONArray("payload")?.optJSONObject(0)?.optString("userId") == "androidtest-user"
            }
        assertEquals("Identify", event.optString("name"))
    }

    /**
     * Two bugs in one assertion. group() rejected the titleless call, and once that was
     * fixed it named the event "Identify" — copy-pasted from identify() — so the name and
     * the type disagreed and anything grouping by name folded account events into user
     * identification.
     */
    @Test
    fun groupWithoutAnEventTitleIsNamedGroupNotIdentify() {
        com.intempt.core.Intempt.group(
            accountId = "androidtest-account",
            accountAttributes = mapOf("tier" to "smb"),
        )
        val event =
            awaitEvent("this test's titleless group") { row ->
                row.optString("type") == "group" &&
                    row.optJSONArray("payload")?.optJSONObject(0)?.optString("accountId") == "androidtest-account"
            }
        assertEquals(
            "a group event must not be named 'Identify'",
            "Group",
            event.optString("name"),
        )
    }

    /**
     * The privacy defect, and the reason this suite is instrumented rather than Robolectric:
     * it needs a real EditText with a real password input type.
     *
     * Autocapture writes both `targetText` and `targetValue`. Masking covered `targetText`
     * and `getViewText`, and missed `getViewValue`, so a queued row read
     * `"targetText":"*****"` next to `"targetValue":"hunter2SECRET"` — masked-looking, with
     * the credential beside it.
     */
    @Test
    fun aPasswordNeverReachesTheQueue() {
        val secret = "hunter2SECRET-${System.nanoTime()}"
        val email = "androidtest-${System.nanoTime()}@intempt.com"

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // The two edits are sequenced, not set back to back. Change events are debounced
            // by 320ms (Constants.DEBOUNCE_DELAY) on a shared main-looper handler, so setting
            // both fields inside one block let the second edit coalesce the first away and
            // the email never reached the queue — which read as masking over-reaching when it
            // was really the test racing the debounce.
            scenario.onActivity { activity ->
                val fields = editTexts(activity.window.decorView)
                assertEquals("expected the sample's two text fields", 2, fields.size)
                assertTrue("the second field must be a password input", isPasswordField(fields[1]))
                // onActivity already runs on the main thread; nesting runOnMainSync in here
                // throws "This method can not be called from the main application thread".
                fields[0].setText(email)
            }
            awaitEvent("a change event from the non-sensitive field") { row ->
                payloadData(row)?.optString("targetValue") == email
            }

            scenario.onActivity { activity ->
                editTexts(activity.window.decorView)[1].setText(secret)
            }
            awaitEvent("a change event from the password field, masked") { row ->
                val value = payloadData(row)?.optString("targetValue")
                value == "[masked]" || value == "*****"
            }
        }

        val serialized = sample().joinToString("\n") { it.toString() }
        assertFalse(
            "the password reached the durable queue in clear text. Queue: $serialized",
            serialized.contains(secret),
        )
        assertTrue(
            "the non-sensitive field should still be captured; masking must not over-reach",
            serialized.contains(email),
        )
    }

    /**
     * `doNotCaptureText(view)` is the opt-out a host app uses for a field autocapture should
     * not read. It tags the view, and both text-reading sites check that tag — so the value
     * must come through masked even though the field is not a password input.
     *
     * This was the last public method with no behavioural assertion. It is worth having a real
     * one: the tag is the only mechanism a customer has for a field the SDK cannot recognise as
     * sensitive on its own, and if it silently stopped working their data would start flowing
     * with no error anywhere.
     */
    @Test
    fun doNotCaptureTextMasksATaggedField() {
        val secret = "opted-out-${System.nanoTime()}"

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                // The plain email field, which autocapture would otherwise record verbatim.
                val field = editTexts(activity.window.decorView)[0]
                com.intempt.core.Intempt.doNotCaptureText(field)
                field.setText(secret)
            }

            awaitEvent("a change event from the opted-out field") { row ->
                payloadData(row)?.optString("targetValue") == "*****"
            }
        }

        assertFalse(
            "a field passed to doNotCaptureText still reached the queue in clear text",
            sample().joinToString("\n") { it.toString() }.contains(secret),
        )
    }

    /**
     * `consent` is the one call that never reaches the durable queue: it posts immediately, so
     * there is no row to read back and no client-side signal of the response. What is
     * assertable is its validation contract — the action must be "accept" or "reject" — and
     * that neither a valid nor an invalid action takes the host app down.
     */
    @Test
    fun consentAcceptsOnlyValidActions() {
        val validUntil = System.currentTimeMillis() + 86_400_000

        // Valid actions, and an invalid one the SDK must reject rather than send or throw.
        com.intempt.core.Intempt.consent(action = "accept", validUntil = validUntil)
        com.intempt.core.Intempt.consent(action = "reject", validUntil = validUntil)
        com.intempt.core.Intempt.consent(action = "not-a-real-action", validUntil = validUntil)

        assertTrue(
            "an invalid consent action must not take the SDK down with it",
            com.intempt.core.Intempt.isInitialized,
        )
    }

    /**
     * Every public entry point, called the way a host app calls it. Asserts only that none
     * of them takes the process down on this API level — which is exactly the defect that
     * shipped.
     */
    @Test
    fun everyPublicCallSurvivesOnThisApiLevel() {
        with(com.intempt.core.Intempt) {
            track("survival ${System.nanoTime()}", mapOf("k" to "v"))
            identify(userId = "u-survive", userAttributes = mapOf("plan" to "free"))
            group(accountId = "a-survive", accountAttributes = mapOf("tier" to "smb"))
            record(eventTitle = "record ${System.nanoTime()}", userId = "u-survive")
            alias("u-survive", "u-survive-2")
            productView("sku-1")
            productAdd("sku-1", 2)
            productOrdered(listOf(mapOf("productId" to "sku-1", "quantity" to 1)))
            consent(action = "accept", validUntil = System.currentTimeMillis() + 86_400_000)
        }
        with(com.intempt.core.Intempt.Logging) {
            start()
            stop()
        }
        with(com.intempt.core.Intempt.Tracking) {
            start()
            stop()
            // Left enabled: the assertions in this class depend on capture being on, and
            // JUnit gives no ordering guarantee between test methods.
            start()
        }
        assertTrue(com.intempt.core.Intempt.isInitialized)
    }

    /**
     * The schema the delivery substrate depends on, asserted against the file the running
     * app actually created rather than one built by a test.
     */
    @Test
    fun theQueueSchemaIsWhatTheSubstrateExpects() {
        awaitEventNamed("View screen")
        val file = context().getDatabasePath(DB)
        assertTrue("the queue database should exist after events are tracked", file.exists())

        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='events'", null).use { c ->
                val names = mutableListOf<String>()
                while (c.moveToNext()) names.add(c.getString(0))
                assertTrue("time_idx on created_at is missing: $names", names.contains("time_idx"))
            }
            db.rawQuery("PRAGMA table_info(events)", null).use { c ->
                val cols = mutableListOf<String>()
                while (c.moveToNext()) cols.add(c.getString(c.getColumnIndexOrThrow("name")))
                assertTrue(cols.contains("data"))
                assertTrue(cols.contains("created_at"))
                assertFalse("Intempt runs one SDK instance per app; there is nothing to partition", cols.contains("token"))
            }
        }
    }

    /**
     * The delivery leg, against the real ingestion endpoint.
     *
     * Everything else in this class proves events reach the durable queue. This proves they
     * leave it — and that they leave it for the right reason. `cleanupEvents` only runs after
     * the server confirms receipt, so a queue that drains is a batch the gateway accepted;
     * a queue that does not drain means the POST failed and the events were correctly kept.
     *
     * Delivery is triggered by exceeding QueueConfig.BULK_UPLOAD_LIMIT (40) rather than by
     * waiting out the 60-second flush timer, which keeps the test bounded and deterministic.
     *
     * Skipped, rather than failed, when the build has no real credentials: the generated
     * config falls back to placeholders, and those legitimately produce a 401. A red test on
     * a contributor's laptop for a missing secret would train people to ignore this suite.
     */
    @Test
    fun deliveredEventsAreRemovedFromTheQueue() {
        val configured = hasRealCredentials()
        Assume.assumeTrue(
            "no real ingestion credentials in this build; set intempt.apiKey in local.properties",
            configured,
        )

        val tag = "e2e delivery ${System.nanoTime()}"
        repeat(45) { i ->
            com.intempt.core.Intempt.track("$tag-$i", mapOf("source" to "android-sdk-e2e"))
        }

        // No assertion that the queue grows first. 45 events exceed
        // QueueConfig.BULK_UPLOAD_LIMIT (40), so a flush is scheduled immediately and the
        // burst can be delivered and deleted before a poll ever observes a larger queue —
        // which failed here as "expected events to be queued before delivery" while delivery
        // was in fact working. Draining is the property worth asserting; how briefly the rows
        // existed is not.
        val drained =
            awaitCondition(timeoutMs = 90_000) {
                rows().none { it.optString("name").startsWith(tag) }
            }

        assertTrue(
            "events were still in the queue after 90s, so the POST to the ingestion endpoint " +
                "never succeeded. Remaining: " + rows().map { it.optString("name") }.take(5),
            drained,
        )
    }

    /** True when the build was given credentials rather than the committed placeholders. */
    private fun hasRealCredentials(): Boolean {
        val json = context().assets.open(CONFIG).bufferedReader().use { it.readText() }
        val auth = JSONObject(json).getJSONObject("auth")
        return auth.getString("INTEMPT_API_KEY") != "sample-key-id.sample-key-secret" &&
            auth.getString("INTEMPT_SOURCE_ID") != "sample-source"
    }

    private fun awaitCondition(
        timeoutMs: Long = TIMEOUT_MS,
        predicate: () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(500)
        }
        return false
    }

    private fun editTexts(root: View): List<EditText> {
        val found = mutableListOf<EditText>()

        fun walk(v: View) {
            if (v is EditText) found.add(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return found
    }

    private fun isPasswordField(field: EditText): Boolean {
        val variation = field.inputType and android.text.InputType.TYPE_MASK_VARIATION
        return variation == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
    }

    /** The `data` object inside the first payload entry, or null when shaped otherwise. */
    private fun payloadData(row: JSONObject): JSONObject? = row.optJSONArray("payload")?.optJSONObject(0)?.optJSONObject("data")
}
