package com.intempt.core.autocapture.lifecycleCallbacksTracker

import com.intempt.core.services.eventPool.EventPoolManagerService
import dagger.Module
import dagger.Provides
import javax.inject.Provider
import javax.inject.Singleton

@Module
internal class LifecycleCallbackModule {
    @Provides
    @Singleton
    fun component(
        srv: LifecycleCallbackService,
        delivery: com.intempt.core.queue.DeliveryMessages,
        events: Provider<EventPoolManagerService>,
    ): LifecycleCallBacksComponent {
        return LifecycleCallBacksComponent(srv, delivery) { events.get().drainConsentsAsync() }
    }
}
