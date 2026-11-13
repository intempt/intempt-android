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

import java.io.IOException;

/**
 * The request failed before its body was fully written: DNS, a refused or timed-out connect, TLS.
 * The server cannot have the batch, so it is the one failure delivery keeps the batch for
 * (CONTRACT-R2 S1.5, INT-7436). Every failure after the write drops, since a resend could
 * duplicate events the server already took.
 */
/* package */ final class NotSentException extends IOException {
    /* package */ NotSentException(Throwable cause) {
        super("Not sent: " + cause, cause);
    }
}
