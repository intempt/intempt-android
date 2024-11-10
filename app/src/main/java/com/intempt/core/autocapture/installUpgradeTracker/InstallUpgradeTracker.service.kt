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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
internal class InstallUpgradeTrackerService @Inject constructor(
    private val context: Context,
    private val eventSrv: EventPoolManagerService,
    private val storage: StorageManagerService,
    val logger: LoggerManagerService,
    private val utils: UtilsService
){




    fun handleVisibilityState(state: AppVisibilityState){
        storage.setStorageItem(
            prefs = StorageKeys.AppPrefs.key,
            key = StorageKeys.AppVisibilityState.key,
            value = state
        ) { key, value ->
            putString(key, value.toString())
        }
    }

    fun getStoredVersionCode():Long {
        val fallbackVersion = -1L
        val versionCode = storage.getStorageItem(
            prefs = StorageKeys.AppPrefs.key,
            key = StorageKeys.PreviousVersionCode.key,
        ){ key, fallBack ->
            getLong(key, fallBack ?: fallbackVersion)
        } ?: fallbackVersion




        logger.log("InstallUpgradeTrackerService | Received version code: $versionCode")

        return versionCode
    }

    fun storeVersionCode(versionCode: Long) {
        logger.log("InstallUpgradeTrackerService | Store version code: $versionCode")

            storage.setStorageItem(
                prefs = StorageKeys.AppPrefs.key,
                key = StorageKeys.PreviousVersionCode.key,
                value = versionCode
            ) { key, value ->
                putLong(key, value)
            }


    }

    fun getConsumerAppVersionCode(): Long {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            logger.log("InstallUpgradeTrackerService | Consumer App packageInfo code: ${packageInfo.longVersionCode}")
            return packageInfo.longVersionCode
        } catch (e: Exception) {
            e.printStackTrace()
            logger.error("InstallUpgradeTrackerService | Error getting consumer app version code: ${e.message}")
            -1
        }
    }

    fun logAndDispatch( logMessage:String) {
        val errorMessage = "AutoCapture | InstallUpgradeTracker Error handling";
        utils.withTryCatch(errorMessage) {
            logger.log("InstallUpgradeTrackerComponent | $logMessage")
            eventSrv.dispatchEvent(
                DispatchEventProps(
                    eventName = Constants.INSTALL_UPGRADE.EVENT_NAME,
                    entityName = Constants.INSTALL_UPGRADE.ENTITY_NAME,
                    type = Constants.INSTALL_UPGRADE.EVENT_TYPE,
                    context = context,
                ),
                "InstallUpgradeTrackerService"
            )
        }
    }


}
