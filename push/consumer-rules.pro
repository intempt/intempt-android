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

# Jackson's TypeReference recovers its type argument at runtime from the class file's
# Signature attribute (getClass().getGenericSuperclass()). R8 discards Signature unless told
# otherwise, which turned every reified readValue<T>() into
# "IllegalArgumentException: TypeReference constructed without actual type information"
# in the minified 4.0.1 AAR — every received push was dropped in onMessageReceived.
#
# The call sites now use the Class<T> overload and need none of this, so this rule is a guard
# for any future reified Jackson call rather than the fix. Keep both.
#
# This file is both the library's own release rules and the rules shipped to consuming apps,
# so one entry covers both R8 runs.
-keepattributes Signature,InnerClasses,EnclosingMethod
