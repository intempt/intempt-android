# Inherit Mixpanel's Delivery Substrate — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Vendor Mixpanel Android's proven event-delivery substrate into the Intempt Android SDK as Java — de-branded, stripped of Mixpanel-specific surface, retargeted to Intempt's REST contract — replacing the in-memory queue that currently loses every unflushed event on network failure or process death.

**Why vendor Java instead of translating to Kotlin:** the previous attempt hand-translated the mechanism into Kotlin and scored **41/100** on independent audit. Four of its seven worst findings were *fidelity misses* — hardening Mixpanel has that a reader silently drops (`mFailedRetries` flush guard, `Math.max` backpressure floor read as a ceiling, no batch cap, no per-row poison-pill skip). **This plan is mostly mechanical — copy, rename, delete — not generative.** You cannot drop a guard you never retyped. Kotlin/Java interop is seamless on Android, and their code already runs at minSdk 21, which serves the reach goal for free.

**Architecture:** A new `com.intempt.core.queue` package holds the vendored Java (`EventDbAdapter`, `DeliveryMessages`, `HttpService`, `RemoteService`, `SynchronizedReference`, `JsonUtils`, `OfflineMode`). `EventPoolManagerService` (Kotlin) hands events to `DeliveryMessages` instead of an in-memory list. Transport encoding and the HTTP error taxonomy are rewritten for Intempt's contract; everything else is inherited verbatim.

**Tech Stack:** Java 8 (vendored substrate) + Kotlin 1.9.22 (existing SDK), Dagger 2.52, Ktor 2.3.11 (side endpoints only — the track path uses the vendored `HttpService`), AGP 8.6.1, compileSdk 35.

## Global Constraints

- **No public API change.** All 19 members and 2 properties in `Intempt.kt` keep their exact signatures. Verified inventory: `docs/FEATURES.md` §1.
- **Wire format is fixed by contract.** `POST /v1/{org}/projects/{project}/sources/{sourceId}/track`, body `{"track":[{"name":…,"payload":[…]}]}`, auth `Authorization: Basic base64(identifier:secret)`. Source: `api-reference.yaml`, `LegacyApiKeyTranslatorFilter.java:30-32`.
- **Inherit verbatim wherever possible.** Every deviation from Mixpanel's logic must be justified against a requirement in `docs/FEATURES.md` or a divergence in `docs/android-sdk-requirements.md` §1. "Cleaner" is not a justification.
- **Fidelity checklist (§9) is a merge gate.** All 12 items must have a named test before this ships. These are the exact behaviors the previous attempt lost.
- **minSdk target 21** (or 23 if `firebase-messaging` floors there — Task 6 Step 1 settles it). `animalsniffer` enforces it at build time.
- **Apache 2.0.** New repo, Apache 2.0 from commit 1. Vendored files carry per-file attribution headers; `NOTICE` ships inside the AAR.
- **`sendConsentEvent`, `optimization/choose-api`, `feeds/{id}/data` keep using Ktor `HttpManagerService`.** Only the batched track path moves to the vendored transport.
- Package for all vendored code: `com.intempt.core.queue`. Visibility: package-private (`/* package */`) exactly as Mixpanel has it, except `DeliveryMessages` which `EventPoolManagerService` must reach.

---

## File Structure

### Vendored — copied from `mixpanel-android@master`, renamed, edited

**Dependency trace, run before writing any code.** Every `com.mixpanel.*` import and cross-type reference in the candidate set was traced against the cut list. Four corrections resulted — the naive set does **not** compile:

| Finding | Evidence | Correction |
|---|---|---|
`JsonUtils` is a feature-flag parser, not a generic utility — `parseFlagVariant`/`parseFlagsResponse` return `MixpanelFlagVariant` (cut, R6), and 17 refs are `MPConstants.Flags.*` | `JsonUtils.java:114,168`, `:122-140` | **CUT it.** Was listed as "rename only" — wrong |
`ProxyServerInteractor` is referenced 4× in `HttpService`, 2× in `RemoteService` | `HttpService.java`, `RemoteService.java` | **Must be vendored** (9 LOC). Was on the cut list |
`MixpanelNetworkErrorListener` is referenced 3× in `AnalyticsMessages`, 4× in `HttpService` | both files | **Must be vendored** (23 LOC). Was cut, though the sed renamed it — an internal contradiction |
`SystemInformation` has 11 call sites, all inside `getDefaultEventProperties` (display metrics, app version, NFC, telephony, carrier, wifi, bluetooth) | `AnalyticsMessages.java:750-783` | **Delete `getDefaultEventProperties` entirely.** Those are Mixpanel's `$`-prefixed properties; ours come from `IntemptEventManager.service.kt:375-455` and our wire format has no slot for `$has_nfc` |

Also confirmed benign: all `MixpanelAPI` references are `LOGTAG` strings or javadoc (`MPDbAdapter.java:24,31`, `AnalyticsMessages.java:888`, `HttpService.java:694`) — **no real coupling to the 3,159-line class.** And `PersistentIdentity`'s 6 references are confined to `FirstLaunchDescription` (`:397-418`) and the `CHECK_FIRST_LAUNCH` handler (`:564`), both already deleted by Task 2 — that coupling dies on its own.

| New path (`app/src/main/java/com/intempt/core/queue/`) | Origin | LOC in | Action |
|---|---|---|---|
`EventDbAdapter.java` | `mpmetrics/MPDbAdapter.java` | 691 | strip to 1 table, drop token column, drop 2 methods + migrations |
`DeliveryMessages.java` | `mpmetrics/AnalyticsMessages.java` | 891 | drop 5 message types, 5 Description classes, **and `getDefaultEventProperties`** |
`HttpService.java` | `util/HttpService.java` | 737 | replace encoding + error taxonomy; inline the 2 `MPConstants.URL` constants |
`RemoteService.java` | `util/RemoteService.java` | 166 | interface — rename only |
`SynchronizedReference.java` | `mpmetrics/SynchronizedReference.java` | 27 | rename only |
`OfflineMode.java` | `util/OfflineMode.java` | 16 | rename only |
`NetworkErrorListener.java` | `util/MixpanelNetworkErrorListener.java` | 23 | **added by the trace** — rename only |
`ProxyServerInteractor.java` | `util/ProxyServerInteractor.java` | 9 | **added by the trace** — rename only |
`QueueConfig.java` | `mpmetrics/MPConfig.java` | 651 | **rewrite** — constants only, sourced from `intempt-config.json` |

### New — Intempt-specific, written from scratch

| Path | Responsibility |
|---|---|
`queue/TrackPayloadBuilder.java` | assemble `{"track":[{name,payload:[]}]}`, grouping by event name |
`queue/HttpStatusPolicy.java` | 429/5xx/timeout → retry · 401/403/400/422 → drop |

### Modified

