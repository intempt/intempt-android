package com.intempt.core.types

/*
 * Feature flags, experiments and personalizations, read by key.
 *
 * The cross-SDK surface is defined in `intempt-swift/docs/SDK-API-CONTRACT.md`, which every
 * Intempt SDK conforms to. Four of its rules shape this file:
 *
 * 1. The caller asks for a KEY, never a mode. The older surface put the mode in the method name,
 *    which forced an integrator to know whether a key was an experiment before reading it and grew
 *    combinatorially with every new mode. The platform resolves mode itself: its serving query
 *    filters on channel and status and never on mode.
 * 2. `defaultValue` is REQUIRED. It is what a caller receives on a network failure, a timeout, an
 *    unknown key or a malformed response.
 * 3. Assignment detail is NOT exposed. It would carry a [FlagReason] the platform does not send,
 *    so it could not tell a deliberate off state
 *    from a request the service never answered — which is exactly why this SDK exposed no
 *    assignment at all until the serving contract could distinguish the two.
 * 4. Evaluation is REMOTE only. There is no local rule engine and no flag store to poll.
 *
 * An Android SDK runs on a device and is still an `api`-channel consumer: there is no visual editor
 * for a native surface, so the value is authored as a payload and the integrator writes the branch.
 */

/** Why an evaluation returned the value it did. */
internal enum class FlagReason(val wireValue: String) {
    TARGETED("targeted"),
    HOLDOUT("holdout"),
    NOT_TARGETED("not_targeted"),
    OFF("off"),
    ;

    companion object {
        /**
         * A reason added server-side that this SDK version predates resolves to [OFF] rather than
         * throwing. An unknown answer is still an answer the caller must not act on.
         */
        @JvmStatic
        fun fromWire(value: String?): FlagReason = entries.firstOrNull { it.wireValue == value } ?: OFF
    }
}

/**
 * Who is being evaluated.
 *
 * [profileId] is the device identifier the SDK already holds. It is present before and after a
 * person signs in, which is what keeps their assignment stable across the transition — deriving on
 * the user id instead re-buckets them mid-session.
 */
data class FlagContext
    @JvmOverloads
    constructor(
        val userId: String? = null,
        val profileId: String? = null,
    )

/** A value and why it was returned. INTERNAL — see the note on variationDetailInternal. */
internal data class FlagDetail
    @JvmOverloads
    constructor(
        val value: Any?,
        val reason: FlagReason,
    )
