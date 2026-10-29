@file:OptIn(com.intempt.core.internal.InternalIntemptApi::class)

package com.intempt.core

import android.content.Context
import com.intempt.core.customCapture.CustomCaptureComponent
import com.intempt.core.customCapture.CustomCaptureService
import com.intempt.core.eventModels.IntemptEvent
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.ErrorReporter
import com.intempt.core.services.IntemptEventManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.StorageManagerService
import com.intempt.core.services.UtilsService
import com.intempt.core.services.eventPool.EventPoolManagerService
import com.intempt.core.types.Constants
import com.intempt.core.types.StorageKeys
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
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
 * actually stamped with, not one the test supplied.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PushTokenReleaseOnLogoutTest {
    private lateinit var config: ConfigManagerService
    private lateinit var storage: StorageManagerService
    private lateinit var eventPool: EventPoolManagerService
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
        component =
            CustomCaptureComponent(
                CustomCaptureService(storage, logger, errors),
                config,
                eventPool,
                IntemptEventManagerService(context, storage, utils, config),
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

    private fun emitted(): IntemptEvent {
        val captor = argumentCaptor<IntemptEvent>()
        verify(eventPool).emitEvent(captor.capture())
        return captor.firstValue
    }

    @Suppress("UNCHECKED_CAST")
    private fun assertClearsTokenOf(
        event: IntemptEvent,
        profileId: String,
    ) {
        assertEquals(Constants.InstallUpgrade.ENTITY_NAME, event.type)
        val formatted = event.payload.single().toFormated()
        assertEquals("stamped with the profile that is logging out", profileId, formatted["profileId"])
        val attributes = formatted["userAttributes"] as Map<String, Any?>
        assertEquals("\"\" is what the backend stores as a clear", "", attributes["fcm_token_" + config.sourceId])
    }

    @Test
    fun `logOut clears the token on the profile it leaves`() {
        registerToken()
        val leaving = storage.getProfileId()

        component.logOut()

        assertClearsTokenOf(emitted(), leaving)
        assertNotEquals("precondition: the identity rotated", leaving, storage.getProfileId())
    }

    /** After the discard, or the discard would throw the clear away with the rest of the queue. */
    @Test
    fun `reset clears the token on the profile it leaves, after discarding the queue`() {
        registerToken()
        val leaving = storage.getProfileId()

        component.reset()

        assertClearsTokenOf(emitted(), leaving)
        val order = inOrder(eventPool)
        order.verify(eventPool).discardQueuedEvents()
        order.verify(eventPool).emitEvent(any())
    }

    /** No token was ever announced for this profile, so there is nothing to take back. */
    @Test
    fun `no registered token sends nothing`() {
        component.logOut()

        verify(eventPool, never()).emitEvent(any())
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

        verify(eventPool, never()).emitEvent(any())
    }
}