| File | Change |
|---|---|
`services/eventPool/EventPoolManagerService.kt` | replace in-memory `eventQueue` with `DeliveryMessages`; **keep `lastDispatchTime`** (v1 deleted it while `sendConsentEvent:202` still used it — compile error) |
`services/ConfigManager.service.kt` | `java.util.Base64` → `android.util.Base64` (API 26 → API 1) |
`autocapture/lifecycleCallbacksTracker/ChangeTracker.service.kt:89,94` | PII mask on `EditText`; `SDK_INT` guard on `TimePicker.hour/minute` |
`services/IntemptEventManager.service.kt:449` | same `TimePicker` guard |
`services/StorageManager.service.kt:139-169` | rotate `profileId` on `logOut()` |
`services/firebase/FirebaseService.kt:126,195` | guard `NotificationChannel` (API 26); fix notification-enabled check |
`services/firebase/webhook/WebhookService.kt` | route the 3 lifecycle webhooks through the queue (decision D3) |
`intemptCore/IntemptCore.module.kt` | provide `EventDbAdapter`, `DeliveryMessages`, `QueueConfig` |
`AndroidManifest.xml` | remove `SCHEDULE_EXACT_ALARM` (declared, zero usages) |
`app/build.gradle.kts`, `gradle/libs.versions.toml` | minSdk, animalsniffer, jacoco, ktlint |
`app/consumer-rules.pro`, `NOTICE`, `LICENSE` | new — packaging |
`.github/workflows/ci.yml` | new — the gate |

### Cut — not vendored

