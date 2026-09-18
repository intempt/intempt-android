@file:OptIn(com.intempt.core.internal.InternalIntemptApi::class)

package com.intempt.core

import com.intempt.core.autocapture.installUpgradeTracker.InstallUpgradeTrackerComponent
import com.intempt.core.autocapture.installUpgradeTracker.InstallUpgradeTrackerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.push.PushModuleEntryPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The FCM device token must reach the platform without depending on an event-volume switch.
 *
 * The token is carried by exactly one thing — the install/upgrade event's
 * `userAttributes.fcm_token_<sourceId>` — and that event used to be gated on
 * `AutomaticEventsOptions.versionChanges`, which defaults to **false**. A host app that followed
 * the README obtained a real token from Firebase and never registered it: no error, no warning, no
 * log, and every push silently went nowhere. The failure surfaced much later and in a different
 * system, as a profile with no device token.
 *
 * These tests drive the real [com.intempt.core.internal.PushBridge], which resolves
 * `com.intempt.push.PushModuleEntryPoint` by name — see this source set's stand-in for it. Nothing
 * about the bridge is stubbed, because the defect was in whether it is consulted at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PushTokenRegistrationTest {
    private lateinit var srv: InstallUpgradeTrackerService
    private lateinit var component: InstallUpgradeTrackerComponent

    private companion object {
        const val TOKEN = "fcm-token-aaa"
        const val ROTATED = "fcm-token-bbb"
        const val CURRENT_VERSION = 7L
    }

    @Before
    fun setUp() {
        PushModuleEntryPoint.token = ""

        srv = mock()
        whenever(srv.logger).thenReturn(mock<LoggerManagerService>())
        whenever(srv.getConsumerAppVersionCode()).thenReturn(CURRENT_VERSION)
        // Not a fresh install and not an upgrade: the version-change half has nothing to say, so
        // any dispatch below is attributable to the token and to nothing else.
        whenever(srv.getStoredVersionCode()).thenReturn(CURRENT_VERSION)
        whenever(srv.getStoredPushToken()).thenReturn(null)

        component = InstallUpgradeTrackerComponent(srv = srv, dispatcher = UnconfinedTestDispatcher())
    }

    /** The regression. With the defect present this dispatches nothing at all. */
    @Test
    fun `a new token is registered although version changes are off`() =
        runTest {
            PushModuleEntryPoint.token = TOKEN

            component.start(versionChanges = false, appStateChanges = false)

            verify(srv).storePushToken(TOKEN)
            verify(srv).logAndDispatch(argThat { contains("Push token") })
        }

    /**
     * The half that stays gated. `versionChanges = false` must still mean no version-change event
     * and no stored version code — the latter is what keeps a later opt-in able to report the
     * install rather than finding it silently consumed.
     */
    @Test
    fun `registering a token does not store the version code or claim an install`() =
        runTest {
            PushModuleEntryPoint.token = TOKEN
            whenever(srv.getStoredVersionCode()).thenReturn(-1L)

            component.start(versionChanges = false, appStateChanges = false)

            verify(srv, never()).storeVersionCode(any())
            verify(srv, never()).logAndDispatch(argThat { contains("Install detected") })
            verify(srv).logAndDispatch(argThat { contains("Push token") })
        }

    @Test
    fun `a token already registered dispatches nothing`() =
        runTest {
            PushModuleEntryPoint.token = TOKEN
            whenever(srv.getStoredPushToken()).thenReturn(TOKEN)

            component.start(versionChanges = false, appStateChanges = false)

            verify(srv, never()).logAndDispatch(any())
            verify(srv, never()).storePushToken(any())
        }

    /** No `:push` module, or Firebase unconfigured: the bridge answers blank and nothing is sent. */
    @Test
    fun `no token means no dispatch`() =
        runTest {
            component.start(versionChanges = false, appStateChanges = false)

            verify(srv, never()).logAndDispatch(any())
            verify(srv, never()).storePushToken(any())
        }

    /**
     * One event, not two. The install/upgrade event already carries the token, so a second
     * dispatch for the token alone would duplicate it in the host app's own analytics.
     */
    @Test
    fun `a fresh install with a new token dispatches exactly one event`() =
        runTest {
            PushModuleEntryPoint.token = TOKEN
            whenever(srv.getStoredVersionCode()).thenReturn(-1L)

            component.start(versionChanges = true, appStateChanges = false)

            verify(srv, times(1)).logAndDispatch(any())
            verify(srv).logAndDispatch(argThat { contains("Install detected") })
            verify(srv).storeVersionCode(eq(CURRENT_VERSION))
            // Claimed even though the install event is what announces it, so the next launch does
            // not announce the same token a second time.
            verify(srv).storePushToken(TOKEN)
        }

    /**
     * FCM rotates tokens of its own accord. Before this, a rotation emitted a `track` event
     * carrying `data.deviceToken` — events route by type, not name, so that never became the
     * `fcm_token_<sourceId>` profile attribute a push destination resolves, and the device stayed
     * unreachable until the app's version changed.
     */
    @Test
    fun `a rotated token is registered without any version change`() =
        runTest {
            whenever(srv.getStoredPushToken()).thenReturn(TOKEN)
            PushModuleEntryPoint.token = ROTATED

            component.registerPushToken()

            verify(srv).storePushToken(ROTATED)
            verify(srv).logAndDispatch(argThat { contains("Push token") })
            verify(srv, never()).storeVersionCode(any())
        }

    @Test
    fun `a rotation callback for an unchanged token dispatches nothing`() =
        runTest {
            whenever(srv.getStoredPushToken()).thenReturn(TOKEN)
            PushModuleEntryPoint.token = TOKEN

            component.registerPushToken()

            verify(srv, never()).logAndDispatch(any())
            verify(srv, never()).storePushToken(any())
        }
}
