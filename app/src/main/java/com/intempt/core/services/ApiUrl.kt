package com.intempt.core.services

import com.intempt.core.types.Constants
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/**
 * Resolves and validates the API base URL (INT-5178).
 *
 * The value used to come from intempt-config.json only and was used as-is: a missing scheme, a
 * plain-http production host or a typo went straight into every request URL. Now:
 * - the runtime option wins over the asset file, and production applies only when neither is set;
 * - `https://` is accepted for any host, `http://` only for a loopback host, so a local stub or
 *   the emulator's host machine works and nothing else travels in cleartext;
 * - anything else throws [IllegalArgumentException]. It never falls back to production, because a
 *   caller who asked for a different host and silently got production would send real data there.
 */
internal object ApiUrl {
    /** `URI.getHost()` keeps the brackets on an IPv6 literal, so both spellings are listed. */
    private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1", "[::1]", "10.0.2.2")

    fun resolve(
        runtime: String?,
        asset: String?,
    ): String {
        val chosen =
            runtime?.takeIf { it.isNotBlank() }
                ?: asset?.takeIf { it.isNotBlank() }
                ?: return Constants.API_URL
        return validate(chosen)
    }

    /** Returns [raw] trimmed and without a trailing `/`, or throws [IllegalArgumentException]. */
    fun validate(raw: String): String {
        val candidate = raw.trim().trimEnd('/')
        val uri =
            try {
                URI(candidate)
            } catch (e: URISyntaxException) {
                throw IllegalArgumentException(problem(raw, "it is not a URL"), e)
            }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT)

        require(!host.isNullOrEmpty()) { problem(raw, "it has no host") }
        require(uri.rawUserInfo == null) { problem(raw, "it must not carry credentials") }
        require(uri.rawQuery == null && uri.rawFragment == null) {
            problem(raw, "it must not have a query or fragment")
        }
        when (scheme) {
            "https" -> Unit
            "http" ->
                require(host in LOOPBACK_HOSTS) {
                    problem(raw, "http:// is allowed only for localhost, 127.0.0.1, [::1] or 10.0.2.2; use https://")
                }
            else -> throw IllegalArgumentException(problem(raw, "the scheme must be https://"))
        }
        return candidate
    }

    private fun problem(
        raw: String,
        reason: String,
    ) = "Invalid apiUrl \"$raw\": $reason. The SDK is disabled rather than sending to an unintended host."
}
