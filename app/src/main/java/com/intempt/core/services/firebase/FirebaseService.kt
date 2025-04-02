package com.intempt.core.services.firebase

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bumptech.glide.Glide
import com.fasterxml.jackson.module.kotlin.readValue
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.bumptech.glide.request.target.Target
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.firebase.messaging.FirebaseMessaging
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.HttpManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.firebase.model.PushNotificationContent
import com.intempt.core.services.firebase.model.PushNotificationMetadata
import com.intempt.core.services.firebase.webhook.WebhookService
import kotlinx.coroutines.tasks.await

class FirebaseService : FirebaseMessagingService() {

    var token: String = ""
    val mapper = jacksonObjectMapper()

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        val config = ConfigManagerService(this)
        val logger = LoggerManagerService(config);
        val http = HttpManagerService(config, logger)
        val webhookService = WebhookService(config, logger, http)

        Log.d("FCM", "Received message from: ${remoteMessage.from}")

        remoteMessage.data.isNotEmpty().let {
            Log.d("FCM", "Message data: ${remoteMessage.data}")
            val contentJson = remoteMessage.data["content"] ?: "{}"
            val metadataJson = remoteMessage.data["metadata"] ?: "{}"

            val content: PushNotificationContent = mapper.readValue(contentJson)
            val metadata: PushNotificationMetadata = mapper.readValue(metadataJson)

            val deliveredWebhookRequest = PushNotificationWebhookRequest(
                PushNotificationWebhookRequest.WebhookType.DELIVERED,
                metadata);
            Log.d("FCM", "Message tracked as delivered")
            webhookService.sendPushNotificationWebhook(mapper.valueToTree(deliveredWebhookRequest))
            sendPushNotification(this, content, metadata, webhookService)
        }

        remoteMessage.notification?.let {
            Log.d("FCM", "Message body: ${it.body}")
        }


    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d("FCM", "Updated token: $token")
        this.token = token;
    }


    suspend fun initializeToken(): String {
        return try {
            val token = FirebaseMessaging.getInstance().token.await()
            Log.d("FCM", "FCM Token: $token")
            token
        } catch (e: Exception) {
            Log.w("FCM", "Fetching FCM registration token failed", e)
            throw e
        }


    }

    private fun sendPushNotification(context: Context, content: PushNotificationContent,
                                     metadata: PushNotificationMetadata, webhookService: WebhookService) {

        Log.d("FCM", "Notification was received")
        Log.d("FCM", "Metadata is: $metadata")
        Log.d("FCM", "Content is: $content")


        val channelId = "default_channel"

        val channel = NotificationChannel(channelId, "Default Channel", NotificationManager.IMPORTANCE_HIGH)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        val intentTest: Intent = when {
            content.webUrl.isNotBlank() -> {
                Log.d("FCM", "Creating VIEW intent for ${content.webUrl}")
                Intent(Intent.ACTION_VIEW, Uri.parse(content.webUrl))
            }
            else -> {
                Log.d("FCM", "Creating launch intent for package")
                context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()
            }
        }
        intentTest.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val dispatcherIntent = Intent(context, NotificationDispatcherActivity::class.java).apply {
            putExtra("metadata", metadata)
            putExtra("intent_test", intentTest)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            System.currentTimeMillis().toInt(),
            dispatcherIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentTitle(content.title)
            .setContentText(content.body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        val requestCode = System.currentTimeMillis().toInt();
        if (content.image.isNotEmpty()) {
            Thread {
                try {
                    val bitmap: Bitmap = Glide.with(context)
                        .asBitmap()
                        .load(content.image)
                        .submit(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL)
                        .get()

                    builder.setStyle(NotificationCompat.BigPictureStyle().bigPicture(bitmap))
                        .setLargeIcon(bitmap)

                    notifySafely(context, webhookService, metadata, builder, requestCode)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }.start()
        } else {
            notifySafely(context, webhookService, metadata, builder, requestCode)
            }
        }

    private fun notifySafely(
        context: Context,
        webhookService: WebhookService,
        metadata: PushNotificationMetadata,
        builder: NotificationCompat.Builder,
        notificationId: Int
    ) {
        with(NotificationManagerCompat.from(context)) {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                notify(notificationId, builder.build())
                Log.d("FCM", "Notification shown with ID: $notificationId")
            } else {
                Log.w("FCM", "POST_NOTIFICATIONS permission not granted. Tracking as bounced.")
                val bouncedWebhookRequest = PushNotificationWebhookRequest(
                    PushNotificationWebhookRequest.WebhookType.BOUNCED,
                    metadata
                )
                val mapper = jacksonObjectMapper()
                webhookService.sendPushNotificationWebhook(mapper.valueToTree(bouncedWebhookRequest))
            }
        }
    }
}