package com.intempt.core.services.firebase

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.Target
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.HttpManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.firebase.model.PushNotificationContent
import com.intempt.core.services.firebase.model.PushNotificationMetadata
import com.intempt.core.services.firebase.webhook.WebhookService
import kotlinx.coroutines.tasks.await

internal class FirebaseService : FirebaseMessagingService() {
    var token: String = ""

    // Lazy, not a field initializer. Dagger constructs FirebaseService eagerly as part of
    // the core graph, so building the ObjectMapper here ran on every app start even when
    // the host app has no Firebase configured and never receives a push. That put a large
    // third-party static initializer on the startup path for no reason, and when it failed
    // it took the whole app down. Deferring it means a Jackson problem can only ever affect
    // push handling, which is the only thing that needs Jackson.
    val mapper by lazy { jacksonObjectMapper() }

    /**
     * The SDK's logger, lazily built for the same reason [mapper] is: this class is constructed
     * eagerly as part of the core graph, and a host app with no Firebase configured never reaches
     * any of these paths.
     *
     * <p>Every log line in this file used to call `android.util.Log` directly, which meant push
     * handling ignored `Intempt.Logging.stop()` entirely — a customer who turned logging off still
     * got push diagnostics in logcat.
     */
    private val logger by lazy { LoggerManagerService(ConfigManagerService(this)) }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        logger.debug("[FCM] Received message from=${remoteMessage.from} messageId=${remoteMessage.messageId}")

        // An Intempt push always carries a "content" entry in the FCM data payload.
        // Any other message — a plain notification message, a different sender, or a
        // malformed payload — is not ours to render. Log and bail out gracefully so we
        // never crash the host app on an unexpected message.
        val contentJson = remoteMessage.data["content"]
        if (contentJson == null) {
            logger.info(
                "[FCM] Ignoring non-Intempt message (no 'content' data field). " +
                    "data=${remoteMessage.data} notificationTitle=${remoteMessage.notification?.title}",
            )
            return
        }

        val content =
            try {
                mapper.readValue<PushNotificationContent>(contentJson)
            } catch (e: Exception) {
                logger.error("[FCM] Ignoring Intempt push: could not parse content=$contentJson", e)
                return
            }

        // Metadata is needed for delivery/open/bounce tracking but not for rendering.
        // If it is absent or malformed we still show the notification, just without tracking.
        val metadata =
            remoteMessage.data["metadata"]?.let { metaJson ->
                try {
                    mapper.readValue<PushNotificationMetadata>(metaJson)
                } catch (e: Exception) {
                    logger.error("[FCM] Could not parse metadata=$metaJson; rendering without tracking", e)
                    null
                }
            }

        val config = ConfigManagerService(this)
        val logger = LoggerManagerService(config)
        val http = HttpManagerService(config, logger)
        val webhookService = WebhookService(config, logger, http)

        if (metadata != null) {
            // Webhook IDs are parsed as Long; a non-numeric value must not crash the app
            // or block rendering. Track best-effort and continue.
            try {
                val deliveredWebhookRequest =
                    PushNotificationWebhookRequest(
                        PushNotificationWebhookRequest.WebhookType.DELIVERED,
                        metadata,
                    )
                logger.debug("[FCM] Tracking push as delivered")
                webhookService.sendPushNotificationWebhook(mapper.valueToTree(deliveredWebhookRequest))
            } catch (e: Exception) {
                logger.error("[FCM] Failed to build/send delivered webhook; rendering anyway", e)
            }
        }

