/*
 * Copyright (c) 2026 Intempt Technologies
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.intempt.core.queue;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Extracts a stable dedup key from a row about to be written to the event queue, so
 * {@code EventDbAdapter} can refuse to store the same logical event twice.
 *
 * <p>Every queued row is shaped {@code {name, type, payload:[{eventId,...}, ...]}} — see
 * {@code IntemptEvent.toFormated()}. Each entry in {@code payload} carries an {@code eventId}
 * that {@code UtilsService} generates once, when the event is created, and that never changes
 * across delivery retries (the same DB row is re-read and re-sent verbatim). Joining every
 * {@code eventId} in the payload into one key means a row can only collide with another row
 * that was built from the exact same set of events.
 *
 * <p>Rows with no {@code payload} array, or whose payload entries carry no {@code eventId} at
 * all, produce a null key. That is deliberate rather than an oversight: a small number of call
 * sites store hand-built JSON with no {@code eventId} field (tests, and any future caller that
 * bypasses {@code IntemptEvent}), and those rows must keep inserting unconditionally rather than
 * colliding with each other under a shared null-ish key.
 */
/* package */ final class EventDedupKey {

    /* package */ static final String PAYLOAD_KEY = "payload";
    /* package */ static final String EVENT_ID_KEY = "eventId";

    /**
     * @param row the row about to be written, in {@code IntemptEvent.toFormated()} shape
     * @return a stable key identifying this exact set of events, or null if none of the payload
     *     entries carry an {@code eventId}
     */
    /* package */ static String extract(JSONObject row) {
        if (row == null) {
            return null;
        }

        final JSONArray payload = row.optJSONArray(PAYLOAD_KEY);
        if (payload == null || payload.length() == 0) {
            return null;
        }

        final StringBuilder key = new StringBuilder();
        boolean sawAnId = false;
        for (int i = 0; i < payload.length(); i++) {
            final JSONObject entry = payload.optJSONObject(i);
            final String eventId = entry == null ? null : entry.optString(EVENT_ID_KEY, null);
            if (eventId != null) {
                sawAnId = true;
            }
            if (i > 0) {
                key.append(',');
            }
            // An entry with no eventId still occupies its position, rather than being skipped,
            // so [id1, missing, id2] can never collide with [id1, id2].
            key.append(eventId == null ? "" : eventId);
        }

        return sawAnId ? key.toString() : null;
    }

    private EventDedupKey() {
    }
}
