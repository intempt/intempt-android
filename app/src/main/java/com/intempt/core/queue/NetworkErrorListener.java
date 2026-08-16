/*
 * Adapted from the Mixpanel Android SDK — https://github.com/mixpanel/mixpanel-android
 * Copyright 2022 Mixpanel, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this
 * file except in compliance with the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Modifications (c) 2026 Intempt Technologies, licensed under the Apache License 2.0:
 *   - renamed from MixpanelNetworkErrorListener; package moved to com.intempt.core.queue
 */
package com.intempt.core.queue;

public interface NetworkErrorListener {
    /**
     * Called when a network request within the Mixpanel SDK fails.
     * This method may be called on a background thread.
     *
     * @param endpointUrl The URL that failed.
     * @param ipAddress The IP address resolved from the endpointUrl's hostname for this attempt (may be "N/A" if DNS lookup failed).
     * @param durationMillis The approximate duration in milliseconds from the start of this specific connection attempt until the exception was thrown.
     * @param uncompressedBodySize The size in bytes of the request body *before* any compression.
     * Will be -1 if no body was sent.
     * @param compressedBodySize The size in bytes of the request body *after* Gzip compression.
     * Will be -1 if no body was sent or if compression was not enabled
     * (in which case uncompressed size applies).
     * @param responseCode The HTTP response code returned by the server, if available.
     * Defaults to -1 if no response code could be retrieved (e.g., connection error).
     * @param responseMessage The HTTP response message returned by the server, if available.
     * Defaults to empty string if no response message could be retrieved.
     * @param exception The exception that occurred (e.g., IOException, EOFException, etc.).
     */
    void onNetworkError(String endpointUrl, String ipAddress, long durationMillis, long uncompressedBodySize, long compressedBodySize, int responseCode, String responseMessage, Exception exception);
}
