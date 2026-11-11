/*
 * Copyright (c) 2026 Intempt Technologies
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Written for Intempt; not derived from mixpanel-android.
 */
package com.intempt.core.queue;

import java.util.Collections;
import java.util.List;

/**
 * The final outcome of one batch that did not deliver, in the cross-SDK shape (CONTRACT-R2 S2,
 * INT-7444). Plain Java so the vendored package stays Kotlin-free (see
 * {@link DeliveryMessages.DeliveryFailureListener}).
 */
public final class DeliveryFailure {

    /** Why the batch did not deliver. */
    public enum Kind {
        /** Never reached the server; the batch is kept. Reported once per batch. */
        TRANSPORT("transport"),
        /** The server answered a status that drops the batch, or the one resend failed. */
        TERMINAL("terminal"),
        /** Dropped on the device: a timeout after the write, or an unbuildable request. */
        DROPPED("dropped");

        private final String mWireName;

        Kind(String wireName) {
            mWireName = wireName;
        }

        /** The contract's name for this kind: transport, terminal or dropped. */
        public String getWireName() {
            return mWireName;
        }
    }

    private final Kind mKind;
    private final int mStatus;
    private final List<String> mEventIds;
    private final int mRetryAfterSeconds;

    /* package */ DeliveryFailure(Kind kind, int status, List<String> eventIds, int retryAfterSeconds) {
        mKind = kind;
        mStatus = status;
        mEventIds = Collections.unmodifiableList(eventIds);
        mRetryAfterSeconds = retryAfterSeconds;
    }

    public Kind getKind() {
        return mKind;
    }

    /** The HTTP status the server answered, or 0 when there was none. */
    public int getStatus() {
        return mStatus;
    }

    public int getEventCount() {
        return mEventIds.size();
    }

    public List<String> getEventIds() {
        return mEventIds;
    }

    /** The server's Retry-After in seconds, or 0 when it sent none. */
    public int getRetryAfterSeconds() {
        return mRetryAfterSeconds;
    }

    /** False only for {@link Kind#TRANSPORT}: the batch is still queued. */
    public boolean isTerminal() {
        return mKind != Kind.TRANSPORT;
    }
}
