package com.intempt.core.queue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Plain JUnit — no Robolectric, no runner annotation.
 *
 * <p>That is the point of this file. {@code TrackPayloadBuilder} and {@code QueueConfig} are pure
 * JVM classes, but their existing tests run under Robolectric, and PIT cannot run against an
 * Android library module. So the highest-value logic in the queue had coverage without mutation
 * testing: the report said the lines ran, and nothing said the tests would notice if they changed.
 *
 * <p>These tests are compiled twice — once by {@code :app} as an ordinary unit test, and once by
 * {@code :mutation}, which applies {@code java-library} and includes the same source files so PIT
 * runs natively. Anything Robolectric-dependent added here breaks that second compile, which is the
 * intended guard rather than an inconvenience.
 *
 * <p>{@code QueueConfig} decides batch sizes, retry windows and the Authorization header. A silent
 * change in any of them loses events without raising anything.
 *
 * <p>{@code TrackPayloadBuilder}'s tests are NOT here. They need {@code org.json}, and under
 * {@code :app}'s unit-test task that resolves to the stub android.jar, where every method throws
 * "not mocked" — which is why the pre-existing TrackPayloadBuilderTest uses Robolectric. Those
 * tests live in {@code mutation/src/test/java}, compiled only by the module that has the real
 * Maven org.json on its classpath.
 */
public class PureJvmQueueTest {

    private static final String AUTH = "Basic dGVzdDp0ZXN0";

    // ------------------------------------------------------------- QueueConfig

    @Test
    public void theAuthorizationHeaderIsCarriedVerbatim() {
        assertEquals(
                "this is the header that, when null, made every batch 401 and be dropped",
                AUTH,
                new QueueConfig("https://example.invalid/track", AUTH).getAuthorization());
    }

    @Test
    public void aConfigWithoutAnAuthorizationReportsNoneRatherThanAnEmptyHeader() {
        final String authorization = new QueueConfig("https://example.invalid/track").getAuthorization();

        assertTrue(
                "delivery logs a hard error when this is absent; a blank string that looked present "
                        + "would suppress that warning and every batch would 401 silently",
                authorization == null || authorization.isEmpty());
    }

    @Test
    public void theEndpointIsCarriedVerbatim() {
        final String url = "https://api.intempt.com/v1/org/projects/proj/sources/123/events";

        assertEquals(url, new QueueConfig(url, AUTH).getEventsEndpoint());
    }

    /**
     * The inherited tuning constants. Pinned as literals rather than read from the getter they
     * verify — an expectation taken from the same source as the code is self-confirming, and these
     * decide how much is sent per request and how long a failing batch is retained.
     */
    @Test
    public void theInheritedTuningConstantsAreUnchanged() {
        final QueueConfig config = new QueueConfig("https://example.invalid/track", AUTH);

        assertEquals("flush batch size", 50, config.getFlushBatchSize());
        assertTrue("bulk upload limit must be positive", config.getBulkUploadLimit() > 0);
        assertTrue("flush interval must be positive", config.getFlushInterval() > 0);
        assertTrue("data expiration must be positive", config.getDataExpiration() > 0);

        // Exact values, not just ordering. `min < max` survived a mutant returning 0 for the
        // minimum — and a minimum of 0 means the queue considers itself out of space immediately
        // and drops every event. The ordering assertion alone could not see that.
        assertEquals("minimum database limit", 20 * 1024 * 1024, config.getMinimumDatabaseLimit());
        assertEquals("maximum database limit", Integer.MAX_VALUE, config.getMaximumDatabaseLimit());
        assertTrue(
                "the database limits must be ordered, or the queue would evict on every write",
                config.getMinimumDatabaseLimit() < config.getMaximumDatabaseLimit());
    }

    /**
     * The six-argument constructor exists so itemsInQueue and timeBuffer from intempt-config.json
     * are honoured. They were previously parsed and then ignored, so a customer raising them saw no
     * effect at all.
     */
    @Test
    public void theConfiguredBatchSizeAndFlushIntervalOverrideTheDefaults() {
        final QueueConfig config =
                new QueueConfig("https://example.invalid/track", AUTH, null, false, 7, 1234L);

        // itemsInQueue maps to the bulk upload limit — the threshold at which a flush is
        // triggered — not to the read cap. Asserting it against getFlushBatchSize() was my
        // misreading, not a defect: the read cap bounds how much one request carries, so a
        // customer setting itemsInQueue=200 gets a flush at 200 events delivered as four requests
        // of 50. Both getters are pinned here so the distinction cannot quietly collapse.
        assertEquals("itemsInQueue must set the flush trigger", 7, config.getBulkUploadLimit());
        assertEquals("timeBuffer must set the flush interval", 1234, config.getFlushInterval());
        assertEquals(
                "the read cap is independent of the flush trigger and stays at the inherited value",
                50,
                config.getFlushBatchSize());
    }

