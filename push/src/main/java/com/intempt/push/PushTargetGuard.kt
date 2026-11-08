package com.intempt.push

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.Locale

/**
 * INT-3893 / INT-3931: the notification-tap target comes from an FCM payload, i.e. from outside
 * the app. A denylist (not https-only) keeps custom-scheme deep links such as `myapp://` working
 * while refusing the schemes that turn a tap into code or local-data access. The built Intent is
 * BROWSABLE-only, and a custom scheme is restricted to the host app's own package.
 */
internal object PushTargetGuard {
    private val WEB_SCHEMES = setOf("http", "https")
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

    private fun isWebScheme(url: String): Boolean =
        try {
            Uri.parse(url).scheme?.lowercase(Locale.ROOT) in WEB_SCHEMES
        } catch (_: Exception) {
            false
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
                    // A payload URL may only open a browsable target. A custom-scheme deep link is
                    // pinned to the host app's own package so no other installed app can claim it.
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    if (!isWebScheme(safe)) setPackage(context.packageName)
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
