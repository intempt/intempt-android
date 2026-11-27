package com.intempt.core.flags

import com.intempt.core.types.FlagDetail
import com.intempt.core.types.FlagError
import com.intempt.core.types.FlagErrorCode
import com.intempt.core.types.FlagErrorKind
import com.intempt.core.types.FlagReason
import com.intempt.core.types.SDK_DEVICE
import com.intempt.core.types.identificationOf
import com.intempt.core.types.transportErrorOf
import com.intempt.core.types.unwrapFlagValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/*
 * The pure half of the flag module: request bodies, answer parsing and the typed-read rule. No
 * android.* import, so `:mutation` compiles it. Shapes come from audience-service
 * `payload/flag/FlagEvaluateRequest`, `FlagAllRequest`, `FlagExposeRequest`, `FlagValue` and
 * `FlagsChangedEvent` (feature/feature-flag e10de6d).
 */

/** Route suffixes under `/v1/{org}/projects/{project}/flags/`, as `OptimizationChooserRoutes` declares them. */
internal object FlagRoute {
    const val EVALUATE = "evaluate"
    const val ALL = "all"
    const val EXPOSE = "expose"
    const val STREAM = "stream"
}

/** One flag call: [status] 0 when no response arrived, [timedOut] when that was a timeout. */
internal class FlagHttpResponse(val status: Int, val body: String?, val timedOut: Boolean)

/**
 * Who a read is for, resolved: the caller's [com.intempt.core.types.FlagContext] over the SDK's own
 * profile id, plus the configured source and the current session.
 */
internal class FlagIdentity(
    val sourceId: String?,
    val profileId: String?,
    val userId: String?,
    val sessionId: String?,
) {
    /** `Identification.hasAnonymousId`: the flag routes answer 400 without one. */
    fun isAnswerable(): Boolean = !userId.isNullOrBlank() || hasProfile()

    private fun hasProfile(): Boolean = !profileId.isNullOrBlank() && !sourceId.isNullOrBlank()

    /**
     * The identity the server buckets on (`Identification.experimentAnonymousId`): source and profile
     * when both are present, else the user. JSON-encoded so no key/id pair can collide with another.
     */
    fun cacheKey(key: String): String {
        val parts =
            if (hasProfile()) {
                listOf(key, "profile", sourceId, profileId)
            } else {
                listOf(key, "user", userId)
            }
        return JsonArray(parts.map { JsonPrimitive(it) }).toString()
    }

    fun request(): Map<String, Any> =
        mapOf(
            "identification" to identificationOf(sourceId, profileId, userId),
            "device" to SDK_DEVICE,
        )

    fun withSession(body: Map<String, Any>): Map<String, Any> =
        sessionId?.takeIf { it.isNotBlank() }?.let { body + ("sessionId" to it) } ?: body
}

/** `/flags/evaluate`: exactly one key; the server records the exposure. */
internal fun evaluateBody(
    identity: FlagIdentity,
    key: String,
): Map<String, Any> = identity.withSession(identity.request() + ("key" to key))

/** `/flags/all` records nothing, so it takes no session. No [keys] answers every served flag. */
internal fun allBody(
    identity: FlagIdentity,
    keys: List<String>?,
): Map<String, Any> = keys?.let { identity.request() + ("keys" to it) } ?: identity.request()

internal fun exposeBody(
    identity: FlagIdentity,
    key: String,
    variationId: Long,
): Map<String, Any> = identity.withSession(identity.request() + mapOf("key" to key, "variationId" to variationId))

/** audience-service `FlagValue`, read field by field: an older or partial answer must not crash. */
internal class RawFlag(private val obj: JsonObject) {
    val key: String? get() = obj.string("key")
    val value: JsonElement? get() = obj["value"]
    val variationId: Long? get() = obj.number("variationId")?.longOrNull
    val valueType: String? get() = obj.string("valueType")
    val reason: String? get() = obj.string("reason")?.takeIf { it.isNotEmpty() }
    val rule: Int? get() = obj.number("rule")?.intOrNull
    val variant: String? get() = obj.string("variant")
    val hasValue: Boolean get() = value.let { it != null && it != JsonNull }
}

