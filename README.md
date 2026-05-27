# Intempt Android SDK

Android SDK for the [Intempt](https://intempt.com) analytics platform. Automatic event tracking with experiments and personalizations.

## Installation

Add the dependency to your module-level `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.intempt.sdk:intempt-android:1.0.8")
}
```

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

## Features

- **Auto-tracking** — automatically capture screen views, taps, and app lifecycle events
- **Custom events** — send any structured event with arbitrary properties
- **User identification** — link events to a known user with `identify()`
- **Account grouping** — associate events with a company or account via `group()`
- **Consent management** — respect user privacy preferences before collecting data
- **Ecommerce** — track product views, cart additions, and purchases
- **Recommendations** — fetch server-side product recommendations
- **Experiments** — serve server-side experiment variants to your users
- **Personalizations** — deliver targeted content and campaigns in real time

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

## Experiments and Personalizations

After initialization, use `Intempt.experiment` and `Intempt.personalization` to fetch server-side variants.

```kotlin
import kotlinx.coroutines.launch

// Fetch experiments by group name
lifecycleScope.launch {
    val variant = Intempt.experiment.getByGroup(listOf("onboarding-flow"))
    // variant is a JsonElement? with the experiment configuration
}

// Fetch personalizations by name
lifecycleScope.launch {
    val content = Intempt.personalization.getByName(listOf("hero-banner"))
}
```

Both `experiment` and `personalization` implement `ModificationProvider` with these methods:

| Method | Description |
|--------|-------------|
| `suspend getByGroup(data: List<String>): JsonElement?` | Fetch modifications by group name |
| `suspend getByName(data: List<String>): JsonElement?` | Fetch modifications by name |
| `getByGroupAsync(data: List<String>): CompletableFuture<JsonElement?>` | Java-friendly async variant |
| `getByNameAsync(data: List<String>): CompletableFuture<JsonElement?>` | Java-friendly async variant |

## Documentation

Full documentation: [docs.intempt.com](https://docs.intempt.com/docs/android-sdk)

## Requirements

- Android API 31+
- Kotlin

## License

MIT -- see [LICENSE](LICENSE) for details.
