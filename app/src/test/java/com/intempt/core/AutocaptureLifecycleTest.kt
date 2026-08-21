@file:OptIn(com.intempt.core.internal.InternalIntemptApi::class)

package com.intempt.core

import android.app.Application
import com.intempt.core.autocapture.AutoCaptureComponent
import com.intempt.core.autocapture.installUpgradeTracker.InstallUpgradeTrackerComponent
import com.intempt.core.autocapture.lifecycleCallbacksTracker.LifecycleCallBacksComponent
import com.intempt.core.autocapture.sessionTracker.SessionTrackerComponent
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.StorageManagerService
import com.intempt.core.types.AutocaptureOptions
import com.intempt.core.types.AutomaticEventsOptions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq

/**
 * Autocapture is UI instrumentation; automatic events are lifecycle facts. They used to be one
 * switch, started together from `initialize()`, and separating them is the contract's requirement
 * and the point of this test.
 *
 * Two rules are contractual rather than stylistic:
 *
 * 1. **Nothing is installed until `start()`.** An SDK may not hook a host app's view layer because
 *    someone called `initialize()`. On Apple this is UIKit swizzling; on Android it is
 *    `registerActivityLifecycleCallbacks`, which is less dramatic and no more invited.
 * 2. **Defaults are sessions on, version changes off, app-state changes off.** The SDK emitted all
 *    three unconditionally, so an app that wanted sessions also got an event on every single
 *    foreground/background transition — an event-volume bill nobody asked for.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutocaptureLifecycleTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = UnconfinedTestDispatcher(scheduler)

    private lateinit var application: Application
    private lateinit var config: ConfigManagerService
    private lateinit var session: SessionTrackerComponent
    private lateinit var installUpgrade: InstallUpgradeTrackerComponent
    private lateinit var callbacks: LifecycleCallBacksComponent
    private lateinit var storage: StorageManagerService
    private lateinit var component: AutoCaptureComponent

    @Before
    fun setUp() {
        application = mock(Application::class.java)
        `when`(application.applicationContext).thenReturn(application)

        config = mock(ConfigManagerService::class.java)
        `when`(config.autocaptureOptions).thenReturn(AutocaptureOptions())
        `when`(config.automaticEventsOptions).thenReturn(AutomaticEventsOptions())

        session = mock(SessionTrackerComponent::class.java)
        installUpgrade = mock(InstallUpgradeTrackerComponent::class.java)
        callbacks = mock(LifecycleCallBacksComponent::class.java)
        storage = mock(StorageManagerService::class.java)

        component =
            AutoCaptureComponent(
                logger = mock(LoggerManagerService::class.java),
                context = application,
                storage = storage,
                config = config,
                session = session,
                installUpgrade = installUpgrade,
                lifecycleCallBacks = callbacks,
                dispatcher = dispatcher,
            )
    }

    // ------------------------------------------------------------------ autocapture

    @Test
    fun `nothing is installed until start is called`() {
        assertFalse("autocapture must be inert before start()", component.isAutocaptureRunning)
        verify(application, never()).registerActivityLifecycleCallbacks(any())
    }

    /**
     * The rule that matters most here: starting automatic events must not install UI hooks.
     *
     * `initialize()` calls `startAutomaticEvents()`, and before the split it called one `start()`
     * that did both. If this regresses, an SDK instruments a host app's view layer as a side
     * effect of being initialised, and nothing else in the suite would notice.
     */
    @Test
    fun `starting automatic events does not install the view-layer hooks`() =
        runTest(scheduler) {
            component.startAutomaticEvents()
            scheduler.advanceUntilIdle()

            assertFalse(component.isAutocaptureRunning)
            verify(application, never()).registerActivityLifecycleCallbacks(any())
        }

    @Test
    fun `start installs the hooks and reports running`() {
        assertTrue(component.startAutocapture())

        assertTrue(component.isAutocaptureRunning)
        verify(application).registerActivityLifecycleCallbacks(eq(callbacks))
    }

    /**
     * A second start must not register a second set of callbacks.
     *
     * Two registrations mean every screen view is emitted twice, which reads as a traffic increase
     * rather than as a bug — the most expensive kind of duplicate.
     */
    @Test
    fun `start is idempotent`() {
        assertTrue(component.startAutocapture())
        assertFalse("the second start must report that it did nothing", component.startAutocapture())

        verify(application, times(1)).registerActivityLifecycleCallbacks(any())
        assertTrue(component.isAutocaptureRunning)
    }

    @Test
    fun `stop uninstalls the hooks and reports stopped`() {
        component.startAutocapture()

        assertTrue(component.stopAutocapture())

        assertFalse(component.isAutocaptureRunning)
        verify(application).unregisterActivityLifecycleCallbacks(eq(callbacks))
    }

    @Test
    fun `stop is idempotent and does not unregister when never started`() {
        assertFalse("stopping what never started must report that it did nothing", component.stopAutocapture())

        verify(application, never()).unregisterActivityLifecycleCallbacks(any())
    }

    @Test
    fun `start applies the options it is given`() {
        val options = AutocaptureOptions(screenViews = true, controlInteractions = false, captureText = false)

        component.startAutocapture(options)

        verify(config).autocaptureOptions = eq(options)
    }

    // -------------------------------------------------------------- automatic events

    /**
     * The profile id is minted regardless of every option here.
     *
     * Every event the SDK sends carries one, so gating it behind an automatic-events switch would
     * mean an app that only calls `track()` never gets an identity — and identity-less events are
     * not recoverable after the fact.
     */
    @Test
    fun `the profile id is ensured synchronously whatever the options say`() =
        runTest(scheduler) {
            `when`(config.automaticEventsOptions)
                .thenReturn(AutomaticEventsOptions(sessions = false, versionChanges = false, appStateChanges = false))

            component.startAutomaticEvents()
            // Deliberately NO advanceUntilIdle before the verify: the mint must happen on the
            // caller's thread, before startAutomaticEvents returns, or getProfileId() races it.
            verify(storage).ensureProfileId()
            scheduler.advanceUntilIdle()
        }

    @Test
    fun `sessions are on by default`() =
        runTest(scheduler) {
            component.startAutomaticEvents()
            scheduler.advanceUntilIdle()

            verify(session).start()
        }

    /**
     * Version changes and app-state changes are off by default, and the tracker is not even
     * started when both are off — so the SDK registers no process-lifecycle observer for a host
     * app that asked for neither.
     */
    @Test
    fun `version and app-state events are off by default`() =
        runTest(scheduler) {
            component.startAutomaticEvents()
            scheduler.advanceUntilIdle()

            verify(installUpgrade, never()).start(anyOrNull(), anyOrNull())
        }

    @Test
    fun `sessions can be turned off`() =
        runTest(scheduler) {
            `when`(config.automaticEventsOptions).thenReturn(AutomaticEventsOptions(sessions = false))

            component.startAutomaticEvents()
            scheduler.advanceUntilIdle()

            verify(session, never()).start()
        }

    @Test
    fun `each automatic event switch is passed through independently`() =
        runTest(scheduler) {
            `when`(config.automaticEventsOptions)
                .thenReturn(AutomaticEventsOptions(sessions = true, versionChanges = true, appStateChanges = false))

            component.startAutomaticEvents()
            scheduler.advanceUntilIdle()

            verify(installUpgrade).start(versionChanges = eq(true), appStateChanges = eq(false))
        }
}
