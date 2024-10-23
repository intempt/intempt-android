package com.intempt.intempt_android.autocapture.installUpgradeTracker

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.intempt.intempt_android.Logger
import com.intempt.intempt_android.StorageHandler
import com.intempt.intempt_android.autocapture.BaseComponent
import com.intempt.intempt_android.types.AppVisibilityState
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class InstallUpgradeTrackerComponent @Inject constructor(
    private val context: Context,
    private val srv: InstallUpgradeTrackerService,
): BaseComponent() {

    init{
        registerVisibilityTracking();
        registerInstallUpgradeTracking();
    }


    private fun registerVisibilityTracking(){
        val lifecycleObserver = object : DefaultLifecycleObserver {
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

    private fun registerInstallUpgradeTracking(){
        val currentVersionCode = srv.getConsumerAppVersionCode(context)
        val storedVersionCode = StorageHandler.getPreviousVersionCode()

        when {
            storedVersionCode == -1 -> {
                srv.logAndDispatch(context, "App Install detected")
            }
            storedVersionCode < currentVersionCode -> {
                srv.logAndDispatch(context, "App Upgrade detected from version $storedVersionCode to $currentVersionCode")
            }
            else -> {
                Logger.log("No Install/Upgrade event. Current version: $currentVersionCode")
            }
        }

        StorageHandler.setVersionCode(currentVersionCode)
    }


}