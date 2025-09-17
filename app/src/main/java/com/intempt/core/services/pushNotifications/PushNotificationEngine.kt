package com.intempt.core.services.pushNotifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.intempt.core.services.LoggerManagerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

data class PushNotificationData(
    val title: String,
    val body: String,
    val url: String? = null,
    val imageUrl: String? = null
)

@Singleton
internal class PushNotificationEngine @Inject constructor(
    private val context: Context,
    private val logger: LoggerManagerService
) {
    
    private val channelId = "intempt_push_notifications"
    private val channelName = "Intempt Push Notifications"
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    
    init {
        createNotificationChannel()
    }
    
    fun processPushNotification(pushData: PushNotificationData) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                logger.log("PushNotificationEngine | Processing push notification: ${pushData.title}")
                
                val notification = buildNotification(pushData)
                val notificationId = System.currentTimeMillis().toInt()
                
                withContext(Dispatchers.Main) {
                    notificationManager.notify(notificationId, notification)
                    logger.log("PushNotificationEngine | Push notification displayed successfully")
                }
                
            } catch (e: Exception) {
                logger.error("PushNotificationEngine | Error processing push notification: ${e.message}")
                retryNotification(pushData, 1)
            }
        }
    }
    
    private suspend fun buildNotification(pushData: PushNotificationData): android.app.Notification {
        val builder = NotificationCompat.Builder(context, channelId)
            .setContentTitle(pushData.title)
            .setContentText(pushData.body)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        
        // Handle URL click action
        pushData.url?.let { url ->
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            val pendingIntent = PendingIntent.getActivity(
                context, 
                0, 
                intent, 
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.setContentIntent(pendingIntent)
        }
        
        // Handle image
        pushData.imageUrl?.let { imageUrl ->
            try {
                val bitmap = loadImageFromUrl(imageUrl)
                bitmap?.let {
                    builder.setLargeIcon(it)
                    builder.setStyle(
                        NotificationCompat.BigPictureStyle()
                            .bigPicture(it)
                            .bigLargeIcon(null)
                    )
                }
            } catch (e: Exception) {
                logger.error("PushNotificationEngine | Error loading image: ${e.message}")
            }
        }
        
        return builder.build()
    }
    
    private suspend fun loadImageFromUrl(imageUrl: String): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL(imageUrl)
                BitmapFactory.decodeStream(url.openConnection().getInputStream())
            } catch (e: Exception) {
                logger.error("PushNotificationEngine | Error loading image from URL: ${e.message}")
                null
            }
        }
    }
    
    private fun retryNotification(pushData: PushNotificationData, attempt: Int) {
        if (attempt <= 3) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    kotlinx.coroutines.delay(attempt * 1000L) // Exponential backoff
                    logger.log("PushNotificationEngine | Retrying push notification (attempt $attempt)")
                    processPushNotification(pushData)
                } catch (e: Exception) {
                    logger.error("PushNotificationEngine | Retry attempt $attempt failed: ${e.message}")
                    if (attempt < 3) {
                        retryNotification(pushData, attempt + 1)
                    } else {
                        logger.error("PushNotificationEngine | All retry attempts failed for notification: ${pushData.title}")
                    }
                }
            }
        }
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Channel for Intempt push notifications"
                enableLights(true)
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
}
