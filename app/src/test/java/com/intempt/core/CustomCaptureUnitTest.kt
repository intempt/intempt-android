package com.intempt.core

import android.content.Context
import com.intempt.core.customCapture.CustomCaptureComponent
import com.intempt.core.customCapture.CustomCaptureService
import com.intempt.core.eventModels.IntemptEvent
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.eventPool.EventPoolManagerService
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.spy
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CustomCaptureUnitTest {
    private lateinit var customCaptureSrv: CustomCaptureService
   // private lateinit var customCapture: CustomCaptureComponent
    private lateinit var config: ConfigManagerService
    private lateinit var context: Context
    private lateinit var eventSrv: EventPoolManagerService

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        ShadowLog.stream = System.out
        ShadowLog.clear()


        context = spy(RuntimeEnvironment.getApplication())
        config = spy(ConfigManagerService(context))
        customCaptureSrv = spy(CustomCaptureService())
    }



    @Test
    fun `should receive event of type identify`()  = runTest  {
        val testDispatcher = StandardTestDispatcher(testScheduler)

        val eventPoolSrv = EventPoolManagerService(config, dispatcher = testDispatcher)
        val srv = CustomCaptureComponent(customCaptureSrv, config, eventPoolSrv)
        srv.identify(
            "test_userID",
            "test_eventTitle",
            mapOf("test" to "test"),
            mapOf("test" to "test")
        )

        testScheduler.advanceUntilIdle()

        val actualEventType = eventPoolSrv.lastEvent?.getEventType()
        val expectedEventType = "identify"

        assertEquals(
             "Expected event of type 'identify'",
             expectedEventType,
             actualEventType
         )

    }

    @Test
    fun `should receive event of type group`()  = runTest  {
        val testDispatcher = StandardTestDispatcher(testScheduler)

        val eventPoolSrv = EventPoolManagerService(config, dispatcher = testDispatcher)
        val srv = CustomCaptureComponent(customCaptureSrv, config, eventPoolSrv)
        srv.group(
            "test_accountID",
            "test_eventTitle",
            mapOf("test" to "test"),
        )

        testScheduler.advanceUntilIdle()

        val actualEventType = eventPoolSrv.lastEvent?.getEventType()
        val expectedEventType = "group"

        assertEquals(
            "Expected event of type 'group'",
            expectedEventType,
            actualEventType
        )
    }

    @Test
    fun `should receive event of type track`()  = runTest  {
        val testDispatcher = StandardTestDispatcher(testScheduler)

        val eventPoolSrv = EventPoolManagerService(config, dispatcher = testDispatcher)
        val srv = CustomCaptureComponent(customCaptureSrv, config, eventPoolSrv)

        srv.track(
            "test_TrackTitle",
            mapOf("test" to "test"),
        )

        testScheduler.advanceUntilIdle()

        val actualEventType = eventPoolSrv.lastEvent?.getEventType()
        val expectedEventType = "track"

        assertEquals(
            "Expected event of type 'track'",
            expectedEventType,
            actualEventType
        )
    }

    @Test
    fun `should receive event of type record`()  = runTest  {
        val testDispatcher = StandardTestDispatcher(testScheduler)

        val eventPoolSrv = EventPoolManagerService(config, dispatcher = testDispatcher)
        val srv = CustomCaptureComponent(customCaptureSrv, config, eventPoolSrv)

        srv.record(
            "test_RecordTitle",
            data = mapOf("test" to "test"),
        )

        testScheduler.advanceUntilIdle()

        val actualEventType = eventPoolSrv.lastEvent?.getEventType()
        val expectedEventType = "record"

        assertEquals(
            "Expected event of type 'record'",
            expectedEventType,
            actualEventType
        )
    }
}