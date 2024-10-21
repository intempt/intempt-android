package com.intempt.intempt_android

import android.content.Context
import com.intempt.intempt_android.autocapture.AutoCapture
import com.intempt.intempt_android.autocapture.changeTracker.ChangeTracker
import com.intempt.intempt_android.autocapture.screenTracker.ScreenTracker
import com.intempt.intempt_android.autocapture.touchTracker.TouchTracker
import com.intempt.intempt_android.eventPool.EventPool
import dagger.Component
import dagger.Module
import dagger.Provides
import javax.inject.Singleton


@Module
class IntemptModule(private val consumerContext: Context) {

    @Provides
    fun provideContext(): Context {
        return consumerContext.applicationContext
    }

    @Provides
    @Singleton
    fun provideAutoCapture(
        context: Context,
        activityTracker: ScreenTracker,
        eventSrv: EventPool,
    ): AutoCapture {
        return AutoCapture(
            context,
            activityTracker,
            eventSrv,
        )
    }

    @Provides
    @Singleton
    fun provideTouchTracker(eventSrv: EventPool): TouchTracker {
        return TouchTracker(eventSrv)
    }

    @Provides
    @Singleton
    fun provideChangeTracker(eventSrv: EventPool): ChangeTracker {
        return ChangeTracker(eventSrv)
    }

    @Provides
    @Singleton
    fun provideEventPool(): EventPool {
        return EventPool()
    }

    @Provides
    @Singleton
    fun provideActivityTracker(
        touchTracker: TouchTracker,
        changeTracker: ChangeTracker,
        eventSrv: EventPool
    ): ScreenTracker {
        return ScreenTracker(
            touchTracker,
            changeTracker,
            eventSrv
        )  // Ensure TouchTracker is injected
    }
}


@Singleton
@Component(modules = [IntemptModule::class])
interface IntemptComponent {
    fun inject(intempt: Intempt)
    fun inject(activityTracker: ScreenTracker)

}