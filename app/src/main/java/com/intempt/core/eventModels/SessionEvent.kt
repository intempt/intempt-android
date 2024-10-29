package com.intempt.core.eventModels

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.intempt.core.types.Constants


@SuppressLint("HardwareIds")
internal data class SessionEvent(
    val context: Context,
    val sessionStartEventName: String,
    val ipAddress: String = "",
    val city: String = "",
    val region: String = "",
    val country: String = ""
): BaseIntemptEvent(Constants.SESSION.EVENT_TYPE) {
    private val userAttributes: SessionUserAttributes = SessionUserAttributes(
        context,
        ipAddress,
        city,
        region,
        country
    );

    private val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
    private val source = "android"
    private val appName = context.applicationInfo?.loadLabel(context.packageManager).toString()
    private val appVersion = context.packageManager?.getPackageInfo(context.packageName, 0)?.versionName.toString()
    private val appIdentifier = context.packageName
    private val androidId = Settings.Secure.getString((context.contentResolver ?: "") as ContentResolver?, Settings.Secure.ANDROID_ID)


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