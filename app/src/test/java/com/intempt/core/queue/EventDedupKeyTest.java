package com.intempt.core.queue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * {@code EventDedupKey} decides whether two queued rows count as "the same event" for
 * {@code EventDbAdapter}'s dedup constraint. Getting either direction wrong is silent: too
 * loose and a real duplicate is inserted twice (double-billed events downstream); too strict
 * and two genuinely different events collapse into one (an event goes missing).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {34}, manifest = Config.NONE)
public class EventDedupKeyTest {

    private static JSONObject row(String... eventIds) throws Exception {
        final JSONArray payload = new JSONArray();
        for (String id : eventIds) {
            final JSONObject entry = new JSONObject();
            if (id != null) {
                entry.put("eventId", id);
            }
            payload.put(entry);
        }
        return new JSONObject().put("name", "Purchase").put("type", "track").put("payload", payload);
    }

    @Test
    public void twoRowsWithTheSameEventIdProduceTheSameKey() throws Exception {
        assertEquals(EventDedupKey.extract(row("abc-123")), EventDedupKey.extract(row("abc-123")));
    }

    @Test
    public void rowsWithDifferentEventIdsProduceDifferentKeys() throws Exception {
        assertNotEquals(EventDedupKey.extract(row("abc-123")), EventDedupKey.extract(row("def-456")));
    }

    @Test
    public void aRowWithNoPayloadArrayHasNoKey() throws Exception {
        assertNull(EventDedupKey.extract(new JSONObject().put("name", "x").put("type", "track")));
    }

    @Test
    public void aRowWithAnEmptyPayloadArrayHasNoKey() throws Exception {
        assertNull(EventDedupKey.extract(new JSONObject().put("payload", new JSONArray())));
    }

    @Test
    public void aNullRowHasNoKey() {
        assertNull(EventDedupKey.extract(null));
    }

    @Test
    public void payloadEntriesWithNoEventIdProduceNoKey() throws Exception {
        assertNull(EventDedupKey.extract(row((String) null)));
    }

    /**
     * A batch of two real events must never collide with a batch containing just the first of
     * them — the missing-id placeholder occupies its position rather than being dropped, so
     * [id1, missing] cannot equal [id1] alone.
     */
    @Test
    public void aPartiallyIdentifiedBatchDoesNotCollideWithItsPrefix() throws Exception {
        final String withTwo = EventDedupKey.extract(row("id1", null));
        final String withOne = EventDedupKey.extract(row("id1"));
        assertNotEquals(withTwo, withOne);
    }

    @Test
    public void orderWithinThePayloadMatters() throws Exception {
        assertNotEquals(EventDedupKey.extract(row("id1", "id2")), EventDedupKey.extract(row("id2", "id1")));
    }

    /**
     * Pins the exact key shape rather than just comparing two calls against each other. A
     * single id must produce that id verbatim — no leading or trailing comma from an
     * off-by-one in the loop bounds or the comma-placement check.
     */
    @Test
    public void aSingleIdProducesTheIdWithNoStraySeparator() throws Exception {
        assertEquals("id1", EventDedupKey.extract(row("id1")));
    }

    @Test
    public void multipleIdsAreJoinedByASingleComma() throws Exception {
        assertEquals("id1,id2,id3", EventDedupKey.extract(row("id1", "id2", "id3")));
    }
}
