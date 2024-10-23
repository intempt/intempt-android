package com.intempt.intempt_android.configManager

import android.content.Context
import dagger.Module
import dagger.Provides
import javax.inject.Singleton


@Module
internal class ConfigManagerModule {
    @Provides
    @Singleton
    fun service(
        context: Context,
    ): ConfigManagerService {
        return ConfigManagerService(context)
    }


}