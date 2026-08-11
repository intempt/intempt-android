package com.intempt.core.queue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Guards the ingestion wire format against drift.
 *
 * <p>These assertions encode what the SDK sends today from
 * {@code EventPoolManagerService.generateTrackRequestBody}. The durable-queue work must
 * not change the bytes on the wire — if it did, a delivery regression and a format
 * regression would be indistinguishable.
 */
// Robolectric is required: android.org.json is a stub in plain JVM unit tests and
// throws "not mocked" on construction. Every other test file in this module runs
// under RobolectricTestRunner for the same reason.
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {34}, manifest = Config.NONE)
public class TrackPayloadBuilderTest {

    private static final String ONE_EVENT =
            "[{\"name\":\"Purchase\",\"type\":\"track\","
                    + "\"payload\":[{\"eventId\":\"ev_1\",\"profileId\":\"prof_1\"}]}]";

    @Test
    public void wrapsTheStoredBatchUnderTheTrackKey() throws Exception {
        JSONObject body = TrackPayloadBuilder.build(ONE_EVENT);

        assertTrue(body.has("track"));
        assertEquals(1, body.getJSONArray("track").length());
    }

    @Test
    public void preservesNameTypeAndPayloadExactly() throws Exception {
        JSONObject entry = TrackPayloadBuilder.build(ONE_EVENT).getJSONArray("track").getJSONObject(0);

        // type is carried today and the endpoint accepts it; dropping it here would be a
        // silent wire change smuggled into a reliability fix.
        assertEquals("Purchase", entry.getString("name"));
        assertEquals("track", entry.getString("type"));
        assertEquals(1, entry.getJSONArray("payload").length());
        assertEquals("ev_1", entry.getJSONArray("payload").getJSONObject(0).getString("eventId"));
    }

    @Test
    public void keepsEveryEventInTheBatch() throws Exception {
        String two =
                "[{\"name\":\"A\",\"type\":\"track\",\"payload\":[{\"eventId\":\"ev_1\"}]},"
                        + "{\"name\":\"B\",\"type\":\"track\",\"payload\":[{\"eventId\":\"ev_2\"}]}]";

        JSONArray track = TrackPayloadBuilder.build(two).getJSONArray("track");

        assertEquals(2, track.length());
        assertEquals("A", track.getJSONObject(0).getString("name"));
        assertEquals("B", track.getJSONObject(1).getString("name"));
    }

    @Test
    public void doesNotGroupSameNamedEventsYet() throws Exception {
        // The contract permits collapsing repeats of one name into a single entry with a
        // multi-element payload, but the SDK does not do that today. Asserting the current
        // behaviour means a future grouping change has to be deliberate.
        String twoSameName =
                "[{\"name\":\"Purchase\",\"type\":\"track\",\"payload\":[{\"eventId\":\"ev_1\"}]},"
                        + "{\"name\":\"Purchase\",\"type\":\"track\",\"payload\":[{\"eventId\":\"ev_2\"}]}]";

        assertEquals(2, TrackPayloadBuilder.build(twoSameName).getJSONArray("track").length());
    }

    @Test
    public void emptyBatchProducesAnEmptyTrackArray() throws Exception {
        assertEquals(0, TrackPayloadBuilder.build("[]").getJSONArray("track").length());
    }

    @Test
    public void nullInputReturnsNull() {
        assertNull(TrackPayloadBuilder.build(null));
    }

    @Test
    public void malformedBatchReturnsNullRatherThanThrowing() {
        // sendData treats null as "drop this batch". Throwing here would escape into the
        // worker's handleMessage and kill the delivery thread.
        assertNull(TrackPayloadBuilder.build("{not json"));
        assertNull(TrackPayloadBuilder.build("{\"track\":[]}"));
    }
}
