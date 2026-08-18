@file:OptIn(com.intempt.core.internal.InternalIntemptApi::class)

package com.intempt.core.eventModels

import com.intempt.core.services.ConfigManagerService
import com.intempt.core.types.AppVisibilityState
import com.intempt.core.types.IntemptEventProvider

// See ScreenViewEvent.kt: `data class` exempted this from LongParameterList; dropping `data` for
// the method-count trim lost that exemption without growing the parameter list.
@Suppress("LongParameterList")
internal class InstallOrUpgradeEvent(
    override val eventId: String,
    override val sessionId: String,
    override val pageId: String,
    override val profileId: String,
    override val timestamp: Long = System.currentTimeMillis(),
    private val currentVersionCode: Long,
    private val previousVersionCode: Long,
    private val previousBuildType: String,
    private val currentBuildType: String,
    private val appVisibilityState: AppVisibilityState,
    private val isUpgrade: Boolean,
    private val token: String,
    private val config: ConfigManagerService,
) : IntemptEventProvider {
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
            "data" to
                mapOf(
                    "currentVersionCode" to currentVersionCode,
                    "previousVersionCode" to previousVersionCode,
                    "previousBuildType" to previousBuildType,
                    "currentBuildType" to currentBuildType,
                    "appVisibilityState" to appVisibilityState,
                    "isUpgrade" to isUpgrade,
                ),
            "userAttributes" to
                mapOf(
                    "fcm_token_" + config.sourceId to token,
                ),
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
                    currentVersionCode: $currentVersionCode,
                    previousVersionCode: $previousVersionCode,
                    previousBuildType: $previousBuildType,
                    currentBuildType: $currentBuildType,
                    currentBuildType: $appVisibilityState,
                    currentBuildType: $appVisibilityState,
                },
                userAttributes: {
                    fcm_token_${config.sourceId}: $token
                }
            }
        """
        return output.trimIndent()
    }

    // See SessionEvent.kt: complexity is one branch per field, the shape a `data class` would
    // have generated unlinted.
    @Suppress("CyclomaticComplexMethod")
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is InstallOrUpgradeEvent) return false
        return eventId == other.eventId &&
            sessionId == other.sessionId &&
            pageId == other.pageId &&
            profileId == other.profileId &&
            timestamp == other.timestamp &&
            currentVersionCode == other.currentVersionCode &&
            previousVersionCode == other.previousVersionCode &&
            previousBuildType == other.previousBuildType &&
            currentBuildType == other.currentBuildType &&
            appVisibilityState == other.appVisibilityState &&
            isUpgrade == other.isUpgrade &&
            token == other.token &&
            config == other.config
    }

    override fun hashCode(): Int {
        var result = eventId.hashCode()
        result = 31 * result + sessionId.hashCode()
        result = 31 * result + pageId.hashCode()
        result = 31 * result + profileId.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + currentVersionCode.hashCode()
        result = 31 * result + previousVersionCode.hashCode()
        result = 31 * result + previousBuildType.hashCode()
        result = 31 * result + currentBuildType.hashCode()
        result = 31 * result + appVisibilityState.hashCode()
        result = 31 * result + isUpgrade.hashCode()
        result = 31 * result + token.hashCode()
        result = 31 * result + config.hashCode()
        return result
    }
}
