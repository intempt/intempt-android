package com.intempt.core.autocapture

import android.content.Context
import com.intempt.core.autocapture.changeTracker.ChangeTrackerModule
import com.intempt.core.autocapture.installUpgradeTracker.InstallUpgradeTrackerComponent
import com.intempt.core.autocapture.installUpgradeTracker.InstallUpgradeTrackerModule
import com.intempt.core.autocapture.lifecycleCallbackManager.LifecycleCallBacksComponent
import com.intempt.core.autocapture.lifecycleCallbackManager.LifecycleCallbackModule
import com.intempt.core.autocapture.screenTracker.ScreenTrackerModule
import com.intempt.core.autocapture.sessionTracker.SessionTrackerComponent
import com.intempt.core.autocapture.sessionTracker.SessionTrackerModule
import com.intempt.core.autocapture.touchTracker.TouchTrackerModule
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.LoggerManagerService
import dagger.Module
import dagger.Provides
import javax.inject.Singleton


@Module(includes = [
    SessionTrackerModule::class,
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
        config: ConfigManagerService,
        logger: LoggerManagerService,
        session: SessionTrackerComponent,
        installUpgrade: InstallUpgradeTrackerComponent,
        lifecycleCallBacksManager: LifecycleCallBacksComponent,
    ): AutoCaptureComponent {
        return AutoCaptureComponent(
            context,
            lifecycleCallBacksManager,
            logger,
            config
        )
    }
}


