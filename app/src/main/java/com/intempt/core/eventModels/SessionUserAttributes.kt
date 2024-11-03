package com.intempt.core.eventModels

data class SessionUserAttributes(
     val ipAddress: String = "",
     val city: String = "",
     val region: String = "",
     val country: String = "",
     val deviceType: String,
     val carrier: String,
     val platform: String,
 ) {}

