@file:OptIn(com.intempt.core.internal.InternalIntemptApi::class)

package com.intempt.core

import com.intempt.core.customCapture.CustomCaptureComponent
import com.intempt.core.customCapture.CustomCaptureService
import com.intempt.core.queue.DeliveryMessages
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.ErrorReporter
import com.intempt.core.services.HttpManagerService
import com.intempt.core.services.IntemptEventManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.StorageManagerService
import com.intempt.core.services.UtilsService
import com.intempt.core.services.eventPool.EventPoolManagerService
import com.intempt.core.types.FlagContext
import com.intempt.core.types.buildChooseBody
import com.intempt.core.types.withGroups
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argThat
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** `choose()` (D13) through the real transport to `/optimization/choose-api`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChooseApiTest {
    private var server: StubChooseServer? = null
    private val logger = mock(LoggerManagerService::class.java)

    @After
    fun tearDown() {
        server?.close()
    }

    private fun component(
        vararg answers: Pair<Int, String>,
        sourceId: String = "src-1",
    ): CustomCaptureComponent {
        val stub = StubChooseServer(*answers).also { server = it }
        val config = mock(ConfigManagerService::class.java)
        `when`(config.token()).thenReturn("dG9rZW4=")
        `when`(config.certificatePins).thenReturn(emptyList())
        `when`(config.optimizationUrl).thenReturn(stub.url)
        `when`(config.sourceId).thenReturn(sourceId)
        val events = mock(IntemptEventManagerService::class.java)
        `when`(events.generateChooseBody(any(), anyOrNull(), anyOrNull())).thenAnswer {
            val ctx = it.arguments[0] as FlagContext
            val names = (it.arguments[1] as List<*>?)?.map { n -> n.toString() }
            val groups = (it.arguments[2] as List<*>?)?.map { g -> g.toString() }
            withGroups(buildChooseBody(sourceId, ctx.profileId ?: "prof-1", ctx.userId, names, "sess-1"), groups)
        }
        val http = HttpManagerService(config, logger)
        val pool = EventPoolManagerService(config, logger, http, events, mock(DeliveryMessages::class.java))
        val srv = mock(CustomCaptureService::class.java)
        `when`(srv.logger).thenReturn(logger)
        return CustomCaptureComponent(
            srv,
            config,
            pool,
            events,
            UtilsService(logger),
            mock(StorageManagerService::class.java),
            mock(ErrorReporter::class.java),
        )
    }

    private fun sent(): JSONObject = JSONObject(server!!.bodies.single())

    @Test
    fun `sends names, groups, session, device and identification, and no exposure flag by default`() =
        runBlocking<Unit> {
            component(200 to """{"choices":[]}""").choose(FlagContext(), listOf("hero"), listOf("home"), false)

            assertEquals("/choose", server!!.paths.single())
            val body = sent()
            assertEquals("hero", body.getJSONArray("names").getString(0))
            assertEquals("home", body.getJSONArray("groups").getString(0))
            assertEquals("sess-1", body.getString("sessionId"))
            assertEquals("mobile", body.getString("device"))
            assertEquals("src-1", body.getJSONObject("identification").getString("sourceId"))
            assertFalse(body.has("exposure"))
        }

    @Test
    fun `disableExposure sends exposure false`() =
        runBlocking<Unit> {
            component(200 to """{"choices":[]}""").choose(FlagContext(), listOf("hero"), null, true)

            assertEquals(false, sent().get("exposure"))
            assertFalse(sent().has("groups"))
        }

    @Test
    fun `parses choices with their optional fields and ignores unserved`() =
        runBlocking<Unit> {
            val reply =
                """{"choices":[{"name":"hero","group":"home","body":"blue","experienceId":9,"variantId":4,
                |"reason":"targeted"},{"name":3}],"unserved":[{"name":"other","reason":"off"}]}
                """.trimMargin()
            val choices = component(200 to reply).choose(FlagContext(), listOf("hero", "other"), null, false)

            assertEquals(listOf("hero"), choices.map { it.name })
            val c = choices.single()
            assertEquals("home", c.group)
            assertEquals(JsonPrimitive("blue"), c.body)
            assertEquals(9L, c.experienceId)
            assertEquals(4L, c.variantId)
            assertEquals("targeted", c.reason)
        }

    @Test
    fun `a failure returns no choices, auth logs an error and anything else a warning`() =
        runBlocking<Unit> {
            val c = component(401 to "{}", 500 to "{}", 200 to "nope")
            repeat(3) { assertTrue(c.choose(FlagContext(), listOf("hero"), null, false).isEmpty()) }

            verify(logger).error(argThat<String> { contains("choose") && contains("auth") })
            verify(logger).warn(argThat<String> { contains("choose") && contains("server") })
            verify(logger).warn(argThat<String> { contains("choose") && contains("parse") })
        }

    @Test
    fun `a malformed name or group throws and sends nothing`() {
        val c = component(200 to """{"choices":[]}""")
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { c.choose(FlagContext(), listOf("a b"), null, false) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { c.choose(FlagContext(), null, listOf(""), false) }
        }
        assertTrue(server!!.bodies.isEmpty())
    }

    @Test
    fun `no source and no userId is not an identity`() {
        val c = component(200 to """{"choices":[]}""", sourceId = "")
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { c.choose(FlagContext(), null, null, false) }
        }
        runBlocking { c.choose(FlagContext(userId = "u"), null, null, false) }
        assertEquals(1, server!!.bodies.size)
        verify(logger, never()).error(argThat<String> { contains("choose") })
    }
}
