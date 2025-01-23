## Add project specific ProGuard rules here.
## You can control the set of applied configuration files using the
## proguardFiles setting in build.gradle.
##
## For more details, see
##   http://developer.android.com/guide/developing/tools/proguard.html
#
## If your project uses WebView with JS, uncomment the following
## and specify the fully qualified class name to the JavaScript interface
## class:
##-keepclassmembers class fqcn.of.javascript.interface.for.webview {
##   public *;
##}
#
## Uncomment this to preserve the line number information for
## debugging stack traces.
##-keepattributes SourceFile,LineNumberTable
#
## If you keep the line number information, uncomment this to
## hide the original source file name.
##-renamesourcefileattribute SourceFile
#
## Dagger
#-keep class dagger.** { *; }
#-keep class javax.inject.** { *; }
#-keepattributes *Annotation*
#-keep class **$$InjectAdapter { *; }
#-keep class **$$ModuleAdapter { *; }
#-keep class **$$MembersInjector { *; }
#
## Ktor
#-keep class io.ktor.** { *; }
#-keep class kotlinx.serialization.** { *; }
## To keep Ktor client serializers
#-keepattributes RuntimeVisibleAnnotations
#
## Kotlin Coroutines
#-dontwarn kotlinx.coroutines.**
#-keep class kotlinx.coroutines.** { *; }
#-keepclassmembers class kotlinx.coroutines.** { *; }
#-keepclassmembers class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
#
## AndroidX
#-keep class androidx.** { *; }
#-dontwarn androidx.**
#
## Jetpack Compose
#-keep class androidx.compose.** { *; }
#-keep class kotlin.Metadata { *; }
#-keep class androidx.lifecycle.LifecycleObserver { *; }
#-keep class androidx.lifecycle.DefaultLifecycleObserver { *; }
#-dontwarn androidx.compose.**
#
## Kotlin Reflection
#-keep class kotlin.reflect.** { *; }
#-keep class kotlin.script.** { *; }
#-keepclassmembers class kotlin.reflect.** { *; }
#-dontwarn kotlin.reflect.**
#
## Keep BuildConfig fields
#-keepclassmembers class **.BuildConfig {
#    public static final *** VERSION;
#}

# ===== General Settings =====
# Preserve line numbers for stack traces (optional for debugging)
# -keepattributes SourceFile,LineNumberTable

# Rename source file attributes (optional for hiding source file names)
# -renamesourcefileattribute SourceFile

# ===== Public API (Keep Public SDK Classes) =====
# Keep the public Intempt class and its methods
-keep class com.intempt.core.Intempt { *; }
-keep class com.intempt.core.types.ModificationProvider { *; }

# ===== Internal Implementation (Obfuscate Internal Classes) =====
# Obfuscate all internal implementation classes
-keep,allowobfuscation class com.intempt.core.intemptCore.** { *; }
-keep,allowobfuscation class com.intempt.core.services.** { *; }
-keep,allowobfuscation class com.intempt.core.autocapture.** { *; }
-keep,allowobfuscation class com.intempt.core.customCapture.** { *; }

# ===== Dagger (Keep Dependency Injection Classes) =====
-keep class dagger.** { *; }
-keep class javax.inject.** { *; }
-keepattributes *Annotation*
-keep class **$$InjectAdapter { *; }
-keep class **$$ModuleAdapter { *; }
-keep class **$$MembersInjector { *; }

# Keep Logging object and its methods
-keep class com.intempt.core.Intempt$Logging { *; }

# Keep Tracking object and its methods
-keep class com.intempt.core.Intempt$Tracking { *; }

# ===== Ktor (Keep HTTP Client Classes) =====
-keep class io.ktor.** { *; }
-keep class kotlinx.serialization.** { *; }
-keepattributes RuntimeVisibleAnnotations

# ===== Kotlin Coroutines (Keep Coroutine Support) =====
-dontwarn kotlinx.coroutines.**
-keep class kotlinx.coroutines.** { *; }
-keepclassmembers class kotlinx.coroutines.** { *; }
-keepclassmembers class kotlinx.coroutines.internal.MainDispatcherFactory { *; }

# ===== AndroidX (Keep Lifecycle and Core Components) =====
-keep class androidx.** { *; }
-dontwarn androidx.**

# ===== Jetpack Compose (Keep Compose-Related Components) =====
-keep class androidx.compose.** { *; }
-keep class kotlin.Metadata { *; }
-keep class androidx.lifecycle.LifecycleObserver { *; }
-keep class androidx.lifecycle.DefaultLifecycleObserver { *; }
-dontwarn androidx.compose.**

# ===== Kotlin Reflection =====
-keep class kotlin.reflect.** { *; }
-keep class kotlin.script.** { *; }
-keepclassmembers class kotlin.reflect.** { *; }
-dontwarn kotlin.reflect.**

# ===== BuildConfig Fields (Keep SDK Version Information) =====
-keepclassmembers class **.BuildConfig {
    public static final *** VERSION;
}

-keep class com.intempt.core.** { *; }
-keep class org.robolectric.** { *; }
-keep class androidx.test.** { *; }
-keep class org.junit.** { *; }
-keepattributes *Annotation*

# Keep any other classes or packages used in your tests
