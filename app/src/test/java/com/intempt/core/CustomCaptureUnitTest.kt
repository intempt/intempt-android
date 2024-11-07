package com.intempt.core

import android.content.Context
import android.content.SharedPreferences
import android.content.res.AssetManager
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
import junit.framework.TestCase.fail
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doNothing
import org.mockito.Mockito.mock
import org.mockito.Mockito.spy
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.ByteArrayInputStream




@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest=Config.NONE
)
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

    private val mockAssets: AssetManager = mock(AssetManager::class.java)

    private val jsonConfig = """
        {
            "auth": {
                "INTEMPT_API_KEY": "9643576a2cfa47729a1eb63213074e78.1a4f98ffc8f648d3a4c8455a2041cae5",
                "INTEMPT_SOURCE_ID": "687499928542224384",
                "INTEMPT_ORGANIZATION_ID": "intempt2",
                "INTEMPT_PROJECT_ID": "intempt2_project"
            },
            "options": {
                "isLoggingEnabled": true,
                "isTouchEnabled": true,
                "isTextCaptureEnabled": true,
                "isQueueEnabled": false,
                "isAutoCaptureEnabled": true,
                "itemsInQueue": 5,
                "timeBuffer": 5000
            }
        }
    """.trimIndent()

    private val mockProfId = "prof_test_id_123456"
    private val mockedSesId = "ses_test_id_123456"
    private val mockedPagId = "pag_test_id_123456"


    @OptIn(ExperimentalCoroutinesApi::class)
    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        ShadowLog.stream = System.out
        ShadowLog.clear()


        context = spy(RuntimeEnvironment.getApplication())

        val inputStream = ByteArrayInputStream(jsonConfig.toByteArray(Charsets.UTF_8))
        `when`(mockAssets.open("intempt-config.json")).thenReturn(inputStream)
        `when`(context.assets).thenReturn(mockAssets)

        val mockSharedPreferences = mock(SharedPreferences::class.java)
        val mockEditor = mock(SharedPreferences.Editor::class.java)

        `when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(mockSharedPreferences)
        `when`(mockSharedPreferences.getString(eq(StorageKeys.ProfileId.key), anyOrNull())).thenReturn(mockProfId)
        `when`(mockEditor.putString(anyString(), anyString())).thenReturn(mockEditor)
        `when`(mockEditor.putInt(anyString(), anyInt())).thenReturn(mockEditor)
        `when`(mockEditor.putBoolean(anyString(), anyBoolean())).thenReturn(mockEditor)

        doNothing().`when`(mockEditor).apply()

        // Stub SharedPreferences methods
        `when`(mockSharedPreferences.edit()).thenReturn(mockEditor)








        config = spy(ConfigManagerService(context))


        storage = spy(StorageManagerService(context))
        logger = spy(LoggerManagerService(config))
        httpSrv = spy(HttpManagerService(config, logger))
        customCaptureSrv = CustomCaptureService(storage, logger)
        utils = spy(UtilsService(logger))
        intemptEvent = spy(IntemptEventManagerService(context, storage, utils, config))


        testDispatcher = UnconfinedTestDispatcher(testScheduler)


        eventPoolSrv = spy(
            EventPoolManagerService(
                config,
                logger,
                httpSrv,
                intemptEvent,
                dispatcher = testDispatcher
            )
        )

        component = CustomCaptureComponent(
            customCaptureSrv,
            config,
            eventPoolSrv,
            intemptEvent
        )


        testScheduler.advanceUntilIdle()
    }


    @Test
    fun `should receive event of type identify`() = runTest {
        interceptTrackHttpRequest("identify")
        component.identify(
            "test_userID",
            "test_eventTitle",
            mapOf("test" to "test"),
            mapOf("test" to "test")
        )
    }

    @Test
    fun `should receive event of type group`() = runTest {
       interceptTrackHttpRequest("group")
       component.group(
            "test_accountID",
            "test_eventTitle",
            mapOf("test" to "test"),
        )
    }

    @Test
    fun `should receive event of type track`() = runTest {
         interceptTrackHttpRequest("track")
         component.track(
                "test_TrackTitle",
                mapOf("test" to "test")
         )
    }

    @Test
    fun `should receive event of type record`() {
        interceptTrackHttpRequest("record")
        component.record(
            "test_RecordTitle",
            data = mapOf("test" to "test"),
        )
    }

    @Test
    fun `should receive event of type alias`()  {
        interceptTrackHttpRequest("alias")
        component.alias(
            "test_userId",
             "test_anotherUserId"
        )



    }

    @Test
    fun `should receive event of type consent`() = runTest {
        interceptConsentHttpRequest()

        component.consent(
            "reject",
            System.currentTimeMillis() + 100000000,
            "test_email",
            "test_message",
            "Test",
        )
    }

   // @Test
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

    private fun interceptTrackHttpRequest(expectedEventType: String) = runBlocking {
        doAnswer { invocation ->
            try {
                val url = invocation.getArgument<String>(0)
                val jsonPayload = invocation.getArgument<JSONObject>(1)

                println("URL: $url")
                println("Captured HTTP request payload: $jsonPayload")
                assertNotNull("Captured HTTP request payload:", jsonPayload)
                val type = jsonPayload
                    .getJSONArray("track")
                    .getJSONObject(0)
                    .getString("type")

                assertEquals("Expected event type to be '$expectedEventType'", expectedEventType, type)


            } catch(e: Exception){
                e.printStackTrace()
                fail("An exception was thrown during the post request: ${e.message}")
            } finally {
                testScheduler.advanceUntilIdle()
            }
            testScheduler.advanceUntilIdle()
        }.whenever(httpSrv).post(any(), any<JSONObject>(), any())
    }

    private fun interceptConsentHttpRequest() = runBlocking {
        doAnswer { invocation ->
            try {
                val url = invocation.getArgument<String>(0)
                val jsonPayload = invocation.getArgument<JSONObject>(1)

                println("URL: $url")
                println("Captured HTTP request payload: $jsonPayload")
                assertNotNull("Captured HTTP request payload:", jsonPayload)

            } catch(e: Exception){
                e.printStackTrace()
                fail("An exception was thrown during the post request: ${e.message}")
            } finally {
                testScheduler.advanceUntilIdle()
            }
        }.whenever(httpSrv).post(any(), any<JSONObject>(), any())
    }
}