/*
 * Adapted from the Mixpanel Android SDK — https://github.com/mixpanel/mixpanel-android
 * Copyright 2022 Mixpanel, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this
 * file except in compliance with the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Modifications (c) 2026 Intempt Technologies, licensed under the Apache License 2.0:
 *   - renamed from AnalyticsMessages; package moved to com.intempt.core.queue
 *   - removed people, group, anonymous-profile and first-launch message types
 *   - removed Mixpanel default event properties and the session-replay event bridge
 *   - replaced form-urlencoded transport encoding with Intempt's JSON contract
 *   - replaced the HTTP error taxonomy so 429 is retried rather than discarded
 */
package com.intempt.core.queue;


import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Process;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.HashMap;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collections;
import java.util.Iterator;
import java.util.Set;
import javax.net.ssl.SSLSocketFactory;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Manage communication of events with the internal database and the Intempt ingestion API.
 *
 * <p>This class straddles the thread boundary between user threads and a logical delivery thread.
 */
public class DeliveryMessages {

    // Replaces Mixpanel's MPConstants.URL.DEFAULT_SERVER_HOST, not inherited.
    private static final String DEFAULT_SERVER_HOST = "api.intempt.com";


    public DeliveryMessages(final Context context, QueueConfig config) {
        this(context, config, null);
    }

    /**
     * Modification: an instance-scoped queue database name.
     *
     * <p>Upstream partitioned one shared queue by project token; the Intempt port replaced that
     * with a Dagger {@code @Singleton} on the assumption of one SDK instance per app. Named
     * instances break that assumption — two instances would open, write to and close the same
     * {@code intempt_events} file, which is two writers on one SQLite database. That raises
     * SQLiteDatabaseLockedException, which {@link EventDbAdapter} answers by deleting the
     * database. A second instance could therefore destroy the first's undelivered queue.
     *
     * @param dbName the queue database file, or null for the default {@code intempt_events}
     */
    public DeliveryMessages(final Context context, QueueConfig config, final String dbName) {
        mContext = context;
        mConfig = config;
        mDbName = dbName;
        mWorker = createWorker();
        getPoster().checkIsServerBlocked();
    }

    protected Worker createWorker() {
        return new Worker();
    }


    public void setNetworkErrorListener(NetworkErrorListener errorListener) {
        mNetworkErrorListener = errorListener;
        if (mHttpService != null) {
            mHttpService.setNetworkErrorListener(errorListener);
        }
    }

    /**
     * A delivery failure, already classified. Not an upstream type.
     *
     * <p>Carries plain values rather than the SDK's {@code IntemptError} on purpose. This package
     * is vendored Java, and a Java file here that imports a Kotlin sealed class breaks kapt's stub
     * generation — which then reports every Dagger binding in the module as unresolvable and never
     * names the real cause. Keeping the queue package Kotlin-free costs one small interface and
     * saves the next person that afternoon.
     *
     * <p>It also keeps {@link HttpStatusPolicy} package-private. {@code terminal} is that class's
     * verdict, passed out rather than re-derived by the caller: two places deciding what is
     * retryable is how they drift, and this one would drift silently, because it only changes what
     * a host app is told while the other decides whether a batch survives.
     */
    public interface DeliveryFailureListener {
        /**
         * @param status HTTP status, or <= 0 when the request never got an answer
         * @param description the exception or response message; never null
         * @param retryAfterMillis the server's Retry-After in milliseconds, or 0 when it sent none
         * @param terminal true when this batch will never succeed on retry
         */
        void onFailure(int status, String description, long retryAfterMillis, boolean terminal);
    }

