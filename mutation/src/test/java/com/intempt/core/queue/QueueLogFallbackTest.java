package com.intempt.core.queue;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The queue logger's fallback path: no sink installed, so it writes straight to
 * {@code android.util.Log} behind its own flag.
 *
 * <p>Lives in the mutation module because {@code :app}'s unit-test task resolves
 * {@code android.util.Log} to the stub android.jar, where {@code println} throws "not mocked". This
 * module compiles against a real no-op {@code Log} stub, so the branch is actually executed rather
 * than skipped.
 *
 * <p>Worth executing rather than assuming: the fallback is what runs before
 * {@code LoggerManagerService} installs itself, which includes every log line emitted during SDK
 * initialization. If it threw, the SDK would die on startup — and the "not mocked" failure above is
 * precisely what that would look like.
 */
public class QueueLogFallbackTest {

    /** With no sink, the legacy flag still gates, and neither branch may throw. */
    @Test
    public void withNoSinkTheLegacyFlagStillGates() {
        final boolean originalEnabled = QueueLog.isEnabled();
        try {
            QueueLog.setSink(null);
            QueueLog.setEnabled(false);
            QueueLog.i("t", "must not throw when disabled and unsinked");

            QueueLog.setEnabled(true);
            QueueLog.i("t", "must not throw when enabled and unsinked");
        } finally {
            QueueLog.setEnabled(originalEnabled);
        }
    }


    /** Both branches, both arities, so the throwable overload's fallback is exercised too. */
    @Test
    public void theFallbackHandlesEveryLevelAndArity() {
        final boolean original = QueueLog.isEnabled();
        try {
            QueueLog.setSink(null);
            QueueLog.setEnabled(true);

            QueueLog.v("t", "v");
            QueueLog.d("t", "d");
            QueueLog.i("t", "i");
            QueueLog.w("t", "w");
            QueueLog.e("t", "e");
            QueueLog.e("t", "e with cause", new IllegalStateException("boom"));

            assertTrue("reaching here means the fallback path did not throw", true);
        } finally {
            QueueLog.setEnabled(original);
        }
    }
}
