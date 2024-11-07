package com.intempt.core

import android.content.Context
import com.intempt.core.modifications.ModificationComponent
import com.intempt.core.modifications.ModificationsService
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.HttpManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.StorageManagerService
import com.intempt.core.services.UtilsService
import io.ktor.client.statement.HttpResponse
import junit.framework.TestCase.assertNotNull
import junit.framework.TestCase.assertNull
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.spy
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
class ModificationsUnitTest {
    private lateinit var context: Context
    private lateinit var storage: StorageManagerService
    private lateinit var config: ConfigManagerService
    private lateinit var logger: LoggerManagerService
    private lateinit var httpSrv: HttpManagerService
    private lateinit var utils: UtilsService
    private lateinit var modComponent: ModificationComponent
    private lateinit var modSrv: ModificationsService

    private lateinit var apiUrl: String
    @Before
    fun setUp() {
        ShadowLog.stream = System.out
        ShadowLog.clear()

        context = spy(RuntimeEnvironment.getApplication())
        config = spy(ConfigManagerService(context))
        storage = spy(StorageManagerService(context))
        logger = spy(LoggerManagerService(config))
        httpSrv = spy(HttpManagerService(config, logger))
        utils = spy(UtilsService(logger))

        modSrv = spy(ModificationsService(storage, config, logger, httpSrv, utils))
        modComponent = ModificationComponent(modSrv)

        apiUrl = "${config.optimizationUrl}?apiKey=${config.apiKey}";
    }


    @Test
    fun `should init handlers`() {
       assertNotNull(modComponent.experimentHandler)
       assertNotNull(modComponent.personalizationHandler)
    }

    @Test
    fun `should call experiment modification by name`() = runTest {
        whenever(httpSrv.post(any(), any(), any())).thenReturn(mock(HttpResponse::class.java))

        modComponent.experimentHandler.getByName(listOf("test_experiment_name"))

        verify(httpSrv).post(eq(apiUrl), any<JSONObject>(), any())
    }

    @Test
    fun `should call experiment modification by group`() = runTest {
        whenever(httpSrv.post(any(), any(), any())).thenReturn(mock(HttpResponse::class.java))

        modComponent.experimentHandler.getByGroup(listOf("test_experiment_name"))

        verify(httpSrv).post(eq(apiUrl), any<JSONObject>(), any())
    }


    @Test
    fun `should call personalization modification by name`() = runTest {
        whenever(httpSrv.post(any(), any(), any())).thenReturn(mock(HttpResponse::class.java))

        modComponent.personalizationHandler.getByName(listOf("test_experiment_name"))

        verify(httpSrv).post(eq(apiUrl), any<JSONObject>(), any())
    }


    @Test
    fun `should call personalization modification by group`() = runTest {
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