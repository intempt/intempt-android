package com.intempt.core.intemptCore

import com.intempt.core.BuildConfig
import com.intempt.core.autocapture.AutoCaptureComponent
import com.intempt.core.customCapture.CustomCaptureComponent
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.ErrorReporter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class IntemptCoreService
    @Inject
    constructor(
        private val autoCaptureComponent: AutoCaptureComponent,
        private val customCaptureComponent: CustomCaptureComponent,
        private val configManager: ConfigManagerService,
        private val errorReporter: ErrorReporter,
    ) {
        internal val capture: CustomCaptureComponent
            get() = customCaptureComponent

        internal val autoCapture: AutoCaptureComponent
            get() = autoCaptureComponent

        internal val config: ConfigManagerService
            get() = configManager

        internal val errors: ErrorReporter
            get() = errorReporter

        init {
            // Automatic events only. Autocapture is NOT started here — installing view-layer hooks
            // because someone called initialize() is what the contract forbids, and what the single
            // `autoCaptureComponent.start()` that used to live on this line did.
            autoCaptureComponent.startAutomaticEvents()
            autoCaptureComponent.logger.log("Intempt SDK initialized")
            autoCaptureComponent.logger.log("VERSION: ${BuildConfig.sdkVersion}")
        }

        /**
         * Starts autocapture when the config asset asked for it.
         *
         * Called by `Intempt.initialize`, not from `init`, so the decision is visible at the point
         * a reader looks for it. `isAutoCaptureEnabled: true` in intempt-config.json is a host app
         * explicitly requesting instrumentation, which satisfies the contract's opt-in rule — an
         * SDK assuming it is what does not.
         */
        internal fun startAutocaptureIfConfigured() {
            if (configManager.autocaptureEnabledByConfig) {
                autoCaptureComponent.startAutocapture()
            } else {
                autoCaptureComponent.logger.log(
                    "Autocapture is off; call Intempt.autocapture.start() or set " +
                        "isAutoCaptureEnabled in intempt-config.json",
                )
            }
        }
    }
