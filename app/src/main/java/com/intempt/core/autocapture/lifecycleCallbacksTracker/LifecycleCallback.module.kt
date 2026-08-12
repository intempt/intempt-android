package com.intempt.core.autocapture.lifecycleCallbacksTracker

import dagger.Module
import dagger.Provides
import javax.inject.Singleton

@Module
internal class LifecycleCallbackModule {
    @Provides
    @Singleton
    fun component(
        srv: LifecycleCallbackService,
        delivery: com.intempt.core.queue.DeliveryMessages,
    ): LifecycleCallBacksComponent  {
        return LifecycleCallBacksComponent(srv, delivery)
    }
}
