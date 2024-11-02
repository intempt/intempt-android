package com.intempt.core

import android.content.Context
import com.intempt.core.customCapture.CustomCaptureComponent
import com.intempt.core.customCapture.CustomCaptureService
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.HttpManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.eventPool.EventPoolManagerService
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.spy
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CustomCaptureUnitTest {
    private lateinit var customCaptureSrv: CustomCaptureService
    private lateinit var config: ConfigManagerService
    private lateinit var logger: LoggerManagerService
    private lateinit var httpSrv: HttpManagerService
    private lateinit var context: Context


    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        ShadowLog.stream = System.out
        ShadowLog.clear()


        context = spy(RuntimeEnvironment.getApplication())
        config = spy(ConfigManagerService(context))
        logger = spy(LoggerManagerService(config))
        httpSrv = spy(HttpManagerService(config, logger))
        customCaptureSrv = spy(CustomCaptureService(logger))
    }





    @Test
    fun `should receive event of type identify`()  = runTest  {
        val testDispatcher = StandardTestDispatcher(testScheduler)

        val eventPoolSrv = EventPoolManagerService(config,logger,httpSrv, dispatcher = testDispatcher)
        val srv = CustomCaptureComponent(customCaptureSrv, config, eventPoolSrv,logger)

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

        val eventPoolSrv = EventPoolManagerService(config,logger, httpSrv, dispatcher = testDispatcher)
        val srv = CustomCaptureComponent(customCaptureSrv, config, eventPoolSrv,logger)

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

        val eventPoolSrv = EventPoolManagerService(config,logger,httpSrv, dispatcher = testDispatcher)
        val srv = CustomCaptureComponent(customCaptureSrv, config, eventPoolSrv,logger)

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

        val eventPoolSrv = EventPoolManagerService(config,logger,httpSrv, dispatcher = testDispatcher)
        val srv = CustomCaptureComponent(customCaptureSrv, config, eventPoolSrv,logger)

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

    @Test
    fun `should receive event of type alias`()  = runTest  {
        val testDispatcher = StandardTestDispatcher(testScheduler)

        val eventPoolSrv = EventPoolManagerService(config,logger,httpSrv, dispatcher = testDispatcher)
        val srv = CustomCaptureComponent(customCaptureSrv, config, eventPoolSrv,logger)

        srv.alias(
            "test_userId",
             "test_anotherUserId"
        )

        testScheduler.advanceUntilIdle()

        val actualEventType = eventPoolSrv.lastEvent?.getEventType()
        val expectedEventType = "alias"

        assertEquals(
            "Expected event of type 'alias'",
            expectedEventType,
            actualEventType
        )
    }

    @Test
    fun `should receive event of type consent`()  = runTest  {
        val testDispatcher = StandardTestDispatcher(testScheduler)

        val eventPoolSrv = EventPoolManagerService(config,logger,httpSrv, dispatcher = testDispatcher)
        val srv = CustomCaptureComponent(customCaptureSrv, config, eventPoolSrv,logger)

        srv.consent(
            "reject",
            System.currentTimeMillis() + 100000000,
            "test_email",
            "test_message",
            "test_category",
        )

        testScheduler.advanceUntilIdle()

        val actualEventType = eventPoolSrv.lastEvent?.getEventType()
        val expectedEventType = "consent"

        assertEquals(
            "Expected event of type 'consent'",
            expectedEventType,
            actualEventType
        )
    }

    @Test
    fun `should receive event of type logOut`()  = runTest  {
        val testDispatcher = StandardTestDispatcher(testScheduler)

        val eventPoolSrv = EventPoolManagerService(config,logger,httpSrv, dispatcher = testDispatcher)
        val srv = CustomCaptureComponent(customCaptureSrv, config, eventPoolSrv,logger)

        srv.logOut()

        testScheduler.advanceUntilIdle()

        val actualEventType = eventPoolSrv.lastEvent?.getEventType()
        val expectedEventType = "logOut"

        assertEquals(
            "Expected event of type 'logOut'",
            expectedEventType,
            actualEventType
        )
    }

    @Test
    fun `on tracking blocked`()  = runTest  {
        val testDispatcher = StandardTestDispatcher(testScheduler)

        val eventPoolSrv = EventPoolManagerService(config,logger,httpSrv, dispatcher = testDispatcher)
        val srv = CustomCaptureComponent(customCaptureSrv, config, eventPoolSrv,logger)

        `when`(config.isUserOptIn).thenReturn(false)

        srv.identify("test", "test", mapOf("test" to "test"))
        srv.group("test", "test", mapOf("test" to "test"))
        srv.track("test", mapOf("test" to "test"))
        srv.record("test", "test")
        srv.alias("test", "test")
        srv.consent("test", 100L, "test", "test", "test")
        srv.logOut()

        testScheduler.advanceUntilIdle()

        val actualEventType = eventPoolSrv.lastEvent?.getEventType()
        val expectedEventType = null

        assertEquals(
            "Events should not be sent",
            expectedEventType,
            actualEventType
        )
    }

    @Test
    fun `should change isUserOptIn value`() {
        val eventPoolSrv = EventPoolManagerService(config, logger,httpSrv)
        val srv = CustomCaptureComponent(customCaptureSrv, config, eventPoolSrv,logger)

        srv.optIn()
        assertTrue(config.isUserOptIn)

        srv.optOut()
        assertTrue(!config.isUserOptIn)
    }

    @Test
    fun `should change isLoggingEnabled value`() {
        val eventPoolSrv = EventPoolManagerService(config, logger,httpSrv)
        val srv = CustomCaptureComponent(customCaptureSrv, config, eventPoolSrv,logger)

        srv.enableLogging()
        assertTrue(config.isLoggingEnabled)

        srv.disableLogging()
        assertTrue(!config.isLoggingEnabled)
    }
}