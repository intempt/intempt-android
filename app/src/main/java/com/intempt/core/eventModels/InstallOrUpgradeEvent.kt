package com.intempt.core.eventModels

import android.content.Context
import com.intempt.core.services.StorageManagerService
import com.intempt.core.types.AppVisibilityState
import com.intempt.core.types.IntemptEventProvider


internal data class InstallOrUpgradeEvent(
    override val eventId: String,
    override val sessionId: String,
    override val pageId: String,
    override val profileId: String,
    override val timestamp: Long = System.currentTimeMillis(),
    private val currentVersionCode: Int,
    private val previousVersionCode: Int,
    private val previousBuildType: String,
    private val currentBuildType: String,
    private val appVisibilityState: AppVisibilityState,
    private val isUpgrade: Boolean,
): IntemptEventProvider {
    override fun getEventTime(): Long {
        return timestamp
    }

    override fun toFormated(): Map<String, Any> {
        return mapOf(
            "sessionId" to sessionId,
            "eventId" to eventId,
            "pageId" to pageId,
            "profileId" to profileId,
            "timestamp" to timestamp,
            "data" to mapOf(
                "currentVersionCode" to currentVersionCode,
                "previousVersionCode" to previousVersionCode,
                "previousBuildType" to previousBuildType,
                "currentBuildType" to currentBuildType,
                "appVisibilityState" to appVisibilityState,
                "isUpgrade" to isUpgrade,
            )
        )
    }

    override fun toString(): String {
        val output = """
            {
                sessionId: $sessionId,
                eventId: $eventId,
                pageId: $pageId,
                profileId: $profileId,
                timestamp: $timestamp,
                data: {
                    currentVersionCode: ${currentVersionCode},
                    previousVersionCode: ${previousVersionCode},
                    previousBuildType: ${previousBuildType},
                    currentBuildType: ${currentBuildType},
                    currentBuildType: ${appVisibilityState},
                    currentBuildType: ${appVisibilityState},
                },
            }
        """
        return output.trimIndent()


    }
}
