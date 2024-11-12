package com.intempt.core.autocapture.installUpgradeTracker

import com.intempt.core.services.LoggerManagerService
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

@Module
internal class InstallUpgradeTrackerModule {
    @Provides
    @Singleton
    fun component(
        service: InstallUpgradeTrackerService
    ): InstallUpgradeTrackerComponent {
        return InstallUpgradeTrackerComponent(service)
    }
}
