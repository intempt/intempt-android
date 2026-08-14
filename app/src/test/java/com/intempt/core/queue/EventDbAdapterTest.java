package com.intempt.core.queue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * {@code EventDbAdapter} is the SDK's entire durability guarantee: it is the only thing standing
 * between an event and a process death. Coverage measured it at 0.0%.
 *
 * That is the worst possible place to have none, because every failure mode here is silent. A
 * cleanup that deletes one row too many loses an event that was never delivered. A cleanup that
 * deletes one too few sends it twice. Neither raises anything — the queue just quietly holds the
 * wrong contents, and the only observable symptom is a number being slightly wrong in a customer's
 * dashboard weeks later.
 *
 * So these tests assert on the queue's contents after each operation rather than on return values,
 * and the boundary cases (`<=` versus `<`) are pinned explicitly in both directions.
 */
@RunWith(RobolectricTestRunner.class)
public class EventDbAdapterTest {

    private static final String DB = "event_db_adapter_test.db";
    private static final EventDbAdapter.Table EVENTS = EventDbAdapter.Table.EVENTS;

    private Context context;
    private QueueConfig config;
    private EventDbAdapter db;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.getDatabasePath(DB).delete();
        config = new QueueConfig("https://example.invalid/track", "Basic dGVzdDp0ZXN0");
        db = new EventDbAdapter(context, DB, config);
    }

    @After
    public void tearDown() {
        context.getDatabasePath(DB).delete();
    }

    private static JSONObject event(String name) throws JSONException {
        return new JSONObject().put("event", name);
    }

    /** The events currently in the queue, in the order the adapter would send them. */
    private JSONArray queued() throws JSONException {
        final String[] batch = db.generateDataString(EVENTS);
        return batch == null ? new JSONArray() : new JSONArray(batch[1]);
    }

    // ------------------------------------------------------------------- write

    @Test
    public void addJsonReturnsTheNewQueueDepth() throws JSONException {
        assertEquals(1, db.addJSON(event("first"), EVENTS));
        assertEquals(2, db.addJSON(event("second"), EVENTS));
        assertEquals(3, db.addJSON(event("third"), EVENTS));
    }

    @Test
    public void aStoredEventSurvivesAndComesBackIntact() throws JSONException {
        final JSONObject original =
                new JSONObject()
                        .put("event", "purchase")
                        .put("properties", new JSONObject().put("amount", 42.5).put("currency", "EUR"));
        db.addJSON(original, EVENTS);

        final JSONArray out = queued();
        assertEquals(1, out.length());
        assertEquals("purchase", out.getJSONObject(0).getString("event"));
        assertEquals(42.5, out.getJSONObject(0).getJSONObject("properties").getDouble("amount"), 0.0001);
        assertEquals("EUR", out.getJSONObject(0).getJSONObject("properties").getString("currency"));
    }

    /**
     * A fresh adapter must reuse the file rather than starting a new queue, or every process
     * restart would drop whatever had not been flushed.
     */
    @Test
    public void eventsSurviveANewAdapterOverTheSameFile() throws JSONException {
        db.addJSON(event("before"), EVENTS);

        db = new EventDbAdapter(context, DB, config);

        final JSONArray out = queued();
        assertEquals(1, out.length());
        assertEquals("before", out.getJSONObject(0).getString("event"));
    }

    // -------------------------------------------------------------------- read

    @Test
    public void anEmptyQueueReadsAsNullRatherThanAnEmptyBatch() {
        assertNull(
                "an empty queue must not produce a batch, or delivery would POST an empty array",
                db.generateDataString(EVENTS));
    }

    @Test
    public void theBatchCarriesTheLastIdAndTheTotalQueueDepth() throws JSONException {
        for (int i = 0; i < 5; i++) {
            db.addJSON(event("e" + i), EVENTS);
        }

        final String[] batch = db.generateDataString(EVENTS);
        assertNotNull(batch);
        assertEquals("last_id must be the highest _id in the batch", "5", batch[0]);
        assertEquals("queue depth is reported separately from the batch", "5", batch[2]);
        assertEquals(5, new JSONArray(batch[1]).length());
    }

    /**
     * The read is capped at {@code flushBatchSize}, but the reported depth is the whole queue —
     * that is how delivery knows to come back for more. Conflating the two would strand events.
     */
    @Test
    public void theReadIsCappedAtTheBatchSizeWhileTheDepthIsNot() throws JSONException {
        // Pinned against the inherited constant rather than only against getFlushBatchSize(). The
        // expectation and the production code previously read the same method, so a wrong value
        // there was self-confirming.
        assertEquals("the inherited flush batch size changed", 50, config.getFlushBatchSize());
        final int cap = config.getFlushBatchSize();
        for (int i = 0; i < cap + 7; i++) {
            db.addJSON(event("e" + i), EVENTS);
        }

        final String[] batch = db.generateDataString(EVENTS);
        assertNotNull(batch);
        assertEquals("the batch must be capped", cap, new JSONArray(batch[1]).length());
        assertEquals("the depth must be the whole queue", String.valueOf(cap + 7), batch[2]);
    }

    @Test
    public void eventsComeBackOldestFirst() throws JSONException {
        // Distinct created_at values, forced. Three back-to-back inserts land on the same
        // millisecond, and SQLite then returns them in rowid order anyway — so the ORDER BY clause
        // could be deleted entirely and this test would not notice.
        db.addJSON(event("oldest"), EVENTS);
        waitForTheClockToAdvancePast(System.currentTimeMillis());
        db.addJSON(event("middle"), EVENTS);
        waitForTheClockToAdvancePast(System.currentTimeMillis());
        db.addJSON(event("newest"), EVENTS);

        final JSONArray out = queued();
        assertEquals("oldest", out.getJSONObject(0).getString("event"));
        assertEquals("middle", out.getJSONObject(1).getString("event"));
        assertEquals("newest", out.getJSONObject(2).getString("event"));
    }

    // ----------------------------------------------------------------- cleanup

    /**
     * The boundary that matters most. Delivery calls this with the last_id it just delivered, so
     * the row at exactly last_id must go — off by one in this direction resends an event, and off
     * by one in the other loses one that was never sent.
     */
    @Test
    public void cleanupByIdIsInclusiveOfTheGivenIdAndSparesTheRest() throws JSONException {
        db.addJSON(event("delivered1"), EVENTS);
        db.addJSON(event("delivered2"), EVENTS);
        db.addJSON(event("arrivedDuringFlush"), EVENTS);

        db.cleanupEvents("2", EVENTS);

        final JSONArray out = queued();
        assertEquals(1, out.length());
        assertEquals(
                "only rows past last_id may survive",
                "arrivedDuringFlush",
                out.getJSONObject(0).getString("event"));
    }

    @Test
    public void cleanupByIdOfTheWholeQueueEmptiesIt() throws JSONException {
        db.addJSON(event("a"), EVENTS);
        db.addJSON(event("b"), EVENTS);

        db.cleanupEvents("2", EVENTS);

        assertNull(db.generateDataString(EVENTS));
        // Both cleanup methods catch SQLiteException by calling deleteDatabase(), so "empty" and
        // "the delete threw and the file was wiped" are the same observation. The file surviving is
        // what tells them apart.
        assertTrue("cleanup must delete rows, not fall into the wipe-and-recreate error path",
                context.getDatabasePath(DB).exists());
    }

    /** An id below everything present must delete nothing. */
    @Test
    public void cleanupByAnIdBelowTheQueueDeletesNothing() throws JSONException {
        db.addJSON(event("a"), EVENTS);
        db.addJSON(event("b"), EVENTS);

        db.cleanupEvents("0", EVENTS);

        assertEquals(2, queued().length());
    }

    /**
     * The time-based cleanup is the expiry path, and it is also `<=`. An event stamped exactly at
     * the cutoff is expired.
     */
    @Test
    public void cleanupByTimeDropsEventsAtOrBeforeTheCutoffAndKeepsNewerOnes() throws JSONException {
        db.addJSON(event("old"), EVENTS);
        final long cutoff = System.currentTimeMillis();
        // The clock has millisecond resolution, so the newer row needs to land after the cutoff.
        waitForTheClockToAdvancePast(cutoff);
        db.addJSON(event("new"), EVENTS);

        db.cleanupEvents(cutoff, EVENTS);

        final JSONArray out = queued();
        assertEquals(1, out.length());
        assertEquals("new", out.getJSONObject(0).getString("event"));
    }

    @Test
    public void cleanupByATimeBeforeEverythingKeepsTheWholeQueue() throws JSONException {
        db.addJSON(event("a"), EVENTS);
        db.addJSON(event("b"), EVENTS);

        db.cleanupEvents(0L, EVENTS);

        assertEquals(2, queued().length());
    }

    @Test
    public void cleanupAllEmptiesTheQueueButLeavesItUsable() throws JSONException {
        db.addJSON(event("a"), EVENTS);
        db.addJSON(event("b"), EVENTS);

        db.cleanupAllEvents(EVENTS);
        assertNull(db.generateDataString(EVENTS));
        assertTrue("cleanupAll must empty the table, not destroy the database",
                context.getDatabasePath(DB).exists());

        assertEquals("the table must still accept writes after being emptied", 1, db.addJSON(event("after"), EVENTS));
    }

    /**
     * {@code deleteDB} drops the file rather than the rows. The adapter has to recreate it on the
     * next write, otherwise a single disk-full event would permanently disable tracking for the
     * rest of the process.
     */
    @Test
    public void deleteDbRemovesTheFileAndTheNextWriteRecreatesIt() throws JSONException {
        db.addJSON(event("a"), EVENTS);

        assertTrue("the file must exist before deleteDB, or this test proves nothing",
                context.getDatabasePath(DB).exists());

        db.deleteDB();

        // Asserted explicitly: clearing the rows produces the identical observable result below
        // (count resets to 1, only "after" is queued), so without this the test would pass against
        // a deleteDB that merely emptied the table.
        assertFalse("deleteDB must remove the file, not just its rows",
                context.getDatabasePath(DB).exists());

        assertEquals("the queue must recover after the file is dropped", 1, db.addJSON(event("after"), EVENTS));
        final JSONArray out = queued();
        assertEquals(1, out.length());
        assertEquals("after", out.getJSONObject(0).getString("event"));
    }

    // ------------------------------------------------------- first-launch flag

    /**
     * This flag is what distinguishes a first app launch, so it has to flip exactly once. Reading
     * it must not consume it either — it is read more than once during startup.
     */
    @Test
    public void isNewDatabaseIsTrueOnlyForTheFirstAdapterOverAFile() {
        assertTrue("a database that did not exist must report as new", db.isNewDatabase());
        assertTrue("reading the flag must not consume it", db.isNewDatabase());

        db.addJSON(new JSONObject(), EVENTS);

        assertFalse(
                "an adapter over an existing file must not report a first launch",
                new EventDbAdapter(context, DB, config).isNewDatabase());
    }

    // ------------------------------------------------------ degraded behaviour

    /**
     * Out of space is the one case where dropping is correct — the alternative is filling a
     * customer's disk. It has to be reported as its own code rather than as a generic failure,
     * because delivery treats the two differently.
     */
    @Test
    public void aFullDiskIsReportedAsOutOfMemoryAndNothingIsWritten() throws JSONException {
        final EventDbAdapter full =
                new EventDbAdapter(context, DB, config) {
                    @Override
                    protected boolean aboveMemThreshold() {
                        return true;
                    }
                };

        assertEquals(EventDbAdapter.DB_OUT_OF_MEMORY_ERROR, full.addJSON(event("dropped"), EVENTS));
        // Read back through the same adapter that refused the write, not a sibling — a sibling's
        // queue is empty in this test for unrelated reasons, so it proves nothing.
        assertNull("nothing may be written when the threshold is exceeded", full.generateDataString(EVENTS));
        assertNull(db.generateDataString(EVENTS));
    }

    /**
     * An empty JSON object is still a row. The adapter must not treat "no properties" as "no
     * event" — that decision belongs upstream.
     */
    @Test
    public void anEmptyJsonObjectIsStoredRatherThanDiscarded() throws JSONException {
        assertEquals(1, db.addJSON(new JSONObject(), EVENTS));
        assertEquals(1, queued().length());
    }

    /** Unicode and emoji have to survive the round trip through SQLite unaltered. */
    @Test
    public void unicodeAndEmojiRoundTripThroughTheQueue() throws JSONException {
        final String name = "购买 🎉 ünïcødé";
        db.addJSON(event(name), EVENTS);

        assertEquals(name, queued().getJSONObject(0).getString("event"));
    }

    /**
     * Round-trip integrity for a value full of SQL metacharacters.
     *
     * <p>Stated accurately rather than as an injection test: {@code addJSON} writes through
     * ContentValues, which binds parameters, so this path was never exposed to injection and this
     * test does not prove it is. What it does pin is that such a value survives the write/read
     * cycle unaltered and does not corrupt the table — which is worth having, since
     * {@code cleanupEvents(String, Table)} DOES interpolate its argument into SQL directly.
     */
    @Test
    public void quotesAndBackslashesInAValueDoNotCorruptTheRow() throws JSONException {
        final String nasty = "it's a \"test\"; DROP TABLE events; -- \\";
        db.addJSON(event(nasty), EVENTS);

        final JSONArray out = queued();
        assertEquals("the row must survive intact", nasty, out.getJSONObject(0).getString("event"));
        assertEquals("the table must still exist", 2, db.addJSON(event("after"), EVENTS));
    }

    /**
     * A large event must be stored rather than truncated. Truncation would corrupt the JSON and
     * the row would then be silently skipped on read, which looks identical to never having been
     * tracked.
     */
    @Test
    public void aLargeEventIsStoredWithoutTruncation() throws JSONException {
        final StringBuilder big = new StringBuilder();
        for (int i = 0; i < 20000; i++) {
            big.append('x');
        }
        db.addJSON(new JSONObject().put("event", "big").put("blob", big.toString()), EVENTS);

        final JSONArray out = queued();
        assertEquals(1, out.length());
        assertEquals(20000, out.getJSONObject(0).getString("blob").length());
    }

    private static void waitForTheClockToAdvancePast(long timestamp) {
        while (System.currentTimeMillis() <= timestamp) {
            Thread.yield();
        }
    }
}
