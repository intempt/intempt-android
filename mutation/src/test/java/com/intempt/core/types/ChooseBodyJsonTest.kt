package com.intempt.core.types

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mutation-module only, for the same reason `TrackPayloadBuilderPureTest` is: `org.json` resolves
 * to the stub `android.jar` under `:app`'s unit-test task and throws "not mocked", so the real
 * serializer only exists here.
 *
 * `generateChooseBody` is the **first** nested `Map` this SDK hands to `JSONObject(Map)` —
 * `generateRecommendationBody` is flat — and nothing asserted the emitted string. `JSONObject(Map)`
 * does recurse into a nested `Map`, but that is a property of the library, not of this code, and
 * "it probably works" is not a gate.
 */
class ChooseBodyJsonTest {
    private fun emit(
        sourceId: String,
        profileId: String?,
        userId: String?,
        names: List<String>?,
    ): JSONObject = JSONObject(buildChooseBody(sourceId, profileId, userId, names))

    @Test
    fun `the nested identification survives serialization as an object, not a toString`() {
        val json = emit("src-1", "prof-1", null, listOf("checkout"))

        val identification = json.getJSONObject("identification")
        assertEquals("src-1", identification.getString("sourceId"))
        assertEquals("prof-1", identification.getString("profileId"))
        assertEquals("mobile", json.getString("device"))
        assertEquals(1, json.getJSONArray("names").length())
        assertEquals("checkout", json.getJSONArray("names").getString(0))
    }

    @Test
    fun `an all-keys request emits no names field at all`() {
        assertFalse(emit("src-1", "prof-1", null, null).has("names"))
    }

    @Test
    fun `omitted identifiers do not appear as empty strings on the wire`() {
        val identification = emit("src-1", "", null, null).getJSONObject("identification")

        assertFalse(identification.has("profileId"))
        assertFalse(identification.has("userId"))
        assertTrue(identification.has("sourceId"))
    }

    @Test
    fun `userId is emitted inside identification when supplied`() {
        assertEquals(
            "user-9",
            emit("src-1", "prof-1", "user-9", null).getJSONObject("identification").getString("userId"),
        )
    }
}
