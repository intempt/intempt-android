package com.intempt.core.types

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** `choose()` (D13): experiments and personalizations over choose-api, parsed as Node's `Choose`. */
class ChooseTest {
    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    @Test
    fun `the production reply of name, group and body parses`() {
        val c = choiceOf(obj("""{"name":"hero","group":"home","body":{"title":"Hi"}}"""))!!
        assertEquals("hero", c.name)
        assertEquals("home", c.group)
        assertEquals(obj("""{"title":"Hi"}"""), c.body)
        assertNull(c.experienceId)
        assertNull(c.variantId)
        assertNull(c.reason)
    }

    @Test
    fun `experienceId, variantId and reason are kept when sent`() {
        val c =
            choiceOf(
                obj("""{"name":"hero","body":1,"experienceId":1003,"variantId":1100,"reason":"targeted"}"""),
            )!!
        assertEquals(1003L, c.experienceId)
        assertEquals(1100L, c.variantId)
        assertEquals("targeted", c.reason)
        assertEquals(JsonPrimitive(1), c.body)
        assertNull(c.group)
        assertTrue(c.toString().contains("hero"))
    }

    @Test
    fun `fields of the wrong type are dropped and a choice without a string name is skipped`() {
        val c = choiceOf(obj("""{"name":"n","group":2,"experienceId":"1","variantId":true,"reason":{}}"""))!!
        assertNull(c.group)
        assertNull(c.experienceId)
        assertNull(c.variantId)
        assertNull(c.reason)
        assertNull(c.body)
        assertNull(choiceOf(obj("""{"name":1,"body":1}""")))
        assertNull(choiceOf(obj("""{"body":1}""")))
    }

    @Test
    fun `choices come from the choices array only, unserved is ignored`() {
        val result =
            chooseResultOf(
                null,
                200,
                """{"choices":[{"name":"a","body":1},{"name":2}],"unserved":[{"name":"b","reason":"off"}]}""",
                false,
            )
        assertEquals(listOf("a"), choicesOf(result))
    }

    private fun choicesOf(result: ChooseResult): List<String> = result.choices.mapNotNull { choiceOf(it)?.name }

    @Test
    fun `names and groups must match the server pattern`() {
        requireChooseNames("names", null)
        requireChooseNames("names", listOf("a-b_C9"))
        for (bad in listOf("", "a b", "a.b")) {
            try {
                requireChooseNames("groups", listOf("ok", bad))
                fail("expected a rejection for \"$bad\"")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("groups"))
                assertTrue(e.message!!.contains("\"$bad\""))
            }
        }
    }

    @Test
    fun `the choose body carries groups only when given`() {
        val base = buildChooseBody("src-1", "prof-1", null, listOf("a"), "sess")
        val body = withGroups(base, listOf("g"))
        assertEquals(listOf("g"), body["groups"])
        assertEquals(listOf("a"), body["names"])
        assertTrue(!withGroups(base, null).containsKey("groups"))
        assertTrue(!base.containsKey("groups"))
    }
}
