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
internal data class SessionUserAttributes(
    val deviceType: String,
    val carrier: String,
    val platform: String,
)
