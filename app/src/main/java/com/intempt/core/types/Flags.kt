package com.intempt.core.types

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
import java.util.concurrent.atomic.AtomicBoolean

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
 * 3. Assignment detail IS exposed, as `variationDetail` (INT-7454, DECISIONS 7454 and
 *    CONTRACT-R2 §S3; this rule used to forbid it). The platform still sends no reason, so an
 *    answered read is [FlagReason.OFF] whether or not a value was served; what the detail adds is
 *    [FlagReason.ERROR] plus a [FlagError] when the default came back because the call failed —
 *    the one distinction the old rule said could not be made, and the SDK can make it alone.
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
 * spec's own language for the same state is "held back". Do not branch on those three until
 * `EXP-SERVE-001` ships and fixes the vocabulary.
 *
 * Public since INT-7454 for [ERROR] and [OFF], the two this SDK produces: [ERROR] is decided on the
 * device (the call failed) and is never decoded from a response.
 *
 * They are pinned by `FlagsTest` so that the day a reason does arrive, the decoding is already
 * under the mutation gate rather than being written for the first time under deadline.
 */
enum class FlagReason(val wireValue: String) {
    TARGETED("targeted"),
    HOLDOUT("holdout"),
    NOT_TARGETED("not_targeted"),
    OFF("off"),
    ERROR("error"),
    ;

