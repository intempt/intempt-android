package com.intempt.intempt_android.autocapture.installUpgradeTracker

import android.content.Context
import com.intempt.intempt_android.Logger
import com.intempt.intempt_android.StorageHandler
import com.intempt.intempt_android.eventPool.EventPool
import com.intempt.intempt_android.types.AppVisibilityState
import com.intempt.intempt_android.types.Constants
import com.intempt.intempt_android.types.DispatchEventProps
import com.intempt.intempt_android.withTryCatch
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
internal class InstallUpgradeTrackerService @Inject constructor(
    private val eventSrv: EventPool
) {

    fun handleVisibilityState(state: AppVisibilityState){
        Logger.log("App is now in the $state state")
        StorageHandler.setAppVisibilityState(state)
    }

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

    fun logAndDispatch(context: Context, logMessage:String) {
        val errorMessage = "AutoCapture | InstallUpgradeTracker Error handling";
        withTryCatch(errorMessage) {
            Logger.log(logMessage)
            dispatchEvent(context)
        }
    }

    private fun dispatchEvent(context: Context) {
        eventSrv.dispatchEvent(
            DispatchEventProps(
                eventName = Constants.INSTALL_UPGRADE.EVENT_NAME,
                entityName = Constants.INSTALL_UPGRADE.ENTITY_NAME,
                type = Constants.INSTALL_UPGRADE.EVENT_TYPE,
                event = null,
                context = context,
            )
        )
    }
}