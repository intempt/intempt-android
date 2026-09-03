# Intempt Android SDK — Requirements & Mixpanel Separability Analysis

**Status:** analysis, no code. This document exists to make "inherit from Mixpanel vs build ourselves" a decision backed by evidence instead of preference.

**Why it exists:** an earlier pass in this workstream produced a 1,880-line implementation plan to hand-port Mixpanel's reliability mechanism into Kotlin. An independent adversarial audit scored that plan **41/100** — it did not compile, its retry mechanism could not fire, and its wiring reintroduced silent event loss one layer upstream of the queue it was making durable. The root cause was not craft. It was that **no one had written down what this SDK is required to do**, so the inherit-vs-build call was made on instinct, and the "faithful port" framing was never tested against our own API contract. That test is below, and it fails in three places.

**Sources of truth used.** Every claim in this document is cited to one of:
- **REST contract**: `website-docs/content/openapi/api-reference.yaml`, `website-docs/content/docs/api/reference/http-error-codes.mdx`
- **Our SDK**: `android-sdk/app/src/main/java/com/intempt/core/**` at `main@a36a754`
- **Reference client**: `intemptjs` (the canonical implementation of Intempt's client model), `nodejs-sdk`
- **Mixpanel**: `mixpanel-android@master`, Apache-2.0

---

## 1. Three-way divergences found (new findings)

These are contradictions between the documented REST contract, our shipped Android SDK, and Mixpanel's design. Each one changes an inherit-vs-build answer. **None of these were known when the port plan was written.**

### 1.1 Authentication — RESOLVED. The SDK is right; the public docs are wrong.

Traced end-to-end through the real request path: `gateway` (`PushSourceAdapterRoutes` → `lb://push-source-intempt-com`) → `push-source-service` (`WebFluxServer.java:86` routes `POST /sources/{sourceId}/track` → `HttpDataHandler::trackData`) → auth enforced by `common-library`'s shared filter, **not** by the handler (`HttpDataHandler` extracts no apiKey; `ApiKeyAccessDenied` exists but is never thrown — dead code).

`common-library/intempt-common-spring/.../LegacyApiKeyTranslatorFilter.java` is authoritative. **Three credential forms are accepted, all normalized to HTTP Basic:**

| Form | Handling | Evidence |
|---|---|---|
| `Authorization: Basic base64(identifier:secret)` | **passes through untouched — the canonical form** | `:30-32, 47-50` |
| `Authorization: ApiKey <identifier>.<secret>` | translated to Basic | `:56-58` |
| `?apiKey=` / `?api_key=<identifier>.<secret>` | translated to Basic, **and logs a deprecation warning** | `:60-68` |

Then `OrgProjectApiKeyAuthenticationWebFilter` reads the Basic credential and validates via `getApiKeyPermission(identifier, secret, orgName, projectName)` (`:55`).

**Conclusions:**
- **The Android SDK needs no change.** `HttpManager.service.kt:43,57` + `ConfigManager.service.kt:84-88` send the native Basic form, which is the preferred path and skips translation entirely.
- **The public OpenAPI spec has a real defect.** `api-reference.yaml:43-51` documents the query-param form as `required: true` — the one our own code warns about verbatim: *"API key received via query parameter — this is deprecated and insecure. Use Authorization header instead."* We are publicly steering customers to a deprecated insecure pattern. **Fix the spec to document the `Authorization` header.**
- `API_CONTRACT.md:250`'s "all routes under `/v1/{org}/projects/{project}/` require `Authorization: Bearer <jwt>`" describes the *user/console* auth path; API-key auth is a separate, parallel mechanism. Both work; the doc is incomplete rather than wrong.

**This was previously flagged as a blocker. It is not one.** No Android work is gated on it. The only action is a docs fix, owned by whoever maintains `website-docs`.

### 1.2 `profileId` is absent from the declared schema but present everywhere else

- Not in the payload schema's declared properties — `x-apidog-orders` lists exactly 11 fields (`eventId`, `userId`, `timestamp`, `accountId`, `sessionId`, `pageId`, `anotherUserId`, `anotherAccountId`, `data`, `userAttributes`, `accountAttributes`) with **no `profileId`** (`api-reference.yaml:170-182`).
- But it appears in **every** request example (`api-reference.yaml:234, 244, 261, 272, 509`) and is referenced in a field description: *"Required when the profileId is null"* (`:89`).
- And our Android SDK puts it on **every** event — `BaseIntemptEvent` declares `profileId` as a non-null field (`BaseIntemptEvent.kt`), and `IntemptEventProvider` requires it (`types/interfaces.kt:31`).

**The schema is incomplete relative to both its own examples and its real clients.** Low severity for SDK work (the field is clearly accepted), but the spec should be corrected.

### 1.3 `data` / `userAttributes` / `accountAttributes` — declared type contradicts every example

- Declared as `type: string, format: json` (`api-reference.yaml:143-144, 155-156, 162-163`).
- Every example passes a **nested JSON object**, not a string (`:217-225`).

Spec-internal contradiction. Our SDK passes objects (matching the examples). Worth fixing in the spec; no SDK change implied.

### 1.4 429 backpressure — and why Mixpanel's retry policy is *wrong* for us

This is the finding that most damages the "faithful port" strategy.

| | Behavior | Evidence |
|---|---|---|
| Our gateway | `429` with **empty body** and `X-RateLimit-Replenish-Rate` / `X-RateLimit-Burst-Capacity` / `X-RateLimit-Requested-Tokens` headers. **No `Retry-After`.** Cap is set by org plan tier | `http-error-codes.mdx`, "Rate limiting (429)" |
| Mixpanel | Treats **any 4xx as a client error and throws immediately without retrying**; handles backpressure only via `503` + `Retry-After` | `HttpService.java:220-259`; `AnalyticsMessages.java:701-704` |
| Our SDK today | `post()` throws on any non-2xx, catches, returns `null` — **no status differentiation at all** | `HttpManager.service.kt:59-70` |

**Consequence: a faithful port of Mixpanel's error taxonomy would permanently drop every rate-limited batch**, because `429` is a 4xx and Mixpanel's rule is "4xx = don't retry, discard." Our gateway uses 429 as its *primary* backpressure signal. Mixpanel never had to handle that because their ingestion doesn't rate-limit this way.

**Required behavior for Intempt (neither current SDK nor a Mixpanel port provides it):**

| Status | Action |
|---|---|
| `2xx` | delete rows, reset backoff |
| `429` | **retry with backoff**, ideally derived from `X-RateLimit-Replenish-Rate`; never discard |
| `401` / `403` | do **not** retry — bad credentials never fix themselves. Log loudly, drop or park |
| `400` / `422` | do **not** retry the same payload — malformed body. Drop the offending batch, don't wedge the queue |
| `5xx`, timeouts, network | retry with backoff |

---

## 2. Requirements — what this SDK must do

Consolidated from the three sources. This is the yardstick every inherit-vs-build decision is measured against.

### R1. Public API surface must not change

13 top-level calls, verified 1:1 against `intemptjs`: `initialize`, `identify`, `group`, `track`, `record`, `consent`, `productAdd`, `productOrdered`, `productView`, `recommendation`, `logOut`, `doNotCaptureText`, plus nested `Logging.start/stop` and `Tracking.start/stop` (`Intempt.kt`). Same `eventTitle`-required-when-attributes-present validation rule as intemptjs (`guards/intemptJs.guard.ts:70-98`).

### R2. One ingestion endpoint, one payload shape

- `POST /v1/{org}/projects/{project}/sources/{sourceId}/track` (`ConfigManager.service.kt:50-51`)
- Body: `{"track": [{"name": ..., "payload": [{...}]}]}` (`api-reference.yaml:52-202`)
- **`track[]` groups by event name; `payload[]` holds N occurrences of that name.** Our current `generateTrackRequestBody` emits one group per event rather than grouping by name — functionally accepted, but it forgoes the batching the contract is shaped for.
- Auth: `Authorization: Basic base64(identifier:secret)` — already correct in the SDK, see §1.1.
- Served by `push-source-service` (`WebFluxServer.java:86`), routed via `gateway`'s `PushSourceAdapterRoutes`, published to Kafka as an Avro-serialised track record.

### R3. Four side endpoints, all non-batched, all fire-immediately

| Endpoint | Purpose | Source |
|---|---|---|
| `consents/data` | consent events | `ConfigManager.service.kt:47-48` |
| `optimization/choose-api` | experiments/personalization → returns "choices" | `:53-54` |
| `feeds/{id}/data` | recommendations | `:59-61` |
| `webhooks/events/push-notification` | push lifecycle → journeys | `:56-57` |

None of these are queued today and none should be. Only the `track` path needs durability.

### R4. Attributes are event-scoped; the backend owns persistence

No super properties. No client-side attribute cache. `userAttributes`/`accountAttributes` ride on individual events and the ingestion pipeline resolves identity and persists profile state. Verified as intentional: `intemptjs` has no `register()`-equivalent either — `profileTracker.module.ts` persists only a `profileId` cookie. **Do not port Mixpanel's `PersistentIdentity`/super-properties model.**

### R5. `group()` is an event, not a mutable profile

One-off event carrying `accountId` + `accountAttributes` (`CustomCapture.component.kt:133-155`), matching `intemptjs`'s `group.model.ts`. No client-side group profile object.

### R6. Experiments = `optimization/choose-api`, not typed feature flags

Request shape `{identification:{profileId,sourceId}, groups|names, optimizationType, device}`, response is a `choices` array (`Modifications.service.kt:56-98`, matching `nodejs-sdk/OptimizationClient.ts`). **Not** an OpenFeature-style boolean/JSON flag evaluator. Do not port Mixpanel's `FeatureFlagManager` (2,202-line test file alone signals how much irrelevant surface that drags in).

### R7. No session replay

Confirmed absent from `intemptjs` with no planning doc proposing it. Do not port Mixpanel's `session-replay` module.

### R8. Push + journeys is a differentiator — protect it

- FCM token capture, rich push (title/body/image/webUrl deep link), and lifecycle webhooks (`DELIVERED`/`BOUNCED`/`OPENED`) carrying 8 journey-execution IDs: `orgId`, `projectId`, `pipelineId`, `transformerId`, `destinationId`, `masterId`, `accountId`, `templateId` (`PushNotificationMetadata.kt`, `FirebaseService.kt:78-91`, `WebhookService.kt`).
- Those field names are `journeys-pipeline`'s own vocabulary (`PipelineOutboxService.java`, `Transformer.java`), so this is a real journey-engine integration, not generic telemetry.
- **Mixpanel dropped push from master entirely** — no `MixpanelFCMMessagingService` exists anymore, only a dangling comment at `MixpanelAPI.java:2371`. Nothing to inherit here; we are ahead.

### R9. Autocapture is broader than Mixpanel's — preserve it

Ours: session start/length, screen view/leave, fragment transitions, UI touch/click/change, install/upgrade (`autocapture/**`). Mixpanel's automatic events are session/first-open/app-updated/crash only (`AutomaticEvents.java`) — **no UI-element autocapture**. Nothing to inherit; do not regress ours.

### R10. Config from `assets/intempt-config.json`

Not manifest metadata (which is how Mixpanel's `MPConfig` reads everything). Keys under `auth` + `options` (`ConfigManager.service.kt:90-127`). Mixpanel's tuning *constants* can transfer; its config *source* cannot.

### R11. Stack and packaging constraints

- Kotlin 1.9.22, Dagger 2.52 (kapt), Ktor 2.3.11, coroutines 1.9.0, AGP 8.6.1, compileSdk 35, **minSdk 31**.
- Published as `com.intempt.sdk:intempt-android` to Maven Central, **MIT license** (`gradle.properties:38-45`), repo is public.
- Note: Mixpanel's minSdk is **21** — inheriting their code would *widen* our device reach, not constrain it.

### R12. Consent API — already ahead, keep it

`optIn()`/`optOut()`/`isTrackingEnabled()` plus a dedicated `consent()` event to `consents/data`. Mixpanel has **no** consent concept at all (`grep -rl consent` across all 4 modules returns nothing) — only a blanket opt-out boolean. One genuine gap on our side: Mixpanel supports `optOutTrackingDefault` at init (`MixpanelOptions.java:129,159`), letting an app start opted-out before first event; ours hardcodes `IsUserOptIn = true` (`SealedTypes.kt:72`). Cheap to add, matters for GDPR-by-default.

### R13. Known defects this SDK must fix (independent of strategy)

| Defect | Location | Severity |
|---|---|---|
| Queue cleared before POST is attempted; in-memory only; no retry | `EventPoolManagerService.kt:210-227` | P0 — permanent silent event loss |
| Raw `EditText` text captured, no password/InputType check | `ChangeTracker.service.kt:89` | P0 — PII exfiltration |
| `logOut()` restores the same `profileId` instead of rotating | `StorageManager.service.kt:139-169` | P0 — cross-user leak on shared devices |
| `onNewToken` writes to a dead field; token never re-sent on rotation, and the manifest-bound service instance differs from the one that fetched the token | `FirebaseService.kt:96-100`, `EventHandlers.kt:22,67` | P1 — journeys push to dead tokens silently |
| `getStorageItem` never reads back from SharedPreferences (dead persistence) | `StorageManager.service.kt:46-54` | P1 — state resets after process restart |
| No status differentiation on HTTP responses (see §1.4) | `HttpManager.service.kt:59-70` | P1 |
| No CI gate on PR/push; only runs on git tag | `.github/workflows/publish.yml` | P1 — process |

---

## 3. The INHERIT / ADAPT / CUT / KEEP / BUILD model

Every number below is measured with `wc -l` against `mixpanel-android@master` and `android-sdk@main`, not estimated. Mixpanel's `analytics` module is **11,384 LOC** (9,869 `mpmetrics/` + 1,515 `util/`) and the three buckets below sum to exactly that.

**Before** = the killed hand-port plan's implicit bucketing. **After** = evidence-based bucketing from §1–2.

| | Before (hand-port plan) | After (this analysis) |
|---|---|---|
| **INHERIT** — mechanical de-brand only, zero logic change | **0** | **598** |
| **ADAPT** — real surgery, Mixpanel-specific surface stripped | **0** | **2,970 in → ~1,926 out** |
| **CUT** — deliberately not taken | **11,384** (all of it) | **7,816** (+5,721 whole modules = 13,537, 79%) |
| **KEEP** — our existing code, untouched | 4,424 | 4,424 |
| **REPLACED** — our code the queue work displaces | 338 | 338 |
| **BUILD** — genuinely new code | **~600 LOC new Kotlin** | **8 items**, mostly wiring |
| **Tests inherited** | **0** | **176** |

The Before row is the whole indictment: the plan **cut 100% of Mixpanel** and rebuilt the mechanism by reading it. That's why it lost the 8 hardening details in §4 and scored 41/100.

### INHERIT — 598 LOC, de-brand only

| File | LOC |
|---|---|
| `util/JsonUtils.java` | 206 |
| `util/RemoteService.java` (interface `HttpService` implements) | 166 |
| `util/W3CTraceContext.java` | 61 |
| `mpmetrics/SharedPreferencesLoader.java` | 51 |
| `util/MPConstants.java` | 39 |
| `mpmetrics/SynchronizedReference.java` | 27 |
| `util/MixpanelNetworkErrorListener.java` | 23 |
| `util/OfflineMode.java` | 16 |
| `util/ProxyServerInteractor.java` | 9 |

### ADAPT — 2,970 in, ~1,926 out

| File | In | Strip | Out |
|---|---|---|---|
| `AnalyticsMessages.java` | 891 | `PeopleDescription` (15), `GroupDescription` (11), `PushAnonymousPeopleDescription` (49), `UpdateEventsPropertiesDescription` (13), `FirstLaunchDescription` (~20), their 6 message-type handlers (~80), people/groups `sendData` calls (~10) | ~693 |
| `HttpService.java` | 737 | form-urlencoded + Base64 encoding path (~35); **add** 429-aware error taxonomy (~40) | ~742 |
| `MPDbAdapter.java` | 691 | v4→v7 migrations (95), `pushAnonymousUpdatesToPeopleDb` (63), `rewriteEventDataWithProperties` (62), 3 surplus tables' DDL + indexes + enum entries (~40) | ~431 |
| `MPConfig.java` | 651 | all manifest-metadata reading, flag/replay/proxy config — keep the tuning constants only | ~60 |

**`HttpService` grows rather than shrinks** — the encoding swap is small, but the 429/401/400 taxonomy from §1.4 is net-new logic Mixpanel never needed.

### CUT — 7,816 LOC in `analytics`, plus 5,721 in whole modules

Largest items: `MixpanelAPI.java` 3,159 (we have `Intempt.kt`) · `FeatureFlagManager.java` 1,560 (R6) · `PersistentIdentity.java` 729 (R4) · `MixpanelOptions.java` 366 · `MixpanelFlagVariant.java` 321 · `FeatureFlagOptions.java` 203 · `SystemInformation.java` 188 (we have `IntemptEventManager.service.kt:375-455`) · `VariantLookupPolicy.java` 168 · `ResourceReader.java` 154 · `FirstTimeEventChecker.java` 145 · `Base64Coder.java` 137 (only needed for the encoding we're not using) · `MPLog.java` 87 (we have `LoggerManagerService`) · 16 smaller files.

Cut entirely as modules: `session-replay` **4,836** (R7) · `common` **562** · `openfeature-provider` **323** (R6).

**Cut total: 13,537 of the 17,105 LOC Mixpanel ships — 79%.** Only about a fifth of Mixpanel is the part worth having. That is the real answer to "should we build on Mixpanel": yes, but on a narrow, identifiable slice of it.

### KEEP — 4,424 LOC of ours stays; 338 of ours is replaced

Our SDK is **4,762 LOC** total. Bucketed against this work:

| Area | LOC | Fate |
|---|---|---|
| `autocapture/` (session, screen, fragment, touch/change, install) | 1,131 | KEEP |
| `eventModels/` (12 event types) | 647 | KEEP |
| `customCapture/` | 525 | KEEP |
| `IntemptEventManager.service.kt` | 475 | KEEP |
| `services/firebase/` (push + journeys) | 397 | KEEP (+1 BUILD fix) |
| `types/` | 311 | KEEP |
| `StorageManager` / `ConfigManager` / `Utils` / `Logger` | 392 | KEEP (+1 BUILD fix) |
| `Intempt.kt` (public API) | 161 | KEEP — R1, must not change |
| `intemptCore/` (Dagger) | 153 | KEEP (+ rewiring) |
| `modifications/` (optimization/choose-api) | 149 | KEEP |
| `HttpManager.service.kt` | 83 | ADAPT — absorbs taxonomy + timeouts |
| `services/eventPool/` (`EventPoolManagerService` 264 + `EventHandlers` 74) | 338 | **REPLACED** by the adapted queue |

This bucket is why Android is not a greenfield — R1, R5–R10 are already built and working, and Mixpanel has no equivalent for most of it (push/journeys, UI autocapture, `choose-api`, consent events). **The blast radius of the queue work is 338 lines of ours**, not a rewrite.

### BUILD — 8 items

| # | Item | Why it can't be inherited or kept |
|---|---|---|
| 1 | 429/401/400-aware error taxonomy | §1.4 — Mixpanel's 4xx rule would discard rate-limited batches |
| 2 | `{track:[...]}` payload grouping by event name | R2 — their wire format differs |
| 3 | PII masking in autocapture (InputType/password check) | P0, no Mixpanel equivalent (they don't autocapture UI text) |
| 4 | `logOut()` profileId rotation | P0 bug fix |
| 5 | FCM `onNewToken` → pipeline wiring | P1 bug fix, Mixpanel deleted push |
| 6 | Dagger wiring for the adapted queue | our DI, their code has none |
| 7 | Apache-2.0 relicense + NOTICE shipped in the AAR | §5 |
| 8 | CI gate (test + lint on PR) | process, currently tag-only |

Items 3–5 and 8 are **independent of the queue work** and shouldn't wait on it.

### Tests — 176 inheritable

On the INHERIT+ADAPT surface only: `MixpanelBasicTest` 25+25, `MPConfigTest` 28+17, `HttpServiceBackupTest` 15+15, `HttpTest` 8+8, `BackupHostTest` 7+9, `SynchronizedReferenceTest` 8, `OptOutTest` 5+5, `HttpServiceTest` 1 — **96 JVM + 80 instrumented = 176**, both real suites (Robolectric and on-device).

Ours today: **56 `@Test` annotations, 48 live** (8 commented out), and **zero** instrumented tests.

**CORRECTION — this bucket does not hold up, and it was the load-bearing argument for inheriting code.** Verified after the fact: there is **no `MPDbAdapterTest` and no `AnalyticsMessagesTest`**. The queue is tested only *indirectly*. `MixpanelBasicTest` does exercise it hard (109 `AnalyticsMessages` refs, 58 `MPDbAdapter` refs) — but by driving `MixpanelAPI.track()` / `People.set()` and injecting instrumented subclasses. `MixpanelAPI` is the 3,159-line class we CUT; `MPConfigTest`'s 45 tests cover manifest-metadata config we also CUT.

So the transferable asset is the **test scenarios and the fake-DbAdapter instrumentation pattern**, not runnable test code. Reusing them means rewriting every driving call against our API and deleting the people/groups/super-props assertions. That is "write our own tests with a good reference," which is available under *any* strategy — it is not an argument for importing Java.

---

## 4. Per-component separability matrix

The earlier error was treating this as **one** global inherit-vs-build choice. It is a per-component decision, and the requirements above resolve each differently.

| Mixpanel component | LOC | Verdict | Reasoning |
|---|---|---|---|
| `MPDbAdapter` — SQLite queue storage | 691 | **ADAPT** | Core value: schema, `aboveMemThreshold` backpressure, per-row `JSONException` skip (`:645-647`), corruption delete-and-rebuild (`:358-369`), `time_idx` index (`:98-99`), `LIMIT flushBatchSize` batching (`:627`). Must drop: 3 of 4 tables (`people`/`groups`/`anonymous_people` — R4/R5), the `token` column and multi-instance `sInstances` map (single SDK instance per app), `pushAnonymousUpdatesToPeopleDb`, `rewriteEventDataWithProperties` |
| `AnalyticsMessages` — worker thread, flush loop, retry | 891 | **ADAPT** | Core value: the `HandlerThread` single-writer discipline (`:459-464`), backoff formula (`:715-718`), delete-only-after-success (`:710-712`), the `&& mFailedRetries <= 0` guard that prevents flush-storms during an outage (`:589`), lazy expiry cleanup (`:477-483`), `isOnline()` gate (`:635-639`). Must drop: `ENQUEUE_PEOPLE`, `ENQUEUE_GROUP`, `PUSH_ANONYMOUS_PEOPLE_RECORDS`, `REWRITE_EVENT_PROPERTIES`, `CHECK_FIRST_LAUNCH` message types |
| `HttpService` — transport | 737 | **SPLIT** | **Inherit the logic**: 3-attempt retry loop, primary/backup host failover, tuned timeouts (`:413-414`), gzip. **Rebuild the encoding**: Mixpanel sends `data=base64(json)` as `application/x-www-form-urlencoded` (`:370-371, 423-424`; `AnalyticsMessages.java:659-661`); we send raw JSON with an auth header (R2). **Rebuild the error taxonomy**: their 4xx-never-retry rule breaks our 429 backpressure (§1.4) |
| `MPConfig` | 651 | **CONSTANTS ONLY** | Tuning values transfer (`FlushInterval` 60s, `BulkUploadLimit` 40, `FlushBatchSize` 50, `DataExpiration` 5 days, `MinimumDatabaseLimit` 20MB). Config *source* is manifest metadata; ours is `intempt-config.json` (R10) |
| `PersistentIdentity` | 729 | **REJECT** | distinct_id/alias/super-properties model is not ours (R4) |
| `FeatureFlagManager` + `FlagsConfig` + `openfeature-provider` | ~1000+ | **REJECT** | Wrong model (R6) |
| `session-replay` module | — | **REJECT** | Not a product direction (R7) |
| Push / FCM | — | **N/A** | Doesn't exist in Mixpanel master anymore; we're ahead (R8) |
| `SynchronizedReference` | 113 | **INHERIT** | Generic thread-safe holder, has its own 8-test suite |
| `SystemInformation` | — | **EVALUATE** | Generic device/OS/carrier introspection; may duplicate our `IntemptEventManagerService:375-455` |
| `MPLog` | — | **REJECT** | We have `LoggerManagerService` |
| `Base64Coder` | — | **REJECT** | Only needed for their form-encoding, which we're not using |

**Reusable substrate: ~3,266 LOC** across `MPDbAdapter` + `AnalyticsMessages` + `HttpService` + `MPConfig` + `SynchronizedReference` + utils. Of that, the genuinely endpoint-agnostic reliability logic is the majority; the Mixpanel-specific surface to strip is concentrated in identifiable, named methods and message types listed above.

**Inheritable test coverage** (the real prize, none of which a hand-rewrite gets): `MixpanelBasicTest` (25 tests, 2,130 lines — end-to-end queue behavior), `HttpTest` (8), `HttpServiceBackupTest` (15), `BackupHostTest` (7–9), `MPConfigTest` (17–28), `SynchronizedReferenceTest` (8), `OptOutTest` (5). Roughly **80–100 real tests** against the exact code paths being inherited.

---

## 4. Why hand-reimplementation scored 41/100 — the empirical case

The audit of the hand-port plan found **8 of 30 findings, including 2 of 4 Criticals**, were all the same root cause: reimplementation silently dropped Mixpanel's accumulated production hardening.

| Mixpanel has | Hand-port dropped it | Consequence |
|---|---|---|
| `&& mFailedRetries <= 0` flush guard (`AnalyticsMessages.java:589`) | omitted | Backoff defeated — one POST per event during an outage |
| `Math.max(usableSpace, 20MB)` backpressure (`MPDbAdapter.java:189-190`) | misread as a hard 20MB ceiling | Drops events on a device with GBs free. Real ceiling is `MaximumDatabaseLimit` = `Integer.MAX_VALUE` (`MPConfig.java:213-215`) |
| Per-row `JSONException` skip (`MPDbAdapter.java:645-647`) | omitted | One malformed row kills event capture for the whole process, permanently |
| `LIMIT flushBatchSize` (50) per POST (`MPDbAdapter.java:627`) | unbounded `getAll()` | Single POST up to the size cap → OOM/413, permanently unflushable |
| Reads deliberately do **not** destroy the DB (`MPDbAdapter.java:653-661`) | destroyed it on read errors | Turns a transient read fault into guaranteed data loss |
| `time_idx` on `created_at` (`:98-99`) | omitted | Full table scan every flush |
| `isOnline()` gate, `Retry-After`, monotonic delay | omitted | Wasted timeout cycles, ignores server backpressure |

**None of these are carelessness.** They exist in Mixpanel's code because they were learned in production over 10+ years across billions of devices. Reading the source to "port the pattern" does not transfer them — and the plan that dropped them still scored 8/10 on craft. Well-made artifact, wrong strategy.

---

## 5. Blocking legal question — applies to *either* strategy

**`gradle.properties:45` → `POM_LICENCE_NAME=MIT License`.** This SDK is published to Maven Central as MIT. Mixpanel's code is **Apache-2.0**.

- Apache-2.0 code cannot be relicensed as MIT.
- A "faithful port" of specific files is a derivative work, so Apache-2.0 §4 attaches whether we copy the files *or* hand-translate them. **The hand-port does not avoid this** — that was an implicit assumption in the earlier strategy and it was wrong.
- Obligations that must actually be met, none of which the earlier plan satisfied: §4(a) include a copy of the License (a URL is not a copy); §4(b) prominent per-file change notices; §4(d) NOTICE distributed **with the artifact** (repo-root presence ≠ distribution — nothing added it to the AAR or the POM).
- Apache-2.0 §6 grants **no trademark rights**: any inherited code must be de-branded (package rename `com.mixpanel.android` → `com.intempt.core`, name stripped from classes and log tags).

**This needs Sid's ruling as legal/infra owner before any code lands**, and the likely resolution is either dual-licensing the SDK or relicensing it Apache-2.0. It is not a blocker on *deciding* strategy, but it is a blocker on shipping.

---

## 6. Recommendation

1. **Kill the hand-port plan.** `plan/mixpanel-foundation-port` @ 41/100 — 4 blockers, 4 criticals, 7 checkpoints asserting PASS that won't pass. Not fixable by polish.
2. **Both former blockers are now closed:**
   - §1.1 — **resolved by reading the code.** All three credential forms work; the SDK's Basic header is the canonical one. No SDK change. One public-docs fix falls out.
   - §5 — **Sid ruled Apache 2.0** (2026-08-11). And since the recommended strategy is now decisions-as-spec rather than importing Java, the license question no longer gates the SDK work at all.
3. **Then adapt, per the §3 matrix** — `MPDbAdapter` + `AnalyticsMessages` as the durable-queue core (de-branded, single-table, message types stripped), `HttpService`'s retry/failover logic with our own encoding and our own 429-aware error taxonomy from §1.4.
4. **Port the inheritable tests alongside the code.** `MixpanelBasicTest`'s 25 end-to-end queue tests are the reason this path beats rewriting. Inheriting code without inheriting its tests forfeits most of the advantage.
5. **R13's other P0s are independent of all of the above** — PII autocapture masking and the `logOut()` profileId rotation are small, self-contained fixes that should not wait on the queue work.

**Open question worth deciding explicitly:** Java-adapted-in-place vs Kotlin-translated. Adapting their Java keeps the battle-tested code paths byte-for-byte and inherits their tests directly, at the cost of a mixed-language SDK. Translating to Kotlin gives a consistent codebase but re-opens exactly the fidelity risk that produced the 41/100. On the evidence in §4, the mixed-language cost looks cheaper than the fidelity risk — but this is a judgment call for the eng owner, not a foregone conclusion.
