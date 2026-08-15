package com.intempt.core.types

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Typed attribute values, and the two things they exist to prevent.
 *
 * Before 3.0 every attribute map was `Map<String, String>`, so a bridge passing a JavaScript object
 * sent `42` and `"42"` identically and `true` as `"true"`. The platform then cannot distinguish a
 * numeric attribute from a string that looks numeric, and every segment, comparison and journey
 * condition downstream is working against the wrong type with nothing failing.
 *
 * The second thing is non-finite numbers. `NaN` and `Infinity` are not JSON. Serialized unchecked
 * they produce a body the gateway rejects — and it rejects the whole batch, so one bad value loses
 * every event queued alongside it.
 */
class IntemptValueTest {
    // ------------------------------------------------------------------ typing

    @Test
    fun `numbers stay numbers rather than becoming strings`() {
        assertEquals(42L, IntemptValue.of(42).raw())
        assertEquals(42L, IntemptValue.of(42L).raw())
        assertEquals(4.5, IntemptValue.of(4.5).raw())

        assertTrue("an Int must not arrive as a String", IntemptValue.of(42).raw() !is String)
    }

    /**
     * A whole-valued Double serializes without a trailing `.0`.
     *
     * A bridge marshalling from JavaScript has only one number type, so an integer arrives as
     * `42.0`. Sending `42.0` where the platform expects `42` is the same class of mismatch this
     * type exists to prevent, one level down.
     */
    @Test
    fun `a whole double serialises without a decimal tail`() {
        assertEquals(42L, IntemptValue.of(42.0).raw())
        assertEquals(4.5, IntemptValue.of(4.5).raw())
    }

    @Test
    fun `booleans stay booleans`() {
        assertEquals(true, IntemptValue.of(true).raw())
        assertTrue("a Boolean must not arrive as a String", IntemptValue.of(true).raw() !is String)
    }

    /** An explicit null is a different statement from an absent key, and both are legal. */
    @Test
    fun `null is preserved as a value rather than dropped`() {
        assertTrue(IntemptValue.of(null) is IntemptValue.Null)
        assertNull(IntemptValue.of(null).raw())
    }

    // -------------------------------------------------------------- validation

    @Test
    fun `non-finite numbers are invalid`() {
        assertFalse("NaN is not representable in JSON", IntemptValue.of(Double.NaN).isValid())
        assertFalse(IntemptValue.of(Double.POSITIVE_INFINITY).isValid())
        assertFalse(IntemptValue.of(Double.NEGATIVE_INFINITY).isValid())
        assertTrue(IntemptValue.of(0.0).isValid())
    }

    /**
     * Validation reaches the leaves. Checking one level deep — which is what upstream Mixpanel's
     * Swift equivalent does — passes a NaN nested inside a list inside a map, and the batch is
     * then rejected server-side with no indication of which value caused it.
     */
    @Test
    fun `validation is recursive through lists and maps`() {
        val nested =
            IntemptValue.of(
                mapOf(
                    "level1" to
                        listOf(
                            mapOf("level3" to Double.NaN),
                        ),
                ),
            )

        assertFalse("a NaN three levels down must invalidate the whole value", nested.isValid())

        val clean = IntemptValue.of(mapOf("level1" to listOf(mapOf("level3" to 1.0))))
        assertTrue(clean.isValid())
    }

    // ------------------------------------------------------------- conversion

    @Test
    fun `nested structures round-trip to plain types`() {
        val raw =
            IntemptValue.of(
                mapOf("plan" to "pro", "seats" to 5, "trial" to false, "tags" to listOf("a", "b")),
            ).raw()

        @Suppress("UNCHECKED_CAST")
        val map = raw as Map<String, Any?>
        assertEquals("pro", map["plan"])
        assertEquals(5L, map["seats"])
        assertEquals(false, map["trial"])
        assertEquals(listOf("a", "b"), map["tags"])
    }

    /**
     * An unsupported type throws rather than being dropped. A silently discarded attribute is a
     * field missing from a customer's dashboard with nothing to point at; a throw names the type
     * at the call site where it can be fixed.
     */
    @Test
    fun `an unsupported type is rejected by name`() {
        val e =
            assertThrows(IllegalArgumentException::class.java) {
                IntemptValue.of(Thread())
            }
        assertTrue("the message must name the offending type: ${e.message}", e.message!!.contains("Thread"))
    }

    /** Non-string keys have no wire representation, so they are refused rather than coerced. */
    @Test
    fun `a non-string map key is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            IntemptValue.of(mapOf(1 to "value"))
        }
    }

    @Test
    fun `the map helper wraps every value`() {
        val wrapped = IntemptValue.mapOf(mapOf("a" to 1, "b" to "two", "c" to true))

        assertEquals(3, wrapped.size)
        assertTrue(wrapped["a"] is IntemptValue.Num)
        assertTrue(wrapped["b"] is IntemptValue.Str)
        assertTrue(wrapped["c"] is IntemptValue.Bool)
    }

    /** Already-wrapped values pass through, so `of` is safe to apply twice. */
    @Test
    fun `wrapping is idempotent`() {
        val once = IntemptValue.of("x")

        assertEquals(once, IntemptValue.of(once))
    }
}
