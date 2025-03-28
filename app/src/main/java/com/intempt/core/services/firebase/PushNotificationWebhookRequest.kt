package com.intempt.core.services.firebase

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

    var destinationType: String = "firebase_cloud_messaging",

    var status: String = "test"
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
        templateId = metadata.templateId.toLong()
    )

    enum class WebhookType(val receivedType: String) {
        BOUNCED("bounced"),
        DELIVERED("delivered"),
        OPENED("opened")
    }
}