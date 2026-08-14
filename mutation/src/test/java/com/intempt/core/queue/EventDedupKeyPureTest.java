package com.intempt.core.queue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * {@code EventDedupKey} under plain JUnit, so PIT can mutate it. See
 * {@code TrackPayloadBuilderPureTest} for why this lives here rather than beside the other
 * queue tests: it needs real {@code org.json}, which the mutation module provides from Maven
 * instead of the stub android.jar that throws "not mocked" under {@code :app}'s unit-test task.
 *
 * <p>What is being protected: this class decides whether two queued rows are "the same event"
 * for {@code EventDbAdapter}'s UNIQUE constraint. Too loose collapses two different events into
 * one and silently loses one of them; too strict lets a real duplicate through and an event is
 * billed or counted twice downstream. Both failures are invisible at the call site.
 */
public class EventDedupKeyPureTest {

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
    public void theSameEventIdProducesTheSameKey() throws Exception {
        assertEquals(EventDedupKey.extract(row("abc-123")), EventDedupKey.extract(row("abc-123")));
    }

    @Test
    public void differentEventIdsProduceDifferentKeys() throws Exception {
        assertNotEquals(EventDedupKey.extract(row("abc-123")), EventDedupKey.extract(row("def-456")));
    }

    @Test
    public void aNullRowHasNoKey() {
        assertNull(EventDedupKey.extract(null));
    }

    @Test
    public void aRowWithNoPayloadFieldHasNoKey() throws Exception {
        assertNull(EventDedupKey.extract(new JSONObject().put("name", "x")));
    }

    @Test
    public void anEmptyPayloadArrayHasNoKey() throws Exception {
        assertNull(EventDedupKey.extract(new JSONObject().put("payload", new JSONArray())));
    }

    @Test
    public void aPayloadEntryWithNoEventIdProducesNoKey() throws Exception {
        assertNull(EventDedupKey.extract(row((String) null)));
    }

    @Test
    public void aBatchDoesNotCollideWithItsOwnPrefix() throws Exception {
        assertNotEquals(EventDedupKey.extract(row("id1", "id2")), EventDedupKey.extract(row("id1")));
    }

    @Test
    public void aBatchDoesNotCollideWithAPlaceholderExtension() throws Exception {
        assertNotEquals(EventDedupKey.extract(row("id1")), EventDedupKey.extract(row("id1", null)));
    }

    @Test
    public void orderWithinThePayloadIsSignificant() throws Exception {
        assertNotEquals(EventDedupKey.extract(row("id1", "id2")), EventDedupKey.extract(row("id2", "id1")));
    }

    /**
     * Pins the exact key shape rather than just comparing two calls against each other, so an
     * off-by-one in the loop bound or the comma-placement check (both boundary conditions)
     * produces a visibly wrong string instead of surviving as an unnoticed extra character.
     */
    @Test
    public void aSingleIdProducesTheIdWithNoStraySeparator() throws Exception {
        assertEquals("id1", EventDedupKey.extract(row("id1")));
    }

    @Test
    public void multipleIdsAreJoinedByASingleComma() throws Exception {
        assertEquals("id1,id2,id3", EventDedupKey.extract(row("id1", "id2", "id3")));
    }

    @Test
    public void theKeyConstantsAreTheDocumentedWireFields() {
        assertEquals("payload", EventDedupKey.PAYLOAD_KEY);
        assertEquals("eventId", EventDedupKey.EVENT_ID_KEY);
    }
}
