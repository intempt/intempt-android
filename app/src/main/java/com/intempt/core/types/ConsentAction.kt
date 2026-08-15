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
        fun fromWireValue(value: String): ConsentAction? =
            entries.firstOrNull { it.wireValue.equals(value.trim(), ignoreCase = true) }
    }
}

/**
 * Which kind of optimization an experiments query is asking for.
 *
 * Swift carries this on `experiments(optimizationType:)`, and it is what distinguishes an
 * experiment from a personalization — the two used to be separate methods on Android
 * (`Intempt.experiment` / `Intempt.personalization`, each with `getByGroup`/`getByName`) and are one
 * query with a discriminator here.
 */
enum class OptimizationType(val wireValue: String) {
    EXPERIMENT("experiment"),
    PERSONALIZATION("personalization"),
    ;

    companion object {
        @JvmStatic
        fun fromWireValue(value: String): OptimizationType? =
            entries.firstOrNull { it.wireValue.equals(value.trim(), ignoreCase = true) }
    }
}
