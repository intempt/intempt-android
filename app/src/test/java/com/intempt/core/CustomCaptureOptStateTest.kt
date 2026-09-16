@file:OptIn(com.intempt.core.internal.InternalIntemptApi::class)

package com.intempt.core

import android.content.Context
import com.intempt.core.customCapture.CustomCaptureComponent
import com.intempt.core.customCapture.CustomCaptureService
import com.intempt.core.queue.DeliveryMessages
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.ErrorReporter
import com.intempt.core.services.HttpManagerService
import com.intempt.core.services.IntemptEventManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.StorageManagerService
import com.intempt.core.services.UtilsService
import com.intempt.core.services.eventPool.EventPoolManagerService
import com.intempt.core.types.StorageKeys
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * INT-3911 — the opt-in/opt-out decision has to outlive the process that made it.
 *
 * `ConfigManagerService` assigns `isUserOptIn = DefaultConfigs.IsUserOptIn.value` (true) every
 * time it loads, and nothing wrote `optIn()`/`optOut()` down anywhere, so an opt-out lasted
 * exactly one process lifetime: the user objected, the app restarted, and capture resumed.
 *
 * These tests use REAL `SharedPreferences` (Robolectric's application, not a mocked prefs
 * double) on purpose — the whole claim is about what survives to disk, and a mock cannot
 * distinguish a write that happened from one that did not.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class CustomCaptureOptStateTest {
    private lateinit var context: Context
    private lateinit var logger: LoggerManagerService
    private lateinit var utils: UtilsService
    private lateinit var storage: StorageManagerService
    private lateinit var baseConfig: ConfigManagerService

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        baseConfig = ConfigManagerService(context)
        logger = LoggerManagerService(baseConfig)
        utils = UtilsService(logger)
        // Unconfined so the persist coroutine runs inline: these tests assert WHAT was
        // written, not in which order — ordering is StorageManagerServiceTest's job.
        storage = StorageManagerService(context, utils, Dispatchers.Unconfined)
    }

    /**
     * A second component over the same storage stands in for the next app launch: the
     * `ConfigManagerService` is freshly loaded (so back at the default) and only the
     * component's `init` block can bring the decision back.
     */
    private fun newComponent(config: ConfigManagerService): CustomCaptureComponent {
        val errors = ErrorReporter(logger)
        val intemptEvent = IntemptEventManagerService(context, storage, utils, config)
        val eventPool =
            EventPoolManagerService(
                config,
                logger,
                HttpManagerService(config, logger),
                intemptEvent,
                mock(DeliveryMessages::class.java),
                dispatcher = Dispatchers.Unconfined,
            )
        return CustomCaptureComponent(
            CustomCaptureService(storage, logger, errors),
            config,
            eventPool,
            intemptEvent,
            utils,
            storage,
            errors,
        )
    }

    private fun userPrefs() = context.getSharedPreferences(StorageKeys.UserPrefs.key, Context.MODE_PRIVATE)

    /** The ticket itself: an objection must still be in force on the next launch. */
    @Test
    fun `an opt-out survives the config reload that used to erase it`() {
        newComponent(baseConfig).optOut()
        assertFalse("precondition: optOut clears the in-memory flag", baseConfig.isUserOptIn)

        val reloaded = ConfigManagerService(context)
        assertTrue("precondition: a reloaded config is back at the opted-in default", reloaded.isUserOptIn)

        newComponent(reloaded)

        assertFalse("the user's opt-out must survive a restart, not just a screen", reloaded.isUserOptIn)
    }

    /**
     * The reverse direction, asserted on the PERSISTED copy rather than the field.
     *
     * `optOut(); optIn()` in one session must leave `true` on disk. Asserting
     * `config.isUserOptIn` alone would pass even if nothing was ever written.
     */
    @Test
    fun `opting back in persists true rather than only updating the field`() {
        val component = newComponent(baseConfig)
        component.optOut()
        component.optIn()

        val key = StorageKeys.IsUserOptIn.key
        assertTrue("the decision must reach SharedPreferences", userPrefs().contains(key))
        assertTrue("the LAST call must be the durable value", userPrefs().getBoolean(key, false))

        val reloaded = ConfigManagerService(context)
        newComponent(reloaded)
        assertTrue("a consenting user must not come back opted out", reloaded.isUserOptIn)
    }

    /**
     * No decision recorded means no decision restored.
     *
     * The config is seeded to a NON-default value first: an `init` block that wrote the
     * fallback `true` back over it unconditionally would flip this, and asserting against the
     * default would not have noticed.
     */
    @Test
    fun `with nothing persisted the config is left untouched`() {
        val key = StorageKeys.IsUserOptIn.key
        assertFalse("precondition: a fresh install has no recorded decision", userPrefs().contains(key))

        val config = ConfigManagerService(context)
        config.isUserOptIn = false

        newComponent(config)

        assertFalse("construction must not overwrite the host app's own setting", config.isUserOptIn)
        assertFalse("restoring must read, never write", userPrefs().contains(key))
    }
}
