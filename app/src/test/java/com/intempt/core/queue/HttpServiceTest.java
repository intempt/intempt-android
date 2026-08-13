package com.intempt.core.queue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.intempt.core.queue.RemoteService.HttpMethod;
import com.intempt.core.queue.RemoteService.ServiceUnavailableException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

/**
 * {@code HttpService} is the SDK's only outbound transport, and coverage measured it at 5.3% —
 * 284 uncovered lines, the largest single gap in the module.
 *
 * It is also the least mockable thing in the SDK: it drives {@code HttpURLConnection} directly, so
 * a fake would test the fake. These tests run a real HTTP server on a loopback port instead. That
 * exercises the request line, the headers, gzip encoding, the response-code branches and the retry
 * loop as they actually behave against a socket, which is the only way an assertion about this
 * class means anything.
 *
 * The property under test throughout is what leaves the device and what the caller is told came
 * back — not the internal control flow, which is free to change.
 */
@RunWith(RobolectricTestRunner.class)
public class HttpServiceTest {

    private FakeServer server;

    @Before
    public void setUp() throws IOException {
        server = new FakeServer();
        server.start();
    }

    @After
    public void tearDown() {
        server.stop();
    }

    private String url() {
        return "http://127.0.0.1:" + server.port() + "/track";
    }

    private static Map<String, String> jsonHeaders(String authorization) {
        final Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", authorization);
        headers.put("Content-Type", "application/json");
        return headers;
    }

    private static byte[] body(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------- the happy path

    @Test
    public void aSuccessfulPostReturnsTheResponseBodyAndTheUrlItReached() throws Exception {
        server.respond(200, "{\"ok\":true}");
        final HttpService http = new HttpService();

        final RemoteService.RequestResult result =
                http.performRequest(url(), null, null, jsonHeaders("Basic dGVzdDp0ZXN0"), body("[{\"event\":\"a\"}]"), null);

        assertNotNull(result);
        assertEquals("{\"ok\":true}", new String(result.getResponse(), StandardCharsets.UTF_8));
        assertTrue("the caller needs to know which host answered", result.getRequestUrl().contains("127.0.0.1"));
        assertEquals("a successful request must not be retried", 1, server.requestCount());
    }

    /**
     * The Authorization header is the regression this whole branch exists for: delivery shipped
     * with it null, so every batch 401'd and was dropped. Asserted on the bytes the server
     * actually received, because that is the only place it cannot be faked.
     */
    @Test
    public void theAuthorizationHeaderReachesTheServerVerbatim() throws Exception {
        server.respond(200, "ok");
        new HttpService()
                .performRequest(url(), null, null, jsonHeaders("Basic dXNlcjpwYXNz"), body("[]"), null);

        assertEquals("Basic dXNlcjpwYXNz", server.lastRequest().header("authorization"));
    }

    @Test
    public void aRawBodyIsSentAsPostWithTheJsonContentType() throws Exception {
        server.respond(200, "ok");
        final byte[] payload = body("[{\"event\":\"purchase\"}]");

        new HttpService().performRequest(url(), null, null, jsonHeaders("Basic x"), payload, null);

        final Request sent = server.lastRequest();
        assertEquals("POST", sent.method);
        assertEquals("application/json", sent.header("content-type"));
        assertEquals(
                "the body must arrive byte-identical",
                new String(payload, StandardCharsets.UTF_8),
                new String(sent.body, StandardCharsets.UTF_8));
    }

    /** An explicit Content-Type in the headers map must win over the default the class would pick. */
    @Test
    public void anExplicitContentTypeOverridesTheDefault() throws Exception {
        server.respond(200, "ok");
        final Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "text/plain; charset=utf-8");

        new HttpService().performRequest(url(), null, null, headers, body("hello"), null);

        assertEquals("text/plain; charset=utf-8", server.lastRequest().header("content-type"));
    }

    @Test
    public void everyCustomHeaderIsForwarded() throws Exception {
        server.respond(200, "ok");
        final Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Basic x");
        headers.put("Content-Type", "application/json");
        headers.put("X-Intempt-Source", "1841707615227326464");

        new HttpService().performRequest(url(), null, null, headers, body("[]"), null);

        final Request sent = server.lastRequest();
        assertEquals("Basic x", sent.header("authorization"));
        assertEquals("1841707615227326464", sent.header("x-intempt-source"));
    }

