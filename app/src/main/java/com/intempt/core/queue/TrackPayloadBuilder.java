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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Wraps a batch of stored events in Intempt's ingestion envelope.
 *
 * <p>Each queue row holds one event exactly as {@code IntemptEvent.toFormated()} produced
 * it — {@code {name, type, payload:[…]}}. {@code EventDbAdapter.generateDataString}
 * returns those rows as a JSON array. The ingestion endpoint expects that array under a
 * {@code track} key:
 *
 * <pre>{"track":[{"name":…,"type":…,"payload":[…]}, …]}</pre>
 *
 * <p>This is byte-for-byte what the SDK sends today from
 * {@code EventPoolManagerService.generateTrackRequestBody}, and it is deliberately left
 * unchanged. Making the queue durable is a reliability change; altering the wire format at
 * the same time would confound the two and make a regression impossible to attribute.
 *
 * <p>One optimisation is deliberately not taken. The contract groups by event name —
 * {@code track[]} entries carry a {@code name} and a {@code payload[]} of every occurrence
 * of that name — so N events sharing a name could collapse into one entry with an N-element
 * payload, cutting request size. The SDK does not do this today, so neither does this
 * class. It belongs in a separate change that can be measured on its own.
 */
/* package */ final class TrackPayloadBuilder {

    /* package */ static final String TRACK_KEY = "track";

    /**
     * @param storedBatchJson the array string from {@code generateDataString}
     * @return the request body, or null if the batch could not be parsed
     */
    /* package */ static JSONObject build(String storedBatchJson) {
        if (storedBatchJson == null) {
            return null;
        }
        try {
            return new JSONObject().put(TRACK_KEY, new JSONArray(storedBatchJson));
        } catch (final JSONException e) {
            // generateDataString already skips rows that fail to parse individually, so
            // reaching here means the assembled array itself is malformed. Returning null
            // lets the caller treat it as a send failure rather than posting a body the
            // endpoint will reject.
            QueueLog.e("Intempt.Messages", "Could not assemble track payload", e);
            return null;
        }
    }

    private TrackPayloadBuilder() {
    }
}
