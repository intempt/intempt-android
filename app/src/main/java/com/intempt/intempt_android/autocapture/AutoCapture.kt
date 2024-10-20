package com.intempt.intempt_android.autocapture
import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.intempt.intempt_android.types.AppVisibilityState
import com.intempt.intempt_android.types.DispatchEventProps
import com.intempt.intempt_android.EventPool
import com.intempt.intempt_android.Logger
import com.intempt.intempt_android.StorageHandler
import com.intempt.intempt_android.autocapture.screenTracker.ScreenTracker
import javax.inject.Inject

class AutoCapture @Inject constructor(
    private val context: Context,
    private val screenTracker: ScreenTracker,
    private val eventSrv: EventPool,
) {
    init{
        StorageHandler.register(context);
        registerGlobalActivityLifecycleCallbacks();
        registerVisibilityTracking();
        registerInstallUpgradeTracking();

    }

    private fun registerGlobalActivityLifecycleCallbacks() {
        val application = context.applicationContext as Application;
        application.registerActivityLifecycleCallbacks(screenTracker)
    }

    private fun registerVisibilityTracking(){
        val lifecycleObserver = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                super.onStart(owner)
                Logger.log("App is now in the Foreground")
                StorageHandler.setAppVisibilityState(AppVisibilityState.Foreground)
            }

            override fun onStop(owner: LifecycleOwner) {
                super.onStop(owner)
                Logger.log("App is now in the Background")
                StorageHandler.setAppVisibilityState(AppVisibilityState.Background)
            }
        }

        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)

    }

    private fun registerInstallUpgradeTracking(){
        fun getConsumerAppVersionCode(context: Context): Int {
            return try {
                val buildConfigClass = Class.forName("${context.packageName}.BuildConfig")
                val versionCodeField = buildConfigClass.getField("VERSION_CODE")
                versionCodeField.get(null) as Int
            } catch (e: Exception) {
                e.printStackTrace()
                -1
            }
        }

        fun dispatchInstallOrUpgradeEvent() {
            eventSrv.dispatchEvent(
                DispatchEventProps(
                    eventName = "App install/upgrade",
                    entityName="installUpgrade",
                    type = "installOrUpgrade",
                    event = null,
                    context = context,
                )
            )
        }

        val currentVersionCode = getConsumerAppVersionCode(context)
        val storedVersionCode = StorageHandler.getPreviousVersionCode()

        when {
            storedVersionCode == -1 -> {
                Logger.log("App Install detected")
                dispatchInstallOrUpgradeEvent()

            }
            storedVersionCode < currentVersionCode -> {
                Logger.log("App Upgrade detected from version $storedVersionCode to $currentVersionCode")
                dispatchInstallOrUpgradeEvent()
            }
            else -> {
                Logger.log("No Install/Upgrade event. Current version: $currentVersionCode")
            }
        }

        StorageHandler.setVersionCode(currentVersionCode)
    }
}