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
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.util.DisplayMetrics;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.SSLSocketFactory;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Manage communication of events with the internal database and the Mixpanel servers.
 *
 * <p>This class straddles the thread boundary between user threads and a logical Mixpanel thread.
 */
/* package */ class DeliveryMessages {

    // Replaces Mixpanel's MPConstants.URL.DEFAULT_SERVER_HOST, not inherited.
    private static final String DEFAULT_SERVER_HOST = "api.intempt.com";


    /** Do not call directly. You should call DeliveryMessages.getInstance() */
    /* package */ DeliveryMessages(final Context context, QueueConfig config) {
        mContext = context;
        mConfig = config;
        mInstanceName = config.getInstanceName();
        mWorker = createWorker();
        getPoster().checkIsServerBlocked();
    }

    protected Worker createWorker() {
        return new Worker();
    }

    /**
     * Use this to get an instance of DeliveryMessages instead of creating one directly for yourself.
     *
     * @param messageContext should be the Main Activity of the application associated with these
     *     messages.
     * @param config The QueueConfig configuration settings for the DeliveryMessages instance.
     */
    public static DeliveryMessages getInstance(final Context messageContext, QueueConfig config) {
        synchronized (sInstances) {
            final Context appContext = messageContext.getApplicationContext();
            DeliveryMessages ret;
            String instanceName = config.getInstanceName();
            if (!sInstances.containsKey(instanceName)) {
                ret = new DeliveryMessages(appContext, config);
                sInstances.put(instanceName, ret);
            } else {
                ret = sInstances.get(instanceName);
            }
            return ret;
        }
    }

    public void setNetworkErrorListener(NetworkErrorListener errorListener) {
        mNetworkErrorListener = errorListener;
        if (mHttpService != null) {
            mHttpService.setNetworkErrorListener(errorListener);
        }
    }

    public void eventsMessage(final EventDescription eventDescription) {
        final Message m = Message.obtain();
        m.what = ENQUEUE_EVENTS;
        m.obj = eventDescription;
        mWorker.runMessage(m);
    }

    // Must be thread safe.

    // Must be thread safe.

    // Must be thread safe.

    // Must be thread safe.

    public void postToServer(final QueueDescription flushDescription) {
        final Message m = Message.obtain();
        m.what = FLUSH_QUEUE;
        m.obj = flushDescription.getToken();
        m.arg1 = 0;

        mWorker.runMessage(m);
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
        return EventDbAdapter.getInstance(context, mConfig);
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
                    // We died under suspicious circumstances. Don't try to send any more events.
                    logAboutMessage("Dead delivery worker dropping a message: " + msg.what);
                } else {
                    mHandler.sendMessage(msg);
                }
            }
        }

        // NOTE that the returned worker will run FOREVER, unless you send a hard kill
        // (which you really shouldn't)
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
                mFlushInterval = mConfig.getFlushInterval();
            }

            @Override
            public void handleMessage(Message msg) {
                if (mDbAdapter == null) {
                    mDbAdapter = makeDbAdapter(mContext);
                    mDbAdapter.cleanupEvents(
                            System.currentTimeMillis() - mConfig.getDataExpiration(), EventDbAdapter.Table.EVENTS);
                    mDbAdapter.cleanupEvents(
                            System.currentTimeMillis() - mConfig.getDataExpiration(), EventDbAdapter.Table.PEOPLE);
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
                        sendAllData(mDbAdapter, token);
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
                    if ((returnCode >= mConfig.getBulkUploadLimit()
                            || returnCode == EventDbAdapter.DB_OUT_OF_MEMORY_ERROR)
                            && mFailedRetries <= 0
                            && token != null) {
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

                        logAboutMessage(
                                "Queue depth " + returnCode + " - Adding flush in " + mFlushInterval);
                        if (mFlushInterval >= 0) {
                            final Message flushMessage = Message.obtain();
                            flushMessage.what = FLUSH_QUEUE;
                            flushMessage.obj = token;
                            flushMessage.arg1 = 1;
                            sendMessageDelayed(flushMessage, mFlushInterval);
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

            private void sendAllData(EventDbAdapter dbAdapter, String token) {
                final RemoteService poster = getPoster();
                if (!poster.isOnline(mContext, mConfig.getOfflineMode())) {
                    logAboutMessage(
                            "Not flushing data because the device is not connected to the internet.");
                    return;
                }

                sendData(dbAdapter, token, EventDbAdapter.Table.EVENTS, mConfig.getEventsEndpoint());
            }

            private void sendData(
                    EventDbAdapter dbAdapter, String token, EventDbAdapter.Table table, String url) {
                final RemoteService poster = getPoster();
                String[] eventsData = dbAdapter.generateDataString(table);
                Integer queueCount = 0;
                if (eventsData != null) {
                    queueCount = Integer.valueOf(eventsData[2]);
                }

                while (eventsData != null && queueCount > 0) {
                    final String lastId = eventsData[0];
                    final String rawMessage = eventsData[1];

                    // Intempt posts the batch as a raw JSON body. Mixpanel posted
                    // data=base64(json) as application/x-www-form-urlencoded, which
                    // our ingestion endpoint does not accept.
                    final byte[] requestBody = rawMessage.getBytes(StandardCharsets.UTF_8);

                    boolean deleteEvents = true;
                    RemoteService.RequestResult result;
                    try {
                        final SSLSocketFactory socketFactory = mConfig.getSSLSocketFactory();
                        result =
                                poster.performRequest(
                                        url, mConfig.getProxyServerInteractor(), null, null, requestBody, socketFactory);
                        byte[] response = result.getResponse();
                        String actualUrl = result.getRequestUrl(); // Get the actual URL that succeeded

                        if (null == response) {
                            deleteEvents = false;
                            logAboutMessage(
                                    "Response was null, unexpected failure posting to " + actualUrl + ".");
                        } else {
                            deleteEvents =
                                    true; // Delete events on any successful post, regardless of 1 or 0 response
                            String parsedResponse;
                            try {
                                parsedResponse = new String(response, "UTF-8");
                            } catch (UnsupportedEncodingException e) {
                                throw new RuntimeException("UTF not supported on this platform?", e);
                            }
                            if (mFailedRetries > 0) {
                                mFailedRetries = 0;
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
                    } catch (final IOException e) {
                        logAboutMessage("Cannot post message to " + url + ".", e);
                        deleteEvents = false;
                    }

                    if (deleteEvents) {
                        logAboutMessage("Not retrying this batch of events, deleting them from DB.");
                        dbAdapter.cleanupEvents(lastId, table);
                    } else {
                        removeMessages(FLUSH_QUEUE, token);
                        mTrackEngageRetryAfter =
                                Math.max((long) Math.pow(2, mFailedRetries) * 60000, mTrackEngageRetryAfter);
                        mTrackEngageRetryAfter =
                                Math.min(mTrackEngageRetryAfter, 10 * 60 * 1000); // limit 10 min
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

            private EventDbAdapter mDbAdapter;
            private final long mFlushInterval;
            private long mTrackEngageRetryAfter;
            private int mFailedRetries;
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

    // Used across thread boundaries
    private final Worker mWorker;
    private final String mInstanceName;
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

    private static final Map<String, DeliveryMessages> sInstances = new HashMap<>();
}
