# Changelog

All notable changes to the Intempt Android SDK are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Only `v2.0.1` is tagged in this repository's history, so entries below it do not exist. Rather than
invent them, the `2.0.1` section records what the tag contains and nothing more. Everything since is
under Unreleased.

## [Unreleased]

## [3.1.0] - 2026-08-31

A minor release: it adds public API and removes none, so `app/api/app.api` grows by 39 entries with
zero removals or signature changes. Existing integrations need no source or build change.

### Added

- **Feature flags, experiments and personalizations, read by key.** `variation(key, context,
  defaultValue)` plus the typed helpers `boolVariation` / `stringVariation` / `numberVariation`, and
  `allFlags(context)` for every key assigned to a person in one call. `waitForInitialization` is
  present for cross-SDK parity and returns immediately — evaluation is remote, so there is no local
  flag store to wait for.
  - Ask for a **key**, never a mode: the platform's serving query filters on channel and status, so
    a caller does not have to know whether a key names a flag, an experiment or a personalization.
  - `defaultValue` is **required**, and it is what comes back on a network failure, a 5xx, a timeout,
    an unknown key or an uninitialized SDK. A returned `null` means the configured value is JSON
    `null`, which is deliberately distinguishable from a failure.
  - A **blank key throws** `IllegalArgumentException`. A service problem is absorbed; a programming
    error the caller can fix at the call site is not.
  - Assignment detail is **not** exposed. It would carry a reason the serving response does not send.
- `FlagContext(userId, profileId)` for evaluating as a specific person. **Supply `userId`
  consistently or not at all** — the serving side prefers it over `sourceId_profileId` when deriving
  assignment, so toggling it at sign-in re-buckets the person mid-session.

### Changed

- `kotlinx-coroutines-core` moved from `implementation` to `api`. The whole flag surface is
  `suspend`, so a consumer could not call a public method without adding the dependency by hand —
  a packaging bug wearing a documentation note. Purely additive for existing consumers.

### Fixed

- The `no-local-bucketing` guard could pass while scanning nothing. Its scan root defaulted to
  `src`, which no Android project has, so running it without `GUARD_SRC` set read zero files and
  then blamed the allowlist. A missing scan root now fails the run, and the output states how many
  files were read.
- Two compiler warnings on every build: a `@JvmOverloads` with no effect on `FlagDetail`, and
  `waitForInitialization`'s unused parameter suppressed under detekt's rule id but not the Kotlin
  compiler's.

### Testing

- The mutation gate's `targetClasses` allowlist matched **none** of the six classes the flag change
  touched, so its 85/85 measured untouched code. The pure decisions now live in `types/Flags.kt`,
  inside the gate: 36 mutants, **36 killed**. Suite: 203/228 (89%) with 380 unit tests passing.
- Verified on-device against a live backend (API 34 emulator): no crash on any path, every failure
  and unmatched-key path returns the caller's default, and the request on the wire matches what the
  unit tests assert — including `allFlags` omitting `names` entirely rather than sending `[]`.
- **Regression coverage for INT-5166** (`logOut()`/`reset()` not rotating the anonymous profile id).
  The defect itself was fixed in 3.0.3 and does not reproduce — three successive reads on an API 34
  emulator returned three distinct ids. What was missing was coverage of the *wiring*: `logOut()`
  rotates only because it shares `logoutHandler()` with `reset()`, and the existing tests assert
  `clearAllStorage()` directly, so giving either call its own path would have reintroduced a
  High-priority privacy bug with every test still green. Three tests now pin it — each entry point
  reaches the rotation, `logOut` keeps the queue while `reset` discards it, and successive rotations
  each yield a new id. All three were proven able to fail against planted mutants, including one
  (rotate once, then reuse) that only the new successive-rotation test catches.

## [3.0.4] - 2026-08-21

### Fixed

- Identity reads no longer race their writers. `getProfileId()`/`getSessionId()` read only an
  in-memory cache that background jobs populated, so a read immediately after `initialize()` —
  or any read after a process restart, before the population job ran — returned `""` even though
  the value sat in SharedPreferences. Three changes close the class of bug: storage writes update
  the cache on the caller's thread (persistence stays async, like `apply()`), reads fall through
  to SharedPreferences on a cache miss, and the profile id is minted synchronously before
  `initialize()` returns (`ensureProfileId`, replacing the fire-and-forget `validateProfileId`).
  Observed intermittently through the React Native bridge's e2e probe on cold devices.

## [3.0.3] - 2026-08-20

### Fixed

- `reset()` raced its own identity rotation. The rotation added in 3.0.2 ran on a fire-and-forget
  coroutine inside `clearAllStorage()`, so a caller reading `getProfileId()` immediately after
  `reset()` could still see the previous user's profile id. The wipe and the fresh mint now run
  synchronously before `reset()` returns — observed and verified end-to-end through the React
  Native bridge's e2e probe.

