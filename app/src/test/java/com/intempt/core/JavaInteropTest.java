package com.intempt.core;

import static org.junit.Assert.assertFalse;

import androidx.test.core.app.ApplicationProvider;

import com.intempt.core.types.ConsentAction;
import com.intempt.core.types.IntemptCredentials;
import com.intempt.core.types.IntemptValue;
import com.intempt.core.types.Product;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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
        // The facade holds a registry of named instances as of 3.0, not a single core reference.
        final java.lang.reflect.Field field = Intempt.class.getDeclaredField("instances");
        field.setAccessible(true);
        ((java.util.Map<?, ?>) field.get(Intempt.INSTANCE)).clear();
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
        Intempt.track("Viewed");
        Intempt.consent(ConsentAction.ACCEPT, 1_800_000_000_000L);
        Intempt.flush();
    }

    /** And at intermediate arities, which is the point of the generated overload set. */
    @Test
    public void defaultedMethodsAreCallableAtIntermediateArityFromJava() {
        final Map<String, IntemptValue> attrs = typedAttributes();

        Intempt.identify("user@example.com", "Signed up");
        Intempt.identify("user@example.com", "Signed up", attrs);
        Intempt.group("acct-1", "Joined");
        Intempt.record("Custom", "user@example.com");
        Intempt.consent(ConsentAction.ACCEPT, 1_800_000_000_000L, "user@example.com");
    }

    /** And at full arity, so the generated set does not shadow the original signature. */
    @Test
    public void defaultedMethodsAreCallableAtFullArityFromJava() {
        final Map<String, IntemptValue> attrs = typedAttributes();

        Intempt.identify("user@example.com", "Signed up", attrs, attrs);
        Intempt.group("acct-1", "Joined", attrs);
        Intempt.record("Custom", "user@example.com", "acct-1", attrs, attrs, attrs);
        Intempt.consent(ConsentAction.ACCEPT, 1_800_000_000_000L, "user@example.com", "why", "marketing");
    }

    /**
     * Typed attribute values, built the way a Java caller has to build them.
     *
     * <p>{@code IntemptValue.mapOf} carries {@code @JvmStatic} for exactly this: without it, a Java
     * app wrapping a plain map would write {@code IntemptValue.Companion.mapOf(...)} at every call
     * site, and the 3.0 typing change would read as a tax rather than a fix.
     */
    private static Map<String, IntemptValue> typedAttributes() {
        final Map<String, Object> raw = new HashMap<>();
        raw.put("plan", "pro");
        raw.put("seats", 5);
        raw.put("trial", false);
        return IntemptValue.mapOf(raw);
    }

    /**
     * The typing actually survives the Java call path.
     *
     * <p>The whole point of 3.0's typed attributes is that {@code 42} does not become {@code "42"}.
     * Java is where that is most likely to be lost, because autoboxing makes {@code Object} the
     * natural map value type and a stringly implementation would compile identically.
     */
    @Test
    public void typedValuesKeepTheirTypeThroughTheJavaCallPath() {
        final Map<String, IntemptValue> attrs = typedAttributes();

        assertTrue(attrs.get("seats") instanceof IntemptValue.Num);
        assertTrue(attrs.get("trial") instanceof IntemptValue.Bool);
        assertTrue(attrs.get("plan") instanceof IntemptValue.Str);
        assertEquals(5L, attrs.get("seats").raw());
        assertEquals(Boolean.FALSE, attrs.get("trial").raw());
    }

    /** Runtime credentials, the path a bridge or a white-label host uses. */
    @Test
    public void runtimeCredentialsAreConstructibleAndValidatableFromJava() {
        final IntemptCredentials good = new IntemptCredentials("id.secret", "org", "proj", "src");
        assertTrue(good.isValid());
        assertTrue(good.problems().isEmpty());

        final IntemptCredentials bad = new IntemptCredentials("no-separator", "org", "proj", "src");
        assertFalse(bad.isValid());
        assertFalse(
                "a rejection message must not quote the key",
                bad.problems().get(0).contains("no-separator"));

        assertFalse(Intempt.initialize(ApplicationProvider.getApplicationContext(), bad));
    }

    /** The 3.0 additions, at the static form a Java host app writes. */
    @Test
    public void the30SurfaceIsCallableStaticallyFromJava() {
        assertEquals("", Intempt.getProfileId());
        assertEquals("", Intempt.getSessionId());

        Intempt.optIn();
        Intempt.optOut();
        assertFalse(Intempt.hasOptedOut());
        assertFalse(Intempt.isOptedIn());

        Intempt.reset();

        // A settable property on a Kotlin object needs @JvmStatic on the property, not only on the
        // methods around it, or Java reaches it through .INSTANCE.
        Intempt.setFlushInterval(30);
        assertEquals(0, Intempt.getFlushInterval());
    }

    /** The methods without default arguments, for completeness of the static-call surface. */
    @Test
    public void undefaultedMethodsAreCallableStaticallyFromJava() {
        final Map<String, IntemptValue> data = typedAttributes();

        Intempt.track("Viewed", data);
        Intempt.productAdd("21", 2);
        Intempt.productView("21");
        Intempt.productOrdered(Collections.singletonList(new Product("21", 1)));
        // The @JvmOverloads-generated single-argument Product constructor, for a view with no
        // quantity. A Java caller cannot use a Kotlin default argument without it.
        Intempt.productOrdered(Collections.singletonList(new Product("21")));
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

        Intempt.optIn();
        Intempt.optOut();
        assertFalse(Intempt.isOptedIn());
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
        Intempt.track("Viewed", new HashMap<String, IntemptValue>());
        Intempt.identify("");
        Intempt.logOut();

        assertTrue(
                "an uninitialized SDK holds no instances, and reaching it from Java must not differ "
                        + "from reaching it from Kotlin",
                readInstances().isEmpty());
    }

    private static java.util.Map<?, ?> readInstances() {
        try {
            final java.lang.reflect.Field field = Intempt.class.getDeclaredField("instances");
            field.setAccessible(true);
            return (java.util.Map<?, ?>) field.get(Intempt.INSTANCE);
        } catch (final ReflectiveOperationException e) {
            throw new AssertionError("the facade's instance registry was renamed", e);
        }
    }
}