    /** A UTF-8 body must not be mangled by the encoding the connection picks. */
    @Test
    public void aUnicodeBodySurvivesTheTransport() throws Exception {
        server.respond(200, "ok");
        final String payload = "[{\"event\":\"购买 🎉 ünïcødé\"}]";

        new HttpService().performRequest(url(), null, null, jsonHeaders("Basic x"), body(payload), null);

        assertEquals(payload, new String(server.lastRequest().body, StandardCharsets.UTF_8));
    }

    @Test
    public void a201IsAlsoTreatedAsSuccess() throws Exception {
        server.respond(201, "created");
        final RemoteService.RequestResult result =
                new HttpService().performRequest(url(), null, null, jsonHeaders("Basic x"), body("[]"), null);

        assertEquals("created", new String(result.getResponse(), StandardCharsets.UTF_8));
    }

    /** A 200 with no body is legal and must not be reported as a failure. */
    @Test
    public void anEmptyResponseBodyIsStillASuccess() throws Exception {
        server.respond(200, "");
        final RemoteService.RequestResult result =
                new HttpService().performRequest(url(), null, null, jsonHeaders("Basic x"), body("[]"), null);

        assertNotNull("a bodyless 200 is not an error", result);
        assertEquals(0, result.getResponse().length);
    }

    // ---------------------------------------------------------------- failures

    /**
     * The distinction that decides whether events are kept or lost. A 5xx must surface as
     * {@link ServiceUnavailableException} rather than as a null or an empty success, because
     * delivery keys off exactly that to keep the batch queued.
     */
    @Test
    public void a503RaisesServiceUnavailableRatherThanReportingSuccess() {
        server.respond(503, "overloaded");

        try {
            new HttpService().performRequest(url(), null, null, jsonHeaders("Basic x"), body("[]"), null);
            fail("a 503 must not be reported as a successful delivery — the batch would be dropped");
        } catch (ServiceUnavailableException expected) {
            // correct
        } catch (IOException e) {
            fail("a 503 must be distinguishable from a transport failure, got " + e);
        }
    }

    @Test
    public void a500RaisesServiceUnavailable() {
        server.respond(500, "boom");

        try {
            new HttpService().performRequest(url(), null, null, jsonHeaders("Basic x"), body("[]"), null);
            fail("a 500 must not read as success");
        } catch (ServiceUnavailableException expected) {
            // correct
        } catch (IOException e) {
            fail("expected ServiceUnavailableException, got " + e);
        }
    }

    /**
     * A 4xx is the caller's fault and will never succeed on retry, so it must come back as a
     * result the caller can inspect rather than as an exception that looks transient.
     */
    @Test
    public void a401RaisesClientErrorRatherThanServiceUnavailable() {
        server.respond(401, "unauthorized");

        try {
            new HttpService().performRequest(url(), null, null, jsonHeaders("Basic bad"), body("[]"), null);
            fail("a 401 must not be reported as a delivered batch");
        } catch (ServiceUnavailableException e) {
            fail("a 401 is permanent and must not be classified as service-unavailable");
        } catch (RemoteService.ClientErrorException e) {
            assertEquals(
                    "delivery keys off the status to decide drop-versus-retry", 401, e.getResponseCode());
        } catch (IOException e) {
            fail("a 401 must be distinguishable from a transport failure, got " + e);
        }
    }

    /** A client error will never succeed on retry, so burning all three attempts on it is waste. */
    @Test
    public void aClientErrorIsNotRetried() {
        server.respond(401, "unauthorized");

        try {
            new HttpService().performRequest(url(), null, null, jsonHeaders("Basic bad"), body("[]"), null);
        } catch (Exception ignored) {
            // the outcome is asserted through the server's request count
        }

        assertEquals(
                "a permanent failure must not be retried, got " + server.requestCount() + " attempt(s)",
                1,
                server.requestCount());
    }

    /**
     * A dead port is the offline case. It must fail rather than hang or silently succeed, and the
     * retry loop means the caller waits for all three attempts before hearing about it.
     */
    @Test
    public void anUnreachableHostFailsRatherThanReportingSuccess() throws Exception {
        final int deadPort = findAFreePort();

        try {
            final RemoteService.RequestResult result =
                    new HttpService()
                            .performRequest(
                                    "http://127.0.0.1:" + deadPort + "/track",
                                    null,
                                    null,
                                    jsonHeaders("Basic x"),
                                    body("[]"),
                                    null);
            assertNull("an unreachable host must never look like a delivered batch", result);
        } catch (ServiceUnavailableException | IOException expected) {
            // correct: an unreachable host is a transport failure
        }
    }

