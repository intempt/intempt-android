package com.intempt.core.services.firebase.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class PushNotificationContent(
    val title: String? = null,
    val body: String? = null,
    val image: String? = null,
    val webUrl: String? = null,
)
