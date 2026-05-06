# Intempt Android SDK

Lightweight Android SDK for tracking user behavior, running experiments, and delivering personalizations with Intempt.

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
Intempt.identify("john@example.com", null, mapOf("plan" to "pro"))
```

## Features

- **Auto-tracking** -- automatically capture screen views, taps, and app lifecycle events
- **Custom events** -- send any structured event with arbitrary properties
- **User identification** -- link events to a known user with `identify()`
- **Consent management** -- respect user privacy preferences before collecting data
- **Experiments** -- serve server-side experiment variants to your users
- **Personalizations** -- deliver targeted content and campaigns in real time

## Documentation

Full integration guide: [docs.intempt.com/docs/android-sdk](https://docs.intempt.com/docs/android-sdk)

## Requirements

- Android API 31+
- Kotlin

## License

MIT -- see [LICENSE](LICENSE) for details.
