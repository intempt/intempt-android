package com.intempt.core.queue;

/**
 * Decides whether a failed batch should be retried or dropped.
 *
 * <p>Mixpanel has no equivalent of this class, and the reason is worth recording
 * precisely, because it is the opposite of what a quick read suggests.
 *
 * <p>{@code HttpService} throws {@link RemoteService.ClientErrorException} for any 4xx and
 * deliberately skips backup-host failover, on the grounds that a different host will not
 * fix a bad request. But {@code ClientErrorException extends IOException}, and
 * {@code DeliveryMessages.sendData} catches {@code IOException} with
 * {@code deleteEvents = false} — so every 4xx is requeued with exponential backoff.
 *
 * <p>Two consequences follow:
 *
 * <ul>
 *   <li><b>429 is already handled correctly.</b> Intempt's gateway signals backpressure
 *       with 429 plus {@code X-RateLimit-*} headers and no {@code Retry-After}, and the
 *       inherited behaviour — requeue, back off, retry — is exactly right.
 *   <li><b>401, 403, 400 and 422 are handled wrongly.</b> None of them can succeed on
 *       retry: a rejected API key stays rejected, a malformed body stays malformed. The
 *       inherited code retries them every ten minutes for the lifetime of the process,
 *       and because the batch is never deleted it sits at the head of the queue and
 *       blocks every event behind it. One bad batch stops all delivery, permanently.
 * </ul>
 *
 * <p>So this class exists to drop what cannot succeed, not to rescue 429.
 */
/* package */ final class HttpStatusPolicy {

    /**
     * True when the batch can never succeed as sent and must be removed from the queue.
     *
     * <p>Dropping loses those events, which is the lesser harm: retaining them wedges the
     * queue head and loses every subsequent event too.
     */
    /* package */ static boolean shouldDrop(int status) {
        switch (status) {
            case 400: // malformed body — will be malformed on every retry
            case 401: // bad or missing credentials — will not start working
            case 403: // credentials lack permission for this source
            case 422: // failed validation — same payload, same outcome
                return true;
            default:
                return false;
        }
    }

    /**
     * True for failures that a later attempt can plausibly succeed at: rate limiting,
     * server faults, and anything without a usable status (network error, timeout).
     */
    /* package */ static boolean isRetryable(int status) {
        return status == 429 || status >= 500 || status <= 0;
    }

    private HttpStatusPolicy() {
    }
}
