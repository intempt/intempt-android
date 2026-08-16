# Intempt Android SDK

Android SDK for the [Intempt](https://intempt.com) analytics platform. Automatic event tracking, product analytics and recommendation feeds.

## Installation

Add the dependency to your module-level `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.intempt.sdk:intempt-android:3.0.0")
}
```

This gives you analytics, auto-tracking and recommendation feeds out of the box.
Push notifications are an **optional** add-on that requires Firebase — see below.

### Without push notifications

Nothing else to do. Add the dependency, call `Intempt.initialize(context)`, and the SDK
runs fully for analytics and recommendations. You do **not** need Firebase.

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

## Configuration

Create `src/main/assets/intempt-config.json`. The SDK reads it on `initialize()` and will refuse
to start without valid credentials:

```json
{
  "auth": {
    "INTEMPT_API_KEY": "your-key-id.your-key-secret",
    "INTEMPT_SOURCE_ID": "your-source-id",
    "INTEMPT_ORGANIZATION_ID": "your-org",
    "INTEMPT_PROJECT_ID": "your-project"
  },
  "options": {
    "isLoggingEnabled": false,
    "isTouchEnabled": true,
    "isTextCaptureEnabled": true,
    "isAutoCaptureEnabled": true,
    "isQueueEnabled": true,
    "useIpAddressForGeolocation": true,
    "itemsInQueue": 5,
    "timeBuffer": 5000
  }
}
```

Every `options` key is optional; the values above are the defaults.

| Option | Type | Default | Meaning |
|---|---|---|---|
| `isLoggingEnabled` | boolean | `false` | Debug logging to logcat, tag `Intempt` |
| `isTouchEnabled` | boolean | `true` | Touch event auto-capture |
| `isTextCaptureEnabled` | boolean | `true` | Capture text/values on interaction |
| `isAutoCaptureEnabled` | boolean | `true` | Screen and interaction auto-capture |
| `isQueueEnabled` | boolean | `true` | Batch events rather than sending one at a time |
| `useIpAddressForGeolocation` | boolean | `true` | Whether Intempt may derive geo from the request IP — see [Geolocation](#geolocation) |
| `itemsInQueue` | int | `5` | Queued events that trigger a flush |
| `timeBuffer` | long | `5000` | **Milliseconds** between batch sends — not seconds |

The API key ships inside your APK and is extractable from any installed build. Use a key scoped
to ingestion only.

`:sample` generates this file at build time from `local.properties` or the environment, so the
credentials never get committed. See `sample/build.gradle.kts` if you want the same for your app.

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
Intempt.identify("john@example.com", "login", mapOf("plan" to "pro"))
```

## Using it from Java

The public API is annotated with `@JvmStatic` and `@JvmOverloads`, so Java callers use the plain
static form and get the overloads that Kotlin default arguments imply:

```java
public class MyApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();

        if (!Intempt.initialize(this)) {
            // Analytics is off. Nothing else needs guarding.
        }

        Intempt.identify("john@example.com");                 // shortest overload
        Intempt.identify("john@example.com", "login");         // and every arity between
        Intempt.track("purchase_completed", Map.of("amount", "49.99"));
        Intempt.Logging.start();                               // nested objects too
    }
}
```

No `Intempt.INSTANCE.` prefix is needed anywhere. `recommendation()` is a `suspend` function and
needs a Kotlin caller or a coroutine bridge.

## Features

- **Auto-tracking** — automatically capture screen views, taps, and app lifecycle events
- **Custom events** — send any structured event with arbitrary properties
- **User identification** — link events to a known user with `identify()`
- **Account grouping** — associate events with a company or account via `group()`
- **Consent management** — respect user privacy preferences before collecting data
- **Ecommerce** — track product views, cart additions, and purchases
- **Recommendations** — fetch server-side product recommendations

## API Reference

### initialize

Set up the SDK. Call once in `Application.onCreate()` or your launch Activity.

```kotlin
Intempt.initialize(context)
```

### track

Send a custom event with a title and key-value data.

```kotlin
Intempt.track("button_clicked", mapOf(
    "button_id" to "checkout",
    "screen" to "cart"
))
```

### identify

Link events to a known user. The optional `eventTitle` parameter names this identification event (e.g. `"login"`, `"signup"`). Pass `userAttributes` for profile traits and `data` for event-specific properties.

```kotlin
// Minimal — just a user ID
Intempt.identify("john@example.com")

