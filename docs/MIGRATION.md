# Migration guide

## Upgrading from 2.0.1 to 3.0.0

`3.0.0` is a major bump because `minSdk` moved and several defaults changed behaviour — see
`CHANGELOG.md` for the full list. This page only covers what a consumer has to *change* to
upgrade; everything else in the changelog (the durable queue, retry policy, CI) is internal and
needs no action.

### 1. Client-side IP capture and geo attributes are gone

The SDK no longer calls `ipapi.co` from the device, and `SessionUserAttributes` no longer carries
`ipAddress`, `city`, `region` or `country`. Geolocation now happens server-side from the request's
source IP, which the platform already receives.

**Before:**

```kotlin
val attrs = SessionUserAttributes(
    deviceType = "phone",
    carrier = "Verizon",
    platform = "Android",
    ipAddress = "203.0.113.4",
    city = "Austin",
    region = "TX",
    country = "US",
)
```

**After:**

```kotlin
val attrs = SessionUserAttributes(
    deviceType = "phone",
    carrier = "Verizon",
    platform = "Android",
)
// city/region/country now arrive on the event after the platform derives them from the
// request's source IP — nothing to pass from the device.
```

If your code constructed `SessionUserAttributes` positionally or read `userAttributes.ipAddress`
/ `.city` / `.region` / `.country` anywhere, both call sites break at compile time and need the
four arguments/fields removed.

To keep the previous default behaviour (platform may geolocate from the source IP), do nothing —
it defaults to on. To opt out, set `"useIpAddressForGeolocation": false` in
`intempt-config.json`.

### 2. `initialize()` now returns `Boolean`, not `Unit`

**Before:**

```kotlin
Intempt.initialize(context) // fire-and-forget; no way to tell if it worked
```

**After:**

```kotlin
val started = Intempt.initialize(context)
if (!started) {
    // SDK is disabled; every other call is now a safe no-op, but you may want to
    // surface this (e.g. to crash reporting) rather than silently lose analytics.
    Log.w("MyApp", "Intempt failed to start — check intempt-config.json")
}
```

Existing call sites that ignore the return value still compile unchanged — this is additive, not
source-breaking — but it's worth checking the result now, since a misconfigured
`intempt-config.json` used to report itself healthy and silently drop every event.

### 3. `minSdk` moved from 31 down to 23 — check your own floor

This *widens* what the library supports; it isn't breaking on its own. But if your app's
`minSdk` was already below 23 and only compiled because the library masked it, it won't anymore
if you were relying on the library's manifest to raise your own effective floor. Most apps need
no change here.

### 4. `kotlinx-serialization-json` is now an `api` dependency

Previously `implementation`, so `JsonElement`/`JsonObject` (used by `recommendation()`) were
invisible on your compile classpath even though the method signature needed them. If your build
already declares its own `kotlinx-serialization-json` version, check for a resolution conflict —
Gradle will pick the higher version, which should be transparent, but pin explicitly if you need
an exact version.

### 5. `logOut()` now rotates the anonymous ID

**Before:** `logOut()` kept the same `profileId`, so events from the next user on a shared device
could be attributed to the previous one.

**After:** `logOut()` generates a fresh `profileId`. No code change needed, but if you had
workarounds that manually reset identity after `logOut()`, they're now redundant and can be
removed.

### 6. Jackson is pinned to `2.13.5` (only if you use `:intempt-push`)

If your app declares a newer Jackson version directly, be aware the push module needs `2.13.5` or
lower on its own classpath — `2.16+` references `java.lang.BootstrapMethodError` (API 26) and
crashes any host app running below Android 8.0 at SDK initialization. Don't force a Jackson BOM
override above this pin without testing on an API 24–25 device. This no longer applies at all if
you don't add `com.intempt.sdk:intempt-push` — see the next section.

### 7. Push notifications moved to a separate module

**Breaking.** `com.intempt.sdk:intempt-android` no longer bundles Firebase Cloud Messaging, the
notification-dispatch activity, or the push-notification webhook path. They moved to a new,
separate artifact, `com.intempt.sdk:intempt-push`, so that an app that doesn't use push
notifications stops paying for Firebase, Jackson, and Glide in its APK.

If you want to keep push notifications working, add the new dependency alongside the existing one:

```kotlin
dependencies {
    implementation("com.intempt.sdk:intempt-android:3.0.1")
    implementation("com.intempt.sdk:intempt-push:3.0.1") // add this line
}
```

No code changes are needed beyond the Gradle dependency. `Intempt.initialize()` looks for
`:intempt-push` at runtime (via a small reflection bridge) and enables push automatically when
it's present — exactly the same way it already handled a host app that hadn't configured Firebase
at all. If you don't use push notifications, no action is needed: `Intempt.initialize()` continues
to work exactly as before, just without pulling in Firebase/Jackson/Glide.

### Not breaking, but worth knowing

- `@JvmStatic`/`@JvmOverloads` were added across the public API. Java callers can now write
  `Intempt.track(...)` instead of `Intempt.INSTANCE.track(...)`; the old form still compiles.
- `SCHEDULE_EXACT_ALARM` permission was removed from the manifest (it was unused) — no action
  needed unless your app relied on the library requesting it for you.
