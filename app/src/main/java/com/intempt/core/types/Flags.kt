package com.intempt.core.types

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

/*
 * Feature flags, experiments and personalizations, read by key.
 *
 * ## Status of the cross-SDK contract — READ THIS BEFORE CITING IT
 *
 * The cross-SDK surface is defined in `intempt-swift/docs/SDK-API-CONTRACT.md`. As of 2026-08-31,
 * that document at `intempt-swift` `origin/main` **does not specify this surface**: it contains no
 * occurrence of `variation`, `allFlags`, `defaultValue` or `waitForInitialization`, and it still
 * carries the section "`experiments()` is deliberately NOT in any SDK — decided 2026-08-15",
 * whose action table names `intempt-android` with "removal required".
 *
 * An earlier version of this file said the wire was "frozen in `intempt-swift` #7". That is false.
 * `intempt-swift` #7 is **CLOSED with `mergedAt: null`** — it never merged. Its replacement,
 * `intempt-swift` #8, is **OPEN and unreviewed**. An open PR is not settled precedent, so the
 * 2026-08-15 removal stands until #8 lands or a dated supersession is recorded in the contract.
 *
 * This code is therefore written **ahead of** the contract, not against it. It must not merge
 * before the contract change does. See `docs/CONVENTIONS.md` for the ordering.
 *
 * Four rules the contract draft in #8 carries, which shape this file:
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

/**
 * Why an evaluation returned the value it did.
 *
 * **Every one of these except [OFF] is unreachable today**, and deliberately kept rather than
 * deleted. `ExperienceApiChoose` on the serving side carries only `name`, `group` and `body`; there
 * is no `reason` key on the wire at all, so [fromWire] can only ever be handed `null`. That is
 * recorded upstream as known limitation 37 on `EXP-SERVE-001` (H, unimplemented).
 *
 * The wire vocabulary is **not agreed** either. [HOLDOUT]'s `"holdout"` is this SDK's guess; the
 * spec's own language for the same state is "held back". Nothing may depend on these strings until
 * `EXP-SERVE-001` ships and fixes the vocabulary — which is why [FlagDetail] and this enum are
 * `internal` and no public method returns them.
 *
 * They are pinned by `FlagsTest` so that the day a reason does arrive, the decoding is already
 * under the mutation gate rather than being written for the first time under deadline.
 */
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
 * person signs in.
 *
 * **[userId] does NOT preserve assignment across sign-in — it breaks it.** This is stated plainly
 * because an earlier version of this comment promised the opposite. `audience-service`'s
 * `experience/payload/Identification.anonymousId()` returns `userId` whenever one is present and
 * only falls back to `sourceId_profileId` otherwise, and `VariantChooserService.choose()` keys the
 * anonymous assignment on that value. So a caller who supplies [userId] on one call and omits it on
 * the next changes the identifier the service derives on, and the person is re-bucketed mid-session
 * — the exact outcome `EXP-ASSIGN-005` (H) and Sid's 2026-08-24 ruling forbid.
 *
 * [userId] stays in the shape because the cross-SDK request body carries it. **Supply it or omit it
 * consistently for the life of an install; do not toggle it at sign-in.** Whether this SDK should
 * send it for evaluation at all is a product question on `EXP-ASSIGN-005`, not one this repo may
 * settle — see `docs/CONVENTIONS.md`.
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

/**
 * The choose-api request body, built from plain values.
 *
 * Extracted out of `IntemptEventManagerService` so it has no Android dependency and the mutation
 * gate can reach it: every branch below is a null/blank decision that changes what the service is
 * asked, and none of them was covered by any gate before.
 *
 * [names] is omitted entirely when null, which is how the service is asked for every key rather
 * than a named subset — sending an empty list would ask for none.
 */
internal fun buildChooseBody(
    sourceId: String,
    profileId: String?,
    userId: String?,
    names: List<String>?,
): Map<String, Any> {
    val identification = mutableMapOf<String, Any>()
    identification["sourceId"] = sourceId
    profileId?.takeIf { it.isNotBlank() }?.let { identification["profileId"] = it }
    userId?.takeIf { it.isNotBlank() }?.let { identification["userId"] = it }

    val map = mutableMapOf<String, Any>()
    map["identification"] = identification
    // `ExperienceDevice` on the serving side is ALL("all")/DESKTOP("desktop")/MOBILE("mobile")
    // with @JsonValue, so this lowercase token is the one it deserializes.
    map["device"] = "mobile"
    names?.let { map["names"] = it }

    return map
}

/**
 * The `name` of a choice, or null when the response did not carry a usable one.
 *
 * Reads through `as?` rather than `jsonPrimitive`, which **throws** on a non-primitive. A response
 * shaped `{"name":{}}` would otherwise propagate an exception out of a public `variation()` call,
 * which is the one thing `docs/CONVENTIONS.md` says a service problem must never do.
 */
internal fun flagNameOf(choice: JsonObject): String? {
    val name = choice["name"] as? JsonPrimitive
    return name?.takeIf { it.isString }?.content
}

/**
 * The reason a choice carries, decoded.
 *
 * There is no `reason` key on the wire today (known limitation 37), so this returns [FlagReason.OFF]
 * for every real response. It reads through `as?` for the same reason [flagNameOf] does: a
 * non-primitive `reason` must not throw out of a public call.
 */
internal fun flagReasonOf(choice: JsonObject): FlagReason =
    FlagReason.fromWire((choice["reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content)

/** The choice matching [key], or null when the service returned none for it. */
internal fun selectChoice(
    choices: List<JsonObject>,
    key: String,
): JsonObject? = choices.firstOrNull { flagNameOf(it) == key }

/** JSON to a Kotlin value the caller can branch on, with types preserved. */
internal fun unwrapFlagValue(element: JsonElement): Any? =
    when (element) {
        is JsonNull -> null
        is JsonPrimitive ->
            when {
                element.isString -> element.content
                element.booleanOrNull != null -> element.boolean
                element.longOrNull != null -> element.long
                element.doubleOrNull != null -> element.double
                else -> element.content
            }
        else -> element
    }
