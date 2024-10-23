package com.intempt.intempt_android.autocapture.installUpgradeTracker

import android.content.Context
import com.intempt.intempt_android.eventPool.EventPool
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

@Module
internal class InstallUpgradeTrackerModule {
    @Provides
    @Singleton
    fun component(
        context: Context,
        service: InstallUpgradeTrackerService
    ): InstallUpgradeTrackerComponent {
        return InstallUpgradeTrackerComponent(
            context,
            service
        )
    }

    @Provides
    @Singleton
    fun service(eventSrv: EventPool,): InstallUpgradeTrackerService {
        return InstallUpgradeTrackerService(eventSrv)
    }

}