private fun JsonObject.string(field: String): String? = (this[field] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.number(field: String): JsonPrimitive? = (this[field] as? JsonPrimitive)?.takeIf { !it.isString }

internal fun rawFlagOf(obj: JsonObject): RawFlag = RawFlag(obj)

private fun parseObject(body: String?): JsonObject? {
    val parsed = body?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
    return parsed as? JsonObject
}

internal class EvaluateOutcome(val flag: RawFlag?, val error: FlagError?)

internal fun evaluateOutcomeOf(
    key: String,
    response: FlagHttpResponse,
): EvaluateOutcome {
    val failure = transportErrorOf(key, response.status, response.timedOut)
    val obj = if (failure == null) parseObject(response.body) else null
    return when {
        failure != null -> EvaluateOutcome(null, failure)
        obj == null -> EvaluateOutcome(null, FlagError(key, FlagErrorKind.PARSE, response.status))
        else -> EvaluateOutcome(rawFlagOf(obj), null)
    }
}

internal class AllOutcome(val flags: List<RawFlag>, val error: FlagError?)

/** [errorKey] is the one key a `disableExposure` read asked for, null for `allFlags`. */
internal fun allOutcomeOf(
    errorKey: String?,
    response: FlagHttpResponse,
): AllOutcome {
    val failure = transportErrorOf(errorKey, response.status, response.timedOut)
    val flags = if (failure == null) parseObject(response.body)?.get("flags") as? JsonArray else null
    return when {
        failure != null -> AllOutcome(emptyList(), failure)
        flags == null -> AllOutcome(emptyList(), FlagError(errorKey, FlagErrorKind.PARSE, response.status))
        else -> AllOutcome(flags.mapNotNull { (it as? JsonObject)?.let(::rawFlagOf) }, null)
    }
}

/** The flag's declared `valueType` tokens. */
internal enum class FlagValueType(val wire: String) {
    BOOLEAN("boolean"),
    STRING("string"),
    NUMBER("number"),
    JSON("json"),
}

/**
 * The type a read expects, from its default (F30 #2). A null default names no type: it is the caller
 * saying "absent", so any served value is returned. A [JsonElement] default is typed by its JSON
 * kind, as Node's `expectedType` types by `typeof` (Beso ruling 2026-11-27): a scalar names its own
 * type and [JsonNull] names none, so `jsonVariation` is checked like the other typed reads.
 */
internal fun expectedTypeOf(defaultValue: Any?): FlagValueType? =
    when (defaultValue) {
        null, JsonNull -> null
        is Boolean -> FlagValueType.BOOLEAN
        is String -> FlagValueType.STRING
        is Number -> FlagValueType.NUMBER
        is JsonPrimitive ->
            when {
                defaultValue.isString -> FlagValueType.STRING
                defaultValue.booleanOrNull != null -> FlagValueType.BOOLEAN
                else -> FlagValueType.NUMBER
            }
        else -> FlagValueType.JSON
    }

/**
 * The declared `valueType` decides when sent (F30 #3); the JSON type is checked as well, so a
 * malformed answer cannot pass as its declared type. Any JSON value is a json value (F30 #4).
 */
internal fun matchesType(
    expected: FlagValueType,
    value: JsonElement,
    valueType: String?,
): Boolean {
    if (valueType != null && valueType != expected.wire) return false
    val primitive = value as? JsonPrimitive
    return when (expected) {
        FlagValueType.JSON -> true
        FlagValueType.STRING -> primitive?.isString == true
        FlagValueType.BOOLEAN -> primitive != null && !primitive.isString && primitive.booleanOrNull != null
        FlagValueType.NUMBER -> primitive != null && !primitive.isString && primitive.doubleOrNull?.isFinite() == true
    }
}

/**
 * The detail for an answered (or cached) flag. A json read, or any read with a [JsonElement] default,
 * gets the raw element back; every other read gets the unwrapped Kotlin value. A value of the wrong type is
 * TYPE_MISMATCH with the default, never a coercion: `"false"` is truthy almost everywhere.
 */
internal fun typedDetail(
    flag: RawFlag,
    defaultValue: Any?,
    reason: FlagReason? = null,
): FlagDetail {
    val answered = reason ?: FlagReason.fromWire(flag.reason)
    val value = flag.value
    val expected = expectedTypeOf(defaultValue)
    return when {
        value == null || value == JsonNull -> detailOf(flag, defaultValue, answered, null)
        expected != null && !matchesType(expected, value, flag.valueType) ->
            detailOf(flag, defaultValue, FlagReason.ERROR, FlagErrorCode.TYPE_MISMATCH)
        expected == FlagValueType.JSON || defaultValue is JsonElement -> detailOf(flag, value, answered, null)
        else -> detailOf(flag, unwrapFlagValue(value), answered, null)
    }
}

private fun detailOf(
    flag: RawFlag,
    value: Any?,
    reason: FlagReason,
    errorCode: FlagErrorCode?,
): FlagDetail = FlagDetail(value, reason, null, errorCode, flag.variationId, flag.valueType, flag.rule, flag.variant)

/** The keys a `flags_changed` event names; empty means "drop everything", as a malformed event does. */
internal fun keysOfFlagsChanged(data: String): List<String> {
    val keys = (runCatching { Json.parseToJsonElement(data) }.getOrNull() as? JsonObject)?.get("keys") as? JsonArray
    val names = keys?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
    return if (names == null || names.any { it == null }) emptyList() else names.filterNotNull()
}

internal class SseEvent(val event: String, val data: String)

/** A line-at-a-time `text/event-stream` reader: comments skipped, an event dispatched on a blank line. */
internal class SseParser {
    private var event: String? = null
    private val data = mutableListOf<String>()
    private var hasData = false

    fun feed(line: String): SseEvent? {
        when {
            line.isEmpty() -> return dispatch()
            line.startsWith(":") -> Unit
            else -> {
                val field = line.substringBefore(':')
                val value = if (line.contains(':')) line.substringAfter(':').removePrefix(" ") else ""
                if (field == "event") event = value
                if (field == "data") {
                    data += value
                    hasData = true
                }
            }
        }
        return null
    }

    private fun dispatch(): SseEvent? {
        val out = if (hasData) SseEvent(event ?: "message", data.joinToString("\n")) else null
        event = null
        data.clear()
        hasData = false
        return out
    }
}

/**
 * One warning per id. Bounded so a stream of distinct bad keys cannot grow memory: when full it
 * starts over, and a key seen long ago may warn once more rather than never.
 */
internal class WarnOnce(
    private val warn: (String) -> Unit,
    private val max: Int = WARNED_MAX,
) {
    private val seen = HashSet<String>()

    fun warn(
        id: String,
        message: String,
    ) {
        val first =
            synchronized(seen) {
                if (id in seen) {
                    false
                } else {
                    if (seen.size >= max) seen.clear()
                    seen.add(id)
                }
            }
        if (first) warn(message)
    }

    private companion object {
        const val WARNED_MAX = 1_000
    }
}
