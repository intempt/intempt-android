package android.util;

/**
 * A stub for {@code android.util.Log}, so the pure-JVM classes this module mutates can compile
 * without android.jar.
 *
 * <p>Needed because {@code QueueLog} — which {@code QueueConfig} and {@code TrackPayloadBuilder}
 * both call — imports {@code android.util.Log}. Without this stub the mutation module could only
 * ever cover {@code HttpStatusPolicy}, which left the highest-value payload and config logic
 * unmutated: coverage said their lines ran, and nothing said the tests would notice a change.
 *
 * <p>Every method is a no-op returning 0. Logging is not the behaviour under test, and a stub that
 * did anything observable would let a mutant be "killed" by a change in log output rather than in
 * the decision being mutated.
 *
 * <p>Scope: this module is not published and is not on any consumer's or :app's classpath. It
 * exists solely so PIT has something to compile against. It must never be added to a source set
 * that ships — shadowing a real Android framework class in a published artifact would be a
 * genuinely dangerous thing to do.
 */
public final class Log {

    /** Priority constants, same values as the framework's. */
    public static final int VERBOSE = 2;
    public static final int DEBUG = 3;
    public static final int INFO = 4;
    public static final int WARN = 5;
    public static final int ERROR = 6;
    public static final int ASSERT = 7;

    public static int println(int priority, String tag, String msg) {
        return 0;
    }

    /**
     * Returns the message rather than a real stack trace. Enough for the fallback path to run, and
     * the format is not something any test asserts on.
     */
    public static String getStackTraceString(Throwable tr) {
        return tr == null ? "" : String.valueOf(tr.getMessage());
    }

    public static int v(String tag, String msg) {
        return 0;
    }

    public static int v(String tag, String msg, Throwable tr) {
        return 0;
    }

    public static int d(String tag, String msg) {
        return 0;
    }

    public static int d(String tag, String msg, Throwable tr) {
        return 0;
    }

    public static int i(String tag, String msg) {
        return 0;
    }

    public static int i(String tag, String msg, Throwable tr) {
        return 0;
    }

    public static int w(String tag, String msg) {
        return 0;
    }

    public static int w(String tag, String msg, Throwable tr) {
        return 0;
    }

    public static int e(String tag, String msg) {
        return 0;
    }

    public static int e(String tag, String msg, Throwable tr) {
        return 0;
    }

    private Log() {
    }
}
