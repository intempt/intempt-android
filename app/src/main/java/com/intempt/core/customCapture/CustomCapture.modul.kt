package com.intempt.core.customCapture

import dagger.Module
import dagger.Provides
import javax.inject.Singleton


@Module()
internal class CustomCaptureModule {
    @Provides
    @Singleton
    fun component(
        //eventPool: EventPool
    ): CustomCaptureService{
       // return CustomCaptureService(eventPool)
        return CustomCaptureService()
    }


}