# Intempt Android SDK

Android SDK for the [Intempt](https://intempt.com) analytics platform. Automatic event tracking with experiments and personalizations.

## Installation

Add the dependency to your module-level `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.intempt.sdk:intempt-android:2.0.1")
}
```

This gives you analytics, auto-tracking, experiments, and personalizations out of the box.
Push notifications are an **optional** add-on that requires Firebase — see below.

### Without push notifications

Nothing else to do. Add the dependency, call `Intempt.initialize(context)`, and the SDK
runs fully for analytics, experiments, and personalizations. You do **not** need Firebase.

### With push notifications (Firebase / FCM)

Push notifications are delivered through Firebase Cloud Messaging. The SDK already contains
all push-handling code and bundles `firebase-messaging` transitively — but **you must add
Firebase to your app** to enable it. The SDK does not ship a Firebase configuration (that
belongs to your app), so follow these steps:

**1. Add your app to a Firebase project** and download its `google-services.json` into your
app module (`app/google-services.json`). The Android package name in Firebase must match your
app's `applicationId`.

**2. Apply the Google Services plugin.**

Root `build.gradle.kts`:

```kotlin
plugins {
    id("com.google.gms.google-services") version "4.4.2" apply false
}
```

Module `app/build.gradle.kts`:

```kotlin
plugins {
    id("com.google.gms.google-services")
}
```

**3. Request the notification permission (Android 13+ / API 33+).**

In `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

At runtime, before notifications can be shown:

```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    ActivityCompat.requestPermissions(
        this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), /* requestCode */ 0
    )
}
```

That's it. `Intempt.initialize(context)` automatically registers the device's FCM token and
handles incoming push notifications — no extra calls, and no need to declare any Firebase
services in your manifest (the SDK provides them).

## Quick Start

```kotlin
import com.intempt.core.Intempt

// Initialize in Application.onCreate() or Activity
Intempt.initialize(context)

// Track a custom event
Intempt.track("purchase_completed", mapOf(
    "item_id" to "sku_123",
    "amount" to "49.99"
))

// Identify a user
Intempt.identify("john@example.com", null, mapOf("plan" to "pro"))
```

## Features

- **Auto-tracking** — automatically capture screen views, taps, and app lifecycle events
- **Custom events** — send any structured event with arbitrary properties
- **User identification** — link events to a known user with `identify()`
- **Consent management** — respect user privacy preferences before collecting data
- **Experiments** — serve server-side experiment variants to your users
- **Personalizations** — deliver targeted content and campaigns in real time

## Documentation

Full documentation: [docs.intempt.com](https://docs.intempt.com/docs/android-sdk)

## Requirements

- Android API 31+
- Kotlin

## License

MIT — see [LICENSE](LICENSE) for details.
