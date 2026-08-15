package com.intempt.core.types

/**
 * A value Intempt can transmit as an event attribute.
 *
 * The Android SDK took `Map<String, String>` everywhere, which is fine until something that is not
 * a string needs to go on the wire. A React Native or Flutter bridge hands over JavaScript objects
 * containing numbers and booleans; forcing those through `String` means `42` and `"42"` arrive
 * identically and `true` becomes `"true"`. The platform then cannot tell a numeric attribute from a
 * string that looks numeric, so every downstream comparison, segment and journey condition is
 * working with the wrong type — and nothing errors.
 *
 * This is the Kotlin counterpart of Swift's `IntemptType`, so the two SDKs accept the same set of
 * values and serialize them the same way. That symmetry is the point: a cross-platform bridge can
 * only be thin if both native surfaces agree on what an attribute is.
 *
 * ## Why a sealed class rather than `Any`
 *
 * `Map<String, Any>` would compile and would push every validation failure to runtime, usually at
 * the server. A sealed hierarchy makes the accepted set closed and checkable, and `of()` gives one
 * place where an unsupported type is rejected with a message naming what was passed.
 *
 * ## Validation is recursive, deliberately
 *
 * [isValid] walks nested lists and maps to the leaves. A single non-finite number anywhere must
 * invalidate the whole payload before it reaches the wire rather than after the gateway rejects
 * it — a 400 on a batch is indistinguishable from a dozen other causes, and by then the offending
 * value is three layers into a log line. Swift's version does the same, and its comment records
 * that upstream Mixpanel checks only one level deep.
 */
sealed class IntemptValue {
    /** True when this value, and everything nested inside it, can be transmitted. */
    abstract fun isValid(): Boolean

    /** The value as the JSON serializer should see it. */
    abstract fun raw(): Any?

    data class Str(val value: String) : IntemptValue() {
        override fun isValid() = true

        override fun raw() = value
    }

    data class Num(val value: Double) : IntemptValue() {
        /**
         * NaN and infinity are not representable in JSON. Left unchecked they serialize to
         * `NaN`/`Infinity`, which is not valid JSON, so the gateway rejects the whole batch —
         * every event in it, not just the one carrying the bad number.
         */
        override fun isValid() = !value.isNaN() && !value.isInfinite()

        override fun raw(): Any = if (value % 1.0 == 0.0 && !value.isInfinite()) value.toLong() else value

        /**
         * Explicit, because the one Kotlin generates for a `data class` with a `Double` property
         * calls the **static** `Double.hashCode(double)`, which is absent from the API 23
         * signature. It compiles, passes lint, and throws `NoSuchMethodError` the first time one of
         * these lands in a HashMap on an API 23 device. AnimalSniffer caught it; lint cannot,
         * because lint does not read the compiled artifact.
         *
         * **`Double` is the only one.** The static `Boolean.hashCode(boolean)`,
         * `Long.hashCode(long)` and `Integer.hashCode(int)` overloads ARE in API 23 — verified by
         * disassembling `IntemptOptions`, `SessionEvent` and `RecommendationBody`, which emit them
         * and pass the same gate. A first pass here "fixed" `Bool` and the options classes too, on
         * the assumption that all four Java 8 statics landed together. They did not, and a comment
         * asserting otherwise would have been a plausible falsehood sitting next to a real one.
         *
         * This is `Double.valueOf(v).hashCode()` written out. `doubleToLongBits` is API 1.
         */
        override fun hashCode(): Int {
            val bits = java.lang.Double.doubleToLongBits(value)
            return (bits xor (bits ushr 32)).toInt()
        }
    }

    data class Bool(val value: Boolean) : IntemptValue() {
        override fun isValid() = true

        override fun raw() = value
    }

    /** An explicit null, which is different from an absent key. */
    data object Null : IntemptValue() {
        override fun isValid() = true

        override fun raw(): Any? = null
    }

    data class Arr(val values: List<IntemptValue>) : IntemptValue() {
        override fun isValid() = values.all { it.isValid() }

        override fun raw(): Any = values.map { it.raw() }
    }

    data class Obj(val values: Map<String, IntemptValue>) : IntemptValue() {
        override fun isValid() = values.values.all { it.isValid() }

        override fun raw(): Any = values.mapValues { it.value.raw() }
    }

    companion object {
        /**
         * Wraps a plain Kotlin value, or throws naming what was rejected.
         *
         * Exists so callers — and a bridge marshalling from JS or Dart — do not have to construct
         * the subclasses by hand. It throws rather than returning null because an unsupported
         * attribute type is a programming error at the call site, and silently dropping it is how
         * an attribute goes missing from a customer's dashboard with nothing to point at.
         */
        @JvmStatic
        fun of(value: Any?): IntemptValue =
            when (value) {
                null -> Null
                is IntemptValue -> value
                is String -> Str(value)
                is Boolean -> Bool(value)
                is Int -> Num(value.toDouble())
                is Long -> Num(value.toDouble())
                is Float -> Num(value.toDouble())
                is Double -> Num(value)
                is Short -> Num(value.toDouble())
                is Byte -> Num(value.toDouble())
                is List<*> -> Arr(value.map { of(it) })
                is Array<*> -> Arr(value.map { of(it) })
                is Map<*, *> ->
                    Obj(
                        value.entries.associate { (k, v) ->
                            val key =
                                k as? String
                                    ?: throw IllegalArgumentException(
                                        "Attribute keys must be String, got ${k?.javaClass?.name}. " +
                                            "The wire format has no representation for a non-string key.",
                                    )
                            key to of(v)
                        },
                    )
                else ->
                    throw IllegalArgumentException(
                        "Unsupported attribute type ${value.javaClass.name}. Supported: String, " +
                            "Boolean, Int, Long, Float, Double, Short, Byte, List, Array, Map, null.",
                    )
            }

        /**
         * Convenience for the common case of a plain map, so a host app writes
         * `mapOf("plan" to "pro", "seats" to 5)` rather than wrapping every value.
         */
        @JvmStatic
        fun mapOf(values: Map<String, Any?>): Map<String, IntemptValue> = values.mapValues { of(it.value) }

        /**
         * The inverse of [mapOf]: unwraps back to plain values for serialization.
         *
         * Every attribute map has to be unwrapped before it reaches `org.json`. `JSONObject`'s
         * `wrap()` understands String, the boxed primitives, Map, Collection and array, and
         * **returns null for anything else** — so handing it an `IntemptValue` directly does not
         * fail, it silently writes `null` for that attribute. Typed values that vanish on the wire
         * would be worse than the stringly-typed maps they replace.
         */
        @JvmStatic
        fun rawMap(values: Map<String, IntemptValue>): Map<String, Any?> = values.mapValues { it.value.raw() }
    }
}
