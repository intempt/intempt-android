package com.intempt.core.autocapture.installUpgradeTracker

import android.content.Context
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

@Module
internal class InstallUpgradeTrackerModule {
//    @Provides
//    @Singleton
//    fun service(
//        context: Context,
//        //eventSrv: EventPool,
//        //storage: StorageManagerService,
//    ): InstallUpgradeTrackerService {
//        return InstallUpgradeTrackerService(
//            context,
//            //eventSrv,
//            //storage
//        )
//    }



    @Provides
    @Singleton
    fun component(
        service: InstallUpgradeTrackerService
    ): InstallUpgradeTrackerComponent {
        return InstallUpgradeTrackerComponent(service)
    }

}