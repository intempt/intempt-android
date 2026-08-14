/*
 * Copyright (c) 2026 Intempt Technologies
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Written for Intempt rather than derived from mixpanel-android, but it lives in the vendored
 * package and its constants and behaviour are taken from Mixpanel's MPConfig and MPDbAdapter,
 * so it is licensed under the same terms and recorded in NOTICE.
 */
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
    private final String mAuthorization;
    private final int mBulkUploadLimitOverride;
    private final int mFlushIntervalOverride;

    public QueueConfig(String eventsEndpoint) {
        this(eventsEndpoint, null, null, false);
    }

    public QueueConfig(String eventsEndpoint, String authorization) {
        this(eventsEndpoint, authorization, null, false);
    }

    public QueueConfig(
            String eventsEndpoint, String authorization, String backupHost, boolean gzipRequestPayload) {
        this(eventsEndpoint, authorization, backupHost, gzipRequestPayload, 0, 0);
    }

    /**
     * Honours the two knobs {@code assets/intempt-config.json} documents.
     *
     * <p>{@code itemsInQueue} and {@code timeBuffer} were read from the config file and stored
     * on ConfigManagerService, and then nothing ever read them — delivery ran on the hardcoded
     * constants regardless. Two documented options that silently did nothing.
     *
     * <p>Zero or negative means "use the inherited Mixpanel value", which is what everyone got
     * before and remains the behaviour for anyone who does not set them.
     */
    public QueueConfig(
            String eventsEndpoint,
            String authorization,
            String backupHost,
            boolean gzipRequestPayload,
            int itemsInQueue,
            long timeBufferMs) {
        mEventsEndpoint = eventsEndpoint;
        mAuthorization = authorization;
        mBackupHost = backupHost;
        mGzipRequestPayload = gzipRequestPayload;
        mBulkUploadLimitOverride = itemsInQueue > 0 ? itemsInQueue : 0;
        mFlushIntervalOverride = timeBufferMs > 0 ? (int) timeBufferMs : 0;
    }

    /**
     * The value for the {@code Authorization} header on every delivery POST, or null when the
     * SDK has no credentials.
     *
     * <p>This existing at all is a P0 fix. Mixpanel authenticates by putting a project token
     * inside the event body, so the vendored substrate posts with {@code headers = null} and
     * has no notion of an auth header. Intempt authenticates with HTTP Basic. Vendoring the
     * transport therefore dropped authentication silently: every batch went out
     * unauthenticated, the gateway answered 401, {@code HttpStatusPolicy.shouldDrop(401)}
     * returned true, and {@code cleanupEvents} deleted it. 100% of events discarded, with the
     * durable queue working exactly as designed.
     *
     * <p>It survived review because both tests that claimed to cover delivery could not see
     * it: the JVM test built its own headers map, and the device test's definition of
     * "delivered" was "the row left the queue", which is equally the signature of a drop.
     */
    public String getAuthorization() {
        return mAuthorization;
    }

    public int getBulkUploadLimit() {
        return mBulkUploadLimitOverride > 0 ? mBulkUploadLimitOverride : BULK_UPLOAD_LIMIT;
    }

    public int getFlushInterval() {
        return mFlushIntervalOverride > 0 ? mFlushIntervalOverride : FLUSH_INTERVAL_MS;
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

    /**
     * Routes the queue package's log output to the SDK's logger.
     *
     * <p>This is what stops the queue being a second logging system. Before it, the queue wrote to
     * logcat behind its own flag while {@code LoggerManagerService} wrote behind a different one —
     * two switches, one destination, so "is logging enabled?" had two answers and turning it off
     * through the SDK's public API silenced only half the output.
     *
     * <p>Takes four primitives rather than a Kotlin type because this package is the vendored Java
     * substrate and must not depend on the Kotlin service layer.
     *
     * @param sink receives (priority, tag, message, throwable); priorities are android.util.Log's.
     *             Null restores the default of writing straight to logcat behind
     *             {@link #setLoggingEnabled(boolean)}.
     */
    public void setLogSink(QueueLogSink sink) {
        QueueLog.setSink(sink == null ? null : sink::write);
    }

    /** Public mirror of the queue's internal sink, so the Kotlin layer can implement it. */
    public interface QueueLogSink {
        void write(int priority, String tag, String message, Throwable throwable);
    }
}
