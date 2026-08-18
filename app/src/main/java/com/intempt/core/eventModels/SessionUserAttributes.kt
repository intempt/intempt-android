package com.intempt.core.eventModels

/**
 * Device facts attached to a session-start event.
 *
 * The geo fields — `ipAddress`, `city`, `region`, `country` — used to live here, populated from a
 * per-session call to ipapi.co. They are gone, matching mixpanel-android: geo is derived
 * server-side from the source IP of the request the platform already receives, so the device never
 * handles its own IP and no third party is involved.
 *
 * What remains is what only the device can know.
 */
internal class SessionUserAttributes(
    val deviceType: String,
    val carrier: String,
    val platform: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SessionUserAttributes) return false
        return deviceType == other.deviceType && carrier == other.carrier && platform == other.platform
    }

    override fun hashCode(): Int {
        var result = deviceType.hashCode()
        result = 31 * result + carrier.hashCode()
        result = 31 * result + platform.hashCode()
        return result
    }
}
