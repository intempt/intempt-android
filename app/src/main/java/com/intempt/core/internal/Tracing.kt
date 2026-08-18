package com.intempt.core.internal

import android.os.Trace

/**
 * Runs [block] inside a named `android.os.Trace` section.
 *
 * `android.os.Trace` directly (API 18+, minSdk 23) rather than `androidx.tracing`: a new dependency
 * would spend the method-count headroom this instrumentation exists to protect.
 *
 * `inline` with `try`/`finally`, not a trailing `endSection()`: callers return early and catch
 * `Throwable`, and an unbalanced begin/end corrupts the **entire** trace — every other section in
 * it, not just this one. The balance guarantee is the whole point of the helper.
 *
 * One implementation, shared by `Intempt` and `IntemptCoreModule`. Two copies would drift.
 *
 * Not part of the SDK's public API; see [InternalIntemptApi]. It is public only because `inline`
 * bodies are inlined into callers in other packages.
 */
@InternalIntemptApi
inline fun <T> traced(
    name: String,
    block: () -> T,
): T {
    Trace.beginSection(name)
    try {
        return block()
    } finally {
        Trace.endSection()
    }
}
