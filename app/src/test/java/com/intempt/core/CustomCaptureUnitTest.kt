package com.intempt.core

import android.content.Context
import android.content.SharedPreferences
import android.content.res.AssetManager
import android.view.View
import android.widget.CheckBox
import android.widget.DatePicker
import android.widget.EditText
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RatingBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.TimePicker
import android.widget.ToggleButton
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
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
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
                "INTEMPT_API_KEY": "1c75007239cf420c929602f74b872906.95084d4f10804fc3b4820dfae579a0a7",
                "INTEMPT_SOURCE_ID": "1430727403234930688",
                "INTEMPT_ORGANIZATION_ID": "intempt_internal_use_only",
                "INTEMPT_PROJECT_ID": "intempt_android"
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
    private val mockProfId = "prof_8fde9691-9040-4bbf-bae6-9ca5567f62d0"
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
        `when`(mockEditor.clear()).thenReturn(mockEditor)
        `when`(mockSharedPreferences.getString(eq(StorageKeys.ProfileId.key), anyOrNull())).thenReturn(mockProfId)
        `when`(mockSharedPreferences.getString(eq(StorageKeys.SessionId.key), anyOrNull())).thenReturn(mockedSesId)
        `when`(mockSharedPreferences.getString(eq(StorageKeys.PageId.key), anyOrNull())).thenReturn(mockedPagId)
        `when`(mockEditor.putString(anyString(), anyString())).thenReturn(mockEditor)
        `when`(mockEditor.putInt(anyString(), anyInt())).thenReturn(mockEditor)
        `when`(mockEditor.putBoolean(anyString(), anyBoolean())).thenReturn(mockEditor)

        doNothing().`when`(mockEditor).apply()

        `when`(mockSharedPreferences.edit()).thenReturn(mockEditor)


        config = spy(ConfigManagerService(context))
        logger = spy(LoggerManagerService(config))
        utils = spy(UtilsService(logger))

        storage = spy(StorageManagerService(context, utils))

        doReturn(mockProfId).`when`(storage).getProfileId()

        httpSrv = spy(HttpManagerService(config, logger))
        customCaptureSrv = CustomCaptureService(storage, logger)

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
            intemptEvent,
            utils
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

    @Test
    fun `should receive event of type productAdd`() = runTest {
        interceptConsentHttpRequest()

        component.productAdd(
            "prt1231231231_23",
            1

        )
    }

    @Test
    fun `should receive event of type productView`() = runTest {
        interceptConsentHttpRequest()

        component.productView("productView_id")
    }

    @Test
    fun `should receive event of type productOrdered`() = runTest {
        interceptConsentHttpRequest()

        component.productOrdered(
           listOf(
              mapOf( "productId" to "prt1231231231_23", "quantity" to 1),
              mapOf( "productId" to "sdfgdfgdfg1234234", "quantity" to 45)
           )

        )
    }

    @Test
    fun `should clear storage on logout`() {
        val sessionPrefs = context.getSharedPreferences(StorageKeys.SessionPrefs.key, Context.MODE_PRIVATE)
        sessionPrefs.edit().putString("someSessionKey", mockedSesId).apply()

        val appPrefs = context.getSharedPreferences(StorageKeys.AppPrefs.key, Context.MODE_PRIVATE)
        appPrefs.edit().putString("someAppKey", "appData").apply()

        val fragmentPrefs = context.getSharedPreferences(StorageKeys.FragmentPrefs.key, Context.MODE_PRIVATE)
        fragmentPrefs.edit().putString("someFragmentKey", "fragmentData").apply()

        val userPrefs = context.getSharedPreferences(StorageKeys.UserPrefs.key, Context.MODE_PRIVATE)
        userPrefs.edit().putString(StorageKeys.ProfileId.key, mockProfId).apply()


        component.logOut()
        testScheduler.advanceUntilIdle()

        assertNull(sessionPrefs.getString("someSessionKey", null))
        assertNull(appPrefs.getString("someAppKey", null))
        assertNull(fragmentPrefs.getString("someFragmentKey", null))

        assertEquals(mockProfId, userPrefs.getString(StorageKeys.ProfileId.key, null))
    }

    @Test
    fun `should add intemptDoNotCapture tag`(){

        val viewsToTest = listOf(
            EditText(context),
            Spinner(context),
            ToggleButton(context),
            CheckBox(context),
            RadioButton(context),
            TextView(context),
            SeekBar(context),
            RatingBar(context),
            TimePicker(context),
            DatePicker(context),
            ListView(context)
        )

        val unsupportedView = View(context)

        viewsToTest.forEach { view ->
            component.doNotCaptureText(view)
            // Check that the tag was set
            assertEquals(true, view.getTag(R.id.intemptDoNotCapture))
        }

        component.doNotCaptureText(unsupportedView)

        assertNull(unsupportedView.getTag(R.id.intemptDoNotCapture))

        val messageCaptor = argumentCaptor<String>()
        verify(logger, atLeastOnce()).error(messageCaptor.capture())

        // Verify that the specific error message is present in the captured logs
        assertTrue(messageCaptor.allValues.any { it.trim() == "Can't accept view of type ${unsupportedView.javaClass.name}. Supported types are: EditText, Spinner, ToggleButton, CheckBox, RadioButton, CompoundButton, TextView, SeekBar, RatingBar, TimePicker, DatePicker, ListView.".trim() })

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

//    @Test
    fun `should call recommendation API`() = runTest {
        val id = "246";
        val quantity = 3;
        val fields = listOf("id","price")
        val productId = "26701";

        val res = component.recommendation(
            id = id,
            quantity = quantity,
            fields = fields,
            productId = productId
        )

    assertNotNull(res)
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