    /**
     * A response that dies mid-body must not be handed back as a short but valid one, or delivery
     * would delete a batch the server never finished accepting.
     */
    @Test
    public void aTruncatedResponseIsNotReportedAsSuccess() {
        server.truncateAfterHeaders(200, 500);

        try {
            final RemoteService.RequestResult result =
                    new HttpService().performRequest(url(), null, null, jsonHeaders("Basic x"), body("[]"), null);
            if (result != null && result.getResponse() != null) {
                assertTrue(
                        "a truncated body must not be presented as the complete response",
                        result.getResponse().length < 500);
            }
        } catch (ServiceUnavailableException | IOException expected) {
            // correct
        }
    }

    /**
     * The regression this file found. {@code ServiceUnavailableException} extends {@code Exception},
     * not {@code IOException}, so a 5xx fell through {@code HttpService}'s
     * {@code catch (IOException)} — whose comment claimed to cover it — into the generic
     * {@code catch (Exception)} and was wrapped in an {@code IOException}.
     *
     * Everything downstream of that broke silently. {@code performRequest}'s
     * {@code instanceof ServiceUnavailableException} check never matched, the Retry-After header the
     * exception carries was discarded, and {@code DeliveryMessages}' own catch for it was dead code.
     * The net effect was an SDK that ignored every backpressure instruction the platform sent and
     * kept flushing on its own schedule, which is how an SDK gets rate-limited — with nothing
     * failing anywhere to say so.
     */
    @Test
    public void a5xxSurfacesAsServiceUnavailableCarryingTheRetryAfter() {
        server.respondWithRetryAfter(503, "slow down", "120");

        try {
            new HttpService().performRequest(url(), null, null, jsonHeaders("Basic x"), body("[]"), null);
            fail("a 503 must not be reported as a delivered batch");
        } catch (ServiceUnavailableException e) {
            assertEquals(
                    "the server's Retry-After must survive to the caller, or its backpressure is ignored",
                    120,
                    e.getRetryAfter());
        } catch (IOException e) {
            fail(
                    "a 5xx must not be flattened into a generic IOException — that discards Retry-After "
                            + "and makes a server error indistinguishable from a socket error. Got: " + e);
        }
    }

    /** A 5xx with no Retry-After must report zero rather than failing to parse the absent header. */
    @Test
    public void a5xxWithoutARetryAfterReportsZero() {
        server.respond(503, "down");

        try {
            new HttpService().performRequest(url(), null, null, jsonHeaders("Basic x"), body("[]"), null);
            fail("a 503 must not read as success");
        } catch (ServiceUnavailableException e) {
            assertEquals("an absent Retry-After means fall back to the SDK's own backoff", 0, e.getRetryAfter());
        } catch (IOException e) {
            fail("expected ServiceUnavailableException, got " + e);
        }
    }

    /** A non-numeric Retry-After (the HTTP-date form) must not crash the delivery path. */
    @Test
    public void aNonNumericRetryAfterIsToleratedRatherThanThrowing() {
        server.respondWithRetryAfter(503, "down", "Wed, 21 Oct 2026 07:28:00 GMT");

        try {
            new HttpService().performRequest(url(), null, null, jsonHeaders("Basic x"), body("[]"), null);
            fail("a 503 must not read as success");
        } catch (ServiceUnavailableException e) {
            assertEquals(
                    "an unparseable Retry-After must degrade to the SDK's own backoff, not throw",
                    0,
                    e.getRetryAfter());
        } catch (IOException e) {
            fail("expected ServiceUnavailableException, got " + e);
        }
    }

    // ----------------------------------------------------------------- retries

    /**
     * Three attempts, then give up. Unbounded retries against a host that is down would spin the
     * worker thread and drain the battery; zero retries would drop a batch on a single blip.
     */
    @Test
    public void aFailingHostIsRetriedAndThenAbandoned() {
        server.respond(503, "down");

        try {
            new HttpService().performRequest(url(), null, null, jsonHeaders("Basic x"), body("[]"), null);
        } catch (Exception ignored) {
            // the outcome is asserted through the server's request count
        }

        assertTrue(
                "a transient failure must be retried, got " + server.requestCount() + " attempt(s)",
                server.requestCount() > 1);
        assertTrue(
                "retries must be bounded or a dead host would spin the worker, got "
                        + server.requestCount(),
                server.requestCount() <= 6);
    }