    /**
     * Installs {@code listener}, invoked on the delivery worker thread.
     *
     * <p>This is the half of the error taxonomy that happens <b>after</b> an event was accepted
     * into the queue — the half where events are actually lost — as opposed to the refusals the
     * capture components report at the call site.
     */
    public void setDeliveryFailureListener(final DeliveryFailureListener listener) {
        if (listener == null) {
            setNetworkErrorListener(null);
            return;
        }
        setNetworkErrorListener(
                new NetworkErrorListener() {
                    @Override
                    public void onNetworkError(
                            String endpointUrl,
                            String ipAddress,
                            long durationMillis,
                            long uncompressedBodySize,
                            long compressedBodySize,
                            int responseCode,
                            String responseMessage,
                            Exception exception) {
                        String description = exception == null ? null : exception.getMessage();
                        if (description == null || description.isEmpty()) {
                            description = responseMessage;
                        }
                        if (description == null || description.isEmpty()) {
                            description = "no response";
                        }

                        long retryAfterMillis = 0L;
                        if (exception instanceof RemoteService.ServiceUnavailableException) {
                            final int seconds =
                                    ((RemoteService.ServiceUnavailableException) exception)
                                            .getRetryAfter();
                            if (seconds > 0) {
                                // Seconds on the wire, milliseconds here — the unit the retry
                                // scheduler already uses, so nothing comparing the two is off by a
                                // factor of a thousand.
                                retryAfterMillis = seconds * 1000L;
                            }
                        }

                        listener.onFailure(
                                responseCode,
                                description,
                                retryAfterMillis,
                                responseCode > 0 && HttpStatusPolicy.shouldDrop(responseCode));
                    }
                });
    }

    /**
     * Routing key for queued work.
     *
     * <p>Inherited from Mixpanel, where this was the project token and partitioned a
     * shared queue across several SDK instances. Intempt has one instance per app, so the
     * value is arbitrary — but it must not be null. {@code handleMessage} guards the
     * bulk-upload flush with {@code && token != null}, so a null here would silently
     * disable size-triggered flushing and leave only the periodic timer.
     */
    private static final String QUEUE_TOKEN = "intempt";

    /**
     * Enqueues one event for durable delivery. This is the entry point the Kotlin SDK
     * uses; it returns as soon as the message is posted to the worker thread and never
     * blocks the caller on disk or network.
     *
     * @param event the event payload as {@code IntemptEvent.toFormated()} produced it
     */
    public void enqueueEvent(final JSONObject event) {
        eventsMessage(new EventDescription(null, event, QUEUE_TOKEN, false, null));
    }

    /** Requests a flush of whatever is currently queued. */
    public void flush() {
        flush(null);
    }

    /**
     * Modification: flush with a completion callback, and a runtime-settable interval.
     *
     * <p>Neither exists upstream. Both are required by the cross-SDK API contract, which
     * specifies {@code flush(completion: (Int) -> Void)} and a settable {@code flushInterval}
     * on the public instance. Upstream exposes flush as fire-and-forget and reads the interval
     * once, into a final field, when the worker's handler is constructed.
     *
     * <p>The callback receives the number of events the server accepted during the flush that
     * answered it, and runs on the delivery worker thread. Completions are held in a queue and
     * drained by the next flush to complete — which may be a scheduled one rather than the call
     * that registered it. The reported count is always a real count from a real flush; it is not
     * a promise that only the caller's own events were in it.
     *
     * @param completion invoked with the count of accepted events, or null for fire-and-forget
     */
    public void flush(final FlushCompletion completion) {
        if (completion != null) {
            mFlushCompletions.add(completion);
        }
        final Message m = Message.obtain();
        m.what = FLUSH_QUEUE;
        m.obj = QUEUE_TOKEN;
        m.arg1 = 0;
        mWorker.runMessage(m);
    }

    /** Notified with the number of events a flush delivered. Not an upstream type. */
    public interface FlushCompletion {
        void onFlushed(int delivered);
    }

    /**
     * Milliseconds the worker waits before a size-triggered flush. Negative disables it.
     *
     * <p>Reads through to {@link QueueConfig} so a change takes effect on the next flush
     * decision rather than at the next worker restart.
     */
    public int getFlushInterval() {
        return mConfig.getFlushInterval();
    }

    /** @see #getFlushInterval() */
    public void setFlushInterval(int millis) {
        mConfig.setFlushInterval(millis);
    }

    public void eventsMessage(final EventDescription eventDescription) {
        final Message m = Message.obtain();
        m.what = ENQUEUE_EVENTS;
        m.obj = eventDescription;
        mWorker.runMessage(m);
    }


