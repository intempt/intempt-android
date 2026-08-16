/*
 * Adapted from the Mixpanel Android SDK — https://github.com/mixpanel/mixpanel-android
 * Copyright 2022 Mixpanel, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this
 * file except in compliance with the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Modifications (c) 2026 Intempt Technologies, licensed under the Apache License 2.0:
 *   - package moved to com.intempt.core.queue; otherwise unmodified
 */
package com.intempt.core.queue;

/**
 * Implement this to allow Mixpanel behave in-sync with your current custom offline logic
 */
public interface OfflineMode {

    /**
     * Returns true if offline-mode is active on the client. When true Mixpanel will not start
     * new connections, but current active connections will not be interrupted.
     *
     * @return true if offline mode is active, false otherwise
     */
    boolean isOffline();

}
