package com.intempt.core.types

/**
 * Lifecycle facts the SDK emits without any instrumentation.
 *
 * **Not the same thing as [AutocaptureOptions], and the two are repeatedly confused.** Automatic
 * events are things the SDK already knows — a session started, the app was updated. Autocapture
 * hooks the view layer. They are separate switches because they have separate costs: one is a
 * handful of events a day, the other is an event per interaction.
 *
 * Defaults follow the cross-SDK contract: [sessions] on, the other two off. Android previously
 * emitted all three whenever autocapture was enabled, which is how an event-volume bill surprises
 * someone who only wanted sessions.
 */
data class AutomaticEventsOptions
    @JvmOverloads
    constructor(
        /** Session start / end, carrying device facts as user attributes. */
        val sessions: Boolean = true,
        /** Application Installed / Application Updated, once per version. */
        val versionChanges: Boolean = false,
        /** Application Opened / Application Backgrounded, on every transition. */
        val appStateChanges: Boolean = false,
    )

/**
 * UI instrumentation: what the SDK hooks in the view layer.
 *
 * The contract names two concepts both platforms have — screen views and control interactions —
 * and leaves finer granularity to a per-platform annex. [screenViews] and [controlInteractions]
 * are the contract's; [captureText] is Android's, and has no cross-platform meaning because iOS
 * makes no equivalent distinction.
 *
 * Nothing here installs anything. `Intempt.autocapture.configure(...)` sets options and
 * `Intempt.autocapture.start()` installs the hooks — a contractual split, because an SDK may not
 * instrument a host app's view layer merely because someone called `initialize()`.
 */
data class AutocaptureOptions
    @JvmOverloads
    constructor(
        /** Screen views and screen exits, from the activity and fragment lifecycles. */
        val screenViews: Boolean = true,
        /** Taps and control changes on supported widgets. */
        val controlInteractions: Boolean = true,
        /**
         * Whether captured control interactions may carry the widget's text.
         *
         * Android-specific, and separate from [controlInteractions] because the sensitive part of a
         * control interaction is usually its contents rather than the fact of it. Turning this off
         * still records that a field changed; it stops recording what it changed to.
         * `Intempt.doNotCaptureText(view)` is the per-view version of the same switch.
         */
        val captureText: Boolean = true,
    )