    public void postToServer(final QueueDescription flushDescription) {
        final Message m = Message.obtain();
        m.what = FLUSH_QUEUE;
        m.obj = flushDescription.getToken();
        m.arg1 = 0;

        mWorker.runMessage(m);
    }

    /**
     * Discards every queued event without sending it. Not an upstream convenience.
     *
     * <p>Exists so callers do not need {@link #QUEUE_TOKEN}, which is private for a reason —
     * passing the wrong token to {@link #emptyTrackingQueues} empties nothing and reports
     * nothing, and an opt-out that silently fails to discard is the failure this method serves.
     */
    public void emptyQueue() {
        emptyTrackingQueues(new QueueDescription(QUEUE_TOKEN));
    }

    public void emptyTrackingQueues(final QueueDescription queueDescription) {
        final Message m = Message.obtain();
        m.what = EMPTY_QUEUES;
        m.obj = queueDescription;

        mWorker.runMessage(m);
    }


    public void hardKill() {
        final Message m = Message.obtain();
        m.what = KILL_WORKER;

        mWorker.runMessage(m);
    }

    /////////////////////////////////////////////////////////
    // For testing, to allow for Mocking.

    /* package */ boolean isDead() {
        return mWorker.isDead();
    }

    protected EventDbAdapter makeDbAdapter(Context context) {
        return mDbName == null
                ? new EventDbAdapter(context, mConfig)
                : new EventDbAdapter(context, mDbName, mConfig);
    }

    private volatile HttpService mHttpService;

    protected RemoteService getPoster() {
        if (mHttpService == null) {
            String serverHost = extractHostFromUrl(mConfig.getEventsEndpoint());
            mHttpService =
                    new HttpService(
                            mConfig.shouldGzipRequestPayload(),
                            mNetworkErrorListener,
                            mConfig.getBackupHost(),
                            serverHost);
        } else {
            // Update backup host and listener in case they changed at runtime
            mHttpService.setBackupHost(mConfig.getBackupHost());
            mHttpService.setNetworkErrorListener(mNetworkErrorListener);
        }
        return mHttpService;
    }

    /**
     * Extracts the host from a URL string.
     * Falls back to the default Intempt API host if extraction fails.
     */
    private String extractHostFromUrl(String urlString) {
        try {
            return new URL(urlString).getHost();
        } catch (Exception e) {
            QueueLog.e(LOGTAG, "Could not extract host from URL " + urlString + ". Using default host instead.", e);
            return DEFAULT_SERVER_HOST;
        }
    }

    ////////////////////////////////////////////////////

    static class EventDescription extends QueueMessageDescription {

        public EventDescription(
                String eventName,
                JSONObject properties,
                String token,
                boolean isAutomatic,
                JSONObject sessionMetadata) {
            this(eventName, properties, token, isAutomatic, sessionMetadata, Collections.emptySet());
        }

        public EventDescription(
                String eventName,
                JSONObject properties,
                String token,
                boolean isAutomatic,
                JSONObject sessionMetadata,
                Set<String> excludeProperties) {
            super(token, properties);
            mEventName = eventName;
            mIsAutomatic = isAutomatic;
            mSessionMetadata = sessionMetadata;
            mExcludeProperties =
                    excludeProperties == null ? Collections.emptySet() : excludeProperties;
        }

        public String getEventName() {
            return mEventName;
        }

        public JSONObject getProperties() {
            return getMessage();
        }

        public JSONObject getSessionMetadata() {
            return mSessionMetadata;
        }

        public boolean isAutomatic() {
            return mIsAutomatic;
        }

        public Set<String> getExcludeProperties() {
            return mExcludeProperties;
        }

        private final String mEventName;
        private final JSONObject mSessionMetadata;
        private final boolean mIsAutomatic;
        private final Set<String> mExcludeProperties;
    }


