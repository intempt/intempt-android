package com.intempt.core.services.firebase.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
internal data class PushNotificationMetadata(
    val orgId: String,
    val projectId: String,
    val transformerId: String,
    val pipelineId: String,
    val destinationId: String,
    val masterId: String,
    val accountId: String,
    val templateId: String,
) : Parcelable