    /** A non-positive override is nonsense and must fall back rather than disable flushing. */
    @Test
    public void aNonPositiveOverrideFallsBackToTheDefault() {
        final QueueConfig zero = new QueueConfig("https://example.invalid/track", AUTH, null, false, 0, 0L);

        assertTrue(
                "a bulk upload limit of zero would mean the trigger never fires",
                zero.getBulkUploadLimit() > 0);
        assertTrue(
                "a flush interval of zero would mean a hot loop or never flushing",
                zero.getFlushInterval() > 0);
    }

    /**
     * Both directions. Asserting only the default-false case let a mutant that always returns false
     * survive — so nothing verified that asking for gzip actually turns it on.
     */
    @Test
    public void gzipIsOffByDefaultAndOnWhenAskedFor() {
        assertFalse(
                "declaring gzip without applying it sends a body the platform cannot read",
                new QueueConfig("https://example.invalid/track", AUTH).shouldGzipRequestPayload());

        assertTrue(
                "a config built with gzip requested must report it, or the header and the body "
                        + "disagree about the encoding",
                new QueueConfig("https://example.invalid/track", AUTH, null, true, 0, 0L)
                        .shouldGzipRequestPayload());
    }

    /** The optional accessors are read on every request and must not throw when unset. */
    @Test
    public void theOptionalAccessorsReturnNullRatherThanThrowing() {
        final QueueConfig config = new QueueConfig("https://example.invalid/track", AUTH);

        assertNull(config.getBackupHost());
        assertNull(config.getOfflineMode());
        assertNull(config.getSSLSocketFactory());
        assertNull(config.getProxyServerInteractor());
    }

    /**
     * The logging toggle must actually reach QueueLog.
     *
     * <p>This previously asserted only that the call did not throw, which a no-op satisfies
     * equally — mutation testing confirmed it by deleting the {@code QueueLog.setEnabled} call and
     * killing no test. Asserted on the observable state now.
     */
    @Test
    public void togglingLoggingReachesTheLogger() {
        final QueueConfig config = new QueueConfig("https://example.invalid/track", AUTH);
        final boolean original = QueueLog.isEnabled();
        try {
            config.setLoggingEnabled(true);
            assertTrue("setLoggingEnabled(true) must enable the logger", QueueLog.isEnabled());

            config.setLoggingEnabled(false);
            assertFalse("setLoggingEnabled(false) must disable it", QueueLog.isEnabled());
        } finally {
            // Static state, shared across every test in the JVM — restored so ordering cannot
            // change another test's behaviour.
            QueueLog.setEnabled(original);
        }
    }
    // -------------------------------------------------------- one logging path

    /**
     * The property the consolidation exists for: when a sink is installed it is the only gate.
     *
     * <p>Before this, the queue package wrote to logcat behind {@code QueueLog.sEnabled} while the
     * SDK's own logger wrote behind {@code ConfigManagerService.isLoggingEnabled}. Two switches, one
     * destination — so calling {@code Intempt.Logging.stop()} silenced half the output and the queue
     * kept narrating. Asserted by installing a sink and confirming it receives the line even with
     * QueueLog's own flag off, which proves the flag is no longer a second gate.
     */
    @Test
    public void aQueueLogSinkReceivesOutputRegardlessOfTheLegacyFlag() {
        final java.util.List<String> received = new java.util.ArrayList<>();
        final boolean originalEnabled = QueueLog.isEnabled();
        try {
            QueueLog.setEnabled(false);
            QueueLog.setSink((priority, tag, message, throwable) -> received.add(tag + "|" + message));

            QueueLog.e("Intempt.Messages", "delivery failed");

            assertEquals(
                    "with a sink installed, the legacy flag must not gate — otherwise there are two "
                            + "switches for one destination and the SDK's own API only controls one",
                    1,
                    received.size());
            assertEquals("Intempt.Messages|delivery failed", received.get(0));
        } finally {
            QueueLog.setSink(null);
            QueueLog.setEnabled(originalEnabled);
        }
    }

    /** Every level reaches the sink, and the throwable is carried through rather than dropped. */
    @Test
    public void everyLevelAndItsThrowableReachTheSink() {
        final java.util.List<String> received = new java.util.ArrayList<>();
        try {
            QueueLog.setSink(
                    (priority, tag, message, throwable) ->
                            received.add(priority + "|" + message + "|" + (throwable == null ? "-" : throwable.getMessage())));

            QueueLog.v("t", "verbose");
            QueueLog.d("t", "debug");
            QueueLog.i("t", "info");
            QueueLog.w("t", "warn");
            QueueLog.e("t", "error");

            // Every level's throwable overload too. Mutation testing found these uncovered: only
            // e(tag, msg, throwable) was exercised, so nothing verified that the other four carried
            // their cause through. The vendored delivery code uses them for exactly that — the
            // network-failure narration in HttpService and EventDbAdapter passes the exception, and
            // a diagnostic that silently drops its cause is worse than no diagnostic.
            QueueLog.v("t", "verbose cause", new IllegalStateException("v-boom"));
            QueueLog.d("t", "debug cause", new IllegalStateException("d-boom"));
            QueueLog.i("t", "info cause", new IllegalStateException("i-boom"));
            QueueLog.w("t", "warn cause", new IllegalStateException("w-boom"));
            QueueLog.e("t", "with cause", new IllegalStateException("boom"));

            assertEquals("all ten calls must arrive", 10, received.size());
            assertTrue("verbose must carry its cause", received.get(5).endsWith("|v-boom"));
            assertTrue("debug must carry its cause", received.get(6).endsWith("|d-boom"));
            assertTrue("info must carry its cause", received.get(7).endsWith("|i-boom"));
            assertTrue("warn must carry its cause", received.get(8).endsWith("|w-boom"));
            assertTrue(
                    "a throwable must survive to the sink — the network-failure narration in "
                            + "HttpService depends on it to be diagnosable",
                    received.get(9).endsWith("|boom"));
            assertTrue("levels must be distinguishable", !received.get(0).equals(received.get(4)));
        } finally {
            QueueLog.setSink(null);
        }
    }

