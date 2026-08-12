package com.intempt.core.queue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Drives the SDK's real transport code against the live ingestion endpoint.
 *
 * The delivery leg is the one part of the chain that queue tests cannot prove: they show an
 * event reaching SQLite, not the gateway accepting it. The instrumented suite covers it on a
 * device, but an emulator needs 7GB of free disk and a working DNS resolver, and neither is
 * always available. HttpService is plain Java over HttpURLConnection, so the same code path
 * runs on the JVM — which makes the delivery leg testable anywhere.
 *
 * This asserts against production deliberately. A stub server would prove the client speaks
 * HTTP; only the real gateway proves the auth header is accepted, the envelope key is the one
 * it reads, and the status it answers with is one the queue treats as success. Getting any of
 * those wrong loses events silently in the field.
 *
 * Skips when credentials are absent, which is the normal state for a contributor and for CI
 * without secrets. A missing secret must not read as a broken SDK.
 */
@RunWith(RobolectricTestRunner.class)
public class ProdDeliveryTest {

    private static String prop(String name) {
        String v = System.getProperty(name);
        return v == null ? "" : v.trim();
    }

    /**
     * Opt-in. These tests reach the live ingestion endpoint, so they are not part of the
     * ordinary unit-test run: an outage or a dropped connection would fail pull requests that
     * have nothing to do with delivery. CI runs them in a separate job that is allowed to
     * fail, and a developer runs them with -Pintempt.prodTests=true.
     */
    private static boolean enabled() {
        return Boolean.parseBoolean(prop("intempt.prodTests"));
    }

    private static boolean credentialsPresent() {
        return !prop("intempt.apiKey").isEmpty()
                && !prop("intempt.organization").isEmpty()
                && !prop("intempt.project").isEmpty()
                && !prop("intempt.sourceId").isEmpty();
    }

    private static String trackUrl() {
        return "https://api.intempt.com/v1/"
                + prop("intempt.organization")
                + "/projects/"
                + prop("intempt.project")
                + "/sources/"
                + prop("intempt.sourceId")
                + "/track";
    }

    /**
     * The auth header exactly as ConfigManagerService builds it: the key split on ".", joined
     * with ":", Base64 with NO_WRAP. NO_WRAP matters — the default inserts newlines, which
     * corrupts an HTTP header, and that was a real bug on this branch.
     */
    private static String authHeader() {
        String[] parts = prop("intempt.apiKey").split("\\.", 2);
        String raw = parts[0] + ":" + (parts.length > 1 ? parts[1] : "");
        return "Basic " + Base64.encodeToString(raw.getBytes(), Base64.NO_WRAP);
    }

    /** One event in the envelope shape TrackPayloadBuilder produces. */
    private static byte[] body(String eventName) throws Exception {
        JSONObject payloadEntry = new JSONObject();
        payloadEntry.put("sessionId", "ses_" + UUID.randomUUID());
        payloadEntry.put("eventId", "ev_" + UUID.randomUUID());
        payloadEntry.put("pageId", "pag_" + UUID.randomUUID());
        payloadEntry.put("profileId", "prof_" + UUID.randomUUID());
        payloadEntry.put("timestamp", System.currentTimeMillis());
        payloadEntry.put("data", new JSONObject().put("source", "android-sdk-jvm-e2e"));

        JSONObject event = new JSONObject();
        event.put("name", eventName);
        event.put("type", "track");
        event.put("payload", new JSONArray().put(payloadEntry));

        return new JSONObject()
                .put(TrackPayloadBuilder.TRACK_KEY, new JSONArray().put(event))
                .toString()
                .getBytes("UTF-8");
    }

    private static Map<String, String> headers() {
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", authHeader());
        h.put("Content-Type", "application/json");
        return h;
    }

    /**
     * The whole point: the gateway accepts what this SDK sends, and answers with a status the
     * queue reads as success. If it answered 201 and HttpService only accepted 200, every
     * batch would be retried forever and the queue head would wedge — so the two assertions
     * belong together.
     */
    @Test
    public void theLiveEndpointAcceptsWhatTheSdkSends() throws Exception {
        assumeFalse("prod tests are opt-in; run with -Pintempt.prodTests=true", !enabled());
        assumeFalse(
                "no prod credentials; set intempt.apiKey and friends in local.properties",
                !credentialsPresent());

        HttpService service = new HttpService();
        RemoteService.RequestResult result =
                service.performRequest(trackUrl(), null, null, headers(), body("jvm e2e delivery"), null);

        assertNotNull("no result from the transport layer", result);
        assertFalse(
                "the gateway rejected the request as a client error, so a real batch would be "
                        + "dropped or retried rather than delivered",
                result.isClientError());
        assertTrue(
                "the request did not succeed against " + trackUrl(),
                result.isSuccess());
    }

    /**
     * A deliberately bad key must come back as a client error, not as a retryable failure.
     * This is the difference between dropping one batch and wedging the queue permanently:
     * HttpStatusPolicy can only drop what the transport reports as a client error.
     */
    @Test
    public void aRejectedKeyIsReportedAsAClientError() throws Exception {
        assumeFalse("prod tests are opt-in; run with -Pintempt.prodTests=true", !enabled());
        assumeFalse("no prod credentials", !credentialsPresent());

        Map<String, String> bad = new HashMap<>();
        bad.put("Authorization", "Basic " + Base64.encodeToString("nope:nope".getBytes(), Base64.NO_WRAP));
        bad.put("Content-Type", "application/json");

        HttpService service = new HttpService();
        RemoteService.RequestResult result = null;
        boolean threwClientError = false;
        try {
            result = service.performRequest(trackUrl(), null, null, bad, body("jvm e2e bad key"), null);
        } catch (RemoteService.ClientErrorException e) {
            threwClientError = true;
            assertTrue(
                    "a rejected key should be an unrecoverable status this SDK drops, got "
                            + e.getResponseCode(),
                    HttpStatusPolicy.shouldDrop(e.getResponseCode()));
            assertFalse(
                    "a rejected key must not be retryable, or the queue head wedges forever",
                    HttpStatusPolicy.isRetryable(e.getResponseCode()));
        }

        if (!threwClientError) {
            assertNotNull(result);
            assertTrue(
                    "a rejected key must surface as a client error rather than a success",
                    result.isClientError());
        }
    }

    /** The envelope key the gateway reads. Hardcoded here so a rename cannot pass silently. */
    @Test
    public void theEnvelopeKeyIsTrack() {
        assertEquals("track", TrackPayloadBuilder.TRACK_KEY);
    }
}
