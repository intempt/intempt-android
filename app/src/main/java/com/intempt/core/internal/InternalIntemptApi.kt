package com.intempt.core.internal

/**
 * Marks a declaration as a cross-module SPI for Intempt's optional feature modules (e.g. `:push`).
 *
 * These declarations are technically public (a separate Gradle module's Kotlin `internal` cannot
 * see across module boundaries), but they are not part of the SDK's public API: excluded from
 * `app.api` via `apiValidation.nonPublicMarkers` and from generated docs. A host app must never
 * depend on anything annotated with this.
 */
@RequiresOptIn(
    message = "Cross-module SPI for Intempt's optional feature modules (e.g. :push). Not part of the public API.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
annotation class InternalIntemptApi