`MixpanelAPI.java` 3,159 · `FeatureFlagManager.java` 1,560 · `PersistentIdentity.java` 729 · `MixpanelOptions.java` 366 · `MixpanelFlagVariant.java` 321 · `FeatureFlagOptions.java` 203 · `SystemInformation.java` 188 (we have `IntemptEventManager.service.kt:375-455`) · `Base64Coder.java` 137 (only needed for form-encoding we're dropping) · `MPLog.java` 87 (we have `LoggerManagerService`) · 19 smaller files · `session-replay` 4,836 · `openfeature-provider` 323 · `common` 562. **Total cut: 13,537 LOC.**

---

## Task 1: Vendor the substrate verbatim, de-branded

No logic changes in this task. It must compile before anything is stripped, so that later diffs are readable.

**Files:** the 7 rename-only/strip-later files above (not `QueueConfig.java` — Task 3).

**Interfaces:**
- Produces: `com.intempt.core.queue.{EventDbAdapter, DeliveryMessages, HttpService, RemoteService, SynchronizedReference, JsonUtils, OfflineMode}` — same members as their Mixpanel originals.

- [ ] **Step 1: Copy the 8 files** (revised by the dependency trace — `JsonUtils` dropped, two interfaces added)

```bash
MP=/private/tmp/claude-501/.../mixpanel-android-master/analytics/src/main/java/com/mixpanel/android
Q=app/src/main/java/com/intempt/core/queue
mkdir -p "$Q"
cp "$MP/mpmetrics/MPDbAdapter.java"             "$Q/EventDbAdapter.java"
cp "$MP/mpmetrics/AnalyticsMessages.java"       "$Q/DeliveryMessages.java"
cp "$MP/mpmetrics/SynchronizedReference.java"   "$Q/SynchronizedReference.java"
cp "$MP/util/HttpService.java"                  "$Q/HttpService.java"
cp "$MP/util/RemoteService.java"                "$Q/RemoteService.java"
cp "$MP/util/OfflineMode.java"                  "$Q/OfflineMode.java"
cp "$MP/util/MixpanelNetworkErrorListener.java" "$Q/NetworkErrorListener.java"
cp "$MP/util/ProxyServerInteractor.java"        "$Q/ProxyServerInteractor.java"
```

Do **not** copy `JsonUtils.java` — it parses feature-flag variants (`JsonUtils.java:114,168`) and returns `MixpanelFlagVariant`, which is cut. If Task 1 Step 5's compile demands its generic `parseJsonValue`/`jsonObjectToMap` half, extract only those three private methods; do not vendor the file.

- [ ] **Step 2: Rewrite packages, class names, and log tags**

```bash
cd app/src/main/java/com/intempt/core/queue
sed -i '' \
  -e 's/package com\.mixpanel\.android\.mpmetrics;/package com.intempt.core.queue;/' \
  -e 's/package com\.mixpanel\.android\.util;/package com.intempt.core.queue;/' \
  -e '/^import com\.mixpanel\.android\.\(util\|mpmetrics\)\./d' \
  -e 's/\bMPDbAdapter\b/EventDbAdapter/g' \
  -e 's/\bAnalyticsMessages\b/DeliveryMessages/g' \
  -e 's/\bMPConfig\b/QueueConfig/g' \
  -e 's/\bMPLog\b/QueueLog/g' \
  -e 's/"MixpanelAPI\./"Intempt./g' \
  -e 's/\bMixpanelNetworkErrorListener\b/NetworkErrorListener/g' \
  *.java
```

`MPLog` is cut, so `QueueLog` will not resolve — Step 3 fixes that. Every removed `import com.mixpanel.android.*` is intra-package after the move, so no import is needed back.

- [ ] **Step 3: Add a 12-line `QueueLog.java` shim delegating to Android `Log`**

```java
package com.intempt.core.queue;

import android.util.Log;

/* package */ final class QueueLog {
    private static boolean sEnabled = false;
    static void setEnabled(boolean enabled) { sEnabled = enabled; }
    static void v(String tag, String msg) { if (sEnabled) Log.v(tag, msg); }
    static void v(String tag, String msg, Throwable t) { if (sEnabled) Log.v(tag, msg, t); }
    static void d(String tag, String msg) { if (sEnabled) Log.d(tag, msg); }
    static void e(String tag, String msg) { if (sEnabled) Log.e(tag, msg); }
    static void e(String tag, String msg, Throwable t) { if (sEnabled) Log.e(tag, msg, t); }
    static void w(String tag, String msg) { if (sEnabled) Log.w(tag, msg); }
    static void w(String tag, String msg, Throwable t) { if (sEnabled) Log.w(tag, msg, t); }
    private QueueLog() {}
}
```

`LoggerManagerService` stays the SDK's public-facing logger; `QueueLog` exists only so the vendored files compile unmodified. Wire `setEnabled` from `ConfigManagerService.isLoggingEnabled` in Task 5.

- [ ] **Step 4: Add Apache 2.0 attribution headers to all 8 vendored files**

Prepend to each:

```java
/*
 * Adapted from the Mixpanel Android SDK
 * https://github.com/mixpanel/mixpanel-android
 * Copyright 2022 Mixpanel, Inc.
 * Licensed under the Apache License, Version 2.0.
 *
 * Modifications by Intempt Technologies: renamed package and classes; reduced the
 * four-table schema to a single events table; removed people/group/anonymous-profile
 * handling; replaced form-urlencoded transport encoding with Intempt's JSON contract;
 * replaced the HTTP error taxonomy to treat 429 as retryable.
 */
```

Apache 2.0 §4(b) requires modified files to carry prominent change notices. This is that notice.

- [ ] **Step 5: Confirm it compiles**

Run: `./gradlew :app:compileDebugJavaWithJavac`
Expected: BUILD SUCCESSFUL. Unresolved symbols here mean a `sed` missed a reference — fix and re-run before proceeding.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/intempt/core/queue/
git commit -m "chore: vendor Mixpanel delivery substrate, de-branded, Apache 2.0 attributed"
```

---

## Task 2: Strip Mixpanel-specific surface from the substrate

**Files:** `queue/EventDbAdapter.java`, `queue/DeliveryMessages.java`

**Interfaces:**
- Consumes: Task 1's vendored classes
- Produces: `EventDbAdapter` with a single `Table.EVENTS`, no `token` parameter on any method; `DeliveryMessages` with only `ENQUEUE_EVENTS`, `FLUSH_QUEUE`, `EMPTY_QUEUES`, `KILL_WORKER` message types.

- [ ] **Step 1: `EventDbAdapter` — collapse to one table**

Delete: `Table.PEOPLE`, `Table.GROUPS`, `Table.ANONYMOUS_PEOPLE` enum entries; `CREATE_PEOPLE_TABLE`, `CREATE_GROUPS_TABLE`, `CREATE_ANONYMOUS_PEOPLE_TABLE` and their `*_TIME_INDEX` constants; those tables' `execSQL` calls in `onCreate`/`onUpgrade`.

**Keep `EVENTS_TIME_INDEX` and its `execSQL`** — the index on `created_at` is fidelity item F8.

Delete the `token` column: remove `KEY_TOKEN` and `TOKEN_COLUMN_INDEX`, drop `KEY_TOKEN` from `CREATE_EVENTS_TABLE`, and remove the `String token` parameter from `addJSON`, `cleanupEvents`, `cleanupAllEvents`, `generateDataString`. Intempt has one SDK instance per app — no multi-token routing.

Delete `pushAnonymousUpdatesToPeopleDb` (63 lines), `rewriteEventDataWithProperties` (62 lines), `migrateTableFrom4To5`, `migrateTableFrom5To6`, `migrateTableFrom6To7`. Set `DATABASE_VERSION = 1`, `MIN_DB_VERSION = 1`, `MAX_DB_VERSION = 1`, and reduce `onUpgrade` to the drop-and-recreate branch.

Rename `DATABASE_NAME` to `"intempt_events"`. Delete the `getDbName(instanceName)` indirection and the `sInstances` map — construct one adapter and let Dagger hold it.

**Preserve verbatim, these are fidelity items:** `aboveMemThreshold()` (F4), the `SQLiteException` → `deleteDatabase()` recovery in `addJSON`/`cleanupEvents` (F7), the read path's deliberate *non*-destruction (F7b), the per-row `JSONException` skip in `generateDataString` (F5), and the `LIMIT getFlushBatchSize()` clause (F6).

- [ ] **Step 2: `DeliveryMessages` — drop unused message types**

Delete constants and their `handleMessage` branches: `ENQUEUE_PEOPLE`, `ENQUEUE_GROUP`, `PUSH_ANONYMOUS_PEOPLE_RECORDS`, `CLEAR_ANONYMOUS_UPDATES`, `REWRITE_EVENT_PROPERTIES`, `REMOVE_RESIDUAL_IMAGE_FILES`, `CHECK_FIRST_LAUNCH`.

Delete classes: `PeopleDescription`, `GroupDescription`, `PushAnonymousPeopleDescription`, `UpdateEventsPropertiesDescription`, `FirstLaunchDescription`.

Delete their public entry points (`peopleMessage`, `groupMessage`, `pushAnonymousPeopleMessage`, `clearAnonymousUpdatesMessage`, `updateEventProperties`, `checkFirstLaunch`) and the `sendData` calls for `PEOPLE`/`GROUPS` in `sendAllData`.

Delete the `notifyEventBridgeListeners` call and `FirstTimeEventListener` hook — no Intempt equivalent.

**Preserve verbatim:** the `Worker`/`HandlerThread` construction (F10), the retry-and-backoff block including `Math.max`/`Math.min` clamping (F1), the `returnCode >= getBulkUploadLimit() && mFailedRetries <= 0` guard (F2 — *this is the exact guard the previous attempt dropped*), `deleteEvents=false` on failure (F3), the `getDataExpiration()` cleanup on first `handleMessage` (F9), and the `poster.isOnline(...)` gate (F11).

- [ ] **Step 3: Compile**

Run: `./gradlew :app:compileDebugJavaWithJavac`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git commit -am "refactor: strip people/group/anonymous surface and multi-token routing from vendored substrate"
```

---

## Task 3: `QueueConfig` — Intempt's config source, Mixpanel's constants

**Files:** Create `queue/QueueConfig.java` (replacing the vendored `MPConfig`, which is deleted)

**Interfaces:**
- Produces: `QueueConfig` with `getBulkUploadLimit()`, `getFlushInterval()`, `getFlushBatchSize()`, `getDataExpiration()`, `getMinimumDatabaseLimit()`, `getMaximumDatabaseLimit()`, `getEventsEndpoint()`, `getOfflineMode()`, `getSSLSocketFactory()`, `getProxyServerInteractor()` — the subset `EventDbAdapter`/`DeliveryMessages`/`HttpService` actually call.

- [ ] **Step 1: Write the failing test**

```java
package com.intempt.core.queue;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class QueueConfigTest {

    @Test public void bulkUploadLimitMatchesMixpanelDefault() {
        assertEquals(40, new QueueConfig("https://api.intempt.com/track").getBulkUploadLimit());
    }

    @Test public void flushIntervalMatchesMixpanelDefault() {
        assertEquals(60_000, new QueueConfig("https://api.intempt.com/track").getFlushInterval());
    }

    @Test public void flushBatchSizeMatchesMixpanelDefault() {
        assertEquals(50, new QueueConfig("https://api.intempt.com/track").getFlushBatchSize());
    }

    @Test public void dataExpirationIsFiveDays() {
        assertEquals(5L * 24 * 60 * 60 * 1000,
                new QueueConfig("https://api.intempt.com/track").getDataExpiration());
    }

    /**
     * Mixpanel's 20MB MinimumDatabaseLimit is a FLOOR inside
     * Math.max(usableSpace, minimum) — not a ceiling. The real ceiling is
     * MaximumDatabaseLimit = Integer.MAX_VALUE. The previous attempt inverted this
     * and would have dropped events on devices with gigabytes free.
     * Source: MPDbAdapter.java:189-190, MPConfig.java:210-215.
     */
    @Test public void databaseLimitsPreserveMixpanelSemantics() {
        QueueConfig c = new QueueConfig("https://api.intempt.com/track");
        assertEquals(20 * 1024 * 1024, c.getMinimumDatabaseLimit());
        assertEquals(Integer.MAX_VALUE, c.getMaximumDatabaseLimit());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.intempt.core.queue.QueueConfigTest"`
Expected: FAIL — `QueueConfig` has no such constructor.

- [ ] **Step 3: Implement**

```java
package com.intempt.core.queue;

import javax.net.ssl.SSLSocketFactory;

/**
 * Tuning constants inherited verbatim from Mixpanel's MPConfig; configuration
 * *source* is Intempt's assets/intempt-config.json, wired in by ConfigManagerService,
 * not AndroidManifest metadata.
 */
public class QueueConfig {
    private static final int BULK_UPLOAD_LIMIT = 40;                       // MPConfig.java:198
    private static final int FLUSH_INTERVAL_MS = 60 * 1000;                // MPConfig.java:201
    private static final int FLUSH_BATCH_SIZE = 50;                        // MPDbAdapter.java:627
    private static final long DATA_EXPIRATION_MS = 1000L * 60 * 60 * 24 * 5; // MPConfig.java:235
    private static final int MINIMUM_DATABASE_LIMIT = 20 * 1024 * 1024;    // MPConfig.java:212 — a FLOOR
    private static final int MAXIMUM_DATABASE_LIMIT = Integer.MAX_VALUE;   // MPConfig.java:215 — the ceiling

    private final String mEventsEndpoint;
    private boolean mLoggingEnabled = false;

    public QueueConfig(String eventsEndpoint) { mEventsEndpoint = eventsEndpoint; }

    public int getBulkUploadLimit() { return BULK_UPLOAD_LIMIT; }
    public int getFlushInterval() { return FLUSH_INTERVAL_MS; }
    public int getFlushBatchSize() { return FLUSH_BATCH_SIZE; }
    public long getDataExpiration() { return DATA_EXPIRATION_MS; }
    public int getMinimumDatabaseLimit() { return MINIMUM_DATABASE_LIMIT; }
    public int getMaximumDatabaseLimit() { return MAXIMUM_DATABASE_LIMIT; }
    public String getEventsEndpoint() { return mEventsEndpoint; }

    public OfflineMode getOfflineMode() { return null; }
    public SSLSocketFactory getSSLSocketFactory() { return null; }
    public ProxyServerInteractor getProxyServerInteractor() { return null; }

    public void setLoggingEnabled(boolean enabled) {
        mLoggingEnabled = enabled;
        QueueLog.setEnabled(enabled);
    }
    public boolean isLoggingEnabled() { return mLoggingEnabled; }
}
```

`getOfflineMode()`/`getSSLSocketFactory()`/`getProxyServerInteractor()` return `null` because the vendored callers already null-check them (`AnalyticsMessages.java:635`, `:669`). Do not delete these methods — the vendored code calls them.

- [ ] **Step 4: Delete the vendored `MPConfig` copy if Task 1 produced one, and run the test**

Run: `./gradlew :app:testDebugUnitTest --tests "com.intempt.core.queue.QueueConfigTest"`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/intempt/core/queue/QueueConfig.java \
        app/src/test/java/com/intempt/core/queue/QueueConfigTest.java
git commit -m "feat: QueueConfig — Mixpanel tuning constants, Intempt config source"
```

---

## Task 4: Retarget the transport — Intempt encoding and error taxonomy

This is the largest genuine deviation from Mixpanel, and both halves are mandated by our contract.

**Files:**
- Modify `queue/HttpService.java`
- Create `queue/HttpStatusPolicy.java`
- Create `queue/TrackPayloadBuilder.java`
- Modify `queue/DeliveryMessages.java` (`sendData` — use the new builder)
- Tests: `queue/HttpStatusPolicyTest.java`, `queue/TrackPayloadBuilderTest.java`

**Interfaces:**
- Produces: `HttpStatusPolicy.isRetryable(int status)`, `.shouldDrop(int status)`; `TrackPayloadBuilder.build(List<JSONObject> events) → JSONObject`; `HttpService.performRequest(..., byte[] jsonBody, String authHeader, ...)`

- [ ] **Step 1: Write the failing tests**

```java
package com.intempt.core.queue;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class HttpStatusPolicyTest {

    /**
     * Mixpanel treats every 4xx as a non-retryable client error and discards the batch
     * (HttpService.java:220-259). Intempt's gateway signals backpressure with 429 —
     * a 4xx — carrying X-RateLimit-* headers and no Retry-After. Inheriting Mixpanel's
     * rule verbatim would permanently drop every rate-limited batch.
     * Source: http-error-codes.mdx, "Rate limiting (429)".
     */
    @Test public void rateLimitIsRetryableNotDropped() {
        assertTrue(HttpStatusPolicy.isRetryable(429));
        assertFalse(HttpStatusPolicy.shouldDrop(429));
    }

    @Test public void serverErrorsAreRetryable() {
        assertTrue(HttpStatusPolicy.isRetryable(500));
        assertTrue(HttpStatusPolicy.isRetryable(503));
    }

    @Test public void authFailuresAreDroppedNotRetried() {
        // A bad API key never fixes itself; retrying forever burns battery and quota.
        assertFalse(HttpStatusPolicy.isRetryable(401));
        assertTrue(HttpStatusPolicy.shouldDrop(401));
        assertFalse(HttpStatusPolicy.isRetryable(403));
        assertTrue(HttpStatusPolicy.shouldDrop(403));
    }

    @Test public void malformedRequestsAreDroppedNotRetried() {
        // Re-POSTing the same malformed body would wedge the queue forever.
        assertTrue(HttpStatusPolicy.shouldDrop(400));
        assertTrue(HttpStatusPolicy.shouldDrop(422));
    }

    @Test public void successIsNeitherRetriedNorDropped() {
        assertFalse(HttpStatusPolicy.isRetryable(200));
        assertFalse(HttpStatusPolicy.shouldDrop(200));
    }
}
```

```java
package com.intempt.core.queue;

import static org.junit.Assert.assertEquals;
import java.util.Arrays;
import org.json.JSONObject;
import org.junit.Test;

public class TrackPayloadBuilderTest {

    private JSONObject event(String name, String eventId) throws Exception {
        JSONObject payload = new JSONObject().put("eventId", eventId);
        return new JSONObject().put("name", name).put("payload", new org.json.JSONArray().put(payload));
    }

    /** Two occurrences of one event name must collapse into a single track[] group
     *  with a two-element payload[] — that is the shape api-reference.yaml documents. */
    @Test public void groupsOccurrencesOfTheSameNameIntoOneEntry() throws Exception {
        JSONObject out = TrackPayloadBuilder.build(
                Arrays.asList(event("Purchase", "ev_1"), event("Purchase", "ev_2")));
        assertEquals(1, out.getJSONArray("track").length());
        assertEquals(2, out.getJSONArray("track").getJSONObject(0).getJSONArray("payload").length());
    }

    @Test public void keepsDistinctNamesInSeparateEntries() throws Exception {
        JSONObject out = TrackPayloadBuilder.build(
                Arrays.asList(event("Purchase", "ev_1"), event("Signup", "ev_2")));
        assertEquals(2, out.getJSONArray("track").length());
    }

    @Test public void emptyInputProducesAnEmptyTrackArray() throws Exception {
        assertEquals(0, TrackPayloadBuilder.build(java.util.Collections.emptyList())
                .getJSONArray("track").length());
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.intempt.core.queue.HttpStatusPolicyTest" --tests "com.intempt.core.queue.TrackPayloadBuilderTest"`
Expected: FAIL — neither class exists.

- [ ] **Step 3: Implement both**

```java
package com.intempt.core.queue;

/**
 * Intempt's HTTP retry taxonomy. Deliberately diverges from Mixpanel, which discards
 * all 4xx without retry (HttpService.java:220-259) — Intempt's gateway uses 429 as its
 * primary backpressure signal, so that rule would drop rate-limited batches for good.
 */
/* package */ final class HttpStatusPolicy {

    static boolean isRetryable(int status) {
        return status == 429 || status >= 500 || status <= 0;
    }

    /** True when the batch can never succeed as-is and must be removed from the queue. */
    static boolean shouldDrop(int status) {
        return status == 401 || status == 403 || status == 400 || status == 422;
    }

    private HttpStatusPolicy() {}
}
```

```java
package com.intempt.core.queue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Builds Intempt's ingestion body: {"track":[{"name":…,"payload":[…]}]}.
 * track[] groups by event name; payload[] holds every occurrence of that name.
 * Source: api-reference.yaml:52-202.
 */
/* package */ final class TrackPayloadBuilder {

    static JSONObject build(List<JSONObject> events) throws JSONException {
        Map<String, JSONArray> byName = new LinkedHashMap<String, JSONArray>();

        for (JSONObject event : events) {
            String name = event.optString("name", "");
            JSONArray occurrences = event.optJSONArray("payload");
            if (occurrences == null) continue;

            JSONArray target = byName.get(name);
            if (target == null) {
                target = new JSONArray();
                byName.put(name, target);
            }
            for (int i = 0; i < occurrences.length(); i++) {
                target.put(occurrences.get(i));
            }
        }

        JSONArray track = new JSONArray();
        for (Map.Entry<String, JSONArray> entry : byName.entrySet()) {
            track.put(new JSONObject().put("name", entry.getKey()).put("payload", entry.getValue()));
        }
        return new JSONObject().put("track", track);
    }

    private TrackPayloadBuilder() {}
}
```

- [ ] **Step 4: Rewire `HttpService` encoding**

In `HttpService.java`, in the params-encoding branch (around the original `:370-371, 423-424`): delete the `URLEncoder`/form-urlencoded path and the `application/x-www-form-urlencoded` content type. Send the raw JSON body with `Content-Type: application/json; charset=utf-8`, and set `Authorization` from a new `authHeader` parameter threaded through `performRequest`.

Delete the `Base64Coder` usage in `DeliveryMessages.sendData` (originally `AnalyticsMessages.java:659-661`) — Intempt posts unencoded JSON, not `data=base64(...)`.

In `DeliveryMessages.sendData`, replace the `params.put("data", encodedData)` assembly with `TrackPayloadBuilder.build(...)`, and replace the `deleteEvents` decision with `HttpStatusPolicy`:

```java
if (HttpStatusPolicy.shouldDrop(status)) {
    QueueLog.e(LOGTAG, "Non-retryable status " + status + "; dropping batch");
    deleteEvents = true;            // remove so a permanently-bad batch cannot wedge the queue
} else if (HttpStatusPolicy.isRetryable(status)) {
    deleteEvents = false;           // keep — Mixpanel's backoff block below reschedules
}
```

**Keep the existing retry/backoff block below this untouched** — that is fidelity item F1.

- [ ] **Step 5: Run tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.intempt.core.queue.*"`
Expected: PASS — `QueueConfigTest` (5), `HttpStatusPolicyTest` (5), `TrackPayloadBuilderTest` (3).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/intempt/core/queue/ app/src/test/java/com/intempt/core/queue/
git commit -m "feat: retarget vendored transport to Intempt JSON contract and 429-aware taxonomy"
```

---

## Task 5: Bridge the Kotlin SDK to the vendored queue

**Files:**
- Modify `services/eventPool/EventPoolManagerService.kt`
- Modify `intemptCore/IntemptCore.module.kt`
- Test: `services/eventPool/EventPoolHandoffTest.kt`

**Interfaces:**
- Consumes: `DeliveryMessages.eventsMessage(...)`, `.postToServer(...)`, `QueueConfig`
- Produces: `EventPoolManagerService(config, logger, http, intemptEvent, delivery: DeliveryMessages, dispatcher)` — one new parameter, appended before `dispatcher`.

**Two v1 bugs this task must not repeat.** The previous attempt (a) deleted `lastDispatchTime` (declared `:48`) while `sendConsentEvent:202` still assigned it — an outright compile error it claimed would build, and (b) made the flow collector `suspend` and awaited the enqueue inline, so with `MutableSharedFlow(replay = 10)` + `tryEmit` (`:52`, `:112`) and two subscribers (`startEventCollection` plus `SessionTracker.service.kt:80`), the 11th event during a slow flush was **silently dropped before reaching the durable queue** — reintroducing the exact bug being fixed.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.intempt.core.services.eventPool

import com.intempt.core.eventModels.IntemptEvent
import com.intempt.core.types.IntemptEventProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class EventPoolHandoffTest {

    private fun event(name: String): IntemptEvent {
        val p = object : IntemptEventProvider {
            override val eventId = "ev_1"; override val sessionId = "ses_1"
            override val pageId = "pag_1"; override val profileId = "prof_1"
            override val timestamp = 1000L
            override fun getEventTime() = 1000L
            override fun toFormated(): Map<String, Any?> = mapOf("eventId" to eventId)
        }
        return IntemptEvent(name = name, type = "track", payload = arrayOf(p))
    }

    /**
     * The collector must hand off without suspending. _eventReceiver is a
     * MutableSharedFlow(replay = 10) emitted via tryEmit, which returns false when the
     * buffer is full — so a collector that awaits I/O causes silent drops upstream of
     * the durable queue. Emitting 100 events with a deliberately slow consumer must
     * lose none.
     */
    @Test
    fun `no events are dropped when the consumer is slow`() {
        val recorded = mutableListOf<IntemptEvent>()
        val slowConsumer = FakeDelivery(recorded, delayMs = 5)

        val pool = newPoolWith(slowConsumer)
        repeat(100) { pool.emitEvent(event("e$it")) }
        slowConsumer.awaitQuiescence()

        assertEquals(100, recorded.size)
    }
}
```

`FakeDelivery` and `newPoolWith` are written in Step 3 alongside the implementation — the test helper and the production seam are designed together, since the seam's whole purpose is testability.

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.intempt.core.services.eventPool.EventPoolHandoffTest"`
Expected: FAIL — `FakeDelivery` / `newPoolWith` unresolved.

- [ ] **Step 3: Modify `EventPoolManagerService.kt`**

Add the constructor parameter and **keep every existing field**, `lastDispatchTime` included:

```kotlin
internal open class EventPoolManagerService @Inject constructor(
    private val config: ConfigManagerService,
    private val logger: LoggerManagerService,
    private val http: HttpManagerService,
    private val intemptEvent: IntemptEventManagerService,
    private val delivery: DeliveryMessages,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
): BaseComponent(logger)
```

Replace `startEventCollection`'s body. The callback stays **non-suspend** and returns immediately — `delivery.eventsMessage` posts to the `HandlerThread` and does not block:

```kotlin
private fun startEventCollection(){
    logger.log("EventPoolManagerService | Started collecting events")
    subscribe(Job()) { event ->
        when (event.getEventType()) {
            EventType.Consent.value -> sendConsentEvent(event)
            else -> delivery.eventsMessage(JSONObject(event.toFormated()))
        }
    }
}
```

Delete `eventQueue`, `addEvent`, `sendTrackEvents`, `generateTrackRequestBody`, `validateEventCall`, and the `eventsList` property. **Do not touch `lastDispatchTime` or `sendConsentEvent`.** Remove the now-unused `import org.json.JSONArray`; keep `import org.json.JSONObject`.

`subscribe`'s signature stays `callback: (value: IntemptEvent) -> Unit` — unchanged, so `SessionTracker.service.kt:80` keeps compiling.

- [ ] **Step 4: Wire Dagger**

In `IntemptCore.module.kt` add providers for `QueueConfig` (endpoint from `ConfigManagerService.eventsUrl`, logging from `isLoggingEnabled`), `EventDbAdapter`, and `DeliveryMessages`, then thread `delivery` into `provideEventPoolManagerService`. All three are `@Singleton`.

- [ ] **Step 5: Update the 5 existing test files**

Each constructs `EventPoolManagerService(...)`. Add a `FakeDelivery` as the 5th argument in: `AutoCaptureUnitTest.kt:188`, `ChangeTrackerUnitTest.kt:220`, `InstallationUnitTest.kt:141`, `CustomCaptureUnitTest.kt:161`, `SessionTrackerUnitTest.kt:73`. In `CustomCaptureUnitTest.kt:351`, replace `eventPoolSrv.eventsList.lastOrNull()` with `fakeDelivery.recorded.lastOrNull()` — `eventsList` no longer exists.

- [ ] **Step 6: Run the full suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL — all pre-existing tests plus the new queue tests pass.

- [ ] **Step 7: Commit**

```bash
git commit -am "refactor: hand events to the vendored delivery queue without suspending the collector"
```

---

## Task 6: minSdk reach — 4 blockers and a build-time gate

**Files:** `ConfigManager.service.kt`, `ChangeTracker.service.kt`, `IntemptEventManager.service.kt`, `FirebaseService.kt`, `AndroidManifest.xml`, `app/build.gradle.kts`, `gradle/libs.versions.toml`

`grep -rn "SDK_INT"` over the SDK currently returns nothing — no API-level compatibility work has ever been done here.

- [ ] **Step 1: Settle the floor**

Run: `./gradlew :app:dependencies --configuration releaseRuntimeClasspath | grep -i firebase`
Then check the resolved `firebase-messaging` AAR's `minSdkVersion`. If it is 23, **minSdk = 23**; if 21, **minSdk = 21**. Record the answer in `docs/FEATURES.md` §9. Do not guess — this decides the number in Step 6.

- [ ] **Step 2: Fix `java.util.Base64` (API 26) — critical, it is in the auth path**

`ConfigManager.service.kt:15,87`. On API 21–25 this throws and **every request fails**.

```kotlin
import android.util.Base64
// …
return Base64.encodeToString("$username:$password".toByteArray(), Base64.NO_WRAP)
```

`NO_WRAP` matters — the platform default inserts newlines, which corrupts an HTTP header.

- [ ] **Step 3: Guard `NotificationChannel` (API 26)**

`FirebaseService.kt:126-128`, currently unconditional:

```kotlin
if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
    val channel = NotificationChannel(channelId, "Default Channel", NotificationManager.IMPORTANCE_HIGH)
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
}
```

- [ ] **Step 4: Guard `TimePicker.hour` / `.minute` (API 23)**

Two sites — `ChangeTracker.service.kt:94` and `IntemptEventManager.service.kt:449`:

```kotlin
is TimePicker -> if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M)
        "${view.hour}:${view.minute}"
    else
        @Suppress("DEPRECATION") "${view.currentHour}:${view.currentMinute}"
```

- [ ] **Step 5: Fix the notification-enabled check**

`FirebaseService.kt:193-197` uses `checkSelfPermission(POST_NOTIFICATIONS)`, which is only a runtime permission on API 33+. Below 33 the correct check is `NotificationManagerCompat.areNotificationsEnabled()`, which also catches users who disabled notifications in system settings on any API level. A wrong answer here fires a spurious `BOUNCED` webhook and **makes journeys branch incorrectly**.

```kotlin
private fun notificationsAllowed(context: Context): Boolean {
    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
    if (android.os.Build.VERSION.SDK_INT < 33) return true
    return ActivityCompat.checkSelfPermission(
        context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
```

- [ ] **Step 6: Manifest, minSdk, and the animalsniffer gate**

Remove `<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />` (`AndroidManifest.xml:4`) — declared with zero usages, and Play-Console-flagged.

Set `minSdk` to Step 1's answer. Add `animalsniffer` with the matching signature, mirroring Mixpanel (`analytics/build.gradle` uses `android-api-level-21`). **This is what actually backs the reach claim** — it fails the build on any call unavailable at the floor, and would have caught all four blockers above.

- [ ] **Step 7: Verify**

Run: `./gradlew :app:animalsnifferRelease :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL. Any animalsniffer violation is a genuine crash-on-old-device — fix it, do not suppress it.

- [ ] **Step 8: Commit**

```bash
git commit -am "fix: API-level compatibility for minSdk reach, add animalsniffer gate"
```

---

## Task 7: Privacy fixes (independent of the queue)

**Files:** `ChangeTracker.service.kt`, `StorageManager.service.kt`

- [ ] **Step 1: Write failing tests**

Two tests: (a) autocapture of an `EditText` whose `inputType` is any password variant must not include the text value; (b) `logOut()` must produce a `profileId` different from the pre-logout one.

- [ ] **Step 2: Mask PII in autocapture**

`ChangeTracker.service.kt:89` currently ships `view.text.toString()` for every `EditText`, password fields included. Mask when `inputType` has `TYPE_TEXT_VARIATION_PASSWORD`, `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD`, `TYPE_TEXT_VARIATION_WEB_PASSWORD`, or `TYPE_NUMBER_VARIATION_PASSWORD` — and honour the existing `intemptDoNotCapture` tag.

- [ ] **Step 3: Rotate `profileId` on logout**

`StorageManager.service.kt:139-169` clears prefs then restores the same `profileId`, so a second user on a shared device inherits the first user's profile. Generate a fresh anonymous id instead.

- [ ] **Step 4: Run, then commit**

```bash
git commit -am "fix: mask password fields in autocapture, rotate profileId on logOut"
```

---

## Task 8: Instrumented tests, and CI as the gate

**Files:** `app/src/androidTest/**` (new — the directory does not exist), `.github/workflows/ci.yml` (new)

Today `.github/workflows/publish.yml` triggers only on tag or manual dispatch, so **nothing runs on a PR**. A broken change can merge and be published.

- [ ] **Step 1: Create `app/src/androidTest/` with a test Activity**

A debug-only Activity hosting the widget types autocapture traverses, so instrumented tests can exercise real lifecycle, real view-tree traversal, and real touch dispatch — none of which Robolectric proves.

- [ ] **Step 2: Write instrumented tests named for the failure mode they guard**

`QueueSurvivesProcessDeathTest` — enqueue, kill the process, reopen, assert the batch is still delivered. This is the P0 this whole plan exists for and **cannot** be tested on the JVM.
`RetryBackoffTest` — fail the first POST, assert the retry fires at the backoff interval, then succeeds.
`RateLimitRetryTest` — 429 must retry, not drop (fidelity F12).
`PoisonPillTest` — one malformed row must not wedge the queue (F5).
`AutocaptureEndToEndTest` — a real tap on the test Activity produces a queued event.
`Api21CompatTest` — the four Task 6 fixes, exercised on an API 21 emulator.

- [ ] **Step 3: Write `.github/workflows/ci.yml`**

On every `push` and `pull_request`: `testDebugUnitTest` → `animalsnifferRelease` → `ktlintCheck` → `lint` → `jacocoTestReport` → instrumented tests on an emulator matrix of **API 21 and 34** (`reactivecircus/android-emulator-runner`, the action Mixpanel uses). Upload reports as artifacts.

Note: Mixpanel emulator-tests its `analytics` module on **API 34 only** — the 21/30/34 matrix is `session-replay`'s. Including 21 here **exceeds** their bar and is the honest way to back a minSdk-21 claim on the emulator as well as statically.

- [ ] **Step 4: Verify locally, then commit**

Run: `./gradlew :app:testDebugUnitTest :app:animalsnifferRelease :app:lint`

```bash
git add app/src/androidTest .github/workflows/ci.yml
git commit -m "test: instrumented suite for process-death durability; CI gate on every PR"
```

---

## Task 9: Packaging — Apache 2.0, NOTICE in the AAR, consumer rules

- [ ] **Step 1: `LICENSE`** — replace MIT with the full Apache 2.0 text.
- [ ] **Step 2: `gradle.properties`** — `POM_LICENCE_NAME=The Apache License, Version 2.0`, `POM_LICENCE_URL=https://www.apache.org/licenses/LICENSE-2.0.txt`.
- [ ] **Step 3: `NOTICE`** — attribute the vendored substrate to Mixpanel (Apache 2.0) and list the modifications, matching the per-file headers from Task 1 Step 4.
- [ ] **Step 4: Ship `LICENSE` + `NOTICE` inside the AAR.** Repo-root presence is not distribution — Apache 2.0 §4(d) requires them to travel with the artifact.
- [ ] **Step 5: `app/consumer-rules.pro`** — keep rules propagated to host apps' R8. Must include `-keepattributes *Annotation*` and keep `kotlin.Metadata`; Kotlin reflection is what `EventPoolManagerService.kt:171`'s `declaredFunctions.find{}` dispatch depends on. Wire via `consumerProguardFiles` in `defaultConfig` (not inside `buildTypes.release`).
- [ ] **Step 6:** `./gradlew :app:assembleRelease`, confirm `LICENSE` and `NOTICE` are present in the AAR, commit.

---

## 9. Fidelity checklist — merge gate

Each item is a behavior Mixpanel has that the previous hand-translation lost. **No item ships without a named test.**

| # | Behavior | Source | Test |
|---|---|---|---|
F1 | Backoff `2^n × 60s`, clamped to 10 min, monotonic via `Math.max` | `AnalyticsMessages.java:715-718` | `RetryBackoffTest` |
F2 | Bulk flush suppressed while a retry is pending (`mFailedRetries <= 0`) | `:589` | `NoFlushStormDuringOutageTest` |
F3 | `deleteEvents = false` on failure — rows survive | `:707, :713` | `RetryBackoffTest` |
F4 | Backpressure `max(usableSpace, 20MB)` — a **floor**, ceiling is `Integer.MAX_VALUE` | `MPDbAdapter.java:189-190` | `QueueConfigTest` |
F5 | Per-row `JSONException` skip — poison-pill immunity | `:645-647` | `PoisonPillTest` |
F6 | `LIMIT flushBatchSize` (50) per POST, loop until drained | `:627`, `AnalyticsMessages.java:729-732` | `BatchSizeCapTest` |
F7 | `SQLiteException` on **write** → delete and rebuild the DB | `:358-369` | `CorruptionRecoveryTest` |
F7b | `SQLiteException` on **read** → do *not* destroy; let it ride | `:653-661` | `CorruptionRecoveryTest` |
F8 | `time_idx` index on `created_at` | `:98-99, :146` | `SchemaTest` |
F9 | Expiry sweep (5 days) on first `handleMessage` | `AnalyticsMessages.java:477-483` | `DataExpirationTest` |
F10 | Single `HandlerThread` — all queue mutation serialized | `:459-464` | `ConcurrentTrackTest` |
F11 | `isOnline()` gate before POST | `:635-639` | `OfflineNoPostTest` |
F12 | **Intempt deviation:** 429 retryable, 401/403/400/422 dropped | `http-error-codes.mdx` | `HttpStatusPolicyTest`, `RateLimitRetryTest` |

---

## 10. Code ledger — inherit vs write

LOC measured with `wc -l` against `mixpanel-android@master` and `android-sdk@main`. Strip amounts are computed from measured method extents; the resulting "out" figures are therefore **estimates** (±30 LOC) until Task 2 actually runs.

### Inherited verbatim — 241 LOC (rename only, zero logic change)

Revised down from 415 by the dependency trace: `JsonUtils` (206) cut as a flag parser; `NetworkErrorListener` (23) and `ProxyServerInteractor` (9) added because the vendored transport genuinely needs them.

| File | LOC |
|---|---|
`RemoteService.java` (interface) | 166 |
`SynchronizedReference.java` | 27 |
`NetworkErrorListener.java` | 23 |
`OfflineMode.java` | 16 |
`ProxyServerInteractor.java` | 9 |

### Inherited then edited — 2,319 in → ~1,749 out

| File | In | Stripped | Out |
|---|---|---|---|
`DeliveryMessages.java` ← `AnalyticsMessages.java` | 891 | ~313 — 5 Description classes, 7 message types + handlers (~198), **`getDefaultEventProperties` (~115)** | ~578 |
`HttpService.java` | 737 | ~35 form-encoding; **+40** taxonomy → net grows | ~742 |
`EventDbAdapter.java` ← `MPDbAdapter.java` | 691 | 260 — migrations 95, `pushAnonymousUpdatesToPeopleDb` 63, `rewriteEventDataWithProperties` 62, 3 surplus tables ~40 | ~431 |

### Written from scratch — ~130 LOC

| File | ~LOC | Why it can't be inherited |
|---|---|---|
`TrackPayloadBuilder.java` | 45 | Intempt's `{track:[{name,payload:[]}]}` grouping; Mixpanel's wire format differs |
`QueueConfig.java` | 50 | Mixpanel's constants, but sourced from `intempt-config.json` not manifest metadata |
`HttpStatusPolicy.java` | 20 | 429 must retry — Mixpanel discards all 4xx |
`QueueLog.java` | 15 | shim so vendored files compile without `MPLog` |

### Modified — our existing Kotlin, ~110 LOC touched

`EventPoolManagerService.kt` (−50/+10) · `IntemptCore.module.kt` (+30) · `FirebaseService.kt` (+15) · `ChangeTracker.service.kt` (+10) · `StorageManager.service.kt` (+10) · `WebhookService.kt` (+10, if D3) · `IntemptEventManager.service.kt` (+3) · `ConfigManager.service.kt` (+2) · `AndroidManifest.xml` (−1) · build files (+20)

### Untouched — 4,424 LOC of ours

Public API, autocapture, push/journeys, `choose-api`, consent, event models, types. Blast radius of this whole plan on existing code is **~110 lines**.

### Cut — 13,537 LOC, 79% of what Mixpanel ships

`MixpanelAPI` 3,159 · `FeatureFlagManager` 1,560 · `PersistentIdentity` 729 · `session-replay` 4,836 · `openfeature-provider` 323 · `common` 562 · 22 smaller files.

**Totals: inherit 2,734 · write 130 · modify 110 · cut 13,537.**

---

## 11. Test inventory — 19 new files, every §9 item owned

The §9 gate previously named tests that no task created. Each is now assigned.

### Unit tests — JVM/Robolectric, `app/src/test/java/com/intempt/core/`

| # | File | Guards | Task |
|---|---|---|---|
1 | `queue/QueueConfigTest.java` | F4 — 20MB is a floor, ceiling is `Integer.MAX_VALUE` | 3 |
2 | `queue/HttpStatusPolicyTest.java` | F12 — 429 retries, 401/403/400/422 drop | 4 |
3 | `queue/TrackPayloadBuilderTest.java` | R2 — name-grouped wire shape | 4 |
4 | `queue/SchemaTest.java` | F8 — `time_idx` on `created_at` exists | 2 |
5 | `queue/BatchSizeCapTest.java` | F6 — `LIMIT 50`, loops until drained | 2 |
6 | `queue/PoisonPillTest.java` | F5 — one malformed row is skipped, not fatal | 2 |
7 | `queue/CorruptionRecoveryTest.java` | F7 + F7b — write destroys and rebuilds; read does **not** | 2 |
8 | `queue/DataExpirationTest.java` | F9 — 5-day sweep on first message | 2 |
9 | `queue/NoFlushStormDuringOutageTest.java` | F2 — no bulk flush while a retry is pending | 2 |
10 | `queue/OfflineNoPostTest.java` | F11 — `isOnline()` gate | 2 |
11 | `services/eventPool/EventPoolHandoffTest.kt` | 100 emits under a slow consumer lose 0 | 5 |
12 | `autocapture/PiiMaskingTest.kt` | password `EditText` never ships its value | 7 |
13 | `services/ProfileIdRotationTest.kt` | `logOut()` yields a different `profileId` | 7 |

### Instrumented tests — `app/src/androidTest/java/com/intempt/core/` (directory does not exist today)

| # | File | Guards | Task |
|---|---|---|---|
14 | `queue/QueueSurvivesProcessDeathTest.kt` | **the P0** — enqueue, kill process, reopen, still delivered. Impossible on the JVM | 8 |
15 | `queue/RetryBackoffTest.kt` | F1 + F3 — backoff timing, rows survive failure | 8 |
16 | `queue/RateLimitRetryTest.kt` | F12 on a real 429 | 8 |
17 | `queue/ConcurrentTrackTest.kt` | F10 — single `HandlerThread` serializes all mutation | 8 |
18 | `AutocaptureEndToEndTest.kt` | real tap on a real Activity → queued event | 8 |
19 | `Api21CompatTest.kt` | the 4 Task 6 fixes on an API 21 emulator | 8 |

Plus `TestHostActivity` (debug-only, hosts the widget types autocapture traverses).

### Modified existing tests — 5 files

`AutoCaptureUnitTest.kt:188` · `ChangeTrackerUnitTest.kt:220` · `InstallationUnitTest.kt:141` · `CustomCaptureUnitTest.kt:161` · `SessionTrackerUnitTest.kt:73` — each gains `FakeDelivery` as the 5th constructor arg. `CustomCaptureUnitTest.kt:351` swaps `eventPoolSrv.eventsList` for `fakeDelivery.recorded`.

**Coverage moves from 48 live JVM tests / 0 instrumented → ~48 + 30 JVM / ~15 instrumented.**

### `FakeDelivery` — the shared test double (Task 5, referenced by 6 files)

```kotlin
package com.intempt.core.testutils

import com.intempt.core.queue.DeliveryMessages
import org.json.JSONObject

/** Records instead of enqueueing. `delayMs` simulates a slow consumer so the
 *  handoff test can prove the collector never blocks and tryEmit never drops. */
internal class FakeDelivery(
    private val recordedOut: MutableList<JSONObject> = mutableListOf(),
    private val delayMs: Long = 0
) : DeliveryMessages() {
    val recorded: List<JSONObject> get() = recordedOut
    private val lock = Object()

    override fun eventsMessage(event: JSONObject) {
        if (delayMs > 0) Thread.sleep(delayMs)
        synchronized(lock) { recordedOut.add(event) }
    }

    fun awaitQuiescence(timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = -1
        while (System.currentTimeMillis() < deadline) {
            val now = synchronized(lock) { recordedOut.size }
            if (now == last && now > 0) return
            last = now
            Thread.sleep(20)
        }
    }
}
```

**Prerequisite:** `DeliveryMessages` must be `open` with an `open fun eventsMessage` for this to subclass. If Task 2 leaves it final, `FakeDelivery` instead wraps an interface extracted in Task 5 — decide during Task 5 Step 3 based on what the vendored class actually looks like after stripping.

---

## 12. Open decisions blocking full execution

| # | Decision | Blocks | Default if unanswered |
|---|---|---|---|
D1 | minSdk 21 or 23 (Firebase floor) | Task 6 Step 6 | resolved empirically in Task 6 Step 1 |
D2 | Sample app module (`:sample`)? | nothing — additive | skip for now |
D3 | Route push lifecycle webhooks through the queue? They currently silent-drop on any network blip and journeys branch on them | Task 5 scope | **recommend yes** — same infrastructure, arguably higher customer impact than track-event loss |
D4 | `Session start` vs intemptjs's `Session Start` — unify casing? | nothing | leave, flagged in `FEATURES.md` §11.3 |
D5 | `group()` defaulting to the event name `"Identify"` (both SDKs do this) | nothing | leave, flagged §11.1 |

---

## Self-Review

**Coverage:** every §9 fidelity item maps to a task that preserves it (Task 2 Steps 1–2 enumerate them as "preserve verbatim") and to a named test. Every requirement in `FEATURES.md` §1–§8 is either untouched (public API, autocapture, push, personalization) or explicitly addressed (reliability → Tasks 1–5; privacy → Task 7; packaging → Task 9).

**The three v1 failure modes are explicitly guarded:** `lastDispatchTime` retained (Task 5 Step 3, called out); collector stays non-suspend so `tryEmit` cannot drop (Task 5 Step 1's test asserts 100/100); and the fidelity misses are structurally prevented because the guards are *inherited rather than retyped* — Task 2 preserves them by not deleting them.

**Known limitation, stated rather than hidden:** this plan has not been compiled or run. Its risk is concentrated in Task 1's mechanical `sed` (caught immediately by Step 5's compile) and Task 4's transport rewrite (the only place new logic is written, and the most-tested). That is a deliberately different risk profile from v1, which wrote ~600 lines of new concurrency logic.

**Not yet verified:** the `firebase-messaging` minSdk floor (D1, resolved in-task), and the pre-33 `POST_NOTIFICATIONS` return value (Task 6 Step 5 fixes the logic regardless of what it returns).
