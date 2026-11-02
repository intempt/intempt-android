@file:OptIn(com.intempt.core.internal.InternalIntemptApi::class)

package com.intempt.core

import android.content.Context
import com.intempt.core.autocapture.installUpgradeTracker.InstallUpgradeTrackerComponent
import com.intempt.core.autocapture.installUpgradeTracker.InstallUpgradeTrackerService
import com.intempt.core.queue.DeliveryMessages
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.HttpManagerService
import com.intempt.core.services.IntemptEventManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.StorageManagerService
import com.intempt.core.services.UtilsService
import com.intempt.core.services.eventPool.EventPoolManagerService
import com.intempt.push.PushModuleEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.timeout
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * INT-5964: a token announced while the user is opted out must not count as announced.
 *
 * The opt-out drop happens in [EventPoolManagerService.dispatchEvent], one layer below the dedupe,
 * and the dedupe recorded the token as registered regardless. After opting back in the same token
 * was skipped as a duplicate on every launch until FCM happened to rotate it, so the device stayed
 * unreachable by push. Real config, event pool and storage: the drop and the dedupe have to meet
 * for the defect to show, and a mocked pool cannot drop anything.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PushTokenOptOutTest {
    private lateinit var config: ConfigManagerService
    private lateinit var delivery: DeliveryMessages
    private lateinit var srv: InstallUpgradeTrackerService
    private lateinit var component: InstallUpgradeTrackerComponent

    private companion object {
        const val TOKEN = "fcm-token-aaa"
        const val WAIT_MS = 5_000L
    }

    @Before
    fun setUp() {
        PushModuleEntryPoint.token = TOKEN
        val context: Context = RuntimeEnvironment.getApplication()
        config = ConfigManagerService(context)
        val logger = LoggerManagerService(config)
        val utils = UtilsService(logger)
        val storage = StorageManagerService(context, utils, Dispatchers.Unconfined)
        storage.ensureProfileId()
        val intemptEvent = IntemptEventManagerService(context, storage, utils, config)
        delivery = mock()
        val eventPool =
            EventPoolManagerService(
                config,
                logger,
                HttpManagerService(config, logger),
                intemptEvent,
                delivery,
                dispatcher = Dispatchers.Unconfined,
            )
        srv = InstallUpgradeTrackerService(context, eventPool, storage, logger, utils)
        component = InstallUpgradeTrackerComponent(srv = srv, dispatcher = UnconfinedTestDispatcher())
    }

    private fun carriesToken(event: JSONObject): Boolean = event.toString().contains(TOKEN)

    @Test
    fun `a token dropped while opted out is not recorded as registered`() =
        runTest {
            config.isUserOptIn = false

            component.registerPushToken()

            assertNull("the event never left, so the token was never announced", srv.getStoredPushToken())
            verify(delivery, never()).enqueueEvent(any())
        }

    @Test
    fun `after opting back in the same token is announced`() =
        runTest {
            config.isUserOptIn = false
            component.registerPushToken()

            config.isUserOptIn = true
            component.registerPushToken()

            verify(delivery, timeout(WAIT_MS)).enqueueEvent(argThat { carriesToken(this) })
            assertEquals(TOKEN, srv.getStoredPushToken())
        }
}
