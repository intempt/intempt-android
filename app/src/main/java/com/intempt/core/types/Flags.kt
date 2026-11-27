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
 * Shared flag and choose types. Flag evaluation lives in `com.intempt.core.flags` (F33) and reads
 * the `/flags` routes only; `/optimization/choose-api` serves `choose()` (experiments and personalizations)
 * and never a flag (D20, DECISIONS-2026-11-23). Evaluation is remote: there is no local rule engine.
 */

/**
 * Why an evaluation returned the value it did: the server's `FlagValue.reason`, or a reason the SDK
 * decides itself. [ERROR] (the read failed or was refused, see [FlagDetail.errorCode]) and [CACHED]
 * (the last known value, served because the read failed) are never decoded from the wire.
 */
enum class FlagReason(val wireValue: String) {
    TARGETED("targeted"),
    HOLDOUT("holdout"),
    NOT_TARGETED("not_targeted"),
    NOT_IN_ROLLOUT("not_in_rollout"),
    OFF("off"),
    KILLED("killed"),
    NOT_CLIENT_SIDE("not_client_side"),
    ERROR("error"),
    CACHED("cached"),
    ;

    companion object {
        /**
         * An absent reason (an older server) and one this SDK version predates both read [OFF], as
         * every answer did before reasons were sent: an unknown answer is not one to act on.
         */
        @JvmStatic
        internal fun fromWire(value: String?): FlagReason {
            val decoded = entries.firstOrNull { it.wireValue == value }
            return if (decoded == null || decoded == ERROR || decoded == CACHED) OFF else decoded
        }
    }
}

/** OpenFeature error codes, set with [FlagReason.ERROR] when the default came back for a known cause (S11). */
enum class FlagErrorCode {
    FLAG_NOT_FOUND,
    TYPE_MISMATCH,
    TARGETING_KEY_MISSING,
    GENERAL,
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
 * A value and why it was returned. [error] is set only when the read itself failed; [errorCode] with
 * every [FlagReason.ERROR]. [variationId], [valueType], [rule] and [variant] are the server's answer
 * fields, null when nothing was served or the server did not send them.
 *
 * Plain classes here and on [FlagError], not `data`: the CI method-count gate, see [IntemptError].
 */
@Suppress("LongParameterList") // one parameter per F6 answer field, the shape every SDK returns
class FlagDetail
    @JvmOverloads
    constructor(
        val value: Any?,
        val reason: FlagReason,
        val error: FlagError? = null,
        val errorCode: FlagErrorCode? = null,
        val variationId: Long? = null,
        val valueType: String? = null,
        val rule: Int? = null,
        val variant: String? = null,
    ) {
        override fun toString(): String =
            "FlagDetail(value=$value, reason=${reason.wireValue}, errorCode=$errorCode, " +
                "variationId=$variationId, valueType=$valueType, rule=$rule, variant=$variant, error=$error)"
    }

/**
 * The `allFlags` answer. As a [Map], a value read records the exposure of each served key it reads,
 * once per map (`/flags/expose`): `get`, `getOrDefault`, and iterating `values` or `entries`, which
 * read every value. `keys`, `containsKey`, `size`, `equals`, `hashCode` and `toString` record nothing.
 *
 * [peek] and [snapshot] read without recording, for a caller that must hand the values on before it
 * knows which keys will be used, such as the React Native bridge (PLAN §7 item 5): the bridge copies
 * the [snapshot] across and calls [expose] for each key the app then reads.
 */
interface FlagMap : Map<String, Any?> {
    /** The served value of [key], or null. Records no exposure. */
    fun peek(key: String): Any?

    /**
     * A copy of every served value. Records no exposure, now or when read: the caller must call
     * [expose] for each key it uses, or those reads go unrecorded.
     */
    fun snapshot(): Map<String, Any?>

    /**
     * Records the exposure of [key] as a value read does: once per map, shared with `get`, so a
     * key already read or exposed sends nothing again. Does nothing for a key this map did not
     * serve with a variation. Fire-and-forget; never throws or waits.
     */
    fun expose(key: String)
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
        transportErrorOf(key, status, timedOut)
            ?: if (choices == null) FlagError(key, FlagErrorKind.PARSE, status) else null
    return ChooseResult(choices.orEmpty(), error)
}

/** The failure a response stands for, or null for a 2xx. [status] 0 means no response arrived. */
internal fun transportErrorOf(
    key: String?,
    status: Int,
    timedOut: Boolean,
): FlagError? =
    when {
        timedOut -> FlagError(key, FlagErrorKind.TIMEOUT, null)
        status == 0 -> FlagError(key, FlagErrorKind.NETWORK, null)
        status !in HTTP_SUCCESS -> FlagError(key, flagErrorKindOf(status), status)
        else -> null
    }

private fun choicesOf(body: String?): List<JsonObject>? {
    val root = body?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } as? JsonObject
    return (root?.get("choices") as? JsonArray)?.mapNotNull { it as? JsonObject }
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
    identification.putAll(identificationOf(null, profileId, userId))

    val map = mutableMapOf<String, Any>()
    map["identification"] = identification
    map["device"] = SDK_DEVICE
    sessionId?.takeIf { it.isNotBlank() }?.let { map["sessionId"] = it }
    names?.let { map["names"] = it }

    return map
}

/**
 * `ExperienceDevice` on the serving side is ALL("all")/DESKTOP("desktop")/MOBILE("mobile") with
 * @JsonValue. Node sends "all" because a server has no device; this SDK runs on one, and sends the
 * same token on choose and every flag route because `/flags/expose` re-runs the assignment with it.
 */
internal const val SDK_DEVICE = "mobile"

/** choose-api's `Identification` (minus `masterId`), blank values omitted rather than sent empty. */
internal fun identificationOf(
    sourceId: String?,
    profileId: String?,
    userId: String?,
): Map<String, Any> {
    val identification = mutableMapOf<String, Any>()
    sourceId?.takeIf { it.isNotBlank() }?.let { identification["sourceId"] = it }
    profileId?.takeIf { it.isNotBlank() }?.let { identification["profileId"] = it }
    userId?.takeIf { it.isNotBlank() }?.let { identification["userId"] = it }
    return identification
}

/** choose-api `groups`, sent only when given: an absent list asks for no group filter. */
internal fun withGroups(
    body: Map<String, Any>,
    groups: List<String>?,
): Map<String, Any> = groups?.let { body + ("groups" to it) } ?: body

/**
 * CONTRACT-R2 §S4 (INT-7456): `exposure: false` asks choose-api to evaluate without recording an
 * exposure. Recording leaves the field OUT rather than sending `true`, so a backend that predates
 * the field, and an SDK that predates it, behave exactly as before.
 */
internal fun withExposure(
    body: Map<String, Any?>,
    recordExposure: Boolean,
): Map<String, Any?> = if (recordExposure) body else body + ("exposure" to false)

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
