# Sample app

A host application that consumes the SDK the way a customer does — `implementation(project(":app"))`,
a config file in `assets/`, and `Intempt.initialize()` from `Application.onCreate`.

It exists because building the library proves less than you'd think. The library's own unit tests
never start the SDK, so nothing in this repository previously answered the question a customer
actually has: add the AAR, call the API, does it work? Two real defects were found by adding this
module:

- **Every consumer with `minifyEnabled true` could not build.** ktor pulls in `slf4j-api`, whose
  `LoggerFactory` references `org.slf4j.impl` classes that don't exist on Android. R8 fails on a
  missing class rather than warning. Fixed by shipping `-dontwarn org.slf4j.impl.**` in the
  library's `consumer-rules.pro`, where it belongs — the SDK is what drags the dependency in.
- The reflective autocapture dispatch survives R8 only because of the keep rules in that same
  file. `sample-release` is what verifies they work; check `EventHandlers` in
  `build/outputs/mapping/release/mapping.txt` and confirm the method names are unrenamed.

## Run it

```bash
./gradlew :sample:testDebugUnitTest    # boots the SDK at API 24 and 34, no device needed
./gradlew :sample:assembleDebug
./gradlew :sample:assembleRelease      # R8 over the consumer, using only the AAR's rules
```

On a device or emulator:

```bash
./gradlew :sample:installDebug
adb shell am start -n com.intempt.sample/.MainActivity
adb logcat -s Intempt IntemptQueue IntemptSample
```

The screen has a button per public call and two text fields. The password field is there on
purpose: autocapture reads input text, and the password must reach the wire as `[masked]`.

## Credentials

`src/main/assets/intempt-config.json` holds placeholders, so requests will 401 against the real
ingestion endpoint. That's fine for everything above — the queue persists and retries regardless
of what the server says, and the parts that depend on a real project are covered elsewhere. To
point it at a real project, replace the four `auth` values.

To aim it at a local or staging server, set `intempt.apiUrl` in `local.properties` or
`INTEMPT_API_URL` in the environment; `build.gradle.kts` writes it into the generated config as
`options.apiUrl`. The SDK accepts `https://`, or `http://` to a loopback host only (`localhost`,
`127.0.0.1`, `[::1]`, or `10.0.2.2` from the emulator), and refuses to initialize on anything
else. An app can also pass `IntemptRuntimeOptions(apiUrl = ...)` to `Intempt.initialize`.
