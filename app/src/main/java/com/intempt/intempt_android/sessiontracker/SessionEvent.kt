package com.intempt.intempt_android.sessiontracker

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.intempt.intempt_android.BaseIntemptEvent

class SessionEvent(context:Context): BaseIntemptEvent(context) {
    //private val userAttributes: SessionUserAttributes = SessionUserAttributes(context);

    val data = mapOf(
        "deviceName" to "${Build.MANUFACTURER} ${Build.MODEL}",
        "source" to "android",
        "appName" to context.applicationInfo.loadLabel(context.packageManager).toString(),
        "appVersion" to context.packageManager.getPackageInfo(context.packageName, 0).versionName.toString(),
        "appIdentifier" to context.packageName,
        "androidId" to Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    )

    override fun toString(): String {
        return "SessionEvent(sessionId='$sessionId', eventId='$eventId', pageId='$pageId', profileId='$profileId', timestamp=$timestamp)"
    }
}