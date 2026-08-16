# Rules propagated into a host application's own R8/ProGuard run.

# Reflection bridge entry point: :app's PushBridge finds and invokes this via
# Class.forName/reflection, so R8 in a host app must not strip or rename it.
-keep class com.intempt.push.PushModuleEntryPoint { *; }

# Parcelable implementation read by the notification dispatcher across a process boundary; the
# CREATOR field must survive.
-keep class com.intempt.push.model.PushNotificationMetadata { *; }
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Jackson data binding on the push payload models is reflective over field names.
-keep class com.intempt.push.model.** { *; }
-keepclassmembers class com.intempt.push.PushNotificationWebhookRequest { *; }