        sendPushNotification(this, content, metadata, webhookService)
    }

    /**
     * FCM rotated the registration token.
     *
     * This previously assigned to a field nothing read. The token only ever reached the
     * backend on the install/upgrade event, so a rotation without an app version change —
     * FCM doing it of its own accord — left that device permanently unreachable, silently.
     * Journeys would keep targeting a token that no longer resolves.
     */
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        logger.debug("[FCM] FCM token rotated, reporting it")
        this.token = token

        try {
            // The SDK's own instance, via the public facade. This used to construct a SECOND
            // DeliveryMessages here, which meant a second HandlerThread and a second
            // EventDbAdapter opening, writing to and closing the SAME intempt_events file.
            // Two writers on one SQLite file raises SQLiteDatabaseLockedException, which is a
            // SQLiteException, which EventDbAdapter answers by calling deleteDatabase() — so a
            // routine FCM token rotation could delete the entire undelivered queue. It also
            // leaked a thread per rotation.
            //
            // Routing through Intempt.track keeps one owner, which is what the Dagger
            // @Singleton was for. If the SDK is not initialized the call is a logged no-op,
            // and the token still reaches the backend on the next install/upgrade event.
            com.intempt.core.Intempt.track(
                "App install/upgrade",
                mapOf("deviceToken" to token),
            )
        } catch (e: Throwable) {
            // Never let a token report crash the messaging service.
            logger.error("[FCM] Could not report the rotated FCM token", e)
        }
    }

    suspend fun initializeToken(): String {
        return try {
            val token = FirebaseMessaging.getInstance().token.await()
            logger.debug("[FCM] FCM Token: $token")
            token
        } catch (e: Exception) {
            logger.error("[FCM] Fetching FCM registration token failed", e)
            throw e
        }
    }

    private fun sendPushNotification(
        context: Context,
        content: PushNotificationContent,
        metadata: PushNotificationMetadata?,
        webhookService: WebhookService,
    ) {
        logger.debug("[FCM] Notification was received")
        logger.debug("[FCM] Metadata is: $metadata")
        logger.debug("[FCM] Content is: $content")

        val channelId = "default_channel"

        // NotificationChannel is API 26. Below that, channels do not exist and posting a
        // notification without one is correct, so the whole block is skipped rather than
        // crashing on class resolution.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel(channelId, "Default Channel", NotificationManager.IMPORTANCE_HIGH)
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        val intentTest: Intent =
            when {
                !content.webUrl.isNullOrBlank() -> {
                    logger.debug("[FCM] Creating VIEW intent for ${content.webUrl}")
                    Intent(Intent.ACTION_VIEW, Uri.parse(content.webUrl))
                }
                else -> {
                    logger.debug("[FCM] Creating launch intent for package")
                    context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()
                }
            }
        intentTest.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val dispatcherIntent =
            Intent(context, NotificationDispatcherActivity::class.java).apply {
                metadata?.let { putExtra("metadata", it) }
                putExtra("intent_test", intentTest)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }

        val pendingIntent =
            PendingIntent.getActivity(
                context,
                System.currentTimeMillis().toInt(),
                dispatcherIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val builder =
            NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setContentTitle(content.title)
                .setContentText(content.body)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)

        val requestCode = System.currentTimeMillis().toInt()
        if (!content.image.isNullOrEmpty()) {
            Thread {
                try {
                    val bitmap: Bitmap =
                        Glide.with(context)
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

    /**
     * Whether this app may post a notification right now.
     *
     * `checkSelfPermission(POST_NOTIFICATIONS)` alone is the wrong test. That permission
     * only exists from API 33; below it the call cannot report the real state, and it also
     * misses a user who disabled notifications in system settings — which is possible on
     * every API level.
     *
     * The distinction matters beyond correctness: a wrong answer here posts a BOUNCED
     * webhook, and journeys branch on that. Reporting a delivered push as bounced sends
     * the wrong follow-up message to a real person.
     */
    private fun notificationsAllowed(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    // notificationsAllowed() performs exactly the check lint is asking for, but lint's
    // dataflow does not follow a permission check through a helper function, so it reports
    // MissingPermission on the notify() call below. Suppressed here rather than inlining
    // the check, because the helper also handles the pre-33 case that a bare
    // checkSelfPermission would get wrong.
    @SuppressLint("MissingPermission")
    private fun notifySafely(
        context: Context,
        webhookService: WebhookService,
        metadata: PushNotificationMetadata?,
        builder: NotificationCompat.Builder,
        notificationId: Int,
    ) {
        with(NotificationManagerCompat.from(context)) {
            if (notificationsAllowed(context)) {
                notify(notificationId, builder.build())
                logger.debug("[FCM] Notification shown with ID: $notificationId")
            } else {
                logger.warn("[FCM] Notifications are disabled for this app. Tracking as bounced.")
                metadata?.let {
                    val bouncedWebhookRequest =
                        PushNotificationWebhookRequest(
                            PushNotificationWebhookRequest.WebhookType.BOUNCED,
                            it,
                        )
                    val mapper = jacksonObjectMapper()
                    webhookService.sendPushNotificationWebhook(mapper.valueToTree(bouncedWebhookRequest))
                }
            }
        }
    }
}
