// ConfigManagerService is @InternalIntemptApi: InstallOrUpgradeEvent's equals()/hashCode() compare
// it, so exercising every branch means holding one. Opted in at file level, matching WireFormatTest.
@file:OptIn(com.intempt.core.internal.InternalIntemptApi::class)

package com.intempt.core.eventModels

import com.intempt.core.services.ConfigManagerService
import com.intempt.core.types.AppVisibilityState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * These event models were converted from `data class` to plain classes with hand-written
 * `equals()`/`hashCode()` to drop unused `copy`/`componentN` methods from the SDK's method count
 * (see the health-check artifact's Correction 3). The hand-written versions are otherwise untested:
 * `WireFormatTest` and `DataClassHashCodeOnDeviceTest` exercise `toFormated()`/`toString()`/one
 * `hashCode()` call each, never the full `&&` chain in `equals()` or every field's contribution to
 * `hashCode()`.
 *
 * Each field is flipped one at a time so a broken comparison on any single field fails on this
 * test rather than silently short-circuiting past it — a `data class` would have generated the
 * identical shape, so this is establishing the same guarantee by hand, not adding a new one.
 */
class EventModelEqualsHashCodeTest {
    private companion object {
        const val EVENT_ID = "evt-1"
        const val SESSION_ID = "sess-1"
        const val PAGE_ID = "page-1"
        const val PROFILE_ID = "prof-1"
        const val TIMESTAMP = 1_700_000_000_000L
    }

    // ------------------------------------------------------------ ScreenViewEvent

    @Suppress("LongParameterList")
    private fun screenView(
        eventId: String = EVENT_ID,
        sessionId: String = SESSION_ID,
        pageId: String = PAGE_ID,
        profileId: String = PROFILE_ID,
        timestamp: Long = TIMESTAMP,
        activity: String = "MainActivity",
        fullActivity: String = "com.example.MainActivity",
        screenName: String = "Home",
        timeOnScreen: Long? = 10L,
    ) = ScreenViewEvent(
        eventId, sessionId, pageId, profileId, timestamp,
        activity, fullActivity, screenName, timeOnScreen,
    )

    @Test
    fun `screen view equality and hashCode`() {
        val a = screenView()
        assertEquals(a, a)
        assertNotEquals(a, null)
        assertNotEquals(a as Any?, Any())
        assertEquals(screenView(), screenView())
        assertEquals(screenView().hashCode(), screenView().hashCode())

        assertNotEquals(a, screenView(eventId = "other"))
        assertNotEquals(a, screenView(sessionId = "other"))
        assertNotEquals(a, screenView(pageId = "other"))
        assertNotEquals(a, screenView(profileId = "other"))
        assertNotEquals(a, screenView(timestamp = TIMESTAMP + 1))
        assertNotEquals(a, screenView(activity = "other"))
        assertNotEquals(a, screenView(fullActivity = "other"))
        assertNotEquals(a, screenView(screenName = "other"))
        assertNotEquals(a, screenView(timeOnScreen = null))
        assertNotEquals(a.hashCode(), screenView(timeOnScreen = 99L).hashCode())
    }

    // ------------------------------------------------------------ UiElementEvent

    @Suppress("LongParameterList")
    private fun uiElement(
        eventId: String = EVENT_ID,
        sessionId: String = SESSION_ID,
        pageId: String = PAGE_ID,
        profileId: String = PROFILE_ID,
        timestamp: Long = TIMESTAMP,
        targetElement: String = "Button",
        hierarchy: String = "root -> Button",
        targetText: String = "Buy",
        targetValue: String = "",
        targetClass: String = "android.widget.Button",
        targetId: String = "buy_btn",
        fullTargetId: String = "com.example:id/buy_btn",
    ) = UiElementEvent(
        eventId, sessionId, pageId, profileId, timestamp,
        targetElement, hierarchy, targetText, targetValue, targetClass, targetId, fullTargetId,
    )

    @Test
    fun `ui element equality and hashCode`() {
        val a = uiElement()
        assertEquals(a, a)
        assertNotEquals(a, null)
        assertNotEquals(a as Any?, Any())
        assertEquals(uiElement(), uiElement())
        assertEquals(uiElement().hashCode(), uiElement().hashCode())

        assertNotEquals(a, uiElement(eventId = "other"))
        assertNotEquals(a, uiElement(sessionId = "other"))
        assertNotEquals(a, uiElement(pageId = "other"))
        assertNotEquals(a, uiElement(profileId = "other"))
        assertNotEquals(a, uiElement(timestamp = TIMESTAMP + 1))
        assertNotEquals(a, uiElement(targetElement = "other"))
        assertNotEquals(a, uiElement(hierarchy = "other"))
        assertNotEquals(a, uiElement(targetText = "other"))
        assertNotEquals(a, uiElement(targetValue = "other"))
        assertNotEquals(a, uiElement(targetClass = "other"))
        assertNotEquals(a, uiElement(targetId = "other"))
        assertNotEquals(a, uiElement(fullTargetId = "other"))
    }

    // ------------------------------------------------------------ FragmentTransitionEvent

    @Suppress("LongParameterList")
    private fun fragmentTransition(
        eventId: String = EVENT_ID,
        sessionId: String = SESSION_ID,
        pageId: String = PAGE_ID,
        profileId: String = PROFILE_ID,
        timestamp: Long = TIMESTAMP,
        visibleFragment: String = "CartFragment",
        addedFragment: String = "CheckoutFragment",
        removedFragment: String = "BrowseFragment",
    ) = FragmentTransitionEvent(
        eventId,
        sessionId,
        pageId,
        profileId,
        timestamp,
        visibleFragment,
        addedFragment,
        removedFragment,
    )

    @Test
    fun `fragment transition equality and hashCode`() {
        val a = fragmentTransition()
        assertEquals(a, a)
        assertNotEquals(a, null)
        assertNotEquals(a as Any?, Any())
        assertEquals(fragmentTransition(), fragmentTransition())
        assertEquals(fragmentTransition().hashCode(), fragmentTransition().hashCode())

        assertNotEquals(a, fragmentTransition(eventId = "other"))
        assertNotEquals(a, fragmentTransition(sessionId = "other"))
        assertNotEquals(a, fragmentTransition(pageId = "other"))
        assertNotEquals(a, fragmentTransition(profileId = "other"))
        assertNotEquals(a, fragmentTransition(timestamp = TIMESTAMP + 1))
        assertNotEquals(a, fragmentTransition(visibleFragment = "other"))
        assertNotEquals(a, fragmentTransition(addedFragment = "other"))
        assertNotEquals(a, fragmentTransition(removedFragment = "other"))
    }

    // ------------------------------------------------------------ SessionEvent / SessionUserAttributes

    private fun sessionUserAttributes(
        deviceType: String = "phone",
        carrier: String = "",
        platform: String = "android",
    ) = SessionUserAttributes(deviceType, carrier, platform)

    @Test
    fun `session user attributes equality and hashCode`() {
        val a = sessionUserAttributes()
        assertEquals(a, a)
        assertNotEquals(a, null)
        assertNotEquals(a as Any?, Any())
        assertEquals(sessionUserAttributes(), sessionUserAttributes())
        assertEquals(sessionUserAttributes().hashCode(), sessionUserAttributes().hashCode())

        assertNotEquals(a, sessionUserAttributes(deviceType = "other"))
        assertNotEquals(a, sessionUserAttributes(carrier = "other"))
        assertNotEquals(a, sessionUserAttributes(platform = "other"))
    }

    @Suppress("LongParameterList")
    private fun sessionEvent(
        eventId: String = EVENT_ID,
        sessionId: String = SESSION_ID,
        pageId: String = PAGE_ID,
        profileId: String = PROFILE_ID,
        timestamp: Long = TIMESTAMP,
        sessionStartEventName: String = "session_start",
        deviceName: String = "device",
        appName: String = "sample",
        appVersion: String = "1.0.0",
        appIdentifier: String = "com.intempt.sample",
        androidId: String = "aid",
        source: String = "android",
        userAttributes: SessionUserAttributes = sessionUserAttributes(),
    ) = SessionEvent(
        eventId, sessionId, pageId, profileId, timestamp,
        sessionStartEventName, deviceName, appName, appVersion, appIdentifier, androidId, source, userAttributes,
    )

    @Test
    fun `session event equality and hashCode`() {
        val a = sessionEvent()
        assertEquals(a, a)
        assertNotEquals(a, null)
        assertNotEquals(a as Any?, Any())
        assertEquals(sessionEvent(), sessionEvent())
        assertEquals(sessionEvent().hashCode(), sessionEvent().hashCode())

        assertNotEquals(a, sessionEvent(eventId = "other"))
        assertNotEquals(a, sessionEvent(sessionId = "other"))
        assertNotEquals(a, sessionEvent(pageId = "other"))
        assertNotEquals(a, sessionEvent(profileId = "other"))
        assertNotEquals(a, sessionEvent(timestamp = TIMESTAMP + 1))
        assertNotEquals(a, sessionEvent(sessionStartEventName = "other"))
        assertNotEquals(a, sessionEvent(deviceName = "other"))
        assertNotEquals(a, sessionEvent(appName = "other"))
        assertNotEquals(a, sessionEvent(appVersion = "other"))
        assertNotEquals(a, sessionEvent(appIdentifier = "other"))
        assertNotEquals(a, sessionEvent(androidId = "other"))
        assertNotEquals(a, sessionEvent(source = "other"))
        assertNotEquals(a, sessionEvent(userAttributes = sessionUserAttributes(deviceType = "tablet")))
    }

    // ------------------------------------------------------------ InstallOrUpgradeEvent

    @Suppress("LongParameterList")
    private fun installOrUpgrade(
        eventId: String = EVENT_ID,
        sessionId: String = SESSION_ID,
        pageId: String = PAGE_ID,
        profileId: String = PROFILE_ID,
        timestamp: Long = TIMESTAMP,
        currentVersionCode: Long = 42L,
        previousVersionCode: Long = 41L,
        previousBuildType: String = "debug",
        currentBuildType: String = "release",
        appVisibilityState: AppVisibilityState = AppVisibilityState.Foreground,
        isUpgrade: Boolean = true,
        token: String = "fcm-token-value",
        config: ConfigManagerService =
            mock(ConfigManagerService::class.java).also { `when`(it.sourceId).thenReturn("src-1") },
    ) = InstallOrUpgradeEvent(
        eventId, sessionId, pageId, profileId, timestamp,
        currentVersionCode, previousVersionCode, previousBuildType, currentBuildType,
        appVisibilityState, isUpgrade, token, config,
    )

    @Test
    fun `install or upgrade equality and hashCode`() {
        val config = mock(ConfigManagerService::class.java)
        `when`(config.sourceId).thenReturn("src-1")
        val a = installOrUpgrade(config = config)

        assertEquals(a, a)
        assertNotEquals(a, null)
        assertNotEquals(a as Any?, Any())
        assertEquals(installOrUpgrade(config = config), installOrUpgrade(config = config))
        assertEquals(installOrUpgrade(config = config).hashCode(), installOrUpgrade(config = config).hashCode())

        assertNotEquals(a, installOrUpgrade(config = config, eventId = "other"))
        assertNotEquals(a, installOrUpgrade(config = config, sessionId = "other"))
        assertNotEquals(a, installOrUpgrade(config = config, pageId = "other"))
        assertNotEquals(a, installOrUpgrade(config = config, profileId = "other"))
        assertNotEquals(a, installOrUpgrade(config = config, timestamp = TIMESTAMP + 1))
        assertNotEquals(a, installOrUpgrade(config = config, currentVersionCode = 0L))
        assertNotEquals(a, installOrUpgrade(config = config, previousVersionCode = 0L))
        assertNotEquals(a, installOrUpgrade(config = config, previousBuildType = "other"))
        assertNotEquals(a, installOrUpgrade(config = config, currentBuildType = "other"))
        assertNotEquals(a, installOrUpgrade(config = config, appVisibilityState = AppVisibilityState.Background))
        assertNotEquals(a, installOrUpgrade(config = config, isUpgrade = false))
        assertNotEquals(a, installOrUpgrade(config = config, token = "other"))

        val otherConfig = mock(ConfigManagerService::class.java)
        `when`(otherConfig.sourceId).thenReturn("src-2")
        assertNotEquals(a, installOrUpgrade(config = otherConfig))
    }

    // ------------------------------------------------------------ cross-type

    @Test
    fun `distinct event model types are never equal to each other`() {
        assertFalse((screenView() as Any).equals(fragmentTransition()))
        assertTrue(screenView().eventId == fragmentTransition().eventId)
    }
}
