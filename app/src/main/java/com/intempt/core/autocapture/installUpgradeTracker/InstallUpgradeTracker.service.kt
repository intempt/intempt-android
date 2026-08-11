package com.intempt.core.autocapture.installUpgradeTracker

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
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
internal class InstallUpgradeTrackerService
    @Inject
    constructor(
        private val context: Context,
        private val eventSrv: EventPoolManagerService,
        private val storage: StorageManagerService,
        val logger: LoggerManagerService,
        private val utils: UtilsService,
    ) {
        fun handleVisibilityState(state: AppVisibilityState)  {
            storage.setStorageItem(
                prefs = StorageKeys.AppPrefs.key,
                key = StorageKeys.AppVisibilityState.key,
                value = state.key,
            ) { key, value ->
                putString(key, value)
            }
        }

        fun getStoredVersionCode(): Long {
            val fallbackVersion = -1L
            val versionCode =
                storage.getStorageItem(
                    prefs = StorageKeys.AppPrefs.key,
                    key = StorageKeys.PreviousVersionCode.key,
                ) { key, fallBack ->
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
                value = versionCode,
            ) { key, value ->
                putLong(key, value)
            }
        }

        fun getConsumerAppVersionCode(): Long {
            return try {
                val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                // PackageInfoCompat, not packageInfo.longVersionCode: the latter is API 28 and
                // lint caught it. The compat helper returns the same Long and falls back to the
                // deprecated Int versionCode below 28.
                val code = PackageInfoCompat.getLongVersionCode(packageInfo)
                logger.log("InstallUpgradeTrackerService | Consumer App packageInfo code: $code")
                return code
            } catch (e: Exception) {
                e.printStackTrace()
                logger.error("InstallUpgradeTrackerService | Error getting consumer app version code: ${e.message}")
                -1
            }
        }

        fun logAndDispatch(logMessage: String) {
            val errorMessage = "AutoCapture | InstallUpgradeTracker Error handling"
            utils.withTryCatch(errorMessage) {
                logger.log("InstallUpgradeTrackerComponent | $logMessage")
                eventSrv.dispatchEvent(
                    DispatchEventProps(
                        eventName = Constants.INSTALL_UPGRADE.EVENT_NAME,
                        entityName = Constants.INSTALL_UPGRADE.ENTITY_NAME,
                        type = Constants.INSTALL_UPGRADE.EVENT_TYPE,
                        context = context,
                    ),
                    "InstallUpgradeTrackerService",
                )
            }
        }
    }
