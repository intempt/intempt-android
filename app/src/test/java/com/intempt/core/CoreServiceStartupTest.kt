package com.intempt.core

import com.intempt.core.autocapture.AutoCaptureComponent
import com.intempt.core.customCapture.CustomCaptureComponent
import com.intempt.core.intemptCore.IntemptCoreService
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.ErrorReporter
import com.intempt.core.services.LoggerManagerService
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.kotlin.anyOrNull

/**
 * What `initialize()` starts, and — the part that matters — what it does not.
 *
 * This test exists because the one next to it did not catch the regression it was written for.
 * `AutocaptureLifecycleTest` asserts that `AutoCaptureComponent.startAutomaticEvents()` installs no
 * view-layer hooks, which is true and insufficient: the contract rule is about **initialisation**,
 * and initialisation happens in [IntemptCoreService]'s `init` block, one layer up. Adding a
 * `startAutocapture()` call there passed the entire suite.
 *
 * Found by breaking that exact line with `scripts/falsify.sh` and watching the tests stay green —
 * which is the only way to tell a test that covers a behaviour from a test that merely runs near
 * it.
 */
class CoreServiceStartupTest {
    private class Fixture {
        val autoCapture: AutoCaptureComponent = mock(AutoCaptureComponent::class.java)
        val config: ConfigManagerService = mock(ConfigManagerService::class.java)

        init {
            // The init block logs through this, so it cannot be a bare mock returning null.
            `when`(autoCapture.logger).thenReturn(mock(LoggerManagerService::class.java))
        }

        fun build(): IntemptCoreService =
            IntemptCoreService(
                autoCapture,
                mock(CustomCaptureComponent::class.java),
                config,
                mock(ErrorReporter::class.java),
            )
    }

    /**
     * Constructing the SDK must not hook the host app's view layer.
     *
     * On Apple the equivalent is swizzling UIKit; here it is
     * `registerActivityLifecycleCallbacks`. Less dramatic, no more invited. An SDK may instrument a
     * host app because it was asked to, never because it was merely initialised.
     */
    @Test
    fun `initialisation starts automatic events and never autocapture`() {
        val fixture = Fixture()

        fixture.build()

        verify(fixture.autoCapture).startAutomaticEvents()
        verify(fixture.autoCapture, never()).startAutocapture(anyOrNull())
    }

    /**
     * The config asset is an explicit request, so honouring it is not the SDK assuming.
     *
     * `isAutoCaptureEnabled: true` in intempt-config.json is a host app asking for instrumentation
     * in writing. That satisfies the opt-in rule; a default the SDK picked would not.
     */
    @Test
    fun `autocapture starts when the config asset asked for it`() {
        val fixture = Fixture()
        `when`(fixture.config.autocaptureEnabledByConfig).thenReturn(true)

        fixture.build().startAutocaptureIfConfigured()

        verify(fixture.autoCapture).startAutocapture(anyOrNull())
    }

    @Test
    fun `autocapture stays off when the config asset did not ask`() {
        val fixture = Fixture()
        `when`(fixture.config.autocaptureEnabledByConfig).thenReturn(false)

        fixture.build().startAutocaptureIfConfigured()

        verify(fixture.autoCapture, never()).startAutocapture(anyOrNull())
    }
}
