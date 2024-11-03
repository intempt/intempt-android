package com.intempt.core

import android.content.Context
import com.intempt.core.customCapture.CustomCaptureComponent
import com.intempt.core.customCapture.CustomCaptureService
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.HttpManagerService
import com.intempt.core.services.IntemptEventManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.StorageManagerService
import com.intempt.core.services.UtilsService
import com.intempt.core.services.eventPool.EventPoolManagerService
import com.intempt.core.types.StorageKeys
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNotNull
import junit.framework.TestCase.assertNull
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.spy
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
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
    private lateinit var storage: StorageManagerService
    private lateinit var component: CustomCaptureComponent
    private lateinit var eventPoolSrv: EventPoolManagerService
    private lateinit var intemptEvent: IntemptEventManagerService
    private lateinit var utils: UtilsService

    private val testScheduler = TestCoroutineScheduler()
    private lateinit var testDispatcher: TestDispatcher


    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        ShadowLog.stream = System.out
        ShadowLog.clear()


        context = spy(RuntimeEnvironment.getApplication())
        config = spy(ConfigManagerService(context))
        config.isQueueEnabled = false
        storage = spy(StorageManagerService(context))
        logger = spy(LoggerManagerService(config))
        httpSrv = spy(HttpManagerService(config, logger))
        customCaptureSrv = spy(CustomCaptureService(storage, logger))
        utils = spy(UtilsService(logger))
        intemptEvent = spy(IntemptEventManagerService(context, storage, utils, config))


        testDispatcher = StandardTestDispatcher(testScheduler)


        eventPoolSrv = spy(EventPoolManagerService(
            config,
            logger,
            httpSrv,
            intemptEvent,
            dispatcher = testDispatcher
        ))

        component = CustomCaptureComponent(
            customCaptureSrv,
            config,
            eventPoolSrv,
            intemptEvent
        )
    }


    @Test
    fun `should receive event of type identify`() {
        interceptHttpRequest()

        component.identify(
            "test_userID",
            "test_eventTitle",
            mapOf("test" to "test"),
            mapOf("test" to "test")
        )

        assertLastEventType("identify")
    }

    @Test
    fun `should receive event of type group`() {
       interceptHttpRequest()
       component.group(
            "test_accountID",
            "test_eventTitle",
            mapOf("test" to "test"),
        )
        assertLastEventType("group")
    }

    @Test
    fun `should receive event of type track`() {
         interceptHttpRequest()

         component.track(
                "test_TrackTitle",
                mapOf("test" to "test")
         )

         assertLastEventType("track")
    }

    @Test
    fun `should receive event of type record`() {

        interceptHttpRequest()
        component.record(
            "test_RecordTitle",
            data = mapOf("test" to "test"),
        )

        assertLastEventType("record")
    }

    @Test
    fun `should receive event of type alias`() {
        interceptHttpRequest()

        component.alias(
            "test_userId",
             "test_anotherUserId"
        )

        assertLastEventType("alias")
    }

    @Test
    fun `should receive event of type consent`() {
        interceptHttpRequest()

        component.consent(
            "reject",
            System.currentTimeMillis() + 100000000,
            "test_email",
            "test_message",
            "test_category",
        )

        assertLastEventType("consent")
    }

    @Test
    fun `should clear storage on logout`() {
        val sessionPrefs = context.getSharedPreferences(StorageKeys.SessionPrefs.key, Context.MODE_PRIVATE)
        sessionPrefs.edit().putString("someSessionKey", "sessionData").apply()

        val appPrefs = context.getSharedPreferences(StorageKeys.AppPrefs.key, Context.MODE_PRIVATE)
        appPrefs.edit().putString("someAppKey", "appData").apply()

        val fragmentPrefs = context.getSharedPreferences(StorageKeys.FragmentPrefs.key, Context.MODE_PRIVATE)
        fragmentPrefs.edit().putString("someFragmentKey", "fragmentData").apply()

        val userPrefs = context.getSharedPreferences(StorageKeys.UserPrefs.key, Context.MODE_PRIVATE)
        userPrefs.edit().putString(StorageKeys.ProfileId.key, "profileIdData").apply()


        component.logOut()
        testScheduler.advanceUntilIdle()

        assertNull(sessionPrefs.getString("someSessionKey", null))
        assertNull(appPrefs.getString("someAppKey", null))
        assertNull(fragmentPrefs.getString("someFragmentKey", null))

        assertEquals("profileIdData", userPrefs.getString(StorageKeys.ProfileId.key, null))
    }

    @Test
    fun `on tracking blocked`() {
        `when`(config.isUserOptIn).thenReturn(false)

        component.identify("test", "test", mapOf("test" to "test"))
        component.group("test", "test", mapOf("test" to "test"))
        component.track("test", mapOf("test" to "test"))
        component.record("test", "test")
        component.alias("test", "test")
        component.consent("test", 100L, "test", "test", "test")
        component.logOut()

        testScheduler.advanceUntilIdle()

        val lastEvent = eventPoolSrv.eventsList.lastOrNull()

        assertEquals(lastEvent , null)
    }

    @Test
    fun `should change isUserOptIn value`() {
       component.optIn()
        assertTrue(config.isUserOptIn)

       component.optOut()
        assertTrue(!config.isUserOptIn)
    }

    @Test
    fun `should change isLoggingEnabled value`() {
       component.enableLogging()
        assertTrue(config.isLoggingEnabled)

       component.disableLogging()
        assertTrue(!config.isLoggingEnabled)
    }


    private fun assertLastEventType(expectedEventType: String) {
        testScheduler.advanceUntilIdle()

        val lastEvent = eventPoolSrv.eventsList.lastOrNull()


        assertNotNull(lastEvent.toString(), "Expected an event in the event queue")

        val actualEventType = lastEvent?.getEventType()

        assertEquals(
            "Expected event of type '$expectedEventType'",
            expectedEventType,
            actualEventType
        )
    }

    private fun interceptHttpRequest() = runBlocking  {
        doAnswer { invocation ->
            val url = invocation.getArgument<String>(0)
            val jsonPayload = invocation.getArgument<JSONObject>(1)

            println(jsonPayload)
            assertEquals(config.eventsUrl, url)
            assertNotNull(jsonPayload)
            println("Captured HTTP request payload: $jsonPayload")

        }.whenever(httpSrv).post(any(), any<JSONObject>(), any())
    }
}