    static class QueueMessageDescription extends QueueDescription {
        public QueueMessageDescription(String token, JSONObject message) {
            super(token);
            if (message != null && message.length() > 0) {
                Iterator<String> it = message.keys();
                while (it.hasNext()) {
                    String jsonKey = it.next();
                    try {
                        message.get(jsonKey).toString();
                    } catch (AssertionError e) {
                        // see https://github.com/mixpanel/mixpanel-android/issues/567
                        message.remove(jsonKey);
                        QueueLog.e(
                                LOGTAG,
                                "Removing people profile property from update (see"
                                        + " https://github.com/mixpanel/mixpanel-android/issues/567)",
                                e);
                    } catch (JSONException e) {
                    }
                }
            }
            this.mMessage = message;
        }

        public JSONObject getMessage() {
            return mMessage;
        }

        private final JSONObject mMessage;
    }


    static class QueueDescription {
        public QueueDescription(String token) {
            this.mToken = token;
        }

        public String getToken() {
            return mToken;
        }

        private final String mToken;
    }


    // Sends a message if and only if queue logging is enabled.
    // Will be called from the delivery worker thread.
    private void logAboutMessage(String message) {
        QueueLog.v(LOGTAG, message + " (Thread " + Thread.currentThread().getId() + ")");
    }

    private void logAboutMessage(String message, Throwable e) {
        QueueLog.v(LOGTAG, message + " (Thread " + Thread.currentThread().getId() + ")", e);
    }

    // Worker will manage the (at most single) IO thread associated with
    // this DeliveryMessages instance.
    // XXX: Worker class is unnecessary, should be just a subclass of HandlerThread
    class Worker {
        public Worker() {
            mHandler = restartWorkerThread();
        }

        public boolean isDead() {
            synchronized (mHandlerLock) {
                return mHandler == null;
            }
        }

        public void runMessage(Message msg) {
            synchronized (mHandlerLock) {
                if (mHandler == null) {
                    // Modification: revive the worker instead of dropping forever.
                    //
                    // Upstream logs and discards. Any RuntimeException in handleMessage nulls
                    // mHandler and quits the looper, and restartWorkerThread() was only ever
                    // called from the constructor — so one transient fault (SQLiteException is
                    // a RuntimeException, and an unopenable or corrupt database throws it
                    // outside the guarded block) killed delivery for the whole process
                    // lifetime. Every later event was silently dropped at this line, with
                    // nothing restarting it and nothing reporting it.
                    QueueLog.e(LOGTAG, "Delivery worker had died; restarting it");
                    mHandler = restartWorkerThread();
                    if (mHandler == null) {
                        logAboutMessage("Could not restart the delivery worker, dropping: " + msg.what);
                        return;
                    }
                }
                mHandler.sendMessage(msg);
            }
        }

        // Runs until a hard kill or an unhandled RuntimeException in handleMessage. The
        // latter nulls mHandler and quits the looper; runMessage restarts it on the next
        // event. Upstream's "will run FOREVER" was not accurate even upstream.
        protected Handler restartWorkerThread() {
            final HandlerThread thread =
                    new HandlerThread(
                            "com.intempt.core.DeliveryWorker", Process.THREAD_PRIORITY_BACKGROUND);
            thread.start();
            return new AnalyticsMessageHandler(thread.getLooper());
        }

        class AnalyticsMessageHandler extends Handler {
            public AnalyticsMessageHandler(Looper looper) {
                super(looper);
                mDbAdapter = null;
            }

