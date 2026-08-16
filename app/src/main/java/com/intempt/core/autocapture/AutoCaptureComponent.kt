package com.intempt.core.autocapture
import android.app.Application
import android.content.Context
import com.intempt.core.autocapture.installUpgradeTracker.InstallUpgradeTrackerComponent
import com.intempt.core.autocapture.lifecycleCallbacksTracker.LifecycleCallBacksComponent
import com.intempt.core.autocapture.sessionTracker.SessionTrackerComponent
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.StorageManagerService
import com.intempt.core.types.AutocaptureOptions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Two things that were one thing, now separated because the contract insists on it.
 *
 * **Automatic events** are lifecycle facts the SDK knows without instrumentation — a session
 * started, the app was updated, it went to the background. **Autocapture** hooks the host app's
 * view layer and emits an event per interaction.
 *
 * They used to start together, behind one `isAutoCaptureEnabled` flag, from `initialize()`. That
 * conflated two very different costs — a handful of events a day against one per tap — and it
 * meant an SDK instrumented a host app's UI because someone had merely initialised it.
 *
 * Now:
 *
 * - [startAutomaticEvents] runs at `initialize()`, honouring [AutomaticEventsOptions] whose
 *   defaults are sessions on, version changes off, app-state changes off.
 * - [startAutocapture] installs the view-layer hooks and is never called implicitly. It is
 *   idempotent, [stopAutocapture] uninstalls, and [isAutocaptureRunning] answers honestly.
 *
 * `validateProfileId` stays on the automatic-events path rather than the autocapture path: the
 * profile id is needed by every event the SDK sends, so gating it behind UI instrumentation would
 * mean an app that only calls `track()` never mints one.
 */
@Singleton
internal class AutoCaptureComponent
    @Inject
    constructor(
        val logger: LoggerManagerService,
        private val context: Context,
        private val storage: StorageManagerService,
        private val config: ConfigManagerService,
        private val session: SessionTrackerComponent,
        private val installUpgrade: InstallUpgradeTrackerComponent,
        private val lifecycleCallBacks: LifecycleCallBacksComponent,
        private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : BaseComponent(logger) {
        private val coroutineScope = CoroutineScope(SupervisorJob() + dispatcher)

        // Atomic rather than a plain Boolean: start() and stop() are public API and can be called
        // from any thread, and a double-register would install two sets of lifecycle callbacks and
        // emit every screen view twice. compareAndSet makes the register-once decision the same
        // operation as the flag write.
        private val autocaptureRunning = AtomicBoolean(false)

        val isAutocaptureRunning: Boolean get() = autocaptureRunning.get()

        /** Lifecycle facts, per [ConfigManagerService.automaticEventsOptions]. */
        fun startAutomaticEvents() {
            coroutineScope.launch {
                // Unconditional: every event the SDK sends carries a profileId, so this cannot be
                // gated behind any of the options below.
                storage.validateProfileId()

                val options = config.automaticEventsOptions

                if (options.sessions) {
                    session.start()
                } else {
                    logger.log("Automatic session events are off")
                }

                if (options.versionChanges || options.appStateChanges) {
                    installUpgrade.start(
                        versionChanges = options.versionChanges,
                        appStateChanges = options.appStateChanges,
                    )
                }
            }
        }

        /**
         * Installs the view-layer hooks. Idempotent; returns false when they were already on.
         *
         * Never called from `initialize()`. That is the contractual half of this change: an SDK
         * may not instrument a host app's UI as a side effect of being initialised.
         */
        fun startAutocapture(options: AutocaptureOptions? = null): Boolean {
            options?.let { config.autocaptureOptions = it }

            if (!autocaptureRunning.compareAndSet(false, true)) {
                logger.log("Autocapture is already running; ignoring this start()")
                return false
            }

            registerGlobalActivityLifecycleCallbacks()
            logger.log("Autocapture started with ${config.autocaptureOptions}")
            return true
        }

        /** Uninstalls the view-layer hooks. Idempotent; returns false when they were already off. */
        fun stopAutocapture(): Boolean {
            if (!autocaptureRunning.compareAndSet(true, false)) {
                logger.log("Autocapture is not running; ignoring this stop()")
                return false
            }

            (context.applicationContext as Application)
                .unregisterActivityLifecycleCallbacks(lifecycleCallBacks)
            logger.log("Autocapture stopped")
            return true
        }

        private fun registerGlobalActivityLifecycleCallbacks() {
            val application = context.applicationContext as Application
            application.registerActivityLifecycleCallbacks(lifecycleCallBacks)
        }
    }
