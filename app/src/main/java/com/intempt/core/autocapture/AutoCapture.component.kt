package com.intempt.core.autocapture
import android.app.Application
import android.content.Context
import com.intempt.core.autocapture.lifecycleCallbackManager.LifecycleCallBacksComponent
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.LoggerManagerService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class AutoCaptureComponent @Inject constructor(
    private val context: Context,
    private val lifecycleCallBacksManager: LifecycleCallBacksComponent,
    val logger: LoggerManagerService,
    private val config: ConfigManagerService
): BaseComponent(logger) {

    fun start(){
        if(!config.isAutoCaptureEnabled) return
        registerGlobalActivityLifecycleCallbacks();
    }


    private fun registerGlobalActivityLifecycleCallbacks() {
        val application = context.applicationContext as Application;
        application.registerActivityLifecycleCallbacks(lifecycleCallBacksManager)
    }
}




