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

/**
 * Pure decision logic extracted from {@code DeliveryMessages.Worker.AnalyticsMessageHandler},
 * where it used to live inline inside the delivery-thread {@code handleMessage}/{@code sendData}
 * methods. Extracted so PIT can mutate it directly; those methods are unreachable from a plain
 * JVM test because they depend on {@code android.os.Handler}/{@code Looper}.
 *
 * <p>Two decisions live here, both load-bearing for how the queue behaves under repeated
 * delivery failures:
 *
 * <ul>
 *   <li>{@link #nextRetryDelay}: the exponential backoff used after a failed post. Getting the
 *       floor/ceiling wrong either hot-loops retries against a struggling server or wedges the
 *       queue for the full ten-minute ceiling on the very first failure.
 *   <li>{@link #shouldFlushOnBulkLimit}: whether a just-inserted row should trigger an immediate
 *       flush because the on-disk queue crossed the configured bulk-upload size. Getting this
 *       wrong either flushes on every single event (defeating batching) or never flushes until
 *       the periodic timer fires, letting the queue grow unbounded between timer ticks.
 * </ul>
 */
/* package */ final class DeliveryRetryPolicy {

    /* package */ static final long BASE_DELAY_MS = 60_000L;
    /* package */ static final long MAX_DELAY_MS = 10 * 60 * 1000L;

    /**
     * Computes the delay before the next retry attempt, given how many consecutive failures have
     * already happened and the current floor already established by a slower-arriving response
     * (e.g. a 503's {@code Retry-After}).
     *
     * <p>The result is never lower than {@code currentFloor} and never higher than {@link
     * #MAX_DELAY_MS}. {@code failedRetries} of 0 starts at {@link #BASE_DELAY_MS} (one minute)
     * and doubles on each subsequent call, so the caller is expected to increment its own
     * counter between calls.
     *
     * @param failedRetries number of consecutive failed delivery attempts so far (0-based)
     * @param currentFloor a delay that must not be shortened, in milliseconds (e.g. a
     *     server-supplied Retry-After already converted to milliseconds); pass 0 if there is none
     * @return the delay to wait before the next retry, in milliseconds
     */
    /* package */ static long nextRetryDelay(int failedRetries, long currentFloor) {
        final long exponential = (long) Math.pow(2, failedRetries) * BASE_DELAY_MS;
        final long delay = Math.max(exponential, currentFloor);
        return Math.min(delay, MAX_DELAY_MS);
    }

    /**
     * Decides whether a just-inserted row should trigger an immediate flush because the on-disk
     * queue has crossed the configured bulk-upload size (or hit the out-of-memory sentinel,
     * which is also treated as "big enough to flush now" since the DB adapter could not even
     * count the rows).
     *
     * <p>A pending retry backoff ({@code failedRetries > 0}) suppresses this: flushing while a
     * retry is already scheduled would just re-fail immediately and reset nothing, so the
     * scheduled retry is left to run instead.
     *
     * @param insertReturnCode the return code from the just-completed insert: either the new
     *     queue depth, or {@code outOfMemorySentinel}
     * @param bulkUploadLimit the configured queue-depth threshold that should trigger a flush
     * @param outOfMemorySentinel the sentinel value the DB adapter uses to signal it could not
     *     complete the insert/count due to an out-of-memory condition
     * @param failedRetries number of consecutive failed delivery attempts already pending a retry
     * @param token the routing token for this queue; a null token means there is nothing to flush
     * @return true if a flush should be triggered immediately
     */
    /* package */ static boolean shouldFlushOnBulkLimit(
            int insertReturnCode,
            int bulkUploadLimit,
            int outOfMemorySentinel,
            int failedRetries,
            String token) {
        return (insertReturnCode >= bulkUploadLimit || insertReturnCode == outOfMemorySentinel)
                && failedRetries <= 0
                && token != null;
    }

    private DeliveryRetryPolicy() {
    }
}
