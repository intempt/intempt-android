@file:OptIn(com.intempt.core.internal.InternalIntemptApi::class)

package com.intempt.core

import com.intempt.core.flags.FlagsService
import com.intempt.core.types.FlagContext
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * F32 hybrid exposure through the real transport: a single read records on `/flags/evaluate`, an
 * opted-out read and `allFlags` use `/flags/all`, which records nothing, and the first value read
 * from an `allFlags` map sends `/flags/expose` once. choose-api is never called for a flag (D20).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FlagExposureTest {
    private var server: StubChooseServer? = null

    @After
    fun tearDown() {
        server?.close()
    }

    private fun serving(vararg answers: Pair<Int, String>): FlagsService {
        val stub = StubChooseServer(*answers).also { server = it }
        return flagsServiceAt(stub.flagsBase)
    }

    private fun flagPaths(): List<String> = server!!.paths.filter { !it.endsWith("/stream") }

    private fun sent(index: Int = 0): JSONObject = JSONObject(server!!.bodies[index])

    @Test
    fun `a single read posts one key to flags evaluate with identity, device and session`() =
        runBlocking<Unit> {
            val flags = serving(200 to """{"key":"k","value":"on","variationId":3,"reason":"targeted"}""")

            assertEquals("on", flags.detail("k", FlagContext(), "off").value)

            assertEquals(listOf("/v1/o/projects/p/flags/evaluate"), flagPaths())
            val body = sent()
            assertEquals("k", body.getString("key"))
            assertEquals("mobile", body.getString("device"))
            assertEquals("sess-1", body.getString("sessionId"))
            assertEquals("src-1", body.getJSONObject("identification").getString("sourceId"))
            assertEquals("prof-1", body.getJSONObject("identification").getString("profileId"))
            assertFalse(body.has("exposure"))
        }

    @Test
    fun `an opted-out read asks flags all for that one key and still returns the value`() =
        runBlocking<Unit> {
            val flags = serving(200 to """{"flags":[{"key":"k","value":"on","variationId":3}]}""")

            assertEquals("on", flags.detail("k", FlagContext(), "off", disableExposure = true).value)

            assertEquals(listOf("/v1/o/projects/p/flags/all"), flagPaths())
            assertEquals("k", sent().getJSONArray("keys").getString(0))
            assertFalse(sent().has("sessionId"))
        }

    @Test
    fun `allFlags records nothing until a value is read, then exposes that key once`() =
        runBlocking<Unit> {
            val flags =
                serving(
                    200 to
                        """{"flags":[{"key":"a","value":1,"variationId":11},{"key":"b","value":2,"variationId":12}]}""",
                    202 to "",
                )

            val all = flags.all(FlagContext())
            assertEquals(setOf("a", "b"), all.keys)
            assertEquals(listOf("/v1/o/projects/p/flags/all"), flagPaths())
            assertFalse(sent().has("keys"))

            assertEquals(1L, all["a"])
            assertEquals(1L, all["a"])
            val deadline = System.currentTimeMillis() + 5_000
            while (flagPaths().size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(20)

            assertEquals(listOf("/v1/o/projects/p/flags/all", "/v1/o/projects/p/flags/expose"), flagPaths())
            val expose = sent(1)
            assertEquals("a", expose.getString("key"))
            assertEquals(11L, expose.getLong("variationId"))
            assertEquals("sess-1", expose.getString("sessionId"))
            Thread.sleep(200)
            assertEquals(2, flagPaths().size)
        }

    @Test
    fun `no flag read reaches choose-api`() =
        runBlocking<Unit> {
            val flags = serving(200 to """{"flags":[]}""")
            flags.detail("k", FlagContext(), 1.0)
            flags.detail("k", FlagContext(), 1.0, disableExposure = true)
            flags.all(FlagContext())

            assertTrue(flagPaths().none { it.contains("choose") })
            assertEquals(3, flagPaths().size)
        }
}
