package com.intempt.core.autocapture.composeHitTest

import android.view.View

/**
 * Resolves a Compose `testTag` for a touch, and is a no-op in a host without Compose.
 *
 * Before 4.1.0 a tap inside a Compose screen produced NO touch event at all: `AndroidComposeView`
 * is a `ViewGroup` with no View children, so `TouchTrackerService.findTouchedView` walked zero
 * children and returned null. Where a `View` under Compose interop was hit, `targetId` was
 * `"unknown"` because the Compose root has `View.NO_ID`. This object gives autocapture the
 * `Modifier.testTag(...)` the integrator placed on the touched node instead (brain ruling A1 (c),
 * Beso 2026-09-20).
 *
 * compose-ui is a `compileOnly` dependency, so every Compose type is confined to
 * [ComposeSemantics] and reached only through [guarded]: the first [LinkageError] — the
 * `NoClassDefFoundError` a View-only host raises — flips [composeUsable] to false and the SDK never
 * pays for a second one. It is a [LinkageError] catch rather than a `Class.forName` probe because a
 * host that minifies renames Compose's classes, and a name probe would then disable this in the one
 * kind of app it exists for. A [RuntimeException] out of Compose itself is swallowed the same way,
 * per `docs/CONVENTIONS.md`: autocapture must never take the host down.
 */
internal object ComposeTargetResolver {
    @Volatile
    private var composeUsable = true

    /** True when [view] is the View Compose renders into — a valid touch target in its own right. */
    fun isComposeRoot(view: View): Boolean = guarded { ComposeSemantics.isComposeRoot(view) } ?: false

    /**
     * The `testTag` under a touch at screen coordinates ([screenX], [screenY]), or null when [view]
     * is not a Compose root, no tagged node contains the point, or Compose is not on the classpath.
     */
    fun testTagAt(
        view: View,
        screenX: Int,
        screenY: Int,
    ): String? {
        if (!composeUsable) return null
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val localX = (screenX - location[0]).toFloat()
        val localY = (screenY - location[1]).toFloat()
        return guarded { ComposeSemantics.testTagAt(view, localX, localY) }
    }

    // The two detekt rules are suppressed knowingly. This is the SDK's boundary with a toolkit it
    // does not ship: a LinkageError here IS the "Compose is absent" signal and carries nothing worth
    // reporting, and a RuntimeException out of a semantics walk during the host's touch dispatch
    // must be dropped rather than propagated — autocapture never takes the host down.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private inline fun <T> guarded(block: () -> T): T? {
        if (!composeUsable) return null
        return try {
            block()
        } catch (e: LinkageError) {
            // compose-ui is absent (or a version whose API differs). Nothing to resolve, ever.
            composeUsable = false
            null
        } catch (e: RuntimeException) {
            null
        }
    }
}
