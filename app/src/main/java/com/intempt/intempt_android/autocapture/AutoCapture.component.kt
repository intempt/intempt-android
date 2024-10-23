package com.intempt.intempt_android.autocapture
import android.app.Application
import android.content.Context
import com.intempt.intempt_android.StorageHandler
import com.intempt.intempt_android.autocapture.lifecycleCallbackManager.LifecycleCallBacksManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AutoCaptureComponent @Inject constructor(
    private val context: Context,
    private val lifecycleCallBacksManager: LifecycleCallBacksManager,
): BaseComponent() {
    init{
        StorageHandler.register(context);
        registerGlobalActivityLifecycleCallbacks();
    }

    private fun registerGlobalActivityLifecycleCallbacks() {
        val application = context.applicationContext as Application;
        application.registerActivityLifecycleCallbacks(lifecycleCallBacksManager)
    }
}