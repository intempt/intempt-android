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

    /**
     * Non-string keys have no wire representation, so they are refused rather than coerced.
     *
     * The message names the offending key's type. Asserted because mutation testing showed the
     * `k?.javaClass?.name` safe-call could be negated with every test still green — the throw was
     * pinned, the diagnostic in it was not, and a message that says "got null" for a real key is
     * how someone spends an afternoon on a one-line fix.
     */
    @Test
    fun `a non-string map key is rejected by type name`() {
        val e =
            assertThrows(IllegalArgumentException::class.java) {
                IntemptValue.of(mapOf(1 to "value"))
            }
        assertTrue("the message must name the key type: ${e.message}", e.message!!.contains("Integer"))

        // A null key is a different message, and must not crash building the message itself.
        val nullKey =
            assertThrows(IllegalArgumentException::class.java) {
                IntemptValue.of(mapOf(null to "value"))
            }
        assertTrue(nullKey.message!!.contains("String"))
    }

    /**
     * An empty list or map is valid and survives the round trip.
     *
     * `all {}` is vacuously true on an empty collection, so a negated validity check reads the same
     * on the nested cases above. These pin the empty case, where the negation flips the answer.
     */
    @Test
    fun `empty collections are valid and preserved`() {
        assertTrue(IntemptValue.of(emptyList<String>()).isValid())
        assertTrue(IntemptValue.of(emptyMap<String, String>()).isValid())

        assertEquals(emptyList<Any?>(), IntemptValue.of(emptyList<String>()).raw())
        assertEquals(emptyMap<String, Any?>(), IntemptValue.of(emptyMap<String, String>()).raw())
    }

    /**
     * Invalidity is detected wherever it sits, not only in the first element.
     *
     * `all {}` short-circuits, so a test that only ever puts the bad value first cannot tell a
     * working check from one that inspects element zero and stops.
     */
    @Test
    fun `a bad value is caught in any position`() {
        assertFalse(IntemptValue.of(listOf(Double.NaN, 1.0, 2.0)).isValid())
        assertFalse(IntemptValue.of(listOf(1.0, Double.NaN, 2.0)).isValid())
        assertFalse(IntemptValue.of(listOf(1.0, 2.0, Double.NaN)).isValid())
        assertTrue(IntemptValue.of(listOf(1.0, 2.0, 3.0)).isValid())

        assertFalse(IntemptValue.of(mapOf("a" to 1.0, "b" to Double.NaN)).isValid())
        assertFalse(IntemptValue.of(mapOf("a" to Double.NaN, "b" to 1.0)).isValid())
        assertTrue(IntemptValue.of(mapOf("a" to 1.0, "b" to 2.0)).isValid())
    }

    /** [IntemptValue.rawMap] is the inverse of [IntemptValue.mapOf] and unwraps every value. */
    @Test
    fun `rawMap unwraps every value`() {
        val raw = IntemptValue.rawMap(IntemptValue.mapOf(mapOf("plan" to "pro", "seats" to 5, "trial" to false)))

        assertEquals("pro", raw["plan"])
        assertEquals(5L, raw["seats"])
        assertEquals(false, raw["trial"])
        assertTrue("no IntemptValue may survive unwrapping", raw.values.none { it is IntemptValue })
        assertEquals(emptyMap<String, Any?>(), IntemptValue.rawMap(emptyMap()))
    }

    /** Every numeric type reaches the wire as a number, not a string. */
    @Test
    fun `every numeric type is wrapped as a number`() {
        listOf<Any>(1.toByte(), 1.toShort(), 1, 1L, 1.0f, 1.0).forEach { n ->
            val wrapped = IntemptValue.of(n)
            assertTrue("$n (${n.javaClass.simpleName}) must wrap as Num", wrapped is IntemptValue.Num)
            assertTrue("$n must not reach the wire as a String", wrapped.raw() !is String)
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

    /**
     * Arrays wrap like lists.
     *
     * A separate branch from `List`, and one nothing exercised — mutation testing found three live
     * mutants in the array loop, including its increment. A bridge marshalling from JS or Java is
     * as likely to hand over an array as a list.
     */
    @Test
    fun `arrays wrap element-wise like lists`() {
        val wrapped = IntemptValue.of(arrayOf("a", "b", "c"))

        assertTrue(wrapped is IntemptValue.Arr)
        assertEquals(listOf("a", "b", "c"), wrapped.raw())
        assertEquals(3, (wrapped as IntemptValue.Arr).values.size)

        assertEquals(emptyList<Any?>(), IntemptValue.of(emptyArray<String>()).raw())
        assertEquals(listOf("only"), IntemptValue.of(arrayOf("only")).raw())

        // Element order is preserved, which an inverted loop would break while keeping the size.
        assertEquals(listOf(1L, 2L, 3L), IntemptValue.of(arrayOf(1, 2, 3)).raw())
        assertFalse(IntemptValue.of(arrayOf(1.0, Double.NaN)).isValid())
    }

    /**
     * `Num.hashCode` is hand-written, so it needs its own test.
     *
     * The generated one called the static `Double.hashCode(double)`, which is absent from API 23
     * and throws `NoSuchMethodError` the first time a `Num` lands in a HashMap on a real device.
     * The replacement folds `doubleToLongBits`, and mutation testing found all three of its
     * operations unpinned — the shift, the XOR and the return.
     */
    @Test
    fun `Num hashCode matches Double and distinguishes values`() {
        listOf(0.0, 1.0, -1.0, 4.5, 1e300, Double.MAX_VALUE, Double.MIN_VALUE).forEach { d ->
            assertEquals(
                "must agree with Double.valueOf(d).hashCode() for $d",
                java.lang.Double.valueOf(d).hashCode(),
                IntemptValue.Num(d).hashCode(),
            )
        }

        assertEquals(IntemptValue.Num(4.5).hashCode(), IntemptValue.Num(4.5).hashCode())
        assertTrue(
            "distinct values must not collapse to one bucket",
            IntemptValue.Num(1.0).hashCode() != IntemptValue.Num(2.0).hashCode(),
        )

        // The property this exists to support: a Num can be a HashMap key on any API level.
        val byValue = hashMapOf(IntemptValue.Num(4.5) to "four and a half")
        assertEquals("four and a half", byValue[IntemptValue.Num(4.5)])
    }

    /**
     * Each subclass reports its own validity and hands back what it was given.
     *
     * Asserted directly rather than through `of()`. These are the accessors every other test reads
     * only indirectly, so a getter replaced by a constant went unnoticed everywhere else.
     */
    @Test
    fun `each variant exposes its value and its validity`() {
        assertEquals("x", IntemptValue.Str("x").value)
        assertTrue(IntemptValue.Str("x").isValid())
        assertEquals("", IntemptValue.Str("").value)

        assertEquals(true, IntemptValue.Bool(true).value)
        assertEquals(false, IntemptValue.Bool(false).value)
        assertTrue(IntemptValue.Bool(false).isValid())

        assertEquals(4.5, IntemptValue.Num(4.5).value, 0.0)
        assertEquals(-1.0, IntemptValue.Num(-1.0).value, 0.0)
        assertTrue(IntemptValue.Num(0.0).isValid())

        assertTrue(IntemptValue.Null.isValid())
        assertNull(IntemptValue.Null.raw())

        val arr = IntemptValue.Arr(listOf(IntemptValue.Str("a")))
        assertEquals(1, arr.values.size)
        assertEquals(IntemptValue.Str("a"), arr.values.first())

        val obj = IntemptValue.Obj(mapOf("k" to IntemptValue.Str("v")))
        assertEquals(1, obj.values.size)
        assertEquals(IntemptValue.Str("v"), obj.values["k"])
    }

    /**
     * A single invalid leaf invalidates its container, and a valid one does not.
     *
     * Both directions on both containers: `all {}` short-circuits, so asserting only the false case
     * cannot distinguish a working check from one that always returns false.
     */
    @Test
    fun `container validity follows its contents in both directions`() {
        assertTrue(IntemptValue.Arr(listOf(IntemptValue.Num(1.0))).isValid())
        assertFalse(IntemptValue.Arr(listOf(IntemptValue.Num(Double.NaN))).isValid())
        assertTrue(IntemptValue.Arr(emptyList()).isValid())

        assertTrue(IntemptValue.Obj(mapOf("a" to IntemptValue.Num(1.0))).isValid())
        assertFalse(IntemptValue.Obj(mapOf("a" to IntemptValue.Num(Double.NaN))).isValid())
        assertTrue(IntemptValue.Obj(emptyMap()).isValid())

        assertFalse(
            "one bad leaf among many must still invalidate",
            IntemptValue.Arr(
                listOf(IntemptValue.Num(1.0), IntemptValue.Num(2.0), IntemptValue.Num(Double.POSITIVE_INFINITY)),
            ).isValid(),
        )
    }

    /** Already-wrapped values pass through, so `of` is safe to apply twice. */
    @Test
    fun `wrapping is idempotent`() {
        val once = IntemptValue.of("x")

        assertEquals(once, IntemptValue.of(once))
    }
}