// With an event title and user attributes
Intempt.identify(
    userId = "john@example.com",
    eventTitle = "login",
    userAttributes = mapOf("plan" to "pro", "role" to "admin"),
    data = mapOf("source" to "google_oauth")
)
```

**Signature:**

```kotlin
fun identify(
    userId: String,
    eventTitle: String? = null,
    userAttributes: Map<String, String>? = null,
    data: Map<String, String>? = null
)
```

### group

Associate events with an account or company. Use `eventTitle` to name the grouping event and `accountAttributes` for account-level traits.

```kotlin
Intempt.group(
    accountId = "acme-corp",
    eventTitle = "account_updated",
    accountAttributes = mapOf("plan" to "enterprise", "industry" to "saas")
)
```

**Signature:**

```kotlin
fun group(
    accountId: String,
    eventTitle: String? = null,
    accountAttributes: Map<String, String>? = null
)
```

### record

Comprehensive event method that can set account, user, and event data in a single call.

```kotlin
Intempt.record(
    eventTitle = "feature_used",
    accountId = "acme-corp",
    userId = "john@example.com",
    accountAttributes = mapOf("plan" to "enterprise"),
    userAttributes = mapOf("role" to "admin"),
    data = mapOf("feature" to "dashboard", "duration_ms" to "3200")
)
```

**Signature:**

```kotlin
fun record(
    eventTitle: String,
    accountId: String? = null,
    userId: String? = null,
    accountAttributes: Map<String, String>? = null,
    userAttributes: Map<String, String>? = null,
    data: Map<String, String>? = null
)
```

### alias

Link two user identities together (e.g. an anonymous ID to a known user ID).

```kotlin
Intempt.alias(
    userId = "john@example.com",
    anotherUserId = "anon_abc123"
)
```

### consent

Record a user's consent decision. Specify the action, an expiration timestamp (epoch millis), and optional details.

```kotlin
Intempt.consent(
    action = "grant",
    validUntil = System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000,
    email = "john@example.com",
    message = "Accepted cookie policy v2",
    category = "analytics"
)
```

**Signature:**

```kotlin
fun consent(
    action: String,
    validUntil: Long,
    email: String? = null,
    message: String? = null,
    category: String? = null
)
```

### logOut

Clear the current user session. Call when the user signs out.

```kotlin
Intempt.logOut()
```

## Ecommerce

### productView

Track when a user views a product.

```kotlin
Intempt.productView(productId = "sku_123")
```

### productAdd

Track when a user adds a product to their cart.

```kotlin
Intempt.productAdd(productId = "sku_123", quantity = 2)
```

### productOrdered

Track a completed purchase. Pass a list of product maps.

```kotlin
Intempt.productOrdered(listOf(
    mapOf("productId" to "sku_123", "quantity" to 2, "price" to 49.99),
    mapOf("productId" to "sku_456", "quantity" to 1, "price" to 19.99)
))
```

### recommendation

Fetch product recommendations from the server. This is a `suspend` function -- call it from a coroutine scope.

```kotlin
import kotlinx.coroutines.launch