    // The no-sink fallback path is tested in mutation/src/test — QueueLogFallbackTest. It cannot
    // live here: with no sink installed, QueueLog writes through android.util.Log.println, and under
    // :app's unit-test task that resolves to the stub android.jar where every method throws
    // "not mocked". The mutation module compiles against a real no-op Log stub instead.

    /** Installing a sink through QueueConfig is the path the SDK actually uses. */
    @Test
    public void theSinkCanBeInstalledThroughQueueConfig() {
        final java.util.List<String> received = new java.util.ArrayList<>();
        final QueueConfig config = new QueueConfig("https://example.invalid/track", AUTH);
        try {
            config.setLogSink((priority, tag, message, throwable) -> received.add(message));
            QueueLog.w("t", "routed via QueueConfig");

            assertEquals(1, received.size());
            assertEquals("routed via QueueConfig", received.get(0));

            config.setLogSink(null);
            received.clear();
            QueueLog.setEnabled(false);
            QueueLog.w("t", "should be suppressed now");
            assertTrue("clearing the sink must restore the default path", received.isEmpty());
        } finally {
            QueueLog.setSink(null);
        }
    }

    // ------------------------------------------------------------- Certificate pinning (opt-in)

    @Test
    public void sslSocketFactoryIsNullByDefaultOnEveryLegacyConstructor() {
        // Off by default: none of the pre-existing constructors take a factory, and HttpService
        // null-checks this to fall back to platform-default TLS trust validation.
        assertNull(new QueueConfig("https://example.invalid/track").getSSLSocketFactory());
        assertNull(new QueueConfig("https://example.invalid/track", AUTH).getSSLSocketFactory());
        assertNull(
                new QueueConfig("https://example.invalid/track", AUTH, null, false).getSSLSocketFactory());
        assertNull(
                new QueueConfig("https://example.invalid/track", AUTH, null, false, 10, 1000L)
                        .getSSLSocketFactory());
    }

    @Test
    public void sslSocketFactoryIsAppliedWhenExplicitlyConfigured() {
        final javax.net.ssl.SSLSocketFactory fake = fakeSslSocketFactory();

        final QueueConfig config =
                new QueueConfig("https://example.invalid/track", AUTH, null, false, 10, 1000L, fake);

        assertNotNull(
                "pinning is opt-in: a configured factory must be surfaced to HttpService",
                config.getSSLSocketFactory());
        assertTrue(
                "the exact factory supplied must be applied, not a copy or a default",
                config.getSSLSocketFactory() == fake);
    }

    @Test
    public void sslSocketFactoryStaysNullWhenExplicitlyPassedAsNull() {
        final QueueConfig config =
                new QueueConfig("https://example.invalid/track", AUTH, null, false, 10, 1000L, null);

        assertNull(
                "explicit null (no certificatePins configured) must mean unchanged, "
                        + "platform-default TLS -- never an implicit pinning behaviour",
                config.getSSLSocketFactory());
    }

    /** A minimal fake -- identity is all these tests need, no real TLS involved. */
    private static javax.net.ssl.SSLSocketFactory fakeSslSocketFactory() {
        return new javax.net.ssl.SSLSocketFactory() {
            @Override
            public String[] getDefaultCipherSuites() {
                return new String[0];
            }

            @Override
            public String[] getSupportedCipherSuites() {
                return new String[0];
            }

            @Override
            public java.net.Socket createSocket(java.net.Socket s, String host, int port, boolean autoClose) {
                throw new UnsupportedOperationException("not used by these tests");
            }

            @Override
            public java.net.Socket createSocket(String host, int port) {
                throw new UnsupportedOperationException("not used by these tests");
            }

            @Override
            public java.net.Socket createSocket(String host, int port, java.net.InetAddress localHost, int localPort) {
                throw new UnsupportedOperationException("not used by these tests");
            }

            @Override
            public java.net.Socket createSocket(java.net.InetAddress host, int port) {
                throw new UnsupportedOperationException("not used by these tests");
            }

            @Override
            public java.net.Socket createSocket(
                    java.net.InetAddress address, int port, java.net.InetAddress localAddress, int localPort) {
                throw new UnsupportedOperationException("not used by these tests");
            }
        };
    }
}
