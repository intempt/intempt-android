package com.intempt.core.autocapture.lifecycleCallbackManager


import dagger.Module
import dagger.Provides
import javax.inject.Singleton

@Module
internal class LifecycleCallbackModule  {
    @Provides
    @Singleton
    fun component(
        srv:LifecycleCallbackService
    ):LifecycleCallBacksComponent{
        return LifecycleCallBacksComponent(srv)
    }
}