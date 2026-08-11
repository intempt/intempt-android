package com.intempt.core.queue;

import javax.net.ssl.SSLSocketFactory;

/**
 * Configuration for the delivery queue.
 *
 * <p>Replaces Mixpanel's {@code MPConfig}, which was not inherited: it read every value
 * from {@code AndroidManifest} metadata, while Intempt configures the SDK from
 * {@code assets/intempt-config.json} via {@code ConfigManagerService}. The tuning
 * constants below are inherited verbatim from Mixpanel — they encode a decade of
 * production experience and are deliberately not re-derived.
 *
 * <p>The member surface here is exactly what the vendored substrate calls. Do not trim
 * it without checking {@code EventDbAdapter}, {@code DeliveryMessages} and
 * {@code HttpService} first.
 */
public class QueueConfig {

    /** Verbose request logging. Mirrors Mixpanel's MPConfig.DEBUG. */
    public static final boolean DEBUG = false;

    /** Flush once this many events are queued. Source: MPConfig.java:198. */
    private static final int BULK_UPLOAD_LIMIT = 40;

    /** Periodic flush interval, milliseconds. Source: MPConfig.java:201. */
    private static final int FLUSH_INTERVAL_MS = 60 * 1000;

    /**
     * Rows read per POST. Bounds a single request so a long offline period cannot
     * produce an unsendable batch. Source: MPDbAdapter.java:627.
     */
    private static final int FLUSH_BATCH_SIZE = 50;

    /** Queued events older than this are swept on first use. Source: MPConfig.java:235. */
    private static final long DATA_EXPIRATION_MS = 1000L * 60 * 60 * 24 * 5;

    /**
     * A FLOOR, not a ceiling. {@code EventDbAdapter.aboveMemThreshold()} evaluates
     * {@code dbSize > max(usableDiskSpace, MINIMUM_DATABASE_LIMIT)}, so this guarantees
     * the queue may always grow to 20MB even on a nearly-full device. The actual upper
     * bound is {@link #MAXIMUM_DATABASE_LIMIT}.
     *
     * <p>Reading this as a hard 20MB cap inverts the semantic and drops events on
     * devices with gigabytes free. Source: MPDbAdapter.java:189-190, MPConfig.java:212.
     */
    private static final int MINIMUM_DATABASE_LIMIT = 20 * 1024 * 1024;

    /** The real ceiling. Source: MPConfig.java:215. */
    private static final int MAXIMUM_DATABASE_LIMIT = Integer.MAX_VALUE;

    private final String mEventsEndpoint;
    private final String mBackupHost;
    private final boolean mGzipRequestPayload;

    public QueueConfig(String eventsEndpoint) {
        this(eventsEndpoint, null, false);
    }

    public QueueConfig(String eventsEndpoint, String backupHost, boolean gzipRequestPayload) {
        mEventsEndpoint = eventsEndpoint;
        mBackupHost = backupHost;
        mGzipRequestPayload = gzipRequestPayload;
    }

    public int getBulkUploadLimit() {
        return BULK_UPLOAD_LIMIT;
    }

    public int getFlushInterval() {
        return FLUSH_INTERVAL_MS;
    }

    public int getFlushBatchSize() {
        return FLUSH_BATCH_SIZE;
    }

    public long getDataExpiration() {
        return DATA_EXPIRATION_MS;
    }

    public int getMinimumDatabaseLimit() {
        return MINIMUM_DATABASE_LIMIT;
    }

    public int getMaximumDatabaseLimit() {
        return MAXIMUM_DATABASE_LIMIT;
    }

    public String getEventsEndpoint() {
        return mEventsEndpoint;
    }

    /**
     * Secondary host tried when the primary fails. Null disables failover; Intempt
     * publishes no backup ingestion host today, so this is null in practice.
     */
    public String getBackupHost() {
        return mBackupHost;
    }

    public boolean shouldGzipRequestPayload() {
        return mGzipRequestPayload;
    }

    /**
     * Null means "use the default connectivity check". Callers in the vendored
     * substrate null-check this (DeliveryMessages, isOnline gate), so returning null
     * is the supported no-op — do not remove the method.
     */
    public OfflineMode getOfflineMode() {
        return null;
    }

    /** Null means "platform default TLS". Null-checked by HttpService. */
    public SSLSocketFactory getSSLSocketFactory() {
        return null;
    }

    /** Null means "no proxy interception". Null-checked by HttpService. */
    public ProxyServerInteractor getProxyServerInteractor() {
        return null;
    }

    /** Wired from ConfigManagerService.isLoggingEnabled. */
    public void setLoggingEnabled(boolean enabled) {
        QueueLog.setEnabled(enabled);
    }
}
