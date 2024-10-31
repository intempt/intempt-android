package com.intempt.core.eventModels

import android.content.Context
import com.intempt.core.types.AppVisibilityState



internal data class InstallOrUpgradeEvent(
    private val context: Context
): BaseIntemptEvent() {

//    private val currentVersionCode: Int = getCurrentVersionCode(context);
//    private val previousVersionCode: Int =  storage.getPreviousVersionCode();
//    private val previousBuildType: String = storage.getPreviousBuildType() ?: "";
//    private val currentBuildType: String = getConsumerAppBuildType(context) ?: "";
//    private val appVisibilityState: AppVisibilityState = storage.getAppVisibilityState();
//    private val isUpgrade: Boolean = defineUpgradeState();
//
//    private fun getCurrentVersionCode(context: Context): Int {
//        val versionCode = context
//            .packageManager
//            .getPackageInfo(context.packageName, 0)
//            .longVersionCode
//
//        return (versionCode and 0xFFFFFFFF).toInt()
//    }
//
//    private fun getConsumerAppBuildType(context: Context): String? {
//        return try {
//            val buildConfigClass = Class.forName("${context.packageName}.BuildConfig")
//            val buildTypeField = buildConfigClass.getField("BUILD_TYPE")
//            buildTypeField.get(null) as String
//        } catch (e: Exception) {
//            e.printStackTrace()
//            null
//        }
//    }
//
//
//    private fun defineUpgradeState(): Boolean {
//        return currentVersionCode != previousVersionCode
//                || currentBuildType != previousBuildType
//    }
//
//
//    override fun toString(): String {
//
//        val output = """
//            {
//                sessionId: $sessionId,
//                eventId: $eventId,
//                pageId: $pageId,
//                profileId: $profileId,
//                timestamp: $timestamp,
//                data: {
//                    currentVersionCode: ${currentVersionCode},
//                    previousVersionCode: ${previousVersionCode},
//                    previousBuildType: ${previousBuildType},
//                    currentBuildType: ${currentBuildType},
//                    currentBuildType: ${appVisibilityState},
//                    currentBuildType: ${appVisibilityState},
//                },
//            }
//        """
//        return output.trimIndent()
//
//
//    }
}
