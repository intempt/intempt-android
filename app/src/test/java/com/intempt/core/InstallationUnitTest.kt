package com.intempt.core

import android.app.Activity
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
import junit.framework.TestCase.assertTrue
import junit.framework.TestCase.fail
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doNothing
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.ByteArrayInputStream





@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest= Config.NONE
)
class InstallationUnitTest {
    private lateinit var context: Context
    private val mockAssets: AssetManager = mock(AssetManager::class.java)
    private lateinit var customCaptureSrv: CustomCaptureService
    private lateinit var config: ConfigManagerService
    private lateinit var logger: LoggerManagerService
    private lateinit var httpSrv: HttpManagerService
    private lateinit var storage: StorageManagerService
    private lateinit var component: CustomCaptureComponent
    private lateinit var eventPoolSrv: EventPoolManagerService
    private lateinit var intemptEvent: IntemptEventManagerService
    private lateinit var utils: UtilsService
    private val testScheduler = TestCoroutineScheduler()
    private lateinit var testDispatcher: TestDispatcher

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
        httpSrv = spy(HttpManagerService(config, logger))
        customCaptureSrv = spy(CustomCaptureService(storage, logger))

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

    }

    @Test
    fun `should init without errors` (){
        try {
            Intempt.initialize(context)
        } catch (e: Exception) {
            fail("Initialization threw an exception: ${e.message}")
        }
    }

    @Test
    fun `should render main activity even if SDK fails to initialize`() {
        val message ="Initialization failed"
        val intempt = spy(Intempt)
        doThrow(RuntimeException(message))
        .`when`(intempt).initialize(context)

        try {
            intempt.initialize(context)
        } catch (e: Exception) {
            assertEquals(message, e.message)
        }

        verify(intempt).initialize(context)
        assertNotNull(context)
    }

    @Test
    fun `should render main activity if identify fails`(){
        doThrow(RuntimeException("Simulated error during emitEvent"))
            .`when`(eventPoolSrv).emitEvent(any())

        component.identify(
            "test_userID",
            "test_eventTitle",
            mapOf("test" to "test"),
            mapOf("test" to "test")
        )

        verify(eventPoolSrv).emitEvent(any())

        assertTrue(true)
    }

    @Test
    fun `should render main activity if group fails`() {
        doThrow(RuntimeException("Simulated error during emitEvent"))
            .`when`(eventPoolSrv).emitEvent(any())

        component.group(
            "test_accountID",
            "test_eventTitle",
            mapOf("key" to "value")
        )

        verify(eventPoolSrv).emitEvent(any())
        assertTrue(true)
    }

    @Test
    fun `should render main activity if track fails`() {
        doThrow(RuntimeException("Simulated error during emitEvent"))
            .`when`(eventPoolSrv).emitEvent(any())

        component.track(
            "test_eventTitle",
            mapOf("key" to "value")
        )

        verify(eventPoolSrv).emitEvent(any())
        assertTrue(true)
    }

    @Test
    fun `should render main activity if record fails`() {
        doThrow(RuntimeException("Simulated error during emitEvent"))
            .`when`(eventPoolSrv).emitEvent(any())

        component.record(
            "test_eventTitle",
            "test_accountID",
            "test_userID",
            mapOf("accountKey" to "accountValue"),
            mapOf("userKey" to "userValue"),
            mapOf("dataKey" to "dataValue")
        )

        verify(eventPoolSrv).emitEvent(any())
        assertTrue(true)
    }

    @Test
    fun `should render main activity if alias fails`() {
        doThrow(RuntimeException("Simulated error during emitEvent"))
            .`when`(eventPoolSrv).emitEvent(any())

        component.alias(
            "test_userID",
            "test_anotherUserID"
        )

        verify(eventPoolSrv).emitEvent(any())
        assertTrue(true)
    }

    @Test
    fun `should render main activity if consent fails`() {
        doThrow(RuntimeException("Simulated error during emitEvent"))
            .`when`(eventPoolSrv).emitEvent(any())

        component.consent(
            "accept",
            1234567890L,
            "test@example.com",
            "test_message",
            "test_category"
        )

        verify(eventPoolSrv).emitEvent(any())
        assertTrue(true)
    }

    @Test
    fun `should render main activity if productAdd fails`() {
        doThrow(RuntimeException("Simulated error during emitEvent"))
            .`when`(eventPoolSrv).emitEvent(any())

        component.productAdd(
            "test_productID",
            3
        )

        verify(eventPoolSrv).emitEvent(any())
        assertTrue(true)
    }

    @Test
    fun `should render main activity if productOrdered fails`() {
        doThrow(RuntimeException("Simulated error during emitEvent"))
            .`when`(eventPoolSrv).emitEvent(any())

        component.productOrdered(
            listOf(
                mapOf("productId" to "test_productID", "quantity" to 3),
                mapOf("productId" to "another_productID", "quantity" to 2)
            )
        )

        verify(eventPoolSrv).emitEvent(any())
        assertTrue(true)
    }

    @Test
    fun `should render main activity if productView fails`() {
        doThrow(RuntimeException("Simulated error during emitEvent"))
            .`when`(eventPoolSrv).emitEvent(any())

        component.productView(
            "test_productID"
        )

        verify(eventPoolSrv).emitEvent(any())
        assertTrue(true)
    }

    @Test
    fun `should render main activity if logOut fails`() {
        doThrow(RuntimeException("Simulated error during logOut"))
            .`when`(customCaptureSrv).logoutHandler()

        component.logOut()

        verify(customCaptureSrv).logoutHandler()
        assertTrue(true)
    }







}