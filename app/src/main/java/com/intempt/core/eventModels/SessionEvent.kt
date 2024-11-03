package com.intempt.core.eventModels
import com.intempt.core.types.IntemptEventProvider



internal data class SessionEvent(
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
    val userAttributes:SessionUserAttributes
): IntemptEventProvider {
    override fun getEventTime(): Long {
        return timestamp
    }

    override fun toFormatted(): Map<String, Any> {
        return mapOf(
            "sessionId" to sessionId,
            "eventId" to eventId,
            "pageId" to pageId,
            "profileId" to profileId,
            "timestamp" to timestamp,
            "data" to mapOf(
                "deviceName" to deviceName,
                "source" to source,
                "appName" to appName,
                "appVersion" to appVersion,
                "appIdentifier" to appIdentifier,
                "androidId" to androidId
            ),
            "userAttributes" to mapOf(
                "deviceType" to userAttributes.deviceType,
                "carrier" to userAttributes.carrier,
                "platform" to userAttributes.platform,
                "ipAddress" to userAttributes.ipAddress,
                "region" to userAttributes.region,
                "country" to userAttributes.country,
                "city" to userAttributes.city
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
                platform: ${userAttributes.platform},
                ipAddress: ${userAttributes.ipAddress},
                region: ${userAttributes.region},
                country: ${userAttributes.country},
                city: ${userAttributes.city}
            }
        }
    """
    return output.trimIndent()
}

}