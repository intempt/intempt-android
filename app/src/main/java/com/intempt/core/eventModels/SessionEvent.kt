package com.intempt.core.eventModels
import com.intempt.core.types.IntemptEventProvider

internal class SessionEvent(
    override val eventId: String,
    override val sessionId: String,
    override val pageId: String,
    override val profileId: String,
    override val timestamp: Long = System.currentTimeMillis(),
    val sessionStartEventName: String,
    val deviceName: String,
    val appName: String,
    val appVersion: String,
    val appIdentifier: String,
    val androidId: String,
    val source: String = "android",
    val userAttributes: SessionUserAttributes,
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
                    "sessionStartEventName" to sessionStartEventName,
                    "deviceName" to deviceName,
                    "source" to source,
                    "appName" to appName,
                    "appVersion" to appVersion,
                    "appIdentifier" to appIdentifier,
                    "androidId" to androidId,
                ),
            "userAttributes" to
                mapOf(
                    "deviceType" to userAttributes.deviceType,
                    "carrier" to userAttributes.carrier,
                    "platform" to userAttributes.platform,
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
                deviceName: $deviceName,
                source: $source,
                appName: $appName,
                appVersion: $appVersion,
                appIdentifier: $appIdentifier,
                androidId: $androidId
            },
            userAttributes: {
                deviceType: ${userAttributes.deviceType},
                carrier: ${userAttributes.carrier},
                platform: ${userAttributes.platform}
            }
        }
    """
        return output.trimIndent()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SessionEvent) return false
        return eventId == other.eventId &&
            sessionId == other.sessionId &&
            pageId == other.pageId &&
            profileId == other.profileId &&
            timestamp == other.timestamp &&
            sessionStartEventName == other.sessionStartEventName &&
            deviceName == other.deviceName &&
            appName == other.appName &&
            appVersion == other.appVersion &&
            appIdentifier == other.appIdentifier &&
            androidId == other.androidId &&
            source == other.source &&
            userAttributes == other.userAttributes
    }

    override fun hashCode(): Int {
        var result = eventId.hashCode()
        result = 31 * result + sessionId.hashCode()
        result = 31 * result + pageId.hashCode()
        result = 31 * result + profileId.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + sessionStartEventName.hashCode()
        result = 31 * result + deviceName.hashCode()
        result = 31 * result + appName.hashCode()
        result = 31 * result + appVersion.hashCode()
        result = 31 * result + appIdentifier.hashCode()
        result = 31 * result + androidId.hashCode()
        result = 31 * result + source.hashCode()
        result = 31 * result + userAttributes.hashCode()
        return result
    }
}
