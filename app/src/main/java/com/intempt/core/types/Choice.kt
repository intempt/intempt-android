package com.intempt.core.types

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * One served experience or personalization from `choose()`. Production choose-api sends only
 * [name], [group] and [body]; [experienceId], [variantId] and [reason] are present only on
 * audience-service builds that send them. Plain class, not `data`: the CI method-count gate.
 */
class Choice internal constructor(
    val name: String,
    val group: String?,
    val body: JsonElement?,
    val experienceId: Long?,
    val variantId: Long?,
    val reason: String?,
) {
    override fun toString(): String =
        "Choice(name=$name, group=$group, body=$body, experienceId=$experienceId, variantId=$variantId, reason=$reason)"
}

private fun JsonObject.string(field: String): String? = (this[field] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.long(field: String): Long? = (this[field] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

/** A choice row, or null without a string `name`. A field of the wrong type is dropped, not trusted. */
internal fun choiceOf(row: JsonObject): Choice? {
    val name = row.string("name") ?: return null
    return Choice(
        name = name,
        group = row.string("group"),
        body = row["body"],
        experienceId = row.long("experienceId"),
        variantId = row.long("variantId"),
        reason = row.string("reason"),
    )
}

/**
 * `ExperienceApiChooseRequest` rejects an entry outside the pattern with a 400 for the whole call, so
 * a malformed `names`/`groups` is the caller's mistake and throws, as Node's `choose` does; only
 * runtime failures resolve to an empty list.
 */
internal fun requireChooseNames(
    field: String,
    values: List<String>?,
) {
    values?.forEach { value ->
        require(isValidFlagKey(value)) {
            "choose: $field entries must match ${FLAG_KEY_PATTERN.pattern}, got \"$value\""
        }
    }
}