## [3.0.2] - 2026-08-18

A performance release. No API changes, no behaviour changes a consumer has to adapt to — the one
behaviour fix is a log line that was lying.

Every figure below was measured with `android.os.Trace` sections read by `TraceSectionMetric` on an
API 34 emulator, 5 iterations. An earlier internal estimate of "+340 ms of startup cost" was wrong:
it came from whole-app `timeToInitialDisplayMs`, which has returned anywhere from 417 to 649 ms on
identical code and cannot resolve a component this small.

### Changed

- **SDK initialization is ~3× faster: 26.18 ms → 7.93 ms.** The ktor `HttpClient` is now built on
  first use rather than in `Application.onCreate`. Constructing it was 13.79 ms — 53% of the SDK's
  entire init — because `HttpClient {}` with no explicit engine runs `ServiceLoader` engine
  discovery, which is classloading and reflection rather than network. Nothing on the common path
  needs it: event delivery uses the queue's own `HttpURLConnection`, and the two callers that do
  need ktor already run off the main thread. An app that never calls `recommendation()` and records
  no consent event never builds it at all.
- **A Baseline Profile ships inside the AAR**, cutting the SDK's own init a further 23%
  (27.81 ms → 21.33 ms under `Partial(Require)`).
- **`ktor-client-cio` removed.** `:app` declared two HTTP engines while pinning neither, so ktor
  selected one by classpath discovery and the other shipped unused. Removing it cut **1,225 methods**
  from a consuming app's dex (33,797 → 32,572), measured as an isolated before/after with only that
  line changed.

### Fixed

- **A rejected consent event no longer logs itself as delivered.** `HttpManagerService.post` reports
  failures — including every non-2xx — by returning `null` rather than throwing, so a 401 printed
  `Failed with status code: 401` and `Successfully sent events to server` one line apart, and the
  internal last-dispatch timestamp advanced for a send that never happened. Consent bypasses the
  durable queue, so that log line was the only signal a compliance decision failed to reach the
  platform.
- **`:push` was missing the `NewApi`/`checkTestSources` lint configuration** that `:app` and
  `:sample` both carry, so lint was not enforcing the `minSdk` floor on that module.

## [3.0.1] - 2026-08-16

`v3.0.0` was tagged and staged once already, then dropped before release: the tagged commit
predated the fix for `:app`/`:push` publishing to Maven Central in the same Gradle invocation (see
`RELEASING.md`), so it was never released. This version carries that fix and is otherwise
identical to what `3.0.0` would have shipped. The major bump is deliberate: `minSdk` moved and
several defaults changed behaviour, so a consumer upgrading needs to read this.

### Added

- **Durable event queue.** Events are persisted to SQLite before delivery and deleted only after the
  gateway confirms them. The previous queue was an in-memory list cleared *before* the network POST,
  so any failure or process death silently lost every unflushed event.
- **Delete-versus-retry policy** (`HttpStatusPolicy`). 408, 429, 5xx and network failures are
  retried; everything else at 300 or above is dropped. Previously an unrecognised 4xx was retried
  forever and, never being deleted, parked at the head of the queue and blocked every event behind
  it — one bad batch stopped all delivery permanently.
- **`Retry-After` is honoured** on 429 and 5xx responses.
- **A host application** (`:sample`) that consumes the library the way a customer does, with
  instrumented tests on an API 23 + 34 emulator matrix.
- **CI.** Compile, unit tests, instrumented tests, lint, ktlint, a coverage floor, mutation testing
  and AnimalSniffer now run on every pull request. Previously nothing ran on a PR at all —
  `publish.yml` triggered only on a `v*` tag, so a change that did not compile could be merged.
- **AnimalSniffer** API-compatibility gate against the `android-api-level-23` signature, so a
  dependency reaching for a class absent at `minSdk` fails the build rather than the app.
- **`@JvmStatic` and `@JvmOverloads`** across the public API. Java callers can now write
  `Intempt.track(...)` instead of `Intempt.INSTANCE.track(...)`, and defaulted methods expose the
  overload set Java expects.
- **`initialize()` returns `Boolean`** and reports failure. It previously returned `Unit` and
  swallowed every error, so a host app could not tell a working SDK from a dead one.
- **Explicit `targetSdk`** on the library module.

### Changed

