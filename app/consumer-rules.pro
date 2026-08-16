# Rules propagated into a host application's own R8/ProGuard run.
#
# The SDK's own proguard-rules.pro only governs building the SDK. Without these,
# a consumer app that enables minification can strip or rename members this SDK
# reaches reflectively, and the failure is silent: autocapture simply stops
# producing events in release builds.

# Public API. Consumers call these by name.
-keep public class com.intempt.core.Intempt { public *; }
-keep public class com.intempt.core.Intempt$Logging { public *; }
-keep public class com.intempt.core.Intempt$Tracking { public *; }
-keep public interface com.intempt.core.types.ModificationProvider { *; }
-keep public class com.intempt.core.types.Product { *; }
-keep public class com.intempt.core.types.UiEventProps { *; }
-keep public class com.intempt.core.types.ScreenEventProps { *; }

# Reflective dispatch. EventPoolManagerService resolves autocapture handlers by
# matching Kotlin function names at runtime:
#
#   eventHandlers::class.declaredFunctions.find { it.name == props.type }
#
# R8 cannot see those call sites, so it renames the targets and find{} returns
# null. Autocapture then silently emits nothing.
-keepclassmembers class com.intempt.core.services.eventPool.EventHandlers {
    <methods>;
}

# Kotlin reflection needs @Metadata intact to enumerate declaredFunctions at all.
-keepattributes *Annotation*
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.reflect.**

# Parcelable implementations read by the notification dispatcher across a process
# boundary; the CREATOR field must survive.
-keep class com.intempt.core.services.firebase.model.PushNotificationMetadata { *; }
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Jackson data binding on the push payload models is reflective over field names.
-keep class com.intempt.core.services.firebase.model.** { *; }
-keepclassmembers class com.intempt.core.services.firebase.PushNotificationWebhookRequest { *; }

# The vendored delivery queue. DeliveryMessages is the entry point Dagger
# constructs, and its inner Description classes are passed as Message payloads.
-keep class com.intempt.core.queue.DeliveryMessages { *; }
-keep class com.intempt.core.queue.QueueConfig { *; }

# This SDK depends on ktor, which depends on slf4j-api. slf4j's LoggerFactory
# resolves its backend by referencing org.slf4j.impl.* classes that ship in a
# binding jar, and no such binding is present on an Android classpath.
#
# Without this rule R8 treats those as missing classes and FAILS the build, so
# any consumer with `minifyEnabled true` cannot compile at all:
#
#   ERROR: Missing class org.slf4j.impl.StaticLoggerBinder
#          (referenced from: void org.slf4j.LoggerFactory.bind())
#
# The SDK never calls slf4j itself; the references are unreachable at runtime.
# The rule belongs here rather than in the consumer's own file because this SDK
# is what drags the dependency in.
-dontwarn org.slf4j.impl.**
