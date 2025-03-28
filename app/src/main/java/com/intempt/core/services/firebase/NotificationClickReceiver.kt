package com.intempt.core.services.firebase

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.intempt.core.services.firebase.model.PushNotificationMetadata

class NotificationClickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val metadata = intent?.getParcelableExtra<PushNotificationMetadata>("metadata")
        Log.d("FCM", "Entered into NotificationClickReceiver")
        if (metadata != null) {
            val openedWebhookRequest = PushNotificationWebhookRequest(
                PushNotificationWebhookRequest.WebhookType.OPENED,
                metadata
            )
            Log.d("FCM", "Message tracked as opened")
        } else {
            Log.e("NotificationClickReceiver", "Metadata is null!")
        }
    }
}