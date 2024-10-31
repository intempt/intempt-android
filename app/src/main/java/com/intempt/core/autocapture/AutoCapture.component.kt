package com.intempt.core.autocapture
import android.app.Application
import android.content.Context
import com.intempt.core.autocapture.lifecycleCallbackManager.LifecycleCallBacksComponent
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class AutoCaptureComponent @Inject constructor(
    private val context: Context,
    private val lifecycleCallBacksManager: LifecycleCallBacksComponent,

    ): BaseComponent() {
    init{

        registerGlobalActivityLifecycleCallbacks();
    }


    private fun registerGlobalActivityLifecycleCallbacks() {
        val application = context.applicationContext as Application;
        application.registerActivityLifecycleCallbacks(lifecycleCallBacksManager)
    }
}