            @Override
            public void handleMessage(Message msg) {
                if (mDbAdapter == null) {
                    mDbAdapter = makeDbAdapter(mContext);
                    sweepExpiredEvents();
                } else {
                    // Modification: re-run the age-based sweep periodically, not only once
                    // per worker lifetime.
                    //
                    // Upstream only ever calls cleanupEvents(time, ...) here, guarded by
                    // "mDbAdapter == null" — i.e. exactly once per (re)start of this handler.
                    // A row only leaves the table two other ways: delivered successfully, or
                    // dropped for an unrecoverable status (see shouldDrop below). A row stuck
                    // on a *retryable* failure (offline, 5xx, timeout) sits in the table for
                    // as long as the process lives, however many days that is; the retry
                    // backoff below keeps re-sending FLUSH_QUEUE the whole time, but nothing
                    // ever re-checks its age. On a process that never restarts the delivery
                    // worker, the 5-day expiration configured in QueueConfig never actually
                    // fires again after the first message. Re-checking on every handled
                    // message would be wasteful, so this is gated to run at most once per
                    // expiration window.
                    sweepExpiredEventsIfDue();
                }

                try {
                    int returnCode = EventDbAdapter.DB_UNDEFINED_CODE;
                    String token = null;

                    if (msg.what == ENQUEUE_EVENTS) {
                        final EventDescription eventDescription = (EventDescription) msg.obj;
                        try {
                            token = eventDescription.getToken();
                            returnCode = insertEventToDb(eventDescription);
                        } catch (final JSONException e) {
                            QueueLog.e(LOGTAG, "Exception tracking event " + eventDescription.getEventName(), e);
                        }
                    } else if (msg.what == FLUSH_QUEUE) {
                        logAboutMessage("Flushing queue due to scheduled or forced flush");
                        updateFlushFrequency();
                        token = (String) msg.obj;
                        final int delivered = sendAllData(mDbAdapter, token);
                        // Modification: answer any pending flush completions. Drained even when
                        // delivered is 0 — offline, empty queue, or a batch that was retried — so a
                        // caller awaiting flush() is never left hanging on a failure.
                        notifyFlushCompletions(delivered);
                    } else if (msg.what == EMPTY_QUEUES) {
                        final QueueDescription message = (QueueDescription) msg.obj;
                        token = message.getToken();
                        mDbAdapter.cleanupAllEvents(EventDbAdapter.Table.EVENTS);
                    } else if (msg.what == KILL_WORKER) {
                        QueueLog.w(
                                LOGTAG,
                                "Worker received a hard kill. Dumping all events and force-killing. Thread id "
                                        + Thread.currentThread().getId());
                        synchronized (mHandlerLock) {
                            mDbAdapter.deleteDB();
                            mHandler = null;
                            Looper.myLooper().quit();
                        }
                    } else {
                        QueueLog.e(LOGTAG, "Unexpected message received by delivery worker: " + msg);
                    }

                    ///////////////////////////
                    if (DeliveryRetryPolicy.shouldFlushOnBulkLimit(
                            returnCode,
                            mConfig.getBulkUploadLimit(),
                            EventDbAdapter.DB_OUT_OF_MEMORY_ERROR,
                            mFailedRetries,
                            token)) {
                        logAboutMessage(
                                "Flushing queue due to bulk upload limit ("
                                        + returnCode
                                        + ") for project "
                                        + token);
                        updateFlushFrequency();
                        sendAllData(mDbAdapter, token);
                    } else if (returnCode > 0 && !hasMessages(FLUSH_QUEUE, token)) {
                        // The !hasMessages(FLUSH_QUEUE, token) check is a courtesy for the common case
                        // of delayed flushes already enqueued from inside of this thread.
                        // Callers outside of this thread can still send
                        // a flush right here, so we may end up with two flushes
                        // in our queue, but we're OK with that.

                        // Modification: read the interval per decision instead of caching it in a
                        // final field at handler construction. setFlushInterval() would otherwise
                        // only take effect after a worker restart, which an app cannot trigger.
                        final int flushInterval = mConfig.getFlushInterval();
                        logAboutMessage(
                                "Queue depth " + returnCode + " - Adding flush in " + flushInterval);
                        // > 0, not >= 0: the contract makes 0 mean "timer disabled". Upstream's
                        // >= 0 would post a zero-delay message instead, spinning the worker.
                        if (flushInterval > 0) {
                            final Message flushMessage = Message.obtain();
                            flushMessage.what = FLUSH_QUEUE;
                            flushMessage.obj = token;
                            flushMessage.arg1 = 1;
                            sendMessageDelayed(flushMessage, flushInterval);
                        }
                    }
                } catch (final RuntimeException e) {
                    QueueLog.e(LOGTAG, "Worker threw an unhandled exception", e);
                    synchronized (mHandlerLock) {
                        mHandler = null;
                        try {
                            Looper.myLooper().quit();
                            QueueLog.e(LOGTAG, "Intempt will not process any more analytics messages", e);
                        } catch (final Exception tooLate) {
                            QueueLog.e(LOGTAG, "Could not halt looper", tooLate);
                        }
                    }
                }
            } // handleMessage

