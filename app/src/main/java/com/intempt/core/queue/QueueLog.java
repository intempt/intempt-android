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

    /**
     * Where queue log lines go. Null means straight to {@code android.util.Log}, which is what the
     * vendored substrate did on its own.
     *
     * <p>This exists so the queue is not a second logging system. It used to write to logcat under
     * its own flag, set from a different place than {@code LoggerManagerService}'s — so the output
     * looked unified while "is logging enabled?" had two independent answers. Now
     * {@code LoggerManagerService} installs itself here and there is one destination and one switch.
     *
     * <p>A sink rather than a direct call because this package is the vendored Java substrate: it
     * cannot see the Kotlin service without inverting the dependency, and keeping the substrate
     * ignorant of what consumes its output is also what keeps it comparable against upstream.
     */
    private static volatile Sink sSink = null;

    /** Receives a queue log line. Priority values are {@code android.util.Log}'s. */
    /* package */ interface Sink {
        void write(int priority, String tag, String message, Throwable throwable);
    }

    /* package */ static void setSink(Sink sink) {
        sSink = sink;
    }

    /**
     * Routes one line, to the installed sink if there is one and to logcat otherwise.
     *
     * <p>The {@code sEnabled} check stays here for the no-sink case. When a sink IS installed it
     * applies its own gate — {@code LoggerManagerService} reads
     * {@code ConfigManagerService.isLoggingEnabled} directly — so this must not also gate, or
     * enabling logging through the SDK's own API would still be suppressed by a flag the consumer
     * never sees.
     */
    private static void emit(int priority, String tag, String message, Throwable throwable) {
        final Sink sink = sSink;
        if (sink != null) {
            sink.write(priority, tag, message, throwable);
            return;
        }
        if (!sEnabled) {
            return;
        }
        if (throwable == null) {
            Log.println(priority, tag, message);
        } else {
            Log.println(priority, tag, message + '\n' + Log.getStackTraceString(throwable));
        }
    }

    /**
     * Whether verbose queue logging is on.
     *
     * <p>Exists so {@code QueueConfig.setLoggingEnabled} has an observable effect. Without it,
     * mutation testing showed that deleting the {@code setEnabled} call from that setter killed no
     * test: the only assertion was that it did not throw, which a no-op also satisfies.
     */
    /* package */ static boolean isEnabled() {
        return sEnabled;
    }

    /* package */ static void setEnabled(boolean enabled) {
        sEnabled = enabled;
    }

    /* package */ static void v(String tag, String message) {
        emit(Log.VERBOSE, tag, message, null);
    }

    /* package */ static void v(String tag, String message, Throwable throwable) {
        emit(Log.VERBOSE, tag, message, throwable);
    }

    /* package */ static void d(String tag, String message) {
        emit(Log.DEBUG, tag, message, null);
    }

    /* package */ static void d(String tag, String message, Throwable throwable) {
        emit(Log.DEBUG, tag, message, throwable);
    }

    /* package */ static void i(String tag, String message) {
        emit(Log.INFO, tag, message, null);
    }

    /* package */ static void i(String tag, String message, Throwable throwable) {
        emit(Log.INFO, tag, message, throwable);
    }

    /* package */ static void w(String tag, String message) {
        emit(Log.WARN, tag, message, null);
    }

    /* package */ static void w(String tag, String message, Throwable throwable) {
        emit(Log.WARN, tag, message, throwable);
    }

    /* package */ static void e(String tag, String message) {
        emit(Log.ERROR, tag, message, null);
    }

    /* package */ static void e(String tag, String message, Throwable throwable) {
        emit(Log.ERROR, tag, message, throwable);
    }

    private QueueLog() {
    }
}
