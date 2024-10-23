package com.intempt.intempt_android.eventModels

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.provider.Settings


@SuppressLint("HardwareIds")
data class SessionEvent(val context: Context): BaseIntemptEvent() {
    private val userAttributes: SessionUserAttributes = SessionUserAttributes(context);

    private val data = mapOf(
        "deviceName" to "${Build.MANUFACTURER} ${Build.MODEL}",
        "source" to "android",
        "appName" to context.applicationInfo?.loadLabel(context.packageManager).toString(),
        "appVersion" to context.packageManager?.getPackageInfo(context.packageName, 0)?.versionName.toString(),
        "appIdentifier" to (context.packageName ?: ""),
        "androidId" to Settings.Secure.getString((context.contentResolver ?: "") as ContentResolver?, Settings.Secure.ANDROID_ID)
        //TODO: need to check
        //advertiserId ?
        //sessionStartEventName
    )

    override fun toString(): String {
        val output = """
            {
                sessionId: $sessionId,
                eventId: $eventId,
                pageId: $pageId,
                profileId: $profileId,
                timestamp: $timestamp,
                data: {
                    deviceName: ${data["deviceName"]},
                    source: ${data["source"]},
                    appName: ${data["appName"]},
                    appVersion: ${data["appVersion"]},
                    appIdentifier: ${data["appIdentifier"]},
                    androidId: ${data["androidId"]}
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