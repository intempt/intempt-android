@file:OptIn(com.intempt.core.internal.InternalIntemptApi::class)

package com.intempt.core

import android.content.Context
import com.intempt.core.autocapture.installUpgradeTracker.InstallUpgradeTrackerComponent
import com.intempt.core.autocapture.installUpgradeTracker.InstallUpgradeTrackerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.StorageManagerService
import com.intempt.core.services.UtilsService
import com.intempt.core.services.eventPool.EventPoolManagerService
import com.intempt.push.PushModuleEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argThat
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * INT-5964, the storage half: what logOut()/reset() leave behind for the push-token dedupe.
 *
 * The report said the stored "last registered" token survives logout, so the next profile's
 * registration is skipped as a duplicate. It does not survive: [StorageManagerService.clearAllStorage]
 * wipes AppPrefs, which is where the token lives. These tests use the real storage so they would
 * notice if that ever changed — the missing piece was the re-registration call, pinned in
 * [IntemptInstanceDelegationTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PushTokenAfterLogoutTest {
    private lateinit var storage: StorageManagerService
    private lateinit var srv: InstallUpgradeTrackerService
    private lateinit var component: InstallUpgradeTrackerComponent

    private companion object {
        const val TOKEN = "fcm-token-aaa"
        const val CURRENT_VERSION = 7L
    }

    @Before
    fun setUp() {
        PushModuleEntryPoint.token = ""
        val context: Context = RuntimeEnvironment.getApplication()
        val utils = mock<UtilsService>()
        var counter = 0
        whenever(utils.generateId(any())).thenAnswer { "prof_fresh_${counter++}" }
        storage = StorageManagerService(context, utils, Dispatchers.Unconfined)
        srv =
            spy(
                InstallUpgradeTrackerService(
                    context = context,
                    eventSrv = mock<EventPoolManagerService>(),
                    storage = storage,
                    logger = mock<LoggerManagerService>(),
                    utils = utils,
                ),
            )
        doReturn(true).whenever(srv).logAndDispatch(any())
        component = InstallUpgradeTrackerComponent(srv = srv, dispatcher = UnconfinedTestDispatcher())
    }

    @Test
    fun `the logout wipe forgets the registered token`() =
        runTest {
            PushModuleEntryPoint.token = TOKEN
            component.registerPushToken()
            assertEquals(TOKEN, srv.getStoredPushToken())

            storage.clearAllStorage()

            assertNull(srv.getStoredPushToken())
        }

    @Test
    fun `the same token is announced again after the logout wipe`() =
        runTest {
            PushModuleEntryPoint.token = TOKEN
            component.registerPushToken()

            storage.clearAllStorage()
            component.registerPushToken()

            verify(srv, times(2)).logAndDispatch(anyOrNull())
            assertEquals(TOKEN, srv.getStoredPushToken())
        }

    /**
     * The wipe used to take the stored app version code with it, so with `versionChanges` on the
     * launch after any logout reported a fresh "App Install" on a device that had it all along.
     */
    @Test
    fun `the launch after a logout reports no install`() =
        runTest {
            doReturn(CURRENT_VERSION).whenever(srv).getConsumerAppVersionCode()
            component.start(versionChanges = true, appStateChanges = false)
            verify(srv).logAndDispatch(argThat { contains("Install detected") })

            storage.clearAllStorage()
            component.start(versionChanges = true, appStateChanges = false)

            verify(srv, times(1)).logAndDispatch(argThat { contains("Install detected") })
        }
}
