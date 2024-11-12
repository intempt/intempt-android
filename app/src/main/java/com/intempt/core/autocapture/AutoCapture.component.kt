package com.intempt.core.autocapture
import android.app.Application
import android.content.Context
import com.intempt.core.autocapture.installUpgradeTracker.InstallUpgradeTrackerComponent
import com.intempt.core.autocapture.lifecycleCallbacksTracker.LifecycleCallBacksComponent
import com.intempt.core.autocapture.sessionTracker.SessionTrackerComponent
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.StorageManagerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class AutoCaptureComponent @Inject constructor(
    val logger: LoggerManagerService,
    private val context: Context,
    private val storage: StorageManagerService,
    private val config: ConfigManagerService,
    private val session: SessionTrackerComponent,
    private val installUpgrade: InstallUpgradeTrackerComponent,
    private val lifecycleCallBacks: LifecycleCallBacksComponent,
): BaseComponent(logger) {

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(){
        if(!config.isAutoCaptureEnabled) return

        coroutineScope.launch {
            storage.validateProfileId()
            registerGlobalActivityLifecycleCallbacks();
            session.start()
            installUpgrade.start()
        }


    }


    private fun registerGlobalActivityLifecycleCallbacks() {
        val application = context.applicationContext as Application;
        application.registerActivityLifecycleCallbacks(lifecycleCallBacks)
    }
}