- **`minSdk` 31 → 23.** A real customer was blocked by the 31 floor.
- **Apache 2.0.** The delivery substrate is vendored from
  [mixpanel-android](https://github.com/mixpanel/mixpanel-android); `NOTICE` names all derived files
  against their upstream paths, and both files ship inside the AAR.
- **`kotlinx-serialization-json` is now an `api` dependency**, not `implementation`. `JsonElement`
  and `JsonObject` appear in the public API, so as `implementation` the types were absent from a
  consumer's compile classpath and calling `recommendation()` failed to compile in the host app.
- **Jackson pinned to 2.13.5.** 2.16+ references `java.lang.BootstrapMethodError` (API 26), which
  killed every host app on Android 7.0/7.1 during initialization.
- **`logOut()` rotates `profileId`.** It previously restored the same one, leaking events between
  users on a shared device.

### Fixed

- **Every event was silently discarded.** Delivery posted with `headers = null`, so no
  `Authorization` header was sent, the gateway answered 401, and the batch was deleted as a
  permanent client error. The durable queue worked exactly as designed while losing 100% of events.
- **Passwords were sent in clear text** by autocapture. There are three text-read sites, not two;
  the third wrote `targetValue`.
- **A truncated 2xx was treated as a delivered batch.** A response shorter than its declared
  `Content-Length` returned success with a partial body and the batch was deleted, so events were
  lost with the platform never having finished accepting them.
- **`Retry-After` was discarded on every 5xx.** `ServiceUnavailableException` does not extend
  `IOException`, so it fell through to a generic handler and was wrapped, leaving the code that
  honours the header unreachable.
- **`isRetryable` disagreed with the transport above status 599.** The policy called such a status
  transient while `HttpService` called it permanent.
- **`CancellationException` was swallowed**, breaking structured concurrency with no exception
  anywhere: a cancelled scope kept working and the symptom was a leak.
- **API-level crashes:** `java.util.Base64` (API 26) in the auth path,
  `DatePicker.setOnDateChangedListener` (API 26), `PackageInfo.longVersionCode` (API 28).
- **R8 failed for every consumer** with `minifyEnabled true`, because ktor pulls in `slf4j-api`
  whose `LoggerFactory` references `org.slf4j.impl` classes absent on Android. The library now ships
  `-dontwarn` for it in `consumer-rules.pro`.
- **A malformed `apiKey` threw `IndexOutOfBoundsException`** from inside the auth path. A key without
  a `.` separator is now reported rather than fatal.
- **`initialize()` returned `true` with no config asset present.** Credentials are read lazily, so a
  misconfigured app was told it was healthy and dropped every event.
- **`SCHEDULE_EXACT_ALARM`** permission removed; it was never used.

### Removed

- **Client-side IP capture and the `ipapi.co` call.** The SDK used to fetch the device's public IP
  and city/region/country from `ipapi.co` on every session start and attach all four to the payload.
  It ran outside consent gating — `SessionTracker` was the only capture path that never checked
  `isUserOptIn` — with no consumer switch and no sub-processor disclosure anywhere in the repo.

  Replaced with the mechanism mixpanel-android uses: `?ip=1` on the ingestion endpoint tells the
  platform it may geolocate from the source IP of the request it already receives, and `?ip=0` tells
  it not to. The device never handles its own IP and no third party is involved. Configurable via
  `"useIpAddressForGeolocation"` in `intempt-config.json`, defaulting to `true` as Mixpanel's does.

  **Breaking, two ways.** `userAttributes` no longer carries `ipAddress`, `city`, `region` or
  `country`, and `SessionUserAttributes`' constructor lost those four parameters. Anyone reading geo
  off a session event gets nothing until the platform derives it.

- **Experiments and personalizations.** Those are `intempt.js` features and were never supported by
  this SDK's backend contract.

- **BREAKING: push notifications are no longer bundled in `com.intempt.sdk:intempt-android`.**
  Firebase Cloud Messaging, the notification-dispatch activity, and the push-notification webhook
  path moved out to a new, separate artifact: `com.intempt.sdk:intempt-push`. A host app that wants
  push notifications must now add that dependency explicitly:

  ```kotlin
  implementation("com.intempt.sdk:intempt-android:3.0.1")
  implementation("com.intempt.sdk:intempt-push:3.0.1") // add this to keep push working
  ```

  No code changes are required in either case: `Intempt.initialize()` detects `:intempt-push` at
  runtime and enables push automatically when it's present, and silently skips it (as it already
  did for a host app with no Firebase config) when it's absent. This was done to stop shipping
  Firebase, Jackson, and Glide — none of which any non-push consumer needs — in every install of
  the core SDK; see `docs/MIGRATION.md` for details.

## [2.0.1]

The last tagged release. Published to Maven Central as `com.intempt.sdk:intempt-android:2.0.1`.

No changelog was kept at the time, and this file does not invent one. `git log v2.0.1` is the
authoritative record of what that tag contains.

[Unreleased]: https://github.com/intempt/intempt-android/compare/v3.0.1...HEAD
[3.0.1]: https://github.com/intempt/intempt-android/compare/v2.0.1...v3.0.1
[2.0.1]: https://github.com/intempt/intempt-android/releases/tag/v2.0.1
