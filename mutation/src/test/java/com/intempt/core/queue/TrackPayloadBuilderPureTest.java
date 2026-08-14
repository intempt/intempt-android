package com.intempt.core.queue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * {@code TrackPayloadBuilder} under plain JUnit, so PIT can mutate it.
 *
 * <p>Lives in the mutation module rather than beside the other queue tests for one reason: it needs
 * {@code org.json}, and under {@code :app}'s unit-test task that resolves to the stub android.jar
 * where every method throws "not mocked". The pre-existing TrackPayloadBuilderTest works around
 * that with Robolectric — and Robolectric is exactly what stops PIT running, so a Robolectric test
 * cannot serve this purpose. This module has the real Maven org.json instead.
 *
 * <p>What is being protected: this class builds the envelope every event is posted inside. Coverage
 * already said its lines ran. Mutation testing is what says the tests would notice if the envelope
 * key changed, or if a malformed batch started producing an empty body instead of none — the latter
 * would post something the endpoint rejects, and delivery would then drop a batch as a permanent
 * client error when nothing was actually wrong with it.
 */
public class TrackPayloadBuilderPureTest {

    @Test
    public void aBatchIsWrappedInTheTrackEnvelope() throws Exception {
        final JSONObject body = TrackPayloadBuilder.build("[{\"name\":\"Viewed\",\"type\":\"track\"}]");

        assertNotNull(body);
        assertTrue(
                "the platform routes on the envelope key, so its absence means the batch is rejected",
                body.has(TrackPayloadBuilder.TRACK_KEY));
        assertEquals(1, body.getJSONArray(TrackPayloadBuilder.TRACK_KEY).length());
        assertEquals(
                "Viewed",
                body.getJSONArray(TrackPayloadBuilder.TRACK_KEY).getJSONObject(0).getString("name"));
    }

    /** The envelope key is on the wire, so it is pinned as a literal rather than read back. */
    @Test
    public void theEnvelopeKeyIsTrack() {
        assertEquals("track", TrackPayloadBuilder.TRACK_KEY);
    }

    @Test
    public void everyEventInTheBatchSurvivesIntoTheEnvelope() throws Exception {
        final JSONArray batch = new JSONArray();
        for (int i = 0; i < 40; i++) {
            batch.put(new JSONObject().put("name", "e" + i).put("type", "track"));
        }

        final JSONObject body = TrackPayloadBuilder.build(batch.toString());

        assertNotNull(body);
        final JSONArray out = body.getJSONArray(TrackPayloadBuilder.TRACK_KEY);
        assertEquals("dropping events during assembly would be silent", 40, out.length());
        assertEquals("order must be preserved", "e0", out.getJSONObject(0).getString("name"));
        assertEquals("e39", out.getJSONObject(39).getString("name"));
    }

    /**
     * Null in, null out. Returning an empty envelope instead would POST a body the endpoint
     * rejects, and delivery would then treat a client error as a permanent failure and drop a batch
     * that was never malformed in the first place.
     */
    @Test
    public void aNullBatchProducesNoBodyRatherThanAnEmptyEnvelope() {
        assertNull(TrackPayloadBuilder.build(null));
    }

    @Test
    public void aMalformedBatchProducesNoBody() {
        assertNull(TrackPayloadBuilder.build("{not json"));
        assertNull("a JSON object where an array belongs must be refused", TrackPayloadBuilder.build("{\"a\":1}"));
        assertNull(TrackPayloadBuilder.build("[[[["));
    }

    /** An empty batch is still a valid array, and must produce an envelope with an empty list. */
    @Test
    public void anEmptyBatchStillProducesAnEnvelope() throws Exception {
        final JSONObject body = TrackPayloadBuilder.build("[]");

        assertNotNull(body);
        assertEquals(0, body.getJSONArray(TrackPayloadBuilder.TRACK_KEY).length());
    }

    @Test
    public void unicodeAndEmojiSurviveTheEnvelope() throws Exception {
        final String name = "购买 🎉 ünïcødé";
        final JSONObject body =
                TrackPayloadBuilder.build(new JSONArray().put(new JSONObject().put("name", name)).toString());

        assertNotNull(body);
        assertEquals(
                name,
                body.getJSONArray(TrackPayloadBuilder.TRACK_KEY).getJSONObject(0).getString("name"));
    }

}