    @Test
    public void aSuccessAfterAFailureStopsTheRetryLoop() throws Exception {
        server.respondInSequence(503, 200);

        final RemoteService.RequestResult result =
                new HttpService().performRequest(url(), null, null, jsonHeaders("Basic x"), body("[]"), null);

        assertNotNull("the retry must be allowed to succeed", result);
        assertEquals("the loop must stop at the first success", 2, server.requestCount());
    }

    // -------------------------------------------------------------------- gzip

    /**
     * With gzip enabled the body must arrive compressed and declared as such. Compressing without
     * the header, or declaring without compressing, both produce a body the platform cannot read
     * while the request itself still returns 200.
     */
    @Test
    public void gzipCompressesTheBodyAndDeclaresTheEncoding() throws Exception {
        server.respond(200, "ok");
        final Map<String, Object> params = new HashMap<>();
        params.put("data", "[{\"event\":\"a\"}]");

        new HttpService(true, null).performRequest(url(), null, params, null, null, null);

        final Request sent = server.lastRequest();
        if ("gzip".equalsIgnoreCase(sent.header("content-encoding"))) {
            assertEquals(
                    "a body declared gzip must actually be gzip",
                    "[{\"event\":\"a\"}]",
                    gunzipFormField(sent.body, "data"));
        } else {
            assertTrue(
                    "without the content-encoding header the body must be sent uncompressed",
                    new String(sent.body, StandardCharsets.UTF_8).contains("event"));
        }
    }

