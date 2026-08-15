package com.intempt.core.types

/**
 * Whether a consent record grants or withdraws permission.
 *
 * `consent()` took a `String`, and the only validation was a check inside `CustomCaptureService`
 * that silently returned when the value was not recognised — so a typo produced no consent record,
 * no error, and no log a caller would see. For a field whose entire purpose is proving a legal
 * basis for processing, "silently did nothing" is the worst available failure.
 *
 * Matches Swift's `ConsentAction`, which has the same two cases. The wire values are what
 * `/consents/data` expects; [wireValue] is what goes on the wire and the enum name is what a caller
 * writes, so renaming the constant cannot silently change the payload.
 *
 * This file briefly also carried an `OptimizationType` for `experiments(optimizationType:)`. It is
 * gone: experiment and personalization assignment is an intemptjs capability and does not belong in
 * a mobile client SDK — decided 2026-08-15, and Swift drops its version too. Recommendation feeds
 * (`/feeds/{id}/data`, `Intempt.recommendation`) are a different capability and are unaffected.
 */
enum class ConsentAction(val wireValue: String) {
    /** The subject granted permission. */
    ACCEPT("accept"),

    /** The subject withdrew permission. */
    REJECT("reject"),

    ;

    companion object {
        /**
         * Parses a wire value, for the asset-config and bridge paths that still arrive as strings.
         *
         * Returns null rather than throwing so a caller can decide — a bridge should reject the
         * call, whereas a stored value read back from disk may reasonably be treated as unknown.
         */
        @JvmStatic
        fun fromWireValue(value: String): ConsentAction? {
            val normalized = value.trim()
            return entries.firstOrNull { it.wireValue.equals(normalized, ignoreCase = true) }
        }
    }
}