            protected long getTrackEngageRetryAfter() {
                return mTrackEngageRetryAfter;
            }

            /** @return the number of events the server accepted. Upstream returns void. */
            private int sendAllData(EventDbAdapter dbAdapter, String token) {
                final RemoteService poster = getPoster();
                if (!poster.isOnline(mContext, mConfig.getOfflineMode())) {
                    logAboutMessage(
                            "Not flushing data because the device is not connected to the internet.");
                    return 0;
                }

                return sendData(
                        dbAdapter, token, EventDbAdapter.Table.EVENTS, mConfig.getEventsEndpoint());
            }

            /** @return the number of events the server accepted. Upstream returns void. */
            private int sendData(
                    EventDbAdapter dbAdapter, String token, EventDbAdapter.Table table, String url) {
                int delivered = 0;
                final RemoteService poster = getPoster();
                String[] eventsData = dbAdapter.generateDataString(table);
                Integer queueCount = 0;
                if (eventsData != null) {
                    queueCount = Integer.valueOf(eventsData[2]);
                }

                while (eventsData != null && queueCount > 0) {
                    final String lastId = eventsData[0];
                    final String rawMessage = eventsData[1];

                    // Intempt posts the batch as a raw JSON body wrapped in its ingestion
                    // envelope. Mixpanel posted data=base64(json) as
                    // application/x-www-form-urlencoded, which our endpoint does not accept.
                    final JSONObject envelope = TrackPayloadBuilder.build(rawMessage);
                    if (envelope == null) {
                        // Unparseable batch: drop it rather than retry forever. Same
                        // reasoning as an unrecoverable 4xx — retaining it would block the
                        // queue head and lose every event behind it too.
                        QueueLog.e(LOGTAG, "Dropping unparseable batch so it cannot block the queue");
                        dbAdapter.cleanupEvents(lastId, table);
                        eventsData = dbAdapter.generateDataString(table);
                        queueCount = eventsData != null ? Integer.valueOf(eventsData[2]) : 0;
                        continue;
                    }
                    final byte[] requestBody = envelope.toString().getBytes(StandardCharsets.UTF_8);

                    boolean deleteEvents = true;
                    // Modification: distinct from deleteEvents, which is also true when an
                    // unrecoverable status makes us drop a batch. A dropped batch is not a
                    // delivered one, and reporting it as delivered through the flush completion
                    // would report total data loss as a successful flush — the same conflation
                    // that made "row left the queue" a useless test oracle.
                    boolean accepted = false;
                    RemoteService.RequestResult result;
                    try {
                        final SSLSocketFactory socketFactory = mConfig.getSSLSocketFactory();
                        // Modification: send an Authorization header.
                        //
                        // Upstream passes null here because Mixpanel authenticates with a
                        // project token inside the event body. Intempt uses HTTP Basic, so
                        // vendoring this method as-is removed authentication entirely: every
                        // batch was posted unauthenticated, answered 401, and then DELETED by
                        // the shouldDrop(401) branch below. The queue behaved perfectly and
                        // discarded 100% of events.
                        final Map<String, String> headers = new HashMap<>();
                        final String authorization = mConfig.getAuthorization();
                        if (authorization != null && !authorization.isEmpty()) {
                            headers.put("Authorization", authorization);
                        } else {
                            // Loud, because the alternative is a silent total loss.
                            QueueLog.e(
                                    LOGTAG,
                                    "No Authorization header configured; every batch will 401 and be"
                                            + " dropped. Check INTEMPT_API_KEY in intempt-config.json.");
                        }
                        headers.put("Content-Type", "application/json");

                        result =
                                poster.performRequest(
                                        url,
                                        mConfig.getProxyServerInteractor(),
                                        null,
                                        headers,
                                        requestBody,
                                        socketFactory);
                        byte[] response = result.getResponse();
                        String actualUrl = result.getRequestUrl(); // Get the actual URL that succeeded

                        if (null == response) {
                            deleteEvents = false;
                            logAboutMessage(
                                    "Response was null, unexpected failure posting to " + actualUrl + ".");
                        } else {
                            deleteEvents =
                                    true; // Delete events on any successful post, regardless of 1 or 0 response
                            accepted = true;
                            String parsedResponse;
                            try {
                                parsedResponse = new String(response, "UTF-8");
                            } catch (UnsupportedEncodingException e) {
                                throw new RuntimeException("UTF not supported on this platform?", e);
                            }
                            if (mFailedRetries > 0) {
                                mFailedRetries = 0;
                                // Also clear the floor, not just the counter. Upstream leaves
                                // mTrackEngageRetryAfter at whatever the last failure raised
                                // it to, and the next delay is max(2^0 * 60s, thatValue) — so
                                // after one bad streak every later retry waits the full ten
                                // minutes for the rest of the process.
                                mTrackEngageRetryAfter = 0;
                                removeMessages(FLUSH_QUEUE, token);
                            }

                            logAboutMessage("Successfully posted to " + actualUrl + ": \n" + rawMessage);
                            logAboutMessage("Response was " + parsedResponse);
                        }
                    } catch (final OutOfMemoryError e) {
                        QueueLog.e(LOGTAG, "Out of memory when posting to " + url + ".", e);
                    } catch (final MalformedURLException e) {
                        QueueLog.e(LOGTAG, "Cannot interpret " + url + " as a URL.", e);
                    } catch (final RemoteService.ServiceUnavailableException e) {
                        logAboutMessage("Cannot post message to " + url + ".", e);
                        deleteEvents = false;
                        mTrackEngageRetryAfter = e.getRetryAfter() * 1000;
                    } catch (final RemoteService.ClientErrorException e) {
                        // Must precede the IOException catch below: ClientErrorException
                        // extends IOException, so without this branch every 4xx falls
                        // through to deleteEvents = false and is retried forever. A
                        // rejected key or malformed body never becomes valid, and because
                        // the batch is never deleted it blocks the queue head and stops
                        // all delivery. 429 is deliberately excluded — the gateway uses it
                        // for backpressure, so retrying is correct there.
                        final int status = e.getResponseCode();
                        if (HttpStatusPolicy.shouldDrop(status)) {
                            QueueLog.e(
                                    LOGTAG,
                                    "Unrecoverable status " + status + " posting to " + url
                                            + "; dropping this batch so it cannot block the queue",
                                    e);
                            deleteEvents = true;
                        } else {
                            logAboutMessage("Retryable client error from " + url + ".", e);
                            deleteEvents = false;
                        }
                    } catch (final IOException e) {
                        logAboutMessage("Cannot post message to " + url + ".", e);
                        deleteEvents = false;
                    }

                    if (deleteEvents) {
                        if (accepted) {
                            delivered += countEvents(envelope);
                        }
                        logAboutMessage("Not retrying this batch of events, deleting them from DB.");
                        dbAdapter.cleanupEvents(lastId, table);
                    } else {
                        removeMessages(FLUSH_QUEUE, token);
                        mTrackEngageRetryAfter =
                                DeliveryRetryPolicy.nextRetryDelay(mFailedRetries, mTrackEngageRetryAfter);
                        final Message flushMessage = Message.obtain();
                        flushMessage.what = FLUSH_QUEUE;
                        flushMessage.obj = token;
                        sendMessageDelayed(flushMessage, mTrackEngageRetryAfter);
                        mFailedRetries++;
                        logAboutMessage(
                                "Retrying this batch of events in " + mTrackEngageRetryAfter + " ms");
                        break;
                    }

                    eventsData = dbAdapter.generateDataString(table);
                    if (eventsData != null) {
                        queueCount = Integer.valueOf(eventsData[2]);
                    }
                }

                return delivered;
            }

            /**
             * Number of events in an assembled request body. Not an upstream method.
             *
             * <p>Counts the {@code track} array rather than the queue depth: the depth is what
             * remained before the post, so a caller told "12 delivered" when 12 were merely
             * pending would read a partial flush as a complete one.
             */
            private int countEvents(JSONObject envelope) {
                final org.json.JSONArray events =
                        envelope == null ? null : envelope.optJSONArray(TrackPayloadBuilder.TRACK_KEY);
                return events == null ? 0 : events.length();
            }


            /**
             * Stores the event exactly as the SDK produced it.
             *
             * <p>Mixpanel wrapped every event in its own envelope here
             * ({@code {event, properties, $mp_metadata}}) and decorated it with
             * device properties. Intempt's wire format is different — the Kotlin
             * layer has already built the correct per-event payload — so the queue
             * is a verbatim durable pipe and {@code TrackPayloadBuilder} assembles
             * the {@code {"track":[{name,payload:[]}]}} request body at flush time.
             */
            private int insertEventToDb(EventDescription eventDescription) throws JSONException {
                final JSONObject message = eventDescription.getProperties();
                logAboutMessage("Queuing event for sending later");
                logAboutMessage("    " + message);
                return mDbAdapter.addJSON(message, EventDbAdapter.Table.EVENTS);
            }

