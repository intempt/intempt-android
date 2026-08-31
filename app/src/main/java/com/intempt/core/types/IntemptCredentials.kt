package com.intempt.core.types

/**
 * The four values the SDK needs to send anything, supplied at runtime.
 *
 * Until 3.0 these could only come from `assets/intempt-config.json`, read during Dagger graph
 * construction. That works for a single app with one Intempt project baked in at build time, and
 * fails for everything else: a React Native or Flutter bridge receives credentials from JavaScript
 * at runtime, a white-label app resolves a different project per tenant, and a test cannot point
 * the SDK anywhere without writing a file into assets.
 *
 * Matches Swift's `IntemptCredentials` + `initialize(apiKey:orgId:projectId:sourceId:instanceName:)`,
 * so a bridge marshals the same four fields to both platforms.
 *
 * The asset file still works and is still the documented path for a plain Android app. Runtime
 * credentials take precedence when supplied; the file is the fallback, not the other way round.
 */
data class IntemptCredentials(
    /**
     * Internal on purpose. As a public `val` this generated `getApiKey()` and `component1()`
     * into the published binary-compatibility surface, so the credential the SDK was configured
     * with was readable from any host application — and from anything else running in that
     * process. No other Intempt SDK exposes one. `internal` keeps it usable inside the SDK, where
     * ConfigManagerService needs it, and out of the ABI. The constructor still takes it, so
     * callers configure the SDK exactly as before.
     */
    internal val apiKey: String,
    val organizationId: String,
    val projectId: String,
    val sourceId: String,
) {
    /**
     * Validates and returns what is wrong, rather than throwing.
     *
     * `initialize` reports failure through its return value and never throws — an analytics SDK
     * taking down a host app is worse than analytics not working — so this hands back a list the
     * caller can log. Empty means usable.
     */
    fun problems(): List<String> =
        buildList {
            if (apiKey.isBlank()) add("apiKey is blank")
            if (organizationId.isBlank()) add("organizationId is blank")
            if (projectId.isBlank()) add("projectId is blank")
            if (sourceId.isBlank()) add("sourceId is blank")

            // An API key is "<id>.<secret>". Without the separator no Authorization header can be
            // built, and the old code destructured the split directly — so a key without a dot
            // threw IndexOutOfBoundsException from inside the auth path on nothing worse than a
            // typo. Checked here so the failure is named at initialize rather than at first send.
            if (apiKey.isNotBlank()) {
                val parts = apiKey.split(".")
                if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                    add("apiKey must be \"<id>.<secret>\" — no Authorization header can be built from it")
                }
            }
        }

    val isValid: Boolean get() = problems().isEmpty()

    /**
     * Redacted. The default `toString` of a data class prints every field, and this one holds a
     * credential — a single log line or crash report would carry it. The old SDK printed the
     * built Authorization header whenever logging was enabled, which put the ingestion key in
     * every bug report; this is the same mistake one `toString` away.
     */
    override fun toString(): String =
        "IntemptCredentials(organizationId=$organizationId, projectId=$projectId, " +
            "sourceId=$sourceId, apiKey=***)"
}
