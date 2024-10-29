package com.intempt.core.autocapture.installUpgradeTracker

import android.content.Context
import com.intempt.core.autocapture.BaseComponent
import com.intempt.core.services.Logger
import com.intempt.core.services.StorageManagerService
import com.intempt.core.services.eventPool.EventPoolManagerService
import com.intempt.core.services.generateId
import com.intempt.core.types.AppVisibilityState
import com.intempt.core.services.withTryCatch
import com.intempt.core.types.Constants
import com.intempt.core.types.DispatchEventProps
import com.intempt.core.types.IdTypeKeys
import com.intempt.core.types.StorageKeys
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
internal class InstallUpgradeTrackerService @Inject constructor(
    private val context: Context,
    private val eventSrv: EventPoolManagerService,
    private val storage: StorageManagerService,
){


    fun handleVisibilityState(state: AppVisibilityState){
        Logger.log("InstallUpgradeTrackerService | App is now in the $state state")
        storage.setStorageItem(
            prefs = StorageKeys.AppPrefs.key,
            key = StorageKeys.AppVisibilityState.key,
            value = state
        ) { key, value ->
            putString(key, value.toString())
        }
    }

    fun getStoredVersionCode():Int {
        val fallbackVersion = -1
        val versionCode = storage.getStorageItem(
            prefs = StorageKeys.AppPrefs.key,
            key = StorageKeys.PreviousVersionCode.key,
        ){ key, fallBack ->
            getInt(key, fallBack ?: fallbackVersion)
        } ?: fallbackVersion
        Logger.log("InstallUpgradeTrackerService | Received version code: $versionCode")

        return versionCode
    }

    fun storeVersionCode(versionCode: Int) {
        Logger.log("InstallUpgradeTrackerService | Store version code: $versionCode")
        storage.setStorageItem(
            prefs = StorageKeys.AppPrefs.key,
            key = StorageKeys.PreviousVersionCode.key,
            value = versionCode
        ) { key, value ->
            putInt(key, value)
        }
    }

    fun getConsumerAppVersionCode(): Int {
        return try {
            val buildConfigClass = Class.forName("${context.packageName}.BuildConfig")
            val versionCodeField = buildConfigClass.getField("VERSION_CODE")
            val consumerCode = versionCodeField.get(null) as Int
            Logger.log("InstallUpgradeTrackerService | Consumer App version code: $consumerCode")
            return consumerCode
        } catch (e: Exception) {
            Logger.error("InstallUpgradeTrackerService | Error getting consumer app version code: ${e.message}")
            -1
        }
    }

    fun logAndDispatch( logMessage:String) {
        val errorMessage = "AutoCapture | InstallUpgradeTracker Error handling";
        withTryCatch(errorMessage) {
            Logger.log("InstallUpgradeTrackerComponent | $logMessage")
            dispatchEvent()
        }
    }

    private fun dispatchEvent() {
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
