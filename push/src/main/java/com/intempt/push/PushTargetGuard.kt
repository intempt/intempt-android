package com.intempt.push

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.Locale

/**
 * INT-3893 / INT-3931: the notification-tap target comes from an FCM payload, i.e. from outside
 * the app. A denylist (not https-only) keeps custom-scheme deep links such as `myapp://` working
 * while refusing the schemes that turn a tap into code or local-data access. http(s) and custom-scheme
 * Intents are BROWSABLE-only, a custom scheme is restricted to the host app's own package, and
 * platform schemes (market, tel, mailto, sms, smsto, geo) open unpinned as they always did.
 */
internal object PushTargetGuard {
    private val WEB_SCHEMES = setOf("http", "https")

    // Platform-handled schemes a marketer legitimately sends (store page, dialer, mail, sms, map).
    // They open exactly as before this guard: no package pin and no BROWSABLE requirement, since
    // the system handlers for them are not necessarily BROWSABLE and are not the host's own links.
    private val SYSTEM_SCHEMES = setOf("market", "tel", "mailto", "sms", "smsto", "geo")
    private val DENIED_SCHEMES = setOf("intent", "javascript", "file", "content", "android-app")

    /** Extra on the dispatcher Intent carrying the vetted URL string (never an Intent). */
    const val EXTRA_TARGET_URL = "target_url"

    /** True when [url] parses, has a scheme, and the scheme is not denied. */
    fun isAllowed(url: String?): Boolean {
        val trimmed = url?.trim()
        if (trimmed.isNullOrEmpty()) return false
        val scheme =
            try {
                Uri.parse(trimmed).scheme
            } catch (_: Exception) {
                null
            }
        val normalized = scheme?.trim()?.lowercase(Locale.ROOT)
        return !normalized.isNullOrEmpty() && normalized !in DENIED_SCHEMES
    }

    private fun schemeOf(url: String): String? =
        try {
            Uri.parse(url).scheme?.lowercase(Locale.ROOT)
        } catch (_: Exception) {
            null
        }

    /** The vetted URL, trimmed, or null when it must not be opened. */
    fun vet(url: String?): String? = url?.trim()?.takeIf { isAllowed(it) }

    /** ACTION_VIEW for an allowed [url]; otherwise the app's launch intent. Rebuilt locally, never forwarded. */
    fun buildTarget(
        context: Context,
        url: String?,
    ): Intent {
        val safe = vet(url)
        val intent =
            if (safe != null) {
                Intent(Intent.ACTION_VIEW, Uri.parse(safe)).apply {
                    // http(s) and custom-scheme links only open a browsable target. A custom-scheme
                    // deep link is also pinned to the host app's own package so no other installed
                    // app can claim it. Platform schemes (market, tel, mailto...) are left as before.
                    val scheme = schemeOf(safe)
                    if (scheme !in SYSTEM_SCHEMES) addCategory(Intent.CATEGORY_BROWSABLE)
                    if (scheme !in WEB_SCHEMES && scheme !in SYSTEM_SCHEMES) setPackage(context.packageName)
                }
            } else {
                context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()
            }
        return intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
    }

    /** Target for the dispatcher Activity, built only from the vetted URL extra. */
    fun resolveDispatchTarget(
        context: Context,
        dispatcherIntent: Intent?,
    ): Intent = buildTarget(context, dispatcherIntent?.getStringExtra(EXTRA_TARGET_URL))
}
