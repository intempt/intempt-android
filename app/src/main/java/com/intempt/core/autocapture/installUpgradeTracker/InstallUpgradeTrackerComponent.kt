package com.intempt.core.autocapture.installUpgradeTracker

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.intempt.core.autocapture.BaseComponent
import com.intempt.core.types.AppVisibilityState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class InstallUpgradeTrackerComponent
    @Inject
    constructor(
        private val srv: InstallUpgradeTrackerService,
        private val dispatcher: CoroutineDispatcher = Dispatchers.Main,
    ) : BaseComponent(srv.logger) {
        /**
         * Each half behind its own switch.
         *
         * These were one `start()` that always did both, so an app that wanted install/upgrade
         * events also got an Application Opened and an Application Backgrounded on every single
         * transition — the highest-volume automatic event the SDK has, emitted by a call that
         * never mentioned it. The contract separates them and defaults both to off.
         */
        suspend fun start(
            versionChanges: Boolean,
            appStateChanges: Boolean,
        ) {
            if (appStateChanges) registerVisibilityTracking()
            if (versionChanges) registerInstallUpgradeTracking()
        }

        private suspend fun registerVisibilityTracking() =
            withContext(dispatcher) {
                val lifecycleObserver =
                    object : DefaultLifecycleObserver {
                        override fun onStart(owner: LifecycleOwner) {
                            super.onStart(owner)
                            srv.handleVisibilityState(AppVisibilityState.Foreground)
                        }

                        override fun onStop(owner: LifecycleOwner) {
                            super.onStop(owner)
                            srv.handleVisibilityState(AppVisibilityState.Background)
                        }
                    }

                ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
            }

        private suspend fun registerInstallUpgradeTracking() =
            withContext(dispatcher) {
                val currentVersionCode = srv.getConsumerAppVersionCode()
                val storedVersionCode = srv.getStoredVersionCode()
                val invalidCode = -1L
                when {
                    storedVersionCode == invalidCode -> {
                        srv.logAndDispatch("App Install detected")
                    }
                    storedVersionCode < currentVersionCode -> {
                        srv.logAndDispatch(
                            "App Upgrade detected from version $storedVersionCode to $currentVersionCode",
                        )
                    }
                    else -> {
                        srv.logger.log("No Install/Upgrade event. Current version: $currentVersionCode")
                    }
                }
                srv.storeVersionCode(currentVersionCode)
            }
    }
