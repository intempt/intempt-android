package com.intempt.push.model

import android.os.Parcelable
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import kotlinx.parcelize.Parcelize

@Parcelize
@JsonIgnoreProperties(ignoreUnknown = true)
internal data class PushNotificationMetadata(
    val orgId: String,
    val projectId: String,
    val transformerId: String,
    val pipelineId: String,
    val destinationId: String,
    val masterId: String,
    val accountId: String,
    val templateId: String,
    val runId: String? = null,
    val subrunId: String? = null,
) : Parcelable
