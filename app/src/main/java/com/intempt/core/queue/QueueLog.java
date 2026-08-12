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

import android.util.Log;

/**
 * Logging shim for the vendored delivery substrate.
 *
 * <p>Exists so the vendored files compile without Mixpanel's MPLog, which was not
 * inherited. Gated by a single flag wired from ConfigManagerService.isLoggingEnabled
 * via QueueConfig.setLoggingEnabled. LoggerManagerService remains the SDK's
 * consumer-facing logger; this is internal to the queue package only.
 */
/* package */ final class QueueLog {

    private static volatile boolean sEnabled = false;

    /* package */ static void setEnabled(boolean enabled) {
        sEnabled = enabled;
    }

    /* package */ static void v(String tag, String message) {
        if (sEnabled) Log.v(tag, message);
    }

    /* package */ static void v(String tag, String message, Throwable throwable) {
        if (sEnabled) Log.v(tag, message, throwable);
    }

    /* package */ static void d(String tag, String message) {
        if (sEnabled) Log.d(tag, message);
    }

    /* package */ static void d(String tag, String message, Throwable throwable) {
        if (sEnabled) Log.d(tag, message, throwable);
    }

    /* package */ static void i(String tag, String message) {
        if (sEnabled) Log.i(tag, message);
    }

    /* package */ static void i(String tag, String message, Throwable throwable) {
        if (sEnabled) Log.i(tag, message, throwable);
    }

    /* package */ static void w(String tag, String message) {
        if (sEnabled) Log.w(tag, message);
    }

    /* package */ static void w(String tag, String message, Throwable throwable) {
        if (sEnabled) Log.w(tag, message, throwable);
    }

    /* package */ static void e(String tag, String message) {
        if (sEnabled) Log.e(tag, message);
    }

    /* package */ static void e(String tag, String message, Throwable throwable) {
        if (sEnabled) Log.e(tag, message, throwable);
    }

    private QueueLog() {
    }
}
