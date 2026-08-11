package com.intempt.core

import android.content.Context
import android.content.SharedPreferences
import android.content.res.AssetManager
import com.intempt.core.modifications.ModificationComponent
import com.intempt.core.modifications.ModificationsService
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.HttpManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.StorageManagerService
import com.intempt.core.services.UtilsService
import com.intempt.core.types.StorageKeys
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import junit.framework.TestCase.assertNotNull
import junit.framework.TestCase.assertNull
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doNothing
import org.mockito.Mockito.mock
import org.mockito.Mockito.spy
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.ByteArrayInputStream


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModificationsUnitTest {
    private lateinit var context: Context
    private lateinit var storage: StorageManagerService
    private lateinit var config: ConfigManagerService
    private lateinit var logger: LoggerManagerService
    private lateinit var httpSrv: HttpManagerService
    private lateinit var utils: UtilsService
    private lateinit var modComponent: ModificationComponent
    private lateinit var modSrv: ModificationsService

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

    private lateinit var apiUrl: String
    private val captor = argumentCaptor<JSONObject>()
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

        storage = spy(StorageManagerService(context,utils))
        httpSrv = spy(HttpManagerService(config, logger))
        modSrv = spy(ModificationsService(storage, config, logger, httpSrv, utils))
        modComponent = ModificationComponent(modSrv)
        apiUrl = config.optimizationUrl
    }


    @Test
    fun `should init handlers`() {
       assertNotNull(modComponent.experimentHandler)
       assertNotNull(modComponent.personalizationHandler)
    }

    @Test
    fun `should call experiment modification by name`() = runTest {
        // httpSrv is a spy, so whenever(httpSrv.post(...)) would invoke the REAL post
        // while setting the stub up — firing an actual HTTP call whose failure lands on
        // a coroutine and is reported against the next test as
        // UncaughtExceptionsBeforeTest. doReturn().whenever() never calls the real
        // method. The four error-path tests below already used this form, which is why
        // they were the only ones passing.
        doReturn(null).whenever(httpSrv).post(any(), any(), any())

        modComponent.experimentHandler.getByName(listOf("test_experiment_name"))

        verify(httpSrv).post(eq(apiUrl), captor.capture(), any())
    }

    @Test
    fun `should call experiment modification by group`() = runTest {
        // httpSrv is a spy, so whenever(httpSrv.post(...)) would invoke the REAL post
        // while setting the stub up — firing an actual HTTP call whose failure lands on
        // a coroutine and is reported against the next test as
        // UncaughtExceptionsBeforeTest. doReturn().whenever() never calls the real
        // method. The four error-path tests below already used this form, which is why
        // they were the only ones passing.
        doReturn(null).whenever(httpSrv).post(any(), any(), any())

        modComponent.experimentHandler.getByGroup(listOf("test_experiment_name"))

        verify(httpSrv).post(eq(apiUrl), any<JSONObject>(), any())
    }


    @Test
    fun `should call personalization modification by name`() = runTest {
        // httpSrv is a spy, so whenever(httpSrv.post(...)) would invoke the REAL post
        // while setting the stub up — firing an actual HTTP call whose failure lands on
        // a coroutine and is reported against the next test as
        // UncaughtExceptionsBeforeTest. doReturn().whenever() never calls the real
        // method. The four error-path tests below already used this form, which is why
        // they were the only ones passing.
        doReturn(null).whenever(httpSrv).post(any(), any(), any())

        modComponent.personalizationHandler.getByName(listOf("test_experiment_name"))

        verify(httpSrv).post(eq(apiUrl), any<JSONObject>(), any())
    }


    @Test
    fun `should call personalization modification by group`() = runTest {
        // This test never stubbed post at all, so the spy called the real method and
        // attempted a live HTTP request — the same leak as the other three, by omission
        // rather than by using the wrong stubbing form.
        doReturn(null).whenever(httpSrv).post(any(), any(), any())

        modComponent.personalizationHandler.getByGroup(listOf("test_experiment_name"))

        verify(httpSrv).post(eq(apiUrl), any<JSONObject>(), any())
    }


    @Test
    fun `should return null on experiment modification by name call error`() = runTest {


        doThrow(RuntimeException("Network error")).whenever(httpSrv).post(eq(apiUrl), any<JSONObject>(), any())

        val response = modComponent.experimentHandler.getByName(listOf("test_experiment_name"))

        verify(httpSrv).post(eq(apiUrl), any<JSONObject>(), any())

        assertNull(response)
    }


    @Test
    fun `should return null on experiment modification by group call error`() = runTest {


        doThrow(RuntimeException("Network error")).whenever(httpSrv).post(eq(apiUrl), any<JSONObject>(), any())

        val response = modComponent.experimentHandler.getByGroup(listOf("test_experiment_name"))

        verify(httpSrv).post(eq(apiUrl), any<JSONObject>(), any())

        assertNull(response)
    }

    @Test
    fun `should return null on personalization modification by name call error`() = runTest {


        doThrow(RuntimeException("Network error")).whenever(httpSrv).post(eq(apiUrl), any<JSONObject>(), any())

        val response = modComponent.personalizationHandler.getByName(listOf("test_experiment_name"))

        verify(httpSrv).post(eq(apiUrl), any<JSONObject>(), any())

        assertNull(response)
    }

    @Test
    fun `should return null on personalization modification by group call error`() = runTest {

        doThrow(RuntimeException("Network error")).whenever(httpSrv).post(eq(apiUrl), any<JSONObject>(), any())

        val response = modComponent.personalizationHandler.getByGroup(listOf("test_experiment_name"))

        verify(httpSrv).post(eq(apiUrl), any<JSONObject>(), any())

        assertNull(response)
    }

}