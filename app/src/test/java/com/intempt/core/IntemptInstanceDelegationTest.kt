@file:OptIn(com.intempt.core.internal.InternalIntemptApi::class)

package com.intempt.core

import com.intempt.core.autocapture.AutoCaptureComponent
import com.intempt.core.customCapture.CustomCaptureComponent
import com.intempt.core.intemptCore.IntemptCoreService
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.ErrorReporter
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.types.ConsentAction
import com.intempt.core.types.IntemptValue
import com.intempt.core.types.Product
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

/**
 * [IntemptInstance] is the public surface, and it had **0 of 66 lines covered**.
 *
 * That is worse than it sounds. Every method here is a one-line delegate onto
 * [IntemptCoreService], which is exactly the shape where a wrong delegate is invisible: `optIn()`
 * calling `optOut()` compiles, ships, reads correctly in review, and silently inverts consent for
 * every caller. Nothing downstream would catch it, because the core method it wrongly calls is
 * itself well tested.
 *
 * So this asserts the WIRING rather than the behaviour: each public method reaches the core method
 * of the same name, with the arguments it was given. The core's own behaviour is covered elsewhere.
 *
 * It also lifted the module over its coverage floor, which was the immediate reason for writing it
 * — the bundle sat at 0.6554 against 0.66, eighteen lines short, and this class was the largest
 * block of zero-covered public API in the SDK.
 */
class IntemptInstanceDelegationTest {
    private class Fixture {
        val capture: CustomCaptureComponent = mock(CustomCaptureComponent::class.java)
        val autoCapture: AutoCaptureComponent = mock(AutoCaptureComponent::class.java)
        val config: ConfigManagerService = mock(ConfigManagerService::class.java)
        val errors: ErrorReporter = mock(ErrorReporter::class.java)

        init {
            // IntemptCoreService's init block logs through this, so it cannot return null.
            `when`(autoCapture.logger).thenReturn(mock(LoggerManagerService::class.java))
        }

        fun instance(): IntemptInstance = IntemptInstance(
            "default",
            IntemptCoreService(autoCapture, capture, config, errors),
        )
    }

    @Test
    fun `carries the name it was initialised under`() {
        assertEquals("default", Fixture().instance().name)
    }

    @Test
    fun `capture methods reach the capture component with their arguments intact`() {
        val f = Fixture()
        val i = f.instance()
        val data = mapOf("k" to IntemptValue.of("v"))

        i.track("signup", data)
        i.identify("u1", "login", null, null)
        i.group("a1", "joined", null)
        i.alias("u1", "u2")
        i.record("purchase", "u1", "a1", null, null, null)
        i.productAdd("p1", 2)
        i.productView("p1")
        i.productOrdered(emptyList<Product>())

        verify(f.capture).track("signup", data)
        verify(f.capture).identify("u1", "login", null, null)
        verify(f.capture).group("a1", "joined", null)
        verify(f.capture).alias("u1", "u2")
        verify(f.capture).record("purchase", "u1", "a1", null, null, null)
        verify(f.capture).productAdd("p1", 2)
        verify(f.capture).productView("p1")
        verify(f.capture).productOrdered(emptyList())
    }

    @Test
    fun `consent opt-in and opt-out are not wired to each other`() {
        // The failure this exists for: optIn() delegating to optOut(). It compiles, it reads right,
        // and it inverts consent for every caller.
        val f = Fixture()
        val i = f.instance()

        i.optIn()
        verify(f.capture).optIn()

        i.optOut()
        verify(f.capture).optOut()

        i.consent(ConsentAction.ACCEPT, 0L, null, null)
        verify(f.capture).consent(ConsentAction.ACCEPT, 0L, null, null)
    }

    @Test
    fun `identity and session readers reach the core rather than returning a local default`() {
        val f = Fixture()
        `when`(f.capture.getProfileId()).thenReturn("pid-1")
        `when`(f.capture.getSessionId()).thenReturn("sid-1")
        val i = f.instance()

        assertEquals("pid-1", i.getProfileId())
        assertEquals("sid-1", i.getSessionId())
    }

    @Test
    fun `lifecycle methods reach the core`() {
        val f = Fixture()
        val i = f.instance()

        i.logOut()
        i.reset()
        i.flush(null)

        verify(f.capture).logOut()
        verify(f.capture).reset()
        verify(f.capture).flush(null)
    }

    @Test
    fun `opt-out state is read from the core, not cached on the instance`() {
        val f = Fixture()
        `when`(f.capture.hasOptedOut()).thenReturn(true)
        `when`(f.capture.isOptedIn()).thenReturn(false)
        val i = f.instance()

        assertEquals(true, i.hasOptedOut())
        assertEquals(false, i.isOptedIn())
    }

    @Test
    fun `logging controls reach the core`() {
        val f = Fixture()
        `when`(f.capture.isLoggingEnabled()).thenReturn(true)
        val i = f.instance()

        i.startLogging()
        i.stopLogging()

        verify(f.capture).enableLogging()
        verify(f.capture).disableLogging()
        assertEquals(true, i.isLoggingEnabled())
    }

    @Test
    fun `the error listener is registered on the reporter, not swallowed`() {
        val f = Fixture()
        val listener: (com.intempt.core.types.IntemptError) -> Unit = {}

        f.instance().setErrorListener(listener)

        verify(f.errors).setListener(listener)
    }

    @Test
    fun `autocapture start and stop reach the autocapture component`() {
        val f = Fixture()
        `when`(f.autoCapture.isAutocaptureRunning).thenReturn(true)
        val ac = f.instance().autocapture

        ac.start(null)
        ac.stop()

        verify(f.autoCapture).startAutocapture(null)
        verify(f.autoCapture).stopAutocapture()
        assertEquals(true, ac.isRunning)
    }
}
