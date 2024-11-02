package com.intempt.core.modifications

import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.HttpManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.StorageManagerService
import com.intempt.core.services.UtilsService
import dagger.Module
import dagger.Provides
import javax.inject.Singleton


@Module()
internal class ModificationsModule {

    @Provides
    @Singleton
    fun service(
        storage: StorageManagerService,
        config: ConfigManagerService,
        logger: LoggerManagerService,
        http: HttpManagerService,
        utils: UtilsService
    ): ModificationsService {
        return ModificationsService(
            storage,
            config,
            logger,
            http,
            utils
        )
    }

    @Provides
    @Singleton
    fun component(
        srv: ModificationsService
    ):ModificationComponent {
        return ModificationComponent(srv)

    }

}