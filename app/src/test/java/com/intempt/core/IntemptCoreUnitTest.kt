package com.intempt.core

import android.content.Context
import android.content.SharedPreferences
import com.intempt.core.autocapture.installUpgradeTracker.InstallUpgradeTrackerService
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.HttpManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.StorageManagerService
import com.intempt.core.services.UtilsService
import com.intempt.core.services.eventPool.EventPoolManagerService
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito.spy
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.anyOrNull
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IntemptCoreUnitTest {


    @Mock()
    private lateinit var mockSharedPreferences : SharedPreferences


    private lateinit var installUpgradeTrackerService: InstallUpgradeTrackerService
    private lateinit var storage: StorageManagerService
    private lateinit var eventSrv: EventPoolManagerService
    private lateinit var config: ConfigManagerService
    private lateinit var context: Context
    private lateinit var logger: LoggerManagerService
    private lateinit var httpSrv: HttpManagerService
    private lateinit var utils: UtilsService

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
        eventSrv = spy(EventPoolManagerService(config, logger, httpSrv))
        utils = spy(UtilsService(logger))


        installUpgradeTrackerService = spy(
            InstallUpgradeTrackerService(
                context,
                eventSrv,
                storage,
                logger,
                utils
            )
        )

        `when`(installUpgradeTrackerService.getConsumerAppVersionCode()).thenReturn(1)

        val key="user_prefs"
        val mode = Context.MODE_PRIVATE

        `when`(
            context.getSharedPreferences(key, mode)
        ).thenReturn(mockSharedPreferences)

        `when`(mockSharedPreferences.getString(eq("ProfileId"), anyOrNull())).thenReturn(null)
    }

    @Test
    fun `should initialize services in order`() {
//        val expectedLogs = listOf(
//            "StorageManagerService initialized",
//            //"ConfigManagerService initialized",
//            "EventPoolManagerService initialized",
//            "SessionTrackerService initialized",
//            "InstallUpgradeTrackerComponent initialized"
//        )
//
//        Shadows.shadowOf(Looper.getMainLooper()).runToEndOfTasks()
//
//
//        val allLogs = ShadowLog.getLogs().map { it.msg }
//
//        var lastIndex = -1
//        expectedLogs.forEach { expectedLog ->
//            val currentIndex = allLogs.indexOf(expectedLog)
//
//            assertTrue("Expected log not found: $expectedLog", currentIndex != -1)
//            assertTrue("Logs are out of order", currentIndex > lastIndex)
//            lastIndex = currentIndex
//        }
    }






}