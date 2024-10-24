package com.intempt.core.autocapture.installUpgradeTracker

import android.content.Context
import com.intempt.core.services.Logger
import com.intempt.core.services.StorageService
import com.intempt.core.services.eventPool.EventPool
import com.intempt.core.types.AppVisibilityState
import com.intempt.core.types.Constants
import com.intempt.core.types.DispatchEventProps
import com.intempt.core.services.withTryCatch
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
internal class InstallUpgradeTrackerService @Inject constructor(
    private val context: Context,
    private val eventSrv: EventPool,
    private val storage: StorageService,
) {

    fun handleVisibilityState(state: AppVisibilityState){
        Logger.log("App is now in the $state state")
        storage.setAppVisibilityState(state)
    }

    fun getStoredVersionCode(): Int {
        return storage.getPreviousVersionCode()
    }

    fun storeVersionCode(versionCode: Int) {
        storage.setVersionCode(versionCode)
    }

    fun getConsumerAppVersionCode(): Int {
        return try {
            val buildConfigClass = Class.forName("${context.packageName}.BuildConfig")
            val versionCodeField = buildConfigClass.getField("VERSION_CODE")
            versionCodeField.get(null) as Int
        } catch (e: Exception) {
            e.printStackTrace()
            -1
        }
    }

    fun logAndDispatch( logMessage:String) {
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