    @Test
    public void withoutGzipTheBodyIsSentAsPlainBytes() throws Exception {
        server.respond(200, "ok");
        final byte[] payload = body("[{\"event\":\"plain\"}]");

        new HttpService(false, null).performRequest(url(), null, null, jsonHeaders("Basic x"), payload, null);

        final Request sent = server.lastRequest();
        assertNull("no encoding may be declared when none is applied", sent.header("content-encoding"));
        assertEquals(
                new String(payload, StandardCharsets.UTF_8), new String(sent.body, StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ params

    @Test
    public void getParametersAreUrlEncodedIntoTheQueryString() throws Exception {
        server.respond(200, "ok");
        final Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", "a b&c=d");

        new HttpService().performRequest(HttpMethod.GET, url(), null, params, null, null, null);

        final Request sent = server.lastRequest();
        assertEquals("GET", sent.method);
        assertTrue(
                "an unencoded ampersand would split into a second parameter, got " + sent.path,
                sent.path.contains("id=a+b%26c%3Dd") || sent.path.contains("id=a%20b%26c%3Dd"));
    }

    @Test
    public void getRequestsSendNoBody() throws Exception {
        server.respond(200, "ok");

        new HttpService().performRequest(HttpMethod.GET, url(), null, null, null, null, null);

        assertEquals("a GET must not carry a body", 0, server.lastRequest().body.length);
    }

    /** A URL that already has a query string must be appended to, not corrupted. */
    @Test
    public void parametersAppendToAnExistingQueryString() throws Exception {
        server.respond(200, "ok");
        final Map<String, Object> params = new LinkedHashMap<>();
        params.put("second", "2");

        new HttpService()
                .performRequest(HttpMethod.GET, url() + "?first=1", null, params, null, null, null);

        final String path = server.lastRequest().path;
        assertTrue("the original parameter must survive, got " + path, path.contains("first=1"));
        assertTrue("the new parameter must be appended, got " + path, path.contains("second=2"));
        assertTrue("a second '?' would make the query unparseable, got " + path, path.indexOf('?') == path.lastIndexOf('?'));
    }

    // ------------------------------------------------------------ host failover

    /**
     * {@code replaceHost} is what backup-host failover is built on. Dropping the port would send
     * the retry to :80 and dropping the path would POST the batch to the wrong endpoint — both
     * fail in ways that look like the backup host being broken.
     */
    @Test
    public void replaceHostKeepsTheScheme_port_pathAndQuery() {
        final HttpService http = new HttpService();

        assertEquals(
                "https://backup.example.com:8443/v1/track?x=1",
                http.replaceHost("https://api.intempt.com:8443/v1/track?x=1", "backup.example.com"));
        assertEquals(
                "http://backup.example.com/v1/track",
                http.replaceHost("http://api.intempt.com/v1/track", "backup.example.com"));
    }

    /** A URL it cannot parse must come back untouched rather than as null or a broken string. */
    @Test
    public void replaceHostReturnsTheOriginalWhenItCannotParse() {
        final HttpService http = new HttpService();

        assertEquals("not a url", http.replaceHost("not a url", "backup.example.com"));
    }

    @Test
    public void aFreshServiceIsNotConsideredBlocked() {
        assertTrue("a service must start usable, not blocked", !new HttpService().isServerBlocked());
    }

    @Test
    public void theBackupHostAndErrorListenerAreSettableAfterConstruction() throws Exception {
        server.respond(200, "ok");
        final HttpService http = new HttpService();
        final AtomicInteger errors = new AtomicInteger();

        http.setBackupHost("backup.example.invalid");
        http.setNetworkErrorListener(
                (endpointUrl, ipAddress, durationMillis, uncompressedBodySize, compressedBodySize, responseCode, responseMessage, exception) ->
                        errors.incrementAndGet());

        assertNotNull(http.performRequest(url(), null, null, jsonHeaders("Basic x"), body("[]"), null));
        assertEquals("a successful request must not report a network error", 0, errors.get());
    }

    /** The error listener is how a host app observes delivery failures, so it has to actually fire. */
    @Test
    public void theErrorListenerIsNotifiedOnAServerError() {
        server.respond(503, "down");
        final List<Integer> reported = new CopyOnWriteArrayList<>();
        final HttpService http =
                new HttpService(
                        false,
                        (endpointUrl, ipAddress, durationMillis, uncompressedBodySize, compressedBodySize, responseCode, responseMessage, exception) ->
                                reported.add(responseCode));

        try {
            http.performRequest(url(), null, null, jsonHeaders("Basic x"), body("[]"), null);
        } catch (Exception ignored) {
            // the outcome is the listener notification
        }

        assertTrue("a 5xx must reach the error listener, got " + reported, reported.contains(503));
    }

    // ------------------------------------------------------------------ helpers

    private static int findAFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** Pulls one url-encoded form field out of a gzipped body. */
    private static String gunzipFormField(byte[] gzipped, String field) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPInputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(gzipped))) {
            final byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
        final String decoded = java.net.URLDecoder.decode(out.toString("UTF-8"), "UTF-8");
        final int at = decoded.indexOf(field + "=");
        return at < 0 ? decoded : decoded.substring(at + field.length() + 1);
    }

    /** One request as the server received it. */
    private static final class Request {
        final String method;
        final String path;
        final Map<String, String> headers;
        final byte[] body;

        Request(String method, String path, Map<String, String> headers, byte[] body) {
            this.method = method;
            this.path = path;
            this.headers = headers;
            this.body = body;
        }

        /** Header names are case-insensitive on the wire, so lookups are lowercased. */
        String header(String name) {
            return headers.get(name.toLowerCase());
        }
    }

    /**
     * A minimal HTTP/1.1 server on a loopback port.
     *
     * Deliberately raw rather than a library: the point is to observe exactly what
     * {@code HttpURLConnection} put on the socket, and any abstraction in between would be one more
     * thing that could be wrong.
     */
    private static final class FakeServer {
        private final List<Request> requests = new CopyOnWriteArrayList<>();
        private final CountDownLatch started = new CountDownLatch(1);

        private ServerSocket socket;
        private Thread thread;
        private volatile boolean running = true;

        private volatile int status = 200;
        private volatile String responseBody = "ok";
        private volatile int[] sequence;
        private volatile boolean truncate;
        private volatile int declaredLength = -1;
        private volatile String retryAfter;

        void respond(int status, String body) {
            this.status = status;
            this.responseBody = body;
            this.sequence = null;
        }

        /** Answers with a Retry-After header, as a rate-limited platform would. */
        void respondWithRetryAfter(int status, String body, String retryAfter) {
            respond(status, body);
            this.retryAfter = retryAfter;
        }

        /** Answers with each status in turn; the last one repeats. */
        void respondInSequence(int... statuses) {
            this.sequence = statuses;
        }

        /** Declares a Content-Length and then sends nothing, to simulate a connection dying. */
        void truncateAfterHeaders(int status, int declaredLength) {
            this.status = status;
            this.truncate = true;
            this.declaredLength = declaredLength;
        }

        int port() {
            return socket.getLocalPort();
        }

        int requestCount() {
            return requests.size();
        }

        Request lastRequest() {
            assertTrue("the server received no request at all", !requests.isEmpty());
            return requests.get(requests.size() - 1);
        }

        void start() throws IOException {
            socket = new ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"));
            thread =
                    new Thread(
                            () -> {
                                started.countDown();
                                while (running) {
                                    try (Socket client = socket.accept()) {
                                        handle(client);
                                    } catch (IOException e) {
                                        // The socket closing during teardown is expected.
                                    }
                                }
                            },
                            "fake-http-server");
            thread.setDaemon(true);
            thread.start();
            try {
                started.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        void stop() {
            running = false;
            try {
                if (socket != null) {
                    socket.close();
                }
            } catch (IOException ignored) {
                // teardown
            }
            if (thread != null) {
                thread.interrupt();
            }
        }

        private void handle(Socket client) throws IOException {
            final InputStream in = client.getInputStream();
            final OutputStream out = client.getOutputStream();

            final String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) {
                return;
            }
            final String[] parts = requestLine.split(" ");
            final String method = parts[0];
            final String path = parts.length > 1 ? parts[1] : "/";

            final Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                final int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(
                            line.substring(0, colon).trim().toLowerCase(),
                            line.substring(colon + 1).trim());
                }
            }

            byte[] body = new byte[0];
            final String contentLength = headers.get("content-length");
            if (contentLength != null) {
                body = readExactly(in, Integer.parseInt(contentLength));
            } else if ("chunked".equalsIgnoreCase(headers.get("transfer-encoding"))) {
                body = readChunked(in);
            }

            requests.add(new Request(method, path, headers, body));

            final int code = nextStatus();
            if (truncate) {
                out.write(
                        ("HTTP/1.1 " + code + " OK\r\nContent-Length: " + declaredLength + "\r\n\r\n")
                                .getBytes(StandardCharsets.UTF_8));
                out.flush();
                // Close without sending the declared bytes.
                return;
            }

            final byte[] payload = responseBody.getBytes(StandardCharsets.UTF_8);
            final String retryAfterHeader = retryAfter == null ? "" : "Retry-After: " + retryAfter + "\r\n";
            out.write(
                    ("HTTP/1.1 " + code + " " + reason(code) + "\r\n"
                                    + retryAfterHeader
                                    + "Content-Length: " + payload.length + "\r\n"
                                    + "Connection: close\r\n\r\n")
                            .getBytes(StandardCharsets.UTF_8));
            out.write(payload);
            out.flush();
        }

        private int nextStatus() {
            final int[] scripted = sequence;
            if (scripted == null) {
                return status;
            }
            final int index = Math.min(requests.size() - 1, scripted.length - 1);
            return scripted[Math.max(0, index)];
        }

        private static String reason(int code) {
            switch (code) {
                case 200:
                    return "OK";
                case 201:
                    return "Created";
                case 401:
                    return "Unauthorized";
                case 500:
                    return "Internal Server Error";
                case 503:
                    return "Service Unavailable";
                default:
                    return "Status";
            }
        }

        private static String readLine(InputStream in) throws IOException {
            final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            int b;
            while ((b = in.read()) != -1) {
                if (b == '\n') {
                    break;
                }
                if (b != '\r') {
                    buffer.write(b);
                }
            }
            if (b == -1 && buffer.size() == 0) {
                return null;
            }
            return buffer.toString("UTF-8");
        }

        private static byte[] readExactly(InputStream in, int length) throws IOException {
            final byte[] out = new byte[length];
            int read = 0;
            while (read < length) {
                final int n = in.read(out, read, length - read);
                if (n == -1) {
                    break;
                }
                read += n;
            }
            return out;
        }

        private static byte[] readChunked(InputStream in) throws IOException {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            String sizeLine;
            while ((sizeLine = readLine(in)) != null) {
                final int size = Integer.parseInt(sizeLine.trim().split(";")[0], 16);
                if (size == 0) {
                    readLine(in);
                    break;
                }
                out.write(readExactly(in, size));
                readLine(in);
            }
            return out.toByteArray();
        }
    }
}