            private void sweepExpiredEvents() {
                mDbAdapter.cleanupEvents(
                        System.currentTimeMillis() - mConfig.getDataExpiration(), EventDbAdapter.Table.EVENTS);
                mLastExpirationSweepTime = System.currentTimeMillis();
            }

            private void sweepExpiredEventsIfDue() {
                final long now = System.currentTimeMillis();
                if (now - mLastExpirationSweepTime >= mConfig.getDataExpiration()) {
                    sweepExpiredEvents();
                }
            }

            private EventDbAdapter mDbAdapter;
            private long mTrackEngageRetryAfter;
            private int mFailedRetries;
            private long mLastExpirationSweepTime;
        } // AnalyticsMessageHandler

        private void updateFlushFrequency() {
            final long now = System.currentTimeMillis();
            final long newFlushCount = mFlushCount + 1;

            if (mLastFlushTime > 0) {
                final long flushInterval = now - mLastFlushTime;
                final long totalFlushTime = flushInterval + (mAveFlushFrequency * mFlushCount);
                mAveFlushFrequency = totalFlushTime / newFlushCount;

                final long seconds = mAveFlushFrequency / 1000;
                logAboutMessage("Average send frequency approximately " + seconds + " seconds.");
            }

            mLastFlushTime = now;
            mFlushCount = newFlushCount;
        }

        private final Object mHandlerLock = new Object();
        private Handler mHandler;
        private long mFlushCount = 0;
        private long mAveFlushFrequency = 0;
        private long mLastFlushTime = -1;
    }

    public long getTrackEngageRetryAfter() {
        return ((Worker.AnalyticsMessageHandler) mWorker.mHandler).getTrackEngageRetryAfter();
    }

    /////////////////////////////////////////////////////////

    /**
     * Modification: pending {@link FlushCompletion} callbacks, drained by the next flush to
     * finish. Concurrent because callers register from any thread and the worker drains from
     * its own.
     */
    private final java.util.Queue<FlushCompletion> mFlushCompletions =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    /**
     * Answers every completion registered before this flush finished.
     *
     * <p>Each callback is invoked inside its own try/catch: a host app's callback that throws
     * must not take down the delivery worker, whose death would silently stop all uploads.
     */
    private void notifyFlushCompletions(int delivered) {
        FlushCompletion completion;
        while ((completion = mFlushCompletions.poll()) != null) {
            try {
                completion.onFlushed(delivered);
            } catch (final Throwable t) {
                QueueLog.e(LOGTAG, "Flush completion threw; ignoring so the worker survives", t);
            }
        }
    }

    /** Instance-scoped queue database, or null for the default. Not an upstream field. */
    private final String mDbName;

    // Used across thread boundaries
    private final Worker mWorker;
    protected final Context mContext;
    protected final QueueConfig mConfig;
    protected NetworkErrorListener mNetworkErrorListener;

    // Messages for our thread
    private static final int ENQUEUE_EVENTS = 1; // push given JSON message to events DB
    private static final int FLUSH_QUEUE = 2; // submit events, people, and groups data
    private static final int KILL_WORKER =
            5; // Hard-kill the worker thread, discarding all events on the event queue. This is for
    // testing, or disasters.
    private static final int EMPTY_QUEUES =
            6; // Remove any local (and pending to be flushed) events or people/group updates from the db

    private static final String LOGTAG = "Intempt.Messages";

}
