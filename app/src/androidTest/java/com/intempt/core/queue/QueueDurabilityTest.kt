package com.intempt.core.queue

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The P0 this branch exists for, verified against real SQLite on a real device.
 *
 * Before this work the queue was an in-memory list that was cleared *before* the network
 * POST was attempted, so any failure or process death lost every unflushed event silently.
 * These tests assert the properties that make that impossible, and they must be
 * instrumented rather than JVM tests: Robolectric substitutes its own SQLite, so it cannot
 * prove that a row written by one adapter instance is readable by a different one against
 * the same file on disk — which is precisely the mechanism that survives process death.
 *
 * Four of the twelve inherited fidelity behaviours are covered here. The rest need the
 * worker thread and a controllable HTTP layer, which is blocked on making production
 * dispatchers injectable.
 */
@RunWith(AndroidJUnit4::class)
class QueueDurabilityTest {

    private lateinit var context: Context
    private lateinit var config: QueueConfig
    private val dbName = "durability_test.db"

    private fun event(id: String) = JSONObject()
        .put("name", "Test event")
        .put("type", "track")
        .put("payload", org.json.JSONArray().put(JSONObject().put("eventId", id)))

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getDatabasePath(dbName).delete()
        // A port nothing listens on. No test here performs a flush, but this guarantees
        // that an accidental one cannot reach a real endpoint.
        config = QueueConfig("http://127.0.0.1:1/track")
    }

    @After
    fun tearDown() {
        context.getDatabasePath(dbName).delete()
    }

    /**
     * The property that defines the fix. A fresh adapter — standing in for the one a new
     * process would construct — must see rows written by an earlier instance.
     */
    @Test
    fun eventsWrittenByOneAdapterAreVisibleToANewOne() {
        val writer = EventDbAdapter(context, dbName, config)
        writer.addJSON(event("ev_1"), EventDbAdapter.Table.EVENTS)
        writer.addJSON(event("ev_2"), EventDbAdapter.Table.EVENTS)

        val reader = EventDbAdapter(context, dbName, config)
        val batch = reader.generateDataString(EventDbAdapter.Table.EVENTS)

        assertNotNull("a new adapter must see previously written rows", batch)
        val payload = org.json.JSONArray(batch!![1])
        assertEquals(2, payload.length())
        assertEquals("2", batch[2])
    }

    /**
     * Delete-only-after-confirmed-delivery. cleanupEvents must remove through the id that
     * was actually sent and no further, or events queued during a flush are lost.
     */
    @Test
    fun cleanupRemovesOnlyThroughTheDeliveredId() {
        val adapter = EventDbAdapter(context, dbName, config)
        adapter.addJSON(event("ev_1"), EventDbAdapter.Table.EVENTS)
        val firstBatch = adapter.generateDataString(EventDbAdapter.Table.EVENTS)!!
        val deliveredUpTo = firstBatch[0]

        // arrives after the batch was read, as it would during an in-flight POST
        adapter.addJSON(event("ev_2"), EventDbAdapter.Table.EVENTS)

        adapter.cleanupEvents(deliveredUpTo, EventDbAdapter.Table.EVENTS)

        val remaining = adapter.generateDataString(EventDbAdapter.Table.EVENTS)
        assertNotNull("the later event must survive the cleanup", remaining)
        val payload = org.json.JSONArray(remaining!![1])
        assertEquals(1, payload.length())
        assertEquals("ev_2", payload.getJSONObject(0).getJSONArray("payload").getJSONObject(0).getString("eventId"))
    }

    /**
     * Poison-pill immunity, inherited from MPDbAdapter's per-row JSONException skip. One
     * unparseable row must not make the whole batch unreadable, or it parks at the queue
     * head and blocks delivery permanently.
     */
    @Test
    fun oneMalformedRowDoesNotPoisonTheBatch() {
        val adapter = EventDbAdapter(context, dbName, config)
        adapter.addJSON(event("ev_good_1"), EventDbAdapter.Table.EVENTS)

        // write a row that is not valid JSON, bypassing addJSON's JSONObject typing
        context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL(
                "INSERT INTO events (data, created_at, automatic_data) VALUES (?, ?, 0)",
                arrayOf("{this is not json", System.currentTimeMillis())
            )
        }
        adapter.addJSON(event("ev_good_2"), EventDbAdapter.Table.EVENTS)

        val batch = adapter.generateDataString(EventDbAdapter.Table.EVENTS)

        assertNotNull("a malformed row must not fail the whole read", batch)
        val payload = org.json.JSONArray(batch!![1])
        assertEquals("the two good rows survive, the bad one is skipped", 2, payload.length())
    }

    /** The created_at index, inherited so the expiration sweep is not a full scan. */
    @Test
    fun theCreatedAtIndexExists() {
        EventDbAdapter(context, dbName, config)
            .addJSON(event("ev_1"), EventDbAdapter.Table.EVENTS)

        context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null).use { db ->
            db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='events'", null
            ).use { c ->
                val names = mutableListOf<String>()
                while (c.moveToNext()) names.add(c.getString(0))
                assertTrue("time_idx on created_at is missing: $names", names.contains("time_idx"))
            }
        }
    }

    /** The schema is a single events table. The other three Mixpanel tables are not ours. */
    @Test
    fun onlyTheEventsTableExists() {
        EventDbAdapter(context, dbName, config)
            .addJSON(event("ev_1"), EventDbAdapter.Table.EVENTS)

        context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null).use { db ->
            db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' " +
                    "AND name != 'android_metadata'", null
            ).use { c ->
                val tables = mutableListOf<String>()
                while (c.moveToNext()) tables.add(c.getString(0))
                assertEquals(listOf("events"), tables)
            }
        }
    }

    /** No token column: Intempt runs one SDK instance per app, so there is nothing to partition. */
    @Test
    fun theSchemaHasNoTokenColumn() {
        EventDbAdapter(context, dbName, config)
            .addJSON(event("ev_1"), EventDbAdapter.Table.EVENTS)

        context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null).use { db ->
            db.rawQuery("PRAGMA table_info(events)", null).use { c ->
                val cols = mutableListOf<String>()
                while (c.moveToNext()) cols.add(c.getString(c.getColumnIndexOrThrow("name")))
                assertFalse("token column should have been removed: $cols", cols.contains("token"))
                assertTrue(cols.contains("data"))
                assertTrue(cols.contains("created_at"))
            }
        }
    }

    /** An empty queue yields null rather than an empty body the endpoint would reject. */
    @Test
    fun anEmptyQueueReadsAsNull() {
        assertNull(
            EventDbAdapter(context, dbName, config)
                .generateDataString(EventDbAdapter.Table.EVENTS)
        )
    }
}
