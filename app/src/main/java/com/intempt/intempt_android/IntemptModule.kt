package com.intempt.intempt_android

import android.content.Context
import com.intempt.intempt_android.autocapture.AutoCaptureModule
import com.intempt.intempt_android.configManager.ConfigManagerModule
import com.intempt.intempt_android.eventPool.EventPool
import dagger.Component
import dagger.Module
import dagger.Provides
import javax.inject.Singleton



@Module(includes = [
    ConfigManagerModule::class,
    AutoCaptureModule::class
])
class IntemptModule(
    private val consumerContext: Context
) {

    @Provides
    fun provideContext(): Context {
        return consumerContext.applicationContext
    }


    @Provides
    @Singleton
    fun provideEventPool(): EventPool {
        return EventPool()
    }
}


@Singleton
@Component(modules = [IntemptModule::class])
interface IntemptComponent {
    fun inject(intempt: Intempt)



    @Component.Factory
    interface Factory {
        fun create(intemptModule: IntemptModule): IntemptComponent
    }

}