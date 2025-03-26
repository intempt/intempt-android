package com.intempt.core.services.firebase

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bumptech.glide.Glide
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.google.firebase.ktx.Firebase
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.google.firebase.messaging.ktx.messaging
import com.intempt.core.Intempt
import com.bumptech.glide.request.target.Target

class FirebaseService : FirebaseMessagingService() {

    var token: String = ""
    val mapper = ObjectMapper()

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        Log.d("FCM", "Received message from: ${remoteMessage.from}")

        remoteMessage.data.isNotEmpty().let {
            Log.d("FCM", "Message data: ${remoteMessage.data}")
            val contentJson = remoteMessage.data["content"] ?: "{}"
            val metadataJson = remoteMessage.data["metadata"] ?: "{}"

            val content: PushNotificationContent = mapper.readValue(contentJson)
            val metadata: PushNotificationMetadata = mapper.readValue(metadataJson)

            sendPushNotification(this, content)
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

    fun initializeToken(): String {
        Firebase.messaging.token.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                Log.w("FCM", "Fetching FCM registration token failed", task.exception)
                return@addOnCompleteListener
            }
            val token = task.result
            Log.d("FCM", "FCM Token: $token")
            this.token = token
        }
        return token
    }

    fun sendPushNotification(context: Context, content: PushNotificationContent) {
        val channelId = "default_channel"

        val channel = NotificationChannel(channelId, "Default Channel", NotificationManager.IMPORTANCE_HIGH)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        val intent = Intent(context, Intempt::class.java)
        val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT)

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(content.title)
            .setContentText(content.body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

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

                    with(NotificationManagerCompat.from(context)) {
                        if (ActivityCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            notify(0, builder.build())
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }.start()
        } else {
            with(NotificationManagerCompat.from(context)) {
                notify(0, builder.build())
            }
        }
    }
}