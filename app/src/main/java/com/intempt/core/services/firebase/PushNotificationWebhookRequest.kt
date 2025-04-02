package com.intempt.core.services.firebase

import com.intempt.core.services.firebase.model.PushNotificationMetadata

data class PushNotificationWebhookRequest(
    var type: WebhookType,

    var orgId: Long,

    var projectId: Long,

    var destinationId: Long,

    var masterId: Long,

    var accountId: Long,

    var pipelineId: Long,

    var transformerId: Long,

    var templateId: Long,

    var subject: String = "firebase_cloud_messaging",

    var destinationType: String = "push_notification",

    var status: String
) {
    constructor(type: WebhookType, metadata: PushNotificationMetadata) : this(
        type = type,
        orgId = metadata.orgId.toLong(),
        projectId = metadata.projectId.toLong(),
        destinationId = metadata.destinationId.toLong(),
        masterId = metadata.masterId.toLong(),
        accountId = metadata.accountId.toLong(),
        pipelineId = metadata.pipelineId.toLong(),
        transformerId = metadata.transformerId.toLong(),
        templateId = metadata.templateId.toLong(),
        status = type.toString().lowercase()
    )

    enum class WebhookType(val receivedType: String) {
        BOUNCED("bounced"),
        DELIVERED("delivered"),
        OPENED("opened")
    }
}