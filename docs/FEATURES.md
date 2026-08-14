# Intempt Android SDK — Feature Specification

**Status:** locked feature set. Every row verified against source on 2026-08-11.

**Verification basis:**
- **Android** — `android-sdk@main` (`a36a754`), `app/src/main/java/com/intempt/core/**`
- **REST contract** — `website-docs/content/openapi/api-reference.yaml`, served by `push-source-service` (`WebFluxServer.java:86`) via `gateway` (`PushSourceAdapterRoutes`)
- **Reference client** — `intemptjs` (canonical implementation of Intempt's client model)

**Legend:** ✅ shipping and verified · 🔧 fixed by the current plan · ✨ new in the current plan · ❌ deliberately excluded

---

## 1. Public API

19 callable members plus `isInitialized`. **Signatures DID change in 3.0.0:** `initialize` returns `Boolean` rather than `Unit` (a binary break for Java callers), and the `experiment` / `personalization` properties were removed — experiments and personalizations are an intemptjs capability, not an Android one.

| Member | Signature | Status |
|---|---|---|
`initialize` | `(context: Context)` | ✅ |
`identify` | `(userId, eventTitle?, userAttributes?, data?)` | ✅ |
`group` | `(accountId, eventTitle?, accountAttributes?)` | ✅ |
`track` | `(eventTitle, data)` | ✅ |
`record` | `(eventTitle, accountId?, userId?, accountAttributes?, userAttributes?, data?)` | ✅ |
`alias` | `(userId, anotherUserId)` | ✅ |
`consent` | `(action, validUntil, email?, message?, category?)` | ✅ |
`productAdd` | `(productId, quantity)` | ✅ |
`productOrdered` | `(products: List<Map<String, Any>>)` | ✅ |
`productView` | `(productId)` | ✅ |
`recommendation` | `suspend (id, quantity, fields, productId?) → JsonObject?` | ✅ |
`logOut` | `()` | 🔧 |
`doNotCaptureText` | `(view: View)` | ✅ |
`Logging.start` / `.stop` / `.isLoggingEnabled` | 3 members | ✅ |
`Tracking.start` / `.stop` / `.isTrackingEnabled` | 3 members (opt-in / opt-out) | ✅ |

`ModificationProvider` exposes `getByGroup(List<String>)`, `getByName(List<String>)` and `@JvmSynthetic` async variants returning `CompletableFuture`.

All capture calls are gated on `config.isUserOptIn` and wrapped in `utils.withTryCatch` — an SDK failure never propagates into the host app.

---

## 2. Wire payloads — verified against REST contract and intemptjs

Every event posts to `POST /v1/{org}/projects/{project}/sources/{sourceId}/track` as `{"track":[{"name":…,"payload":[…]}]}`.

Auth: `Authorization: Basic base64(identifier:secret)` — the canonical form; passes through `LegacyApiKeyTranslatorFilter` untouched.

| Call | `type` | Wire fields | vs intemptjs |
|---|---|---|---|
`identify` | `identify` | `eventId`, `sessionId`, `pageId`, `profileId`, `timestamp`, `userId`, `userAttributes?`, `data?` | **match** |
`group` | `group` | `eventId`, `sessionId`, `pageId`, `profileId`, `timestamp`, `accountId`, `accountAttributes?` | **match** |
`track` | `track` | `eventId`, `sessionId`, `pageId`, `profileId`, `timestamp`, `data` | **match** |
`record` | `record` | `eventId`, `sessionId`, `pageId`, `profileId`, `timestamp`, `accountId?`, `userId?`, `accountAttributes?`, `userAttributes?`, `data?` | **match** |
`alias` | `alias` | `eventId`, `sessionId`, `pageId`, `profileId`, `timestamp`, `userId`, `anotherUserId` | Android adds `sessionId`/`pageId`; harmless superset |
`productAdd/Ordered/View` | `product` | `eventId`, `sessionId`, `pageId`, `profileId`, `timestamp`, `data:{productId, quantity?}` | **match** |
`consent` | `consent` | `profileId`, `timestamp`, `sourceId`, `source:"android"`, `validUntil`, `action`, `email?`, `message?`, `category?` | matches; `source` correctly `"android"` vs `"web"` |

**Event-name constants match intemptjs exactly** (`IntemptEventName`): `"Added to cart"`, `"Product ordered"`, `"Product viewed"`.

### Endpoints

| Purpose | Endpoint | Batched |
|---|---|---|
Event ingestion | `sources/{sourceId}/track` | ✅ yes |
Consent | `consents/data` | no — fires immediately |
Recommendations | `feeds/{id}/data` | n/a |
Push lifecycle | `webhooks/events/push-notification` | no |

---

## 3. Autocapture

| Family | Events | Detail |
|---|---|---|
Session | `Session start` | 30-min inactivity timeout; geo is derived **server-side** from the request IP (see `?ip=` below), not fetched on device |
Screen | `View screen`, `Leave screen` | per Activity |
Fragment | `Fragment transition` | |
Touch | `Touch event` | UI element taps |
Change | `Change event` | 9 widget types |
Install | `App install/upgrade` | first-install vs version-bump; carries FCM token |

**Change-tracked widgets:** `CompoundButton`, `EditText`, `Spinner`, `SeekBar`, `RatingBar`, `DatePicker`, `TimePicker`, `ListView`, `RadioButton` — with `RecyclerView` / `ViewGroup` traversal.

Toggles: `isAutoCaptureEnabled`, `isTouchEnabled`, `isTextCaptureEnabled`.

**Broader than Mixpanel**, whose automatic events are session / first-open / app-updated / crash only — no UI-element capture.

---

## 4. Push notifications + journeys

| Capability | Status |
|---|---|
FCM token capture; graceful skip when Firebase unconfigured | ✅ |
Rich push: title, body, image, `webUrl` deep link | ✅ |
Lifecycle webhooks: `DELIVERED`, `BOUNCED`, `OPENED` | ✅ |
8 journey-execution IDs: `orgId`, `projectId`, `pipelineId`, `transformerId`, `destinationId`, `masterId`, `accountId`, `templateId` | ✅ |
Non-Intempt FCM messages ignored without crashing | ✅ |
Token re-sent when FCM rotates it | 🔧 |

Journey IDs are `journeys-pipeline`'s own vocabulary (`PipelineOutboxService`, `Transformer`) — a real journey-engine integration.

**Mixpanel has no push support in current master.** This is an Intempt differentiator.

---

## 5. Experiments & personalization — NOT in this SDK

Removed deliberately. Experiments and personalizations are an intemptjs capability and are
not part of the Android or iOS SDKs, so `optimization/choose-api` is not called from here and
`ModificationProvider` no longer exists. Recommendation feeds (`/feeds/{id}/data`) are
unaffected and remain part of the SDK.

---

## 6. Reliability

| Behavior | Before | After |
|---|---|---|
Queue durability | in-memory only | ✨ disk-backed (SQLiteOpenHelper), survives process death |
Delete timing | cleared **before** POST | 🔧 only after confirmed 2xx |
Retry | none | ✨ 429 + 5xx + timeout + offline; exponential backoff capped at 10 min |
`Retry-After` honored | no | ✨ yes |
413 Payload Too Large | n/a | ✨ adaptive batch halving; drop single oversized event |
Non-retryable statuses | all failures dropped | ✨ 401 / 403 / 400 / 422 not retried, logged |
Duplicate protection | none | ✨ dedup by `eventId`, marked **before** send |
Concurrent flush | race | ✨ in-progress guard |
Crash-stranded items | lost | ✨ orphan recovery via `flushAfter` |
Delete verification | none | ✨ remove-then-verify |
Runaway failures | none | ✨ circuit breaker (>5 removal failures → stop) |
HTTP timeouts | **none configured** | ✨ explicit connect / read / request |
Batch tuning | 5 events / 5 s | ✨ 40 events / 60 s (Mixpanel's inherited defaults; overridable via itemsInQueue / timeBuffer) |
Backpressure | unbounded | ✨ disk ceiling |
Concurrency model | unsynchronized shared list | ✨ single-writer dispatcher |

Semantics ported from `intemptjs/src/shared/queue/requestBatcher.ts` + `requestQueue.ts` — same API, same auth, same payload shape, already production-validated. `SharedLock` (multi-tab) and the page-unload machinery are **not** ported; Android is single-process.

---

## 7. Privacy, consent, security

| Capability | Status |
|---|---|
`Tracking.start()` / `.stop()` gate every capture path | ✅ |
Dedicated `consent()` event → `consents/data` | ✅ |
`doNotCaptureText(view)` — per-view opt-out, 12 supported types | ✅ |
HTTPS enforced (hardcoded base URL) | ✅ |
No hardcoded secrets; apiKey loaded from assets at runtime | ✅ |
Automatic PII masking — password inputs masked at all three read sites; **every other `EditText` still ships its text verbatim** while `isTextCaptureEnabled` defaults true. Use `doNotCaptureText(view)` per field, or disable text capture. | ✅ |
`profileId` rotates on `logOut()` — currently restores the same ID (cross-user leak on shared devices) | 🔧 |
`SCHEDULE_EXACT_ALARM` permission removed (declared, zero usages) | 🔧 |
`consumerProguardFiles` shipped so host-app R8 can't strip reflection dispatch | ✨ |

**Mixpanel has no consent concept at all** — only a blanket opt-out boolean. Intempt is ahead here.

---

## 8. Configuration — `assets/intempt-config.json`

```json
{
  "auth": {
    "INTEMPT_API_KEY": "identifier.secret",
    "INTEMPT_SOURCE_ID": "…",
    "INTEMPT_ORGANIZATION_ID": "…",
    "INTEMPT_PROJECT_ID": "…"
  },
  "options": {
    "isLoggingEnabled": false,
    "isTouchEnabled": true,
    "isTextCaptureEnabled": true,
    "isQueueEnabled": true,
    "isAutoCaptureEnabled": true,
    "itemsInQueue": 5,
    "timeBuffer": 5000
  }
}
```

---

## 9. Packaging

| | |
|---|---|
Coordinates | `com.intempt.sdk:intempt-android` |
License | **Apache 2.0** from 3.0.0 (2.0.1 and earlier were MIT and stay MIT on Maven Central). LICENSE + NOTICE are packaged into the AAR under META-INF. |
Distribution | Maven Central |
Stack | Kotlin 1.9.22, Dagger 2.52, Ktor 2.3.11, coroutines 1.9.0, SQLiteOpenHelper |
compileSdk | 35 |
minSdk | 23 — **open decision, see §11** |

---

## 10. Deliberately excluded

| Feature | Why |
|---|---|
Session replay | ❌ Not an Intempt product direction; absent from `intemptjs` with no plan proposing it |
Super properties | ❌ Attributes are event-scoped; the backend owns profile persistence. `intemptjs` has no `register()` either |
Mutable group profiles | ❌ `group()` is an event, not a profile object — matches `intemptjs` |
OpenFeature-style feature flags | ❌ Intempt's model is `optimization/choose-api` returning DOM/content choices |
In-app messaging | ❌ Absent from both Intempt and current Mixpanel |
Client-side attribute cache | ❌ Same reason as super properties |

---

## 11. Divergences found during verification

Three real inconsistencies surfaced by comparing Android against `intemptjs`. **None are Android-only defects; all need a product decision.**

### 11.1 `group()` event defaults to the name `"Identify"`

Both SDKs do this — Android `CustomCapture.component.kt:145` and `intemptjs/group.model.ts` both use `eventTitle ?? 'Identify'`. A group event named "Identify" is confusing in analytics. **If it's wrong, it's wrong in both** and should be fixed together, or documented as intentional.

### 11.2 `timestamp` — Android sends it, intemptjs has it commented out

Android stamps `timestamp` on every event. `intemptjs` has `//timestamp: new Date().getTime()` commented out in **every** model, so the server applies ingest time (the REST contract permits this: *"If not set, the current timestamp will be applied"*).

**Android's behavior is correct and must stay.** With a durable queue, an event captured offline may not flush for minutes or hours — server-stamping would attribute it to flush time and corrupt event ordering. This is arguably a latent bug in `intemptjs`, which has a persistent queue with orphan recovery and still lets the server stamp.

### 11.3 Autocapture event names differ between web and mobile

| Concept | intemptjs | Android |
|---|---|---|
Page/screen view | `View Page` | `View screen` |
Page/screen leave | `Leave Page` | `Leave screen` |
Click / tap | `Click On` | `Touch event` |
Change | `Change On` | `Change event` |
Session start | `Session Start` | `Session start` |

Page-vs-screen is defensible — mobile has screens. **But `Session Start` vs `Session start` is a casing difference only, and will create two distinct event names in analytics for the same concept.** That is a data-quality defect regardless of the naming debate. Needs a decision: unify casing, or accept the split.

---

## 12. Open decisions

| # | Decision | Owner |
|---|---|---|
1 | ~~minSdk~~ **Done: 23.** A customer was blocked at 23; `CompletableFuture` was the only blocker and is gone. 21 would need core-library desugaring for a residual jackson `Stream` path. | Closed |
2 | **§11.3 casing** — unify `Session start` / `Session Start` across SDKs? | Product |
3 | **§11.1** — is a `group()` event named "Identify" intentional? | Product |
4 | **CI gate** (test + lint on every PR) in the same push? Process, not a feature | Eng |
5 | **Public docs fix** — `api-reference.yaml:43-51` documents the deprecated, insecure query-param auth as `required`; our own filter logs a warning against it | Docs |

## Geolocation

The SDK does not fetch, store or transmit the device's IP address.

It appends `?ip=1` to the ingestion endpoint, which tells the platform it may derive
city / region / country from the source IP of the request it already receives. `?ip=0` tells it not
to. This is the same mechanism mixpanel-android uses
(`MPConfig.getEndPointWithIpTrackingParam`), and it means no third party is involved and the device
never handles its own IP.

Turn it off in `intempt-config.json`:

```json
{ "options": { "useIpAddressForGeolocation": false } }
```

Defaults to `true`, matching Mixpanel's `UseIpAddressForGeolocation`.

**Until the platform honours `?ip=`, session events carry no geo.** Previously the SDK called
`ipapi.co` per session and put the IP plus city/region/country in the payload — outside consent
gating, with no consumer switch and no sub-processor disclosure. That has been removed; the
server-side half is tracked separately.
