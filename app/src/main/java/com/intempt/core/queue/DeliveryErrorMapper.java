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
 * package because it reads {@link HttpStatusPolicy}, which is package-private.
 */
package com.intempt.core.queue;

import com.intempt.core.types.IntemptError;

/**
 * Maps a delivery failure onto the cross-SDK contract's error cases.
 *
 * <p>Lives in this package rather than beside the Dagger module because {@link HttpStatusPolicy}
 * is package-private and should stay that way. The alternative was widening a retry-policy class
 * to the whole SDK so that a log line could read it, which is exactly how a class ends up public
 * for no reason — {@code QueueConfig.getAuthorization()} reached the published API that way, still
 * carrying the ingestion credential.
 *
 * <p>Java rather than Kotlin, deliberately: a Kotlin file in this package reading a package-private
 * Java type breaks kapt's stub generation, which then reports every Dagger binding in the module as
 * unresolvable and never names the real cause.
 *
 * <p>The terminal/retryable split is read from {@link HttpStatusPolicy} rather than re-derived. Two
 * places deciding what is retryable is how they drift, and this one would drift <b>silently</b>: it
 * only affects what a host app is told, while the other decides whether a batch survives. Telling
 * an app "retrying" about a batch the transport just deleted is worse than telling it nothing.
 */
/* package */ final class DeliveryErrorMapper {

    private DeliveryErrorMapper() {}

    /* package */ static IntemptError map(
            int responseCode, String responseMessage, Exception exception) {
        // No usable status: the request never got an answer, so this is a transport failure rather
        // than a rejection. The vendored listener passes -1 when no response code could be
        // retrieved, and a connection error leaves 0.
        if (responseCode <= 0) {
            String description = exception == null ? null : exception.getMessage();
            if (description == null || description.isEmpty()) {
                description = responseMessage;
            }
            if (description == null || description.isEmpty()) {
                description = "no response";
            }
            return new IntemptError.Transport(description);
        }

        if (HttpStatusPolicy.shouldDrop(responseCode)) {
            return new IntemptError.Terminal(responseCode);
        }

        Long retryAfterMillis = null;
        if (exception instanceof RemoteService.ServiceUnavailableException) {
            final int seconds = ((RemoteService.ServiceUnavailableException) exception).getRetryAfter();
            if (seconds > 0) {
                // Seconds on the wire, milliseconds in the error — the same unit the retry
                // scheduler uses, so an app comparing the two is not off by a factor of 1000.
                retryAfterMillis = seconds * 1000L;
            }
        }
        return new IntemptError.Retryable(responseCode, retryAfterMillis);
    }
}
