package com.intempt.core.autocapture.installUpgradeTracker

import android.content.Context
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.StorageManagerService
import com.intempt.core.services.UtilsService
import com.intempt.core.services.eventPool.EventPoolManagerService
import com.intempt.core.types.AppVisibilityState
import com.intempt.core.types.Constants
import com.intempt.core.types.DispatchEventProps
import com.intempt.core.types.StorageKeys
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
internal class InstallUpgradeTrackerService @Inject constructor(
    private val context: Context,
    private val eventSrv: EventPoolManagerService,
    private val storage: StorageManagerService,
    private val logger: LoggerManagerService,
    private val utils: UtilsService
){


    fun handleVisibilityState(state: AppVisibilityState){
        logger.log("InstallUpgradeTrackerService | App is now in the $state state")
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
        logger.log("InstallUpgradeTrackerService | Received version code: $versionCode")

        return versionCode
    }

    fun storeVersionCode(versionCode: Int) {
        logger.log("InstallUpgradeTrackerService | Store version code: $versionCode")
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
            logger.log("InstallUpgradeTrackerService | Consumer App version code: $consumerCode")
            return consumerCode
        } catch (e: Exception) {
            logger.error("InstallUpgradeTrackerService | Error getting consumer app version code: ${e.message}")
            -1
        }
    }

    fun logAndDispatch( logMessage:String) {
        val errorMessage = "AutoCapture | InstallUpgradeTracker Error handling";
        utils.withTryCatch(errorMessage) {
            logger.log("InstallUpgradeTrackerComponent | $logMessage")
            dispatchEvent()
        }
    }

    private fun dispatchEvent() {
        eventSrv.dispatchEvent(
            DispatchEventProps(
                eventName = Constants.INSTALL_UPGRADE.EVENT_NAME,
                entityName = Constants.INSTALL_UPGRADE.ENTITY_NAME,
                type = Constants.INSTALL_UPGRADE.EVENT_TYPE,
                context = context,
            )
        )
    }


}
