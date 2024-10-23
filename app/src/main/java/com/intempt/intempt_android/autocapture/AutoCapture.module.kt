package com.intempt.intempt_android.autocapture

import android.content.Context
import com.intempt.intempt_android.autocapture.changeTracker.ChangeTrackerModule
import com.intempt.intempt_android.autocapture.installUpgradeTracker.InstallUpgradeTrackerModule
import com.intempt.intempt_android.autocapture.lifecycleCallbackManager.LifecycleCallBacksManager
import com.intempt.intempt_android.autocapture.lifecycleCallbackManager.LifecycleCallbackModule
import com.intempt.intempt_android.autocapture.screenTracker.ScreenTrackerModule
import com.intempt.intempt_android.autocapture.touchTracker.TouchTrackerModule
import dagger.Module
import dagger.Provides
import javax.inject.Singleton


@Module(includes = [
    InstallUpgradeTrackerModule::class,
    ScreenTrackerModule::class,
    ChangeTrackerModule::class,
    TouchTrackerModule::class,
    LifecycleCallbackModule::class,
])
internal class AutoCaptureModule {

    @Provides
    @Singleton
    fun provideAutoCapture(
        context: Context,
        lifecycleCallBacksManager: LifecycleCallBacksManager
    ): AutoCaptureComponent {
        return AutoCaptureComponent(
            context,
            lifecycleCallBacksManager
        )
    }

}