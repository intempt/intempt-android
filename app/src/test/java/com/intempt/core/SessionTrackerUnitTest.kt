package com.intempt.core

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import com.intempt.core.autocapture.sessionTracker.SessionTrackerService
import com.intempt.core.eventModels.IntemptEvent
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.HttpManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.StorageManagerService
import com.intempt.core.services.eventPool.EventPoolManagerService
import com.intempt.core.types.Constants
import com.intempt.core.types.StorageKeys
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.mock
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog



@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionTrackerUnitTest {
    private lateinit var context: Context
    private lateinit var storage: StorageManagerService
    private lateinit var sessionTrackerService: SessionTrackerService
    private lateinit var eventPool: EventPoolManagerService
    private lateinit var config: ConfigManagerService
    private lateinit var eventFlow: MutableSharedFlow<IntemptEvent>
    private lateinit var logger: LoggerManagerService
    private lateinit var httpSrv: HttpManagerService

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        ShadowLog.stream = System.out
        ShadowLog.clear()

        context = spy(RuntimeEnvironment.getApplication())
        config = spy(ConfigManagerService(context))

        config.isLoggingEnabled = true

        logger = spy(LoggerManagerService(config))
        storage = spy(StorageManagerService(context, logger))
        httpSrv = spy(HttpManagerService(config, logger))
        eventPool = spy(EventPoolManagerService(config, logger, httpSrv))

        eventFlow = MutableSharedFlow<IntemptEvent>()


        `when`(eventPool.eventReceiver).thenReturn(eventFlow)

        sessionTrackerService = spy(
            SessionTrackerService(
                context,
                logger,
                storage,
                eventPool,
                httpSrv
            )
        )

    }

    @Test
    fun `onInit should start new session if session is expired`() {
        sessionTrackerService.onInit()
        val expiredSessionTimestamp = System.currentTimeMillis() - (Constants.SESSION.SESSION_TIMEOUT + 1000)
        val sessionId = "test_session_id"

        `when`(
            storage.getStorageItem<Long>(
                eq(StorageKeys.SessionPrefs.key),
                eq(StorageKeys.SessionTimestamp.key),
                anyLong(),
                any<SharedPreferences.(String, Long?) -> Long?>()  ?: { _, _ -> expiredSessionTimestamp }
            )
        ).thenReturn(expiredSessionTimestamp)

        `when`(
            storage.getStorageItem<String>(
                eq(StorageKeys.SessionPrefs.key),
                eq(StorageKeys.SessionId.key),
                anyString(),
                any<SharedPreferences.(String, String?) -> String?>()  ?: { _, _ -> sessionId }
            )
        ).thenReturn(sessionId)


        verify(storage,  atLeastOnce()).setStorageItem(
            eq(StorageKeys.SessionPrefs.key),
            eq(StorageKeys.SessionTimestamp.key),
            anyLong(),
            any<SharedPreferences.Editor.(String, Long) -> Unit>()
        )
        verify(storage, atLeastOnce()).setStorageItem(
            eq(StorageKeys.SessionPrefs.key),
            eq(StorageKeys.SessionId.key),
            anyString(),
            any<SharedPreferences.Editor.(String, String) -> Unit>()
        )

    }


    @Test
    fun `onInit should not start new session if session is active`() {

        val activeSessionTimestamp = System.currentTimeMillis() + 1000

        `when`(sessionTrackerService.getSessionTime()).thenReturn(activeSessionTimestamp)

        sessionTrackerService.onInit()


        val allLogs = ShadowLog.getLogs().map { it.msg.trim() }

        assertTrue(allLogs.any { it.contains("Session is active".trim()) })
        assertFalse(allLogs.any { it.contains("Store session id".trim()) })
    }

    @Test
    fun `subscribe to event receiver should collect events`() = runTest {
        sessionTrackerService.subscribeToEventReceiver()
        val mockEvent = mock(IntemptEvent::class.java)
        `when`(mockEvent.getEventType()).thenReturn("test_event")

        eventFlow.emit(mockEvent)
        verify(eventPool, atLeastOnce()).eventReceiver

        Shadows.shadowOf(Looper.getMainLooper()).runToEndOfTasks()
        val allLogs = ShadowLog.getLogs().map { it.msg.trim() }


//        assertTrue(allLogs.any { it.contains("eventReceiver $mockEvent".trim()) })
//        assertTrue(allLogs.any { it.contains("getEventType test_event".trim()) })

    }

    @Test
    fun `validateSession should start new session if event occurs after timeout`() = runTest {
        val expiredSessionTimestamp = System.currentTimeMillis() - (Constants.SESSION.SESSION_TIMEOUT + 1000)

        `when`(
            storage.getStorageItem<Long>(
                eq(StorageKeys.SessionPrefs.key),
                eq(StorageKeys.SessionTimestamp.key),
                anyLong(),
                any<SharedPreferences.(String, Long?) -> Long?>()  ?: { _, _ -> expiredSessionTimestamp }
            )
        ).thenReturn(expiredSessionTimestamp)

        val mockEvent = mock(IntemptEvent::class.java)
        `when`(mockEvent.getEventTimestamp()).thenReturn(System.currentTimeMillis())

        sessionTrackerService.subscribeToEventReceiver()
        sessionTrackerService.onInit()



        eventFlow.emit(mockEvent)

        verify(eventPool,  atLeastOnce()).eventReceiver

        verify(storage,  atLeastOnce() ).setStorageItem(
            eq(StorageKeys.SessionPrefs.key),
            eq(StorageKeys.SessionId.key),
            anyString(),
            any()
        )
        verify(storage, atLeastOnce() ).setStorageItem(
            eq(StorageKeys.SessionPrefs.key),
            eq(StorageKeys.SessionTimestamp.key),
            anyLong(),
            any()
        )
    }


    @Test
    fun `runSessionStart should fetch location info and dispatch event`() = runTest {
        val expiredSessionTimestamp = System.currentTimeMillis() - (Constants.SESSION.SESSION_TIMEOUT + 1000)
        val testDispatcher = StandardTestDispatcher(testScheduler)

        sessionTrackerService = spy(
            SessionTrackerService(
                context,
                logger,
                storage,
                eventPool,
                httpSrv,
                dispatcher = testDispatcher
            )
        )

        `when`(sessionTrackerService.getSessionTime()).thenReturn(expiredSessionTimestamp)

        sessionTrackerService.onInit()

        testScheduler.advanceUntilIdle()

        verify(sessionTrackerService,  atLeastOnce()).getLocationInfo()

    }
}