    companion object {
        /**
         * A reason added server-side that this SDK version predates resolves to [OFF] rather than
         * throwing. An unknown answer is still an answer the caller must not act on.
         */
        @JvmStatic
        internal fun fromWire(value: String?): FlagReason {
            val decoded = entries.firstOrNull { it.wireValue == value }
            return if (decoded == null || decoded == ERROR) OFF else decoded
        }
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

/**
 * A value and why it was returned. [error] is set only when [reason] is [FlagReason.ERROR].
 *
 * Plain classes here and on [FlagError], not `data`: the CI method-count gate, see [IntemptError].
 */
class FlagDetail
    @JvmOverloads
    constructor(
        val value: Any?,
        val reason: FlagReason,
        val error: FlagError? = null,
    ) {
        override fun toString(): String = "FlagDetail(value=$value, reason=${reason.wireValue}, error=$error)"
    }

/** CONTRACT-R2 §S3 kinds. 429 is [SERVER] and 408 is [TIMEOUT], as in the node and php SDKs. */
enum class FlagErrorKind(val wireName: String) {
    AUTH("auth"),
    BAD_REQUEST("bad_request"),
    SERVER("server"),
    TIMEOUT("timeout"),
    NETWORK("network"),
    PARSE("parse"),
}

/**
 * Why a flag read returned its default. [key] is null for `allFlags`; [status] is the HTTP status,
 * null when no response arrived.
 */
class FlagError internal constructor(
    val key: String?,
    val kind: FlagErrorKind,
    val status: Int?,
) {
    override fun toString(): String = "FlagError(key=$key, kind=${kind.wireName}, status=$status)"
}

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_REQUEST_TIMEOUT = 408
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_SERVER_ERROR = 500
private const val HTTP_OK_FIRST = 200
private const val HTTP_OK_LAST = 299
private val HTTP_SUCCESS = HTTP_OK_FIRST..HTTP_OK_LAST

internal fun flagErrorKindOf(status: Int): FlagErrorKind =
    when {
        status == HTTP_UNAUTHORIZED || status == HTTP_FORBIDDEN -> FlagErrorKind.AUTH
        status == HTTP_REQUEST_TIMEOUT -> FlagErrorKind.TIMEOUT
        status == HTTP_TOO_MANY_REQUESTS || status >= HTTP_SERVER_ERROR -> FlagErrorKind.SERVER
        else -> FlagErrorKind.BAD_REQUEST
    }

/** A choose call's choices, or why there are none to read. */
internal class ChooseResult(
    val choices: List<JsonObject>,
    val error: FlagError?,
)

/**
 * Classifies one choose call. [status] 0 means no response arrived. A 2xx must be an object with a
 * `choices` array: anything else is [FlagErrorKind.PARSE], where it used to read as "no flags".
 */
internal fun chooseResultOf(
    key: String?,
    status: Int,
    body: String?,
    timedOut: Boolean,
): ChooseResult {
    val choices = if (status in HTTP_SUCCESS) choicesOf(body) else null
    val error =
        when {
            timedOut -> FlagError(key, FlagErrorKind.TIMEOUT, null)
            status == 0 -> FlagError(key, FlagErrorKind.NETWORK, null)
            status !in HTTP_SUCCESS -> FlagError(key, flagErrorKindOf(status), status)
            choices == null -> FlagError(key, FlagErrorKind.PARSE, status)
            else -> null
        }
    return ChooseResult(choices.orEmpty(), error)
}

private fun choicesOf(body: String?): List<JsonObject>? {
    val root = body?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } as? JsonObject
    return (root?.get("choices") as? JsonArray)?.mapNotNull { it as? JsonObject }
}

/** The detail for [key]: the failure, an answered miss ([FlagReason.OFF]), or the served value. */
internal fun flagDetailOf(
    result: ChooseResult,
    key: String,
    defaultValue: Any?,
): FlagDetail {
    val choice = selectChoice(result.choices, key)
    return when {
        result.error != null -> FlagDetail(defaultValue, FlagReason.ERROR, result.error)
        choice == null -> FlagDetail(defaultValue, FlagReason.OFF)
        else -> FlagDetail(choice["body"]?.let { unwrapFlagValue(it) } ?: defaultValue, flagReasonOf(choice))
    }
}

/**
 * Delivers flag errors to the host's `onFlagError` and the log. One per SDK instance.
 *
 * A rejected key (401/403) is logged at error level ONCE per instance, naming the source and never
 * the key material: every later read would fail the same way, and an error per read floods logcat.
 * Everything else is debug, because the hook is the channel a caller acts on.
 */
internal class FlagErrorReporter(
    private val sourceId: () -> String,
    private val logError: (String) -> Unit,
    private val logDebug: (String) -> Unit,
) {
    @Volatile
    var hook: ((FlagError) -> Unit)? = null

    private val authLogged = AtomicBoolean(false)

    fun report(error: FlagError) {
        val what = listOfNotNull(error.kind.wireName, error.status?.toString()).joinToString(" ")
        val subject = error.key?.let { "key $it" } ?: "all flags"
        val line = "flag evaluation failed ($what) for $subject; returned the default"
        if (error.kind == FlagErrorKind.AUTH && authLogged.compareAndSet(false, true)) {
            logError(
                "$line. The API key for source ${sourceId()} was rejected; " +
                    "check the credentials passed to initialize()",
            )
        } else {
            logDebug(line)
        }
        hook?.let { h -> runCatching { h(error) }.onFailure { logError("onFlagError threw: ${it.message}") } }
    }
}

/**
 * The choose-api request body, built from plain values.
 *
 * Extracted out of `IntemptEventManagerService` so it has no Android dependency and the mutation
 * gate can reach it: every branch below is a null/blank decision that changes what the service is
 * asked, and none of them was covered by any gate before.
 *
 * [names] is omitted entirely when null, which is how the service is asked for every key rather
 * than a named subset — sending an empty list would ask for none.
 *
 * [sessionId] is the SDK's current session id (`StorageManagerService.getSessionId()`), sent as a
 * top-level `sessionId` beside `identification` — the optional `ExperienceApiChooseRequest.sessionId`
 * on the serving side. Without it `ChooserHelper.allowOncePerSession` in `audience-service` has no
 * session to key on, so a "Once per session" experience is served once EVER per profile rather than
 * once per session, and every `ExperienceChoose` record is unattributable to a session. Swift has
 * sent it since 0.4.0 (`Flags.swift`); this closes the same gap here (brain tracker A1 (c)-ii, Beso
 * 2026-09-20). Blank is omitted rather than sent — the SDK's accessor returns `""` before the first
 * session starts, and an empty string would be stored as a real session key.
 */
internal fun buildChooseBody(
    sourceId: String,
    profileId: String?,
    userId: String?,
    names: List<String>?,
    sessionId: String?,
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
    sessionId?.takeIf { it.isNotBlank() }?.let { map["sessionId"] = it }
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

/**
 * The serving side's name rule (`ExperienceApiChooseRequest` in `audience-service`). A key outside it
 * is answered 400, which `variation` would turn into the default — indistinguishable from a paused
 * flag — so INT-7455 rejects it before the request. `+`, not the server's `*`: a blank key is
 * already a caller error here.
 */
internal val FLAG_KEY_PATTERN = Regex("^[a-zA-Z0-9_-]+$")

internal fun isValidFlagKey(key: String): Boolean = FLAG_KEY_PATTERN.matches(key)
