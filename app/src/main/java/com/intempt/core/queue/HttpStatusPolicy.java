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

/**
 * Retry-versus-drop for a batch, after ruling R-retry as amended (CONTRACT-R2 S1, INT-7436).
 *
 * <ul>
 *   <li><b>Resent once:</b> 503 and 429. {@code HttpService} waits the Retry-After (clamped to
 *       [1, 60] s, 1 s when absent) and sends one more time, in the same call.
 *   <li><b>Dropped:</b> every status at 300 or above that is still the answer after that, since
 *       a status reaches delivery only once the transport is done with it. Re-posting cannot
 *       change a 4xx, and a 5xx resent later risks duplicating a batch the server took.
 *   <li><b>Kept:</b> only a request that never reached the server ({@link NotSentException}).
 *       That is decided from the exception, not from a status, so no status keeps a batch.
 * </ul>
 *
 * <p>The vendored taxonomy kept 403, 404, 408, 429 and every 5xx at the queue head with
 * exponential backoff (INT-5897). It is gone, for consent as well as events (R3, Beso
 * 2026-11-13): consent reads {@link #resendDelaySeconds} through QueueStatusBridge.kt.
 */
/* package */ final class HttpStatusPolicy {

    /** True when the batch can never succeed as sent and must be removed from the queue. */
    /* package */ static boolean shouldDrop(int status) {
        // 3xx included: HttpService does not follow redirects, so a redirect cannot succeed either.
        return status >= 300;
    }

    /** The statuses the transport resends once, after the server's Retry-After. */
    /* package */ static boolean isResentOnce(int status) {
        return status == 503 || status == 429;
    }

    /**
     * Seconds to wait before the one resend, or -1 for none. Only 503 and 429 qualify
     * (CONTRACT-R2 S1.2): the server's Retry-After clamped to [1, 60] s, so an absent or
     * non-positive one waits 1 s and a hostile one cannot park the sender.
     */
    /* package */ static int resendDelaySeconds(int status, int retryAfterSeconds) {
        if (!isResentOnce(status)) {
            return -1;
        }
        return Math.max(MIN_RESEND_DELAY_S, Math.min(retryAfterSeconds, MAX_RESEND_DELAY_S));
    }

    private static final int MIN_RESEND_DELAY_S = 1;
    private static final int MAX_RESEND_DELAY_S = 60;

    private HttpStatusPolicy() {
    }
}
