package com.intempt.core.autocapture.lifecycleCallbackManager

import android.util.Log
import com.intempt.core.autocapture.changeTracker.ChangeTrackerComponent
import com.intempt.core.autocapture.screenTracker.ScreenTrackerComponent
import com.intempt.core.autocapture.touchTracker.TouchTrackerComponent
import com.intempt.core.services.ConfigManagerService
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

@Module
internal class LifecycleCallbackModule  {

    @Provides
    @Singleton
    fun provideLifecycleCallBacksManager(
        config: ConfigManagerService,
        screenTrackerComponent: ScreenTrackerComponent,
        changeTrackerComponent: ChangeTrackerComponent,
        touchTrackerComponent: TouchTrackerComponent
    ): LifecycleCallBacksManager {
        val activityLifecycleListenersList = mutableListOf<ActivityLifecycleListener>()
        val fragmentLifecycleListenersList = mutableListOf<FragmentLifecycleListener>()

        Log.d("LifecycleCallbackModule: isTouchEnabled", "${config.isTouchEnabled}")

        when {
            config.isTouchEnabled  -> {
                activityLifecycleListenersList.add(touchTrackerComponent)
                fragmentLifecycleListenersList.add(touchTrackerComponent)
            }
            else -> {

                activityLifecycleListenersList.add(screenTrackerComponent)
                fragmentLifecycleListenersList.add(screenTrackerComponent)

                activityLifecycleListenersList.add(changeTrackerComponent)
                fragmentLifecycleListenersList.add(changeTrackerComponent)

            }

        }

        return LifecycleCallBacksManager(
            activityLifecycleListeners = activityLifecycleListenersList,
            fragmentLifecycleListeners = fragmentLifecycleListenersList
        )
    }
}