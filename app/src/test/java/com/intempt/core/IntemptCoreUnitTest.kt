package com.intempt.core

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import com.intempt.core.autocapture.installUpgradeTracker.InstallUpgradeTrackerService
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.StorageManagerService
import com.intempt.core.services.eventPool.EventPoolManagerService
import junit.framework.TestCase.assertTrue
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
import org.robolectric.Shadows
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

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        ShadowLog.stream = System.out
        ShadowLog.clear()


        context = spy(RuntimeEnvironment.getApplication())
        storage = spy(StorageManagerService(context))
        config = spy(ConfigManagerService(context))
        eventSrv = spy(EventPoolManagerService(config))

        installUpgradeTrackerService = spy(
            InstallUpgradeTrackerService(
                context,
                eventSrv,
                storage
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
    fun testInitializationOrder() {
        val expectedLogs = listOf(
            "StorageManagerService initialized",
            "ConfigManagerService initialized",
            "EventPoolManagerService initialized",
            "SessionTrackerService initialized",
            "InstallUpgradeTrackerComponent initialized"
        )

        Intempt.initialize(context)

        Shadows.shadowOf(Looper.getMainLooper()).runToEndOfTasks()


        val allLogs = ShadowLog.getLogs().map { it.msg }

        var lastIndex = -1
        expectedLogs.forEach { expectedLog ->
            val currentIndex = allLogs.indexOf(expectedLog)

            assertTrue("Expected log not found: $expectedLog", currentIndex != -1)
            assertTrue("Logs are out of order", currentIndex > lastIndex)
            lastIndex = currentIndex
        }
    }
}