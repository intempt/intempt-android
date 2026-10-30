@file:OptIn(com.intempt.core.internal.InternalIntemptApi::class)

package com.intempt.core

import android.content.Context
import com.intempt.core.customCapture.CustomCaptureComponent
import com.intempt.core.customCapture.CustomCaptureService
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.ErrorReporter
import com.intempt.core.services.IntemptEventManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.StorageManagerService
import com.intempt.core.services.UtilsService
import com.intempt.core.services.eventPool.EventPoolManagerService
import com.intempt.core.types.Constants
import com.intempt.core.types.IntemptEventProvider
import com.intempt.core.types.StorageKeys
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.spy
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * INT-5964, the privacy half: logOut()/reset() must take the device token off the profile they
 * leave behind.
 *
 * The token is a profile attribute (`fcm_token_<sourceId>`), and the rotation only changes which
 * profile the *next* events belong to. Without an explicit clear the previous profile keeps the
 * token, so on a shared device every push addressed to the user who logged out still lands on the
 * phone of whoever uses it next. The backend clears an extracted attribute on an explicit `""`
 * (cdp-data-processor `AttributeCalculatorHelper.calculate`); a JSON `null` is skipped and would
 * leave the token in place.
 *
 * Real storage and a real event builder, so the profileId asserted is the one the event was
 * actually stamped with, not one the test supplied. The pool is a mock: the release hands it a
 * builder, which a real pool runs on its background dispatcher; here the test runs it, after the
 * rotation, which is the order production has too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PushTokenReleaseOnLogoutTest {
    private lateinit var config: ConfigManagerService
    private lateinit var storage: StorageManagerService
    private lateinit var eventPool: EventPoolManagerService
    private lateinit var events: IntemptEventManagerService
    private lateinit var component: CustomCaptureComponent

    private companion object {
        const val TOKEN = "fcm-token-aaa"
    }

    @Before
    fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        config = ConfigManagerService(context)
        val logger = LoggerManagerService(config)
        val utils = UtilsService(logger)
        val errors = ErrorReporter(logger)
        storage = StorageManagerService(context, utils, Dispatchers.Unconfined)
        storage.ensureProfileId()
        eventPool = mock()
        events = spy(IntemptEventManagerService(context, storage, utils, config))
        component =
            CustomCaptureComponent(
                CustomCaptureService(storage, logger, errors),
                config,
                eventPool,
                events,
                utils,
                storage,
                errors,
            )
    }

    private fun registerToken() {
        storage.setStorageItem(
            prefs = StorageKeys.AppPrefs.key,
            key = StorageKeys.LastRegisteredPushToken.key,
            value = TOKEN,
        ) { key, value -> putString(key, value) }
    }

    /** The builder the release handed the pool, checked against the event name and entity. */
    private fun releaseBuilder(): () -> Array<IntemptEventProvider> {
        val captor = argumentCaptor<() -> Array<IntemptEventProvider>>()
        verify(eventPool).enqueueDurably(
            eq(Constants.InstallUpgrade.EVENT_NAME),
            eq(Constants.InstallUpgrade.ENTITY_NAME),
            captor.capture(),
        )
        return captor.firstValue
    }

    private fun emitted(): IntemptEventProvider = releaseBuilder().invoke().single()

    @Suppress("UNCHECKED_CAST")
    private fun data(event: IntemptEventProvider): Map<String, Any?> {
        return event.toFormated()["data"] as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private fun assertClearsTokenOf(
        event: IntemptEventProvider,
        profileId: String,
    ) {
        val formatted = event.toFormated()
        assertEquals("stamped with the profile that is logging out", profileId, formatted["profileId"])
        val attributes = formatted["userAttributes"] as Map<String, Any?>
        assertEquals("\"\" is what the backend stores as a clear", "", attributes["fcm_token_" + config.sourceId])
    }

    @Test
    fun `logOut clears the token on the profile it leaves`() {
        registerToken()
        val leaving = storage.getProfileId()

        component.logOut()

        val event = emitted()
        assertClearsTokenOf(event, leaving)
        assertEquals("journeys on this event exclude the release by reason", "logout", data(event)["reason"])
        assertNotEquals("precondition: the identity rotated", leaving, storage.getProfileId())
    }

    /** After the discard, or the discard would throw the clear away with the rest of the queue. */
    @Test
    fun `reset clears the token on the profile it leaves, after discarding the queue`() {
        registerToken()
        val leaving = storage.getProfileId()

        component.reset()

        val event = emitted()
        assertClearsTokenOf(event, leaving)
        assertEquals("reset is told apart from logOut", "reset", data(event)["reason"])
        val order = inOrder(eventPool)
        order.verify(eventPool).discardQueuedEvents()
        order.verify(eventPool).enqueueDurably(any(), any(), any())
    }

    /**
     * The PackageManager and `BuildConfig` lookups are not made on the caller's thread (often
     * main): logOut() hands the pool a builder and returns. What the builder reads from the profile
     * being left was captured before the rotation, so it still stamps the old profileId although
     * it runs after it.
     */
    @Test
    fun `logOut builds the payload off the caller's thread, from state captured before the rotation`() {
        registerToken()
        val leaving = storage.getProfileId()

        component.logOut()

        verify(events).snapshotInstallUpgradeState()
        verify(events, never()).generateInstallUpgradeEventPayload(any(), anyOrNull(), any())
        assertNotEquals("precondition: the identity rotated", leaving, storage.getProfileId())

        val event = releaseBuilder().invoke().single()
        assertClearsTokenOf(event, leaving)
    }

    /**
     * A real install/upgrade carries no reason, so a journey filter on `reason` excludes only the
     * release, and the existing payload shape is unchanged.
     */
    @Suppress("UNCHECKED_CAST")
    @Test
    fun `a real install or upgrade event carries no reason`() {
        val formatted = events.generateInstallUpgradeEventPayload(token = TOKEN).single().toFormated()

        val data = formatted["data"] as Map<String, Any?>
        assertFalse("no reason key at all, not a null one", data.containsKey("reason"))
        assertEquals(
            setOf(
                "currentVersionCode",
                "previousVersionCode",
                "previousBuildType",
                "currentBuildType",
                "appVisibilityState",
                "isUpgrade",
            ),
            data.keys,
        )
        val attributes = formatted["userAttributes"] as Map<String, Any?>
        assertEquals(TOKEN, attributes["fcm_token_" + config.sourceId])
    }

    /** No token was ever announced for this profile, so there is nothing to take back. */
    @Test
    fun `no registered token sends nothing`() {
        component.logOut()

        verify(eventPool, never()).enqueueDurably(any(), any(), any())
    }

    /**
     * Opted out, nothing leaves the device: the clear is an event like any other. A token
     * announced before the opt-out stays on that profile, the same as every other attribute.
     */
    @Test
    fun `an opted-out user sends nothing`() {
        registerToken()
        config.isUserOptIn = false

        component.logOut()

        verify(eventPool, never()).enqueueDurably(any(), any(), any())
    }
}
