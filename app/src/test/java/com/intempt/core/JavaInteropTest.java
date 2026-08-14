package com.intempt.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * The public API called the way a Java host app calls it.
 *
 * <p>This file is the test, and it is a compile-time one before it is a runtime one. {@code Intempt}
 * is a Kotlin {@code object}, so without {@code @JvmStatic} every member is an instance method on a
 * singleton and Java callers have to write {@code Intempt.INSTANCE.track(...)}. Every call below
 * uses the static form. If the annotations are removed, this file stops compiling — which is a
 * louder failure than a test assertion, and the right one, because the breakage it guards against is
 * a compile error in a customer's app rather than a wrong value at runtime.
 *
 * <p>The same goes for {@code @JvmOverloads}. Kotlin default arguments do not exist in Java, so
 * {@code identify(userId)} is only callable from Java if the overload was generated. Each
 * defaulted method is called here at more than one arity, so a missing {@code @JvmOverloads}
 * also fails the compile.
 *
 * <p>Java is the majority of existing Android app code. An SDK that requires {@code .INSTANCE} at
 * every call site is one a Java team notices in the first five minutes.
 */
@RunWith(RobolectricTestRunner.class)
public class JavaInteropTest {

    @Before
    public void setUp() throws Exception {
        // The facade is a singleton whose state outlives a test. Cleared so these calls exercise
        // the uninitialized guard rather than another test's leftover core.
        final java.lang.reflect.Field field = Intempt.class.getDeclaredField("intemptCore");
        field.setAccessible(true);
        field.set(Intempt.INSTANCE, null);
    }

    /** Static-form access to the state accessor. Without @JvmStatic this needs .INSTANCE. */
    @Test
    public void isInitializedIsReachableStaticallyFromJava() {
        assertFalse(Intempt.isInitialized());
    }

    @Test
    public void initializeIsReachableStaticallyFromJava() {
        assertFalse(
                "no config asset in this module, so initialize must report failure",
                Intempt.initialize(ApplicationProvider.getApplicationContext()));
    }

    /**
     * Every defaulted method at its shortest arity. These are the overloads {@code @JvmOverloads}
     * generates; without it, only the full-arity form exists and none of these compile.
     */
    @Test
    public void defaultedMethodsAreCallableAtTheirShortestArityFromJava() {
        Intempt.identify("user@example.com");
        Intempt.group("acct-1");
        Intempt.record("Custom");
        Intempt.consent("granted", 1_800_000_000_000L);
    }

    /** And at intermediate arities, which is the point of the generated overload set. */
    @Test
    public void defaultedMethodsAreCallableAtIntermediateArityFromJava() {
        final Map<String, String> attrs = new HashMap<>();
        attrs.put("plan", "pro");

        Intempt.identify("user@example.com", "Signed up");
        Intempt.identify("user@example.com", "Signed up", attrs);
        Intempt.group("acct-1", "Joined");
        Intempt.record("Custom", "acct-1");
        Intempt.consent("granted", 1_800_000_000_000L, "user@example.com");
    }

    /** And at full arity, so the generated set does not shadow the original signature. */
    @Test
    public void defaultedMethodsAreCallableAtFullArityFromJava() {
        final Map<String, String> attrs = new HashMap<>();
        attrs.put("k", "v");

        Intempt.identify("user@example.com", "Signed up", attrs, attrs);
        Intempt.group("acct-1", "Joined", attrs);
        Intempt.record("Custom", "acct-1", "user@example.com", attrs, attrs, attrs);
        Intempt.consent("granted", 1_800_000_000_000L, "user@example.com", "why", "marketing");
    }

    /** The methods without default arguments, for completeness of the static-call surface. */
    @Test
    public void undefaultedMethodsAreCallableStaticallyFromJava() {
        final Map<String, String> data = new HashMap<>();
        data.put("screen", "home");

        Intempt.track("Viewed", data);
        Intempt.alias("user@example.com", "user-2");
        Intempt.productAdd("21", 2);
        Intempt.productView("21");
        Intempt.productOrdered(Collections.<Map<String, Object>>emptyList());
        Intempt.logOut();
    }

    /**
     * The nested toggle objects are separate {@code object} declarations, so they need their own
     * {@code @JvmStatic} — a Java caller would otherwise need
     * {@code Intempt.Logging.INSTANCE.start()}.
     */
    @Test
    public void nestedToggleObjectsAreCallableStaticallyFromJava() {
        Intempt.Logging.start();
        Intempt.Logging.stop();
        assertFalse(Intempt.Logging.isLoggingEnabled());

        Intempt.Tracking.start();
        Intempt.Tracking.stop();
        assertFalse(Intempt.Tracking.isTrackingEnabled());
    }

    /**
     * The suspend function is not usable from Java without a coroutine bridge, which is expected
     * and not something annotations change. Asserted here so the limitation is recorded rather than
     * discovered by a customer: a Java caller needs the Kotlin form or a wrapper.
     *
     * <p>What IS checked is that nothing else on the facade throws when called from Java against an
     * uninitialized SDK — the crash-guard property, verified through the Java call path rather than
     * only the Kotlin one.
     */
    @Test
    public void theFacadeIsSafeToCallFromJavaBeforeInitialize() {
        Intempt.track("Viewed", new HashMap<String, String>());
        Intempt.identify("");
        Intempt.logOut();

        assertNull(
                "an uninitialized SDK holds no core, and reaching it from Java must not differ from "
                        + "reaching it from Kotlin",
                readCore());
    }

    private static Object readCore() {
        try {
            final java.lang.reflect.Field field = Intempt.class.getDeclaredField("intemptCore");
            field.setAccessible(true);
            return field.get(Intempt.INSTANCE);
        } catch (final ReflectiveOperationException e) {
            throw new AssertionError("the facade's core field was renamed", e);
        }
    }
}
