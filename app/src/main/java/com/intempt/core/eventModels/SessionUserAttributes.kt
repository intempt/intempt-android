package com.intempt.core.eventModels

import android.content.Context
import android.content.res.Configuration
import android.telephony.TelephonyManager

class SessionUserAttributes(
     val context: Context,
     val ipAddress: String = "",
     val city: String = "",
     val region: String = "",
     val country: String = ""
 ) {
    val deviceType: String = retrieveType();
    val carrier: String = retrieveCarrier();
    val platform: String = retrievePlatform();


    private fun retrieveType(): String {
        return when (context.resources.configuration.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK) {
            Configuration.SCREENLAYOUT_SIZE_SMALL, Configuration.SCREENLAYOUT_SIZE_NORMAL -> "Phone"
            Configuration.SCREENLAYOUT_SIZE_LARGE, Configuration.SCREENLAYOUT_SIZE_XLARGE -> "Tablet"
            else -> "Unknown"
        }
    }

    private fun retrieveCarrier(): String {
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        return telephonyManager.networkOperatorName?.toString() ?: ""
    }

    private fun retrievePlatform(): String {
        return "Android ${android.os.Build.VERSION.RELEASE}"
    }

}

