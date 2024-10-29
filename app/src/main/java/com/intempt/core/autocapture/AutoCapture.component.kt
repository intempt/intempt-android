package com.intempt.core.autocapture
import android.app.Application
import android.content.Context
import com.intempt.core.autocapture.installUpgradeTracker.InstallUpgradeTrackerService
import com.intempt.core.autocapture.lifecycleCallbackManager.LifecycleCallBacksManager
import com.intempt.core.autocapture.sessiontracker.SessionTrackerService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class AutoCaptureComponent @Inject constructor(
    private val context: Context,
//    private val sessionSrv: SessionTrackerService,
//    private val installUpgradeSrv: InstallUpgradeTrackerService,
    private val lifecycleCallBacksManager: LifecycleCallBacksManager,

    ): BaseComponent() {
    init{

        registerGlobalActivityLifecycleCallbacks();
    }


    private fun registerGlobalActivityLifecycleCallbacks() {
        val application = context.applicationContext as Application;
        application.registerActivityLifecycleCallbacks(lifecycleCallBacksManager)
    }
}




