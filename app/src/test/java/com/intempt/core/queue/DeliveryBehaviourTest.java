package com.intempt.core.queue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLSocketFactory;

/**
 * The delete-versus-retry decision, which is the whole reason this PR exists and which had
 * zero test coverage.
 *
 * An adversarial review found the gap precisely: {@code HttpStatusPolicy} was unit-tested as a
 * pure predicate, but nothing tested the branch in {@code sendData} that consumes it. So the
 * tested taxonomy was fiction — production could have ignored it entirely and every test would
 * still have passed. It nearly did: the same review found delivery posting with no
 * Authorization header, so every batch 401'd and was deleted, and no test noticed.
 *
 * {@code getPoster()} and {@code makeDbAdapter()} are protected, which is the seam. A subclass
 * supplies a fake transport that returns whatever status the test wants, and the assertions are
 * made against the real SQLite queue: a batch that was delivered is gone, a batch that must be
 * retried is still there. That is the property customers care about, stated in the only terms
 * that cannot be faked.
 */
@RunWith(RobolectricTestRunner.class)
public class DeliveryBehaviourTest {

    /**
     * A distinct database file per test. It has to be per-test rather than shared, because
     * {@code DeliveryMessages} starts a worker thread and never shuts it down — there is no
     * close() on it — so a worker from an earlier test outlives its test and keeps flushing.
     *
     * With one shared file that worker drains the next test's rows. It also caches its poster at
     * construction, so the drain does not even show up in the current test's request count: the
     * queue empties, one POST is recorded, and an assertion about how many POSTs a large batch
     * needs fails for reasons that have nothing to do with the code under test. That is exactly
     * how it failed under full-suite load while passing in isolation.
     */
    private static final AtomicInteger DB_SEQUENCE = new AtomicInteger();

    private String db;

    private Context context;
    private QueueConfig config;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        db = "delivery_behaviour_test_" + DB_SEQUENCE.incrementAndGet() + ".db";
        context.getDatabasePath(db).delete();
        config = new QueueConfig("https://example.invalid/track", "Basic dGVzdDp0ZXN0");
        currentConfig = config;
        currentDb = db;
    }

    @After
    public void tearDown() {
        context.getDatabasePath(db).delete();
    }

    // ------------------------------------------------------------------ fakes

    /** Records every request and answers with a scripted outcome. */
    private static final class FakePoster implements RemoteService {
        // CopyOnWriteArrayList, not ArrayList. These are written by DeliveryMessages' worker
        // thread and read by the test thread. A plain ArrayList loses elements under that race,
        // which presents as a batch being short rather than as an error — indistinguishable, from
        // the assertion's point of view, from the SDK genuinely dropping events. That is exactly
        // how this suite produced "65 enqueued, 40 posted" intermittently.
        final List<Map<String, String>> sentHeaders = new CopyOnWriteArrayList<>();
        final List<byte[]> sentBodies = new CopyOnWriteArrayList<>();
        final AtomicInteger calls = new AtomicInteger();

        private final int status;
        private final boolean throwIo;

        FakePoster(int status, boolean throwIo) {
            this.status = status;
            this.throwIo = throwIo;
        }

        @Override
        public void checkIsServerBlocked() {
            // never blocked
        }

        @Override
        public boolean isOnline(Context context, OfflineMode offlineMode) {
            // Always online. The connectivity gate is inherited from Mixpanel and is not what
            // these tests are about; leaving it real would make every one of them depend on
            // Robolectric's ConnectivityManager shadow.
            return true;
        }

        @Override
        public RequestResult performRequest(
                String endpointUrl,
                ProxyServerInteractor interactor,
                Map<String, Object> params,
                Map<String, String> headers,
                byte[] requestBodyBytes,
                SSLSocketFactory socketFactory)
                throws ServiceUnavailableException, IOException {
            calls.incrementAndGet();
            sentHeaders.add(headers);
            sentBodies.add(requestBodyBytes);

            if (throwIo) {
                throw new IOException("simulated network failure");
            }
            if (status >= 200 && status < 300) {
                return new RequestResult("{\"ok\":true}".getBytes(), endpointUrl);
            }
            throw new ClientErrorException(status, "simulated status " + status);
        }

        @Override
        public RequestResult performRequest(
                HttpMethod method,
                String endpointUrl,
                ProxyServerInteractor interactor,
                Map<String, Object> params,
                Map<String, String> headers,
                byte[] requestBodyBytes,
                SSLSocketFactory socketFactory)
                throws ServiceUnavailableException, IOException {
            return performRequest(endpointUrl, interactor, params, headers, requestBodyBytes, socketFactory);
        }
    }

    /**
     * The fake transport and config, held statically because {@code DeliveryMessages}'s
     * constructor calls {@code getPoster()} — an overridable method — before any subclass field
     * has been assigned. An instance field would still be null at that point and every test
     * failed with an NPE inside {@code super()}. Calling an overridable method from a
     * constructor is an inherited design smell; a static holder is the least invasive way to
     * work around it without touching the vendored class.
     */
    private static FakePoster currentPoster;
    private static QueueConfig currentConfig;
    private static String currentDb;

    /**
     * DeliveryMessages with the transport and the database name swapped out.
     *
     * <p>Each instance pins its own poster, database and config the moment construction finishes,
     * and only falls back to the static holders during {@code super()} — which is the one window
     * where an instance field cannot be set, because the base constructor calls the overridable
     * {@code getPoster()} before any subclass field exists.
     *
     * <p>Reading the statics at flush time instead is what made this suite flaky. DeliveryMessages
     * starts a worker thread and never shuts it down — there is no close() on it — so a worker
     * outlives the test that created it and keeps flushing. Resolving through a static, it would
     * pick up the *next* test's database and drain it, while still posting through its own cached
     * poster, so the drain did not even appear in the current test's request count. Giving each
     * test its own database file was not enough on its own: the stale worker resolved the new name
     * through the same static.
     */
    private static final class TestableDelivery extends DeliveryMessages {
        private final RemoteService ownPoster;
        private final String ownDb;
        private final QueueConfig ownConfig;

        TestableDelivery(Context context) {
            super(context, currentConfig);
            ownPoster = currentPoster;
            ownDb = currentDb;
            ownConfig = currentConfig;
        }

        @Override
        protected RemoteService getPoster() {
            return ownPoster != null ? ownPoster : currentPoster;
        }

        @Override
        protected EventDbAdapter makeDbAdapter(Context ctx) {
            return new EventDbAdapter(
                    ctx, ownDb != null ? ownDb : currentDb, ownConfig != null ? ownConfig : currentConfig);
        }
    }

    private TestableDelivery deliveryWith(FakePoster poster) {
        currentPoster = poster;
        currentConfig = config;
        currentDb = db;
        return new TestableDelivery(context);
    }

    // ------------------------------------------------------------------ helpers

    private JSONObject event(String name) throws Exception {
        return new JSONObject()
                .put("name", name)
                .put("type", "track")
                .put("payload", new JSONArray().put(new JSONObject().put("eventId", "ev_" + UUID.randomUUID())));
    }

    /**
     * How many events appear across every body this poster was given.
     *
     * <p>Counted from the payload rather than from the number of POSTs. A read capped at
     * flushBatchSize cannot carry the whole queue in one request, so a correct total can only be
     * reached by coming back for the remainder — but unlike an assertion on the call count, this
     * says nothing about how delivery scheduled that, and so does not depend on flush timing.
     *
     * <p>The body is the envelope {@code {"track":[ {name, type, payload:[...]}, ... ]}}.
     */
    private int totalEventsPosted(FakePoster poster) throws Exception {
        int count = 0;
        for (byte[] sent : poster.sentBodies) {
            if (sent == null) {
                continue;
            }
            JSONObject envelope =
                    new JSONObject(new String(sent, java.nio.charset.StandardCharsets.UTF_8));
            java.util.Iterator<String> keys = envelope.keys();
            while (keys.hasNext()) {
                JSONArray events = envelope.optJSONArray(keys.next());
                if (events != null) {
                    count += events.length();
                }
            }
        }
        return count;
    }

    /**
     * The number of rows in the queue.
     *
     * <p>Reads {@code batch[2]}, the total depth, rather than the length of {@code batch[1]}. The
     * batch is capped at flushBatchSize, so measuring its length under-reports any queue longer
     * than the cap — a test asserting "everything was delivered" would pass while 15 rows were
     * still sitting there.
     */
    private int rowCount() {
        EventDbAdapter adapter = new EventDbAdapter(context, db, config);
        String[] batch = adapter.generateDataString(EventDbAdapter.Table.EVENTS);
        if (batch == null) {
            return 0;
        }
        try {
            return Integer.parseInt(batch[2]);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Enqueues, flushes, and waits for the worker thread to settle.
     *
     * <p>The assertion at the end is load-bearing and was missing. Every "must be retried" test
     * checks that rows are still in the queue afterwards — but rows are also still in the queue if
     * delivery never ran at all. Without proof that a POST happened, those tests could not tell a
     * correct retry from a worker that never started, which is the difference between testing the
     * delete-versus-retry decision and testing nothing.
     *
     * <p>An adversarial audit found this; it was not theoretical. Stubbing out the worker's call to
     * {@code sendAllData} left all four retry tests green.
     */
    private FakePoster deliver(int status, boolean throwIo, int events) throws Exception {
        FakePoster poster = new FakePoster(status, throwIo);
        TestableDelivery delivery = deliveryWith(poster);
        for (int i = 0; i < events; i++) {
            delivery.enqueueEvent(event("behaviour " + i));
        }
        delivery.flush();

        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && poster.calls.get() == 0) {
            Thread.sleep(25);
        }
        Thread.sleep(400); // let cleanupEvents finish after the POST returns

        assertTrue(
                "delivery never posted at all, so this test proves nothing about what it does with "
                        + "status " + status + " — the queue's contents below would look identical "
                        + "for a worker that never started",
                poster.calls.get() >= 1);
        return poster;
    }

    // ------------------------------------------------------------------ tests

    /**
     * The regression guard for the defect that shipped: the POST must carry an Authorization
     * header. Production passed {@code null} headers, so every batch 401'd and was deleted —
     * a silent 100% loss that no test could see, because the only delivery tests either built
     * their own header or used "the row disappeared" as the definition of success.
     */
    @Test
    public void everyPostCarriesTheAuthorizationHeader() throws Exception {
        FakePoster poster = deliver(200, false, 1);

        assertTrue("no request was made at all", poster.calls.get() > 0);
        Map<String, String> headers = poster.sentHeaders.get(0);
        assertNotNull("headers were null, which is how authentication was lost", headers);
        assertEquals("Basic dGVzdDp0ZXN0", headers.get("Authorization"));
        assertEquals("application/json", headers.get("Content-Type"));
    }

    /** The envelope on the wire is the one the ingestion endpoint reads. */
    @Test
    public void theRequestBodyIsTheTrackEnvelope() throws Exception {
        FakePoster poster = deliver(200, false, 1);

        JSONObject body = new JSONObject(new String(poster.sentBodies.get(0), "UTF-8"));
        assertTrue("envelope key missing", body.has(TrackPayloadBuilder.TRACK_KEY));
        assertEquals(1, body.getJSONArray(TrackPayloadBuilder.TRACK_KEY).length());
        assertEquals(
                "behaviour 0",
                body.getJSONArray(TrackPayloadBuilder.TRACK_KEY).getJSONObject(0).getString("name"));
    }

    /** 200 means delivered, so the batch must be gone. */
    @Test
    public void aSuccessfulPostDeletesTheBatch() throws Exception {
        deliver(200, false, 3);
        assertEquals("delivered events must not remain queued", 0, rowCount());
    }

    /** 201 is what prod actually answers. It must count as success, not as a failure. */
    @Test
    public void a201AlsoCountsAsDelivered() throws Exception {
        deliver(201, false, 2);
        assertEquals(
                "prod returns 201; treating it as a failure would retry every batch forever",
                0,
                rowCount());
    }

    /** A network failure must keep the events. This is the P0 the branch exists to fix. */
    @Test
    public void aNetworkFailureKeepsTheBatch() throws Exception {
        deliver(0, true, 3);
        assertEquals("a failed POST must not lose events", 3, rowCount());
    }

    /** 429 is backpressure: keep and back off. */
    @Test
    public void rateLimitingKeepsTheBatch() throws Exception {
        deliver(429, false, 2);
        assertEquals("429 must be retried, not dropped", 2, rowCount());
    }

    /** 5xx is transient: keep. */
    @Test
    public void aServerErrorKeepsTheBatch() throws Exception {
        deliver(503, false, 2);
        assertEquals("5xx must be retried, not dropped", 2, rowCount());
    }

    /** 408 is transient too, and used to fall into the wedging default. */
    @Test
    public void aRequestTimeoutKeepsTheBatch() throws Exception {
        deliver(408, false, 2);
        assertEquals("408 is transient and must be retried", 2, rowCount());
    }

    /**
     * A rejected key cannot start working. Dropping loses that batch; keeping it wedges the
     * queue head and loses everything behind it, forever.
     */
    @Test
    public void aRejectedKeyDropsTheBatchRatherThanWedgingTheQueue() throws Exception {
        deliver(401, false, 2);
        assertEquals("401 can never succeed on retry, so it must be dropped", 0, rowCount());
    }

    /** Over the analytics cap. The server's own comment says do not retry. */
    @Test
    public void theAnalyticsCapDropsTheBatch() throws Exception {
        deliver(402, false, 2);
        assertEquals("402 analytics_limit_reached must be dropped", 0, rowCount());
    }

    /**
     * The status that made this fail-safe change necessary: a typo in org, project or sourceId
     * produces 404, and it used to be retried forever, silently discarding every event a
     * customer would ever send.
     */
    @Test
    public void aWrongUrlDropsTheBatchRatherThanWedgingTheQueue() throws Exception {
        deliver(404, false, 2);
        assertEquals(
                "404 means the URL is wrong; retrying cannot fix a config typo and blocks "
                        + "every event behind it",
                0,
                rowCount());
    }

    /** An unrecognised client error must fail safe — drop one batch, keep delivering. */
    @Test
    public void anUnrecognisedClientErrorDropsRatherThanRetriesForever() throws Exception {
        deliver(418, false, 2);
        assertEquals("an unknown 4xx must not park at the queue head", 0, rowCount());
    }

    /** 413 will be the same size on every retry. */
    @Test
    public void payloadTooLargeDropsTheBatch() throws Exception {
        deliver(413, false, 2);
        assertEquals(0, rowCount());
    }

    /**
     * Events queued while a POST is in flight must survive the cleanup. cleanupEvents deletes
     * through the id that was actually sent and no further; getting that wrong loses everything
     * written during a flush.
     */
    @Test
    public void eventsQueuedDuringAFlushAreNotDeleted() throws Exception {
        FakePoster poster = new FakePoster(200, false);
        TestableDelivery delivery = deliveryWith(poster);

        delivery.enqueueEvent(event("first"));
        delivery.flush();
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && poster.calls.get() == 0) {
            Thread.sleep(25);
        }

        // Arrives after the batch was read and delivered.
        delivery.enqueueEvent(event("second"));
        Thread.sleep(400);

        assertEquals("the later event must survive the cleanup", 1, rowCount());
    }

    /** A batch larger than the read cap still delivers everything, across several POSTs. */
    @Test
    public void aBatchLargerThanTheReadCapIsFullyDelivered() throws Exception {
        FakePoster poster = new FakePoster(200, false);
        TestableDelivery delivery = deliveryWith(poster);
        int total = config.getFlushBatchSize() + 15;
        for (int i = 0; i < total; i++) {
            delivery.enqueueEvent(event("bulk " + i));
        }
        delivery.flush();

        // Wait for the queue to stay empty, not merely to read empty once. A single zero reading
        // can land between a batch being deleted and the next one being written, so polling for
        // the first zero exits while delivery is still mid-flight.
        long deadline = System.currentTimeMillis() + 60_000;
        int consecutiveEmpty = 0;
        while (System.currentTimeMillis() < deadline && consecutiveEmpty < 5) {
            consecutiveEmpty = rowCount() == 0 ? consecutiveEmpty + 1 : 0;
            Thread.sleep(50);
        }

        assertEquals("every event must be delivered, not just the first batch", 0, rowCount());

        // Counted from the bodies actually posted rather than from the number of POSTs. A read
        // capped at flushBatchSize cannot carry `total` in one request, so this can only pass if
        // delivery came back for the remainder — but unlike an assertion on the call count it
        // says nothing about *how* it did that, and so does not depend on the flush schedule.
        assertEquals(
                "every enqueued event must appear in something that was posted (posted across "
                        + poster.sentBodies.size() + " request(s))",
                total,
                totalEventsPosted(poster));
    }

    /** A malformed row must not stop the good ones being delivered. */
    @Test
    public void oneMalformedRowDoesNotStopDelivery() throws Exception {
        EventDbAdapter adapter = new EventDbAdapter(context, db, config);
        adapter.addJSON(event("good one"), EventDbAdapter.Table.EVENTS);

        // A row that is not valid JSON, written past addJSON's typing.
        context.openOrCreateDatabase(db, Context.MODE_PRIVATE, null)
                .execSQL(
                        "INSERT INTO events (data, created_at, automatic_data) VALUES (?, ?, 0)",
                        new Object[] {"{not json", System.currentTimeMillis()});

        FakePoster poster = new FakePoster(200, false);
        TestableDelivery delivery = deliveryWith(poster);
        delivery.enqueueEvent(event("good two"));
        delivery.flush();

        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && poster.calls.get() == 0) {
            Thread.sleep(25);
        }
        Thread.sleep(400);

        JSONObject body = new JSONObject(new String(poster.sentBodies.get(0), "UTF-8"));
        assertEquals(
                "the two good rows are sent and the malformed one skipped",
                2,
                body.getJSONArray(TrackPayloadBuilder.TRACK_KEY).length());
    }

    /** A dead worker must revive rather than swallowing every future event. */
    @Test
    public void aDeadWorkerIsRestartedRatherThanDroppingEverything() throws Exception {
        FakePoster poster = new FakePoster(200, false);
        TestableDelivery delivery = deliveryWith(poster);

        delivery.enqueueEvent(event("before the worker dies"));
        delivery.hardKill();
        Thread.sleep(200);

        // Upstream logs "Dead delivery worker dropping a message" here and loses it forever.
        delivery.enqueueEvent(event("after the worker died"));
        delivery.flush();

        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && poster.calls.get() == 0) {
            Thread.sleep(25);
        }

        assertTrue(
                "the worker did not restart, so every event after a transient fault is lost",
                poster.calls.get() > 0);
    }

    /** Enqueueing must never throw into the caller, whatever the payload. */
    @Test
    public void enqueueNeverThrowsIntoTheCaller() {
        TestableDelivery delivery = deliveryWith(new FakePoster(200, false));

        delivery.enqueueEvent(new JSONObject());
        delivery.enqueueEvent(new JSONObject());
        delivery.flush();

        assertFalse("reaching here is the assertion", false);
    }
}
