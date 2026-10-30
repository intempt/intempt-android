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
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.timeout
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * INT-5964 review: the token re-registration after logOut()/reset()/optIn() is an "App
 * install/upgrade" event, the one install journeys trigger on, but no install happened. It went out
 * with no `reason`, so the documented journey filter (exclude events where `reason` is set) let it
 * through and install journeys fired for the new anonymous profile on every logout.
 *
 * Real config, pool and event builder down to the durable queue's input, so the asserted `reason`
 * is the one on the wire, not one a mock was told to return.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PushTokenReRegistrationReasonTest {
    private lateinit var delivery: DeliveryMessages
    private lateinit var component: InstallUpgradeTrackerComponent

    private companion object {
        const val TOKEN = "fcm-token-rereg"
        const val WAIT_MS = 5_000L
    }

    @Before
    fun setUp() {
        PushModuleEntryPoint.token = TOKEN
        val context: Context = RuntimeEnvironment.getApplication()
        val config = ConfigManagerService(context)
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
        val srv = InstallUpgradeTrackerService(context, eventPool, storage, logger, utils)
        component = InstallUpgradeTrackerComponent(srv = srv, dispatcher = UnconfinedTestDispatcher())
    }

    private fun queuedData(): JSONObject {
        val captor = argumentCaptor<JSONObject>()
        verify(delivery, timeout(WAIT_MS)).enqueueEvent(captor.capture())
        val event = captor.firstValue
        assertEquals("App install/upgrade", event.getString("name"))
        assertEquals("precondition: the event carries the token", true, event.toString().contains(TOKEN))
        return event.getJSONArray("payload").getJSONObject(0).getJSONObject("data")
    }

    @Test
    fun `the re-registration after a logout or reset is marked reason token`() =
        runTest {
            component.reRegisterPushToken()

            assertEquals("token", queuedData().getString("reason"))
        }

    /**
     * An FCM rotation reported by `:push` is not a re-registration after a rotation of the profile,
     * and keeps the event's existing shape.
     */
    @Test
    fun `an ordinary token registration carries no reason`() =
        runTest {
            component.registerPushToken()

            assertFalse(queuedData().has("reason"))
        }
}
