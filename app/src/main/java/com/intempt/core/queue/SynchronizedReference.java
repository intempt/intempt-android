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
 * We need this for stronger ordering guarantees than AtomicReference
 * (and we don't need compareAndSet)
 */
/* package */ class SynchronizedReference<T> {
    public SynchronizedReference() {
        mContents = null;
    }

    public synchronized void set(T contents) {
        mContents = contents;
    }

    public synchronized T getAndClear() {
        final T ret = mContents;
        mContents = null;
        return ret;
    }

    public synchronized T get() {
        return mContents;
    }

    private T mContents;
}