lifecycleScope.launch {
    val result = Intempt.recommendation(
        id = "homepage-recs",
        quantity = 5,
        fields = listOf("name", "price", "imageUrl"),
        productId = null
    )
    // result is a JsonObject? with the recommendation payload
}
```

**Signature:**

```kotlin
suspend fun recommendation(
    id: String,
    quantity: Int,
    fields: List<String>,
    productId: String?
): JsonObject?
```

## Privacy and Control

### doNotCaptureText

Exclude a specific view from automatic text capture. Call after the view is inflated.

```kotlin
Intempt.doNotCaptureText(mySecretTextField)
```

### Geolocation

The SDK does not fetch, store or transmit the device's IP address.

`useIpAddressForGeolocation` controls whether Intempt may derive city / region / country from the
source IP of the requests it already receives. It defaults to `true`; set it to `false` in
`intempt-config.json` and no geolocation happens at either end.

Session events carry `deviceType`, `carrier` and `platform` in `userAttributes`. They no longer
carry `ipAddress`, `city`, `region` or `country` — earlier versions fetched those from a
third-party service on every session start. If you read those fields, see
[docs/MIGRATION.md](docs/MIGRATION.md).

### Logging

Toggle SDK debug logging.

```kotlin
Intempt.Logging.start()              // Enable debug logs
Intempt.Logging.stop()               // Disable debug logs
Intempt.Logging.isLoggingEnabled()   // Check current state
```

### Tracking

Globally enable or disable all event tracking (opt-in / opt-out).

```kotlin
Intempt.Tracking.stop()              // Opt out — stop all tracking
Intempt.Tracking.start()             // Opt back in — resume tracking
Intempt.Tracking.isTrackingEnabled() // Check current state
```

## Checking the SDK started

`initialize` never throws and never takes your app down. It returns whether the SDK is
running, and every other call is a no-op while it is not:

```kotlin
if (!Intempt.initialize(context)) {
    // Analytics is off. Your app is unaffected; nothing else needs guarding.
}

Intempt.isInitialized   // same answer, readable later
```

Calls made before or without a successful `initialize` log a warning and do nothing. They do
not throw, so you do not need to guard your tracking calls.

## Sample app

`sample/` is a host application that consumes this SDK the way your app does — a config file
in `assets/`, `Intempt.initialize()` from `Application.onCreate`, and a button per public
call.

```bash
./gradlew :sample:installDebug
adb shell am start -n com.intempt.sample/.MainActivity
adb logcat -s Intempt
```

It is also a test target. `:sample:testDebugUnitTest` boots the SDK on the JVM at API 24 and
34, and `:sample:connectedDebugAndroidTest` runs it on a real emulator and reads assertions
back out of the on-device queue — including that a password typed into a real `EditText`
never reaches it. Both run in CI on every pull request.

## Usage recipes

The API Reference above documents each call in isolation. For realistic, multi-step flows —
consent-gated tracking, a full ecommerce funnel, rendering a recommendation feed with error
handling — see [docs/RECIPES.md](docs/RECIPES.md).

## Upgrading

Moving from `2.0.1`? See [docs/MIGRATION.md](docs/MIGRATION.md) for the breaking changes in
`3.0.0` and how to update your code, including a required change if you construct
`SessionUserAttributes` or read geo fields off it.

## Documentation

Full documentation: [docs.intempt.com/api/sdk/android](https://docs.intempt.com/api/sdk/android)

## Requirements

- **Android API 23+** (Android 6.0). Verified by an instrumented suite that runs on an
  API 23 emulator in CI, not only on the current target — three crashes that were invisible
  above the floor shipped before that gate existed.
- **compileSdk / targetSdk 35**, both set explicitly on the library module.
- **Kotlin or Java.** The public API is annotated for Java interop — see
  [Using it from Java](#using-it-from-java). `recommendation()` is the one exception; it is a
  `suspend` function.

## For devs

Releases publish to Maven Central automatically, **only from `main`**, when you push a `v*` tag:

```bash
git checkout main
git pull origin main
git tag v3.0.0
git push origin v3.0.0
```

Full release guide: [RELEASING.md](RELEASING.md).

## License

Apache 2.0 -- see [LICENSE](LICENSE) and [NOTICE](NOTICE) for details.

Version 3.0.0 onwards is Apache 2.0. It incorporates the event delivery queue from
[mixpanel-android](https://github.com/mixpanel/mixpanel-android), which is Apache 2.0, so this
SDK is distributed under the same terms. `NOTICE` lists every derived file against its
upstream path and records which behaviours are inherited unchanged.

Versions up to and including 2.0.1 were published under MIT and remain MIT on Maven Central.
