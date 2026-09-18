@file:OptIn(com.intempt.core.internal.InternalIntemptApi::class)

package com.intempt.core.autocapture.installUpgradeTracker

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.intempt.core.autocapture.BaseComponent
import com.intempt.core.internal.PushBridge
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
         *
         * **[registerInstallUpgradeTracking] is not one of those halves and is no longer gated.**
         * It carries two unrelated things: the version-change *event*, which [versionChanges]
         * still decides, and the FCM device token, which nothing may decide. The token reaches
         * the platform through this path and no other, so gating it behind an event-volume switch
         * meant a host app that followed the README obtained a token from Firebase and never
         * registered it — no error, no log, and push silently never arrived.
         */
        suspend fun start(
            versionChanges: Boolean,
            appStateChanges: Boolean,
        ) {
            if (appStateChanges) registerVisibilityTracking()
            registerInstallUpgradeTracking(versionChanges)
        }

        /**
         * FCM rotated the device token; announce the new one without waiting for a version change.
         *
         * Called by `:push` through `Intempt.registerPushToken()`. The previous rotation handler
         * emitted `track("App install/upgrade", {deviceToken})`, which is not a shape the platform
         * reads: a push destination resolves its token from the profile attribute
         * `fcm_token_<sourceId>`, and only the install/upgrade event writes that attribute.
         */
        suspend fun registerPushToken() =
            withContext(dispatcher) {
                if (claimNewPushToken()) {
                    srv.logAndDispatch("Push token registration")
                } else {
                    srv.logger.log("No push token to register, or it is already registered")
                }
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

        private suspend fun registerInstallUpgradeTracking(versionChanges: Boolean) =
            withContext(dispatcher) {
                val currentVersionCode = srv.getConsumerAppVersionCode()
                val storedVersionCode = srv.getStoredVersionCode()
                val invalidCode = -1L

                // Claimed before the version branch, and never gated: this event is the only thing
                // that puts `fcm_token_<sourceId>` on the wire.
                val hasNewToken = claimNewPushToken()

                val versionReason =
                    when {
                        !versionChanges -> null
                        storedVersionCode == invalidCode -> "App Install detected"
                        storedVersionCode < currentVersionCode ->
                            "App Upgrade detected from version $storedVersionCode to $currentVersionCode"
                        else -> null
                    }

                // One event, not two. The install/upgrade event already carries the token, so when
                // both reasons hold at once there is nothing a second dispatch would add.
                val reason = versionReason ?: "Push token registration".takeIf { hasNewToken }

                if (reason != null) {
                    srv.logAndDispatch(reason)
                } else {
                    srv.logger.log("No Install/Upgrade event. Current version: $currentVersionCode")
                }

                // Only when the version-change half actually ran. With [versionChanges] off the
                // stored code stays -1, so turning the flag on later still reports the install
                // rather than finding it silently consumed by a launch that never emitted it.
                if (versionChanges) srv.storeVersionCode(currentVersionCode)
            }

        /**
         * Reads the current FCM token and records it as registered, answering whether it is new.
         *
         * Recorded *before* the dispatch rather than after. The cost of this order is that a token
         * lost between here and the durable queue waits for the next rotation; the cost of the
         * other order is a duplicate registration on every launch until the handler has run, which
         * is both worse and far more likely. Returns false when `:push` is absent, when Firebase
         * is unconfigured, or when this exact token was already announced.
         */
        private suspend fun claimNewPushToken(): Boolean {
            val token = PushBridge.registerTokenIfPresent()
            if (token.isNullOrBlank() || token == srv.getStoredPushToken()) return false
            srv.storePushToken(token)
            return true
        }
    }
