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
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * INT-7456 / CONTRACT-R2 §S4: evaluation is not exposure. A bulk read asks the service not to
 * record; a single read records by leaving the field out, so an older backend behaves as before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FlagExposureTest {
    private val server = StubChooseServer(200 to """{"choices":[{"name":"k","body":"on"}]}""")

    @After
    fun tearDown() = server.close()

    private fun component(): CustomCaptureComponent {
        val config = mock(ConfigManagerService::class.java)
        `when`(config.token()).thenReturn("dG9rZW4=")
        `when`(config.certificatePins).thenReturn(emptyList())
        `when`(config.optimizationUrl).thenReturn(server.url)
        `when`(config.sourceId).thenReturn("src-1")
        val events = mock(IntemptEventManagerService::class.java)
        `when`(events.generateChooseBody(any(), anyOrNull())).thenReturn(mapOf("device" to "mobile"))
        val logger = mock(LoggerManagerService::class.java)
        val pool =
            EventPoolManagerService(
                config,
                logger,
                HttpManagerService(config, logger),
                events,
                mock(DeliveryMessages::class.java),
            )
        return CustomCaptureComponent(
            mock(CustomCaptureService::class.java),
            config,
            pool,
            events,
            UtilsService(logger),
            mock(StorageManagerService::class.java),
            mock(ErrorReporter::class.java),
        )
    }

    private fun sent(): JSONObject = JSONObject(server.bodies.single())

    @Test
    fun `allFlags asks the service to evaluate without recording an exposure`() =
        runBlocking<Unit> {
            component().allFlags(FlagContext())

            assertEquals(false, sent().opt("exposure"))
        }

    @Test
    fun `a default single read leaves the exposure field out so the service records`() =
        runBlocking<Unit> {
            assertEquals("on", component().variationDetailInternal("k", FlagContext(), "off").value)

            assertFalse(sent().has("exposure"))
        }

    @Test
    fun `an opted-out single read sends exposure false and still returns the served value`() =
        runBlocking<Unit> {
            val detail = component().variationDetailInternal("k", FlagContext(), "off", recordExposure = false)

            assertEquals("on", detail.value)
            assertEquals(false, sent().opt("exposure"))
        }
}
