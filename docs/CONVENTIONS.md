# Conventions

**The cross-SDK surface is not decided here.** Every Intempt SDK conforms to
`intempt-swift/docs/SDK-API-CONTRACT.md`, which is the single authority on method names, argument
order, defaults and what is deliberately withheld. This file covers what is specific to Kotlin and Android and
to this repo. Where the two disagree, the contract wins and this file is the bug.

## Status of the flag surface — the contract does NOT yet specify it

Read this before citing the contract for anything below.

| | |
|---|---|
| `SDK-API-CONTRACT.md` at `intempt-swift` `origin/main` | Contains **0** occurrences of `variation`, `allFlags`, `defaultValue` or `waitForInitialization` |
| Its `experiments()` section | Still says **"deliberately NOT in any SDK — decided 2026-08-15"**, and its action table names `intempt-android` with *"removal required"* |
| `intempt-swift` **#7** — *"...superseding the 2026-08-15 removal"* | **CLOSED**, `mergedAt: null`. It never merged |
| `intempt-swift` **#8** — same title as this work | **OPEN** |

An earlier version of this file and of the PR body said this surface was built *"against the wire
frozen in `intempt-swift` #7"*. **It was not frozen and #7 is not authority** — a closed, unmerged
PR settles nothing, and an open one is not precedent either. The 2026-08-15 removal therefore
stands.

**Ordering, not direction.** The work is not wrong; it is early. `intempt-swift` #8 must land, or a
dated supersession of the 2026-08-15 decision must be recorded in `SDK-API-CONTRACT.md`, **before**
this surface merges — otherwise the seven SDKs diverge in exactly the way that section exists to
prevent. By this file's own tie-break rule, until then this file is the bug.

## The rules that come from the contract

- **A caller asks for a KEY, never a mode.** The platform resolves whether a key is an experiment, a
  personalization or a flag; its serving query filters on channel and status and never on mode. A
  method name that encodes the mode forces an integrator to know the answer before they can ask the
  question, and grows combinatorially with every mode added.
- **`defaultValue` is REQUIRED, everywhere.** It is what a caller receives on a network failure, a
  timeout, an unknown key or a malformed response. An SDK that throws when the service is
  unreachable takes the application down with it, which is the opposite of what a kill switch is for.
- **A wrong-typed value falls back; it is never coerced.** A flag configured as a string and read as
  a boolean returns the caller's default, not `true`. Coercion makes a misconfiguration look like a
  deliberate value.
- **`variationDetail` is NOT exposed.** It would carry a reason, and the serving response does not
  send one — so it could only report "off" for a person who was in fact targeted and served, which
  is the single thing such a method exists to tell you. It stays internal until the platform sends a
  reason. Do not re-add it, and do not document it on a docs page either.
- **Evaluation is REMOTE only.** No local rule engine, no flag store to poll, and no hashing utility:
  the server buckets, so no second implementation can disagree with it. `check-no-local-bucketing.mjs`
  enforces this in CI and a new bucketing helper fails the build.
- **A validation mistake throws; a service problem does not.** A blank key or a missing default is a
  programming error the caller can fix, so it fails loudly at the call site — `variation("")` raises
  `IllegalArgumentException`. A 5xx is absorbed. This cuts both ways: a malformed *response* must
  not throw either, which is why `flagNameOf` and `flagReasonOf` read through `as?` rather than
  `jsonPrimitive`, which throws on a non-primitive.

## Credentials — OPEN, not settled

**Do not read the paragraph below as a ruling. It is the open question.**

`brain/product/specs/experiences/experiences-spec.md` records **D4 — "Does the server evaluation
endpoint require a credential?"** as unruled, with *"No recommendation. This is Sid's or the
reviewer's, and it blocks the audience-service work."* An earlier version of this file stated the
answer as settled. It is not, and an SDK repo is not where a priority-C security requirement gets
decided.

The two positions:

- `EXP-SERVE-004` (priority **C**) requires a **server** credential on the SDK-surface evaluation
  endpoint: the browser-facing path stays on the public key because it serves anonymous visitors,
  the server path must not, because the key held by a customer's backend is the credential and the
  response describes how the experience targets.
- Review asked for that to be removed and the call made with an API key instead. The spec states
  plainly that the two are not compatible.

**What this SDK actually sends today**, `core/services/HttpManagerService.kt`:

```kotlin
val authHeader = "Basic ${config.token()}"
```

That is the project's **ingestion** credential — the same one `/track` uses — shipped inside an APK
on an end-user device. It is not a server credential by any reading of `EXP-SERVE-004`. It is left
as-is deliberately: changing it is a D4 decision, and this repo may not make it.

**Consequence if D4 resolves toward `EXP-SERVE-004`:** the SDK's own credential is refused,
`chooseFlags` returns `emptyList()` on the 401, `variationDetailInternal` maps that to the default,
and **every `variation()` call returns the caller's default forever, silently** — nothing in the SDK
or the product surfaces it. That is the same absent-vs-unanswered collapse recorded below.

**Owner: Sid.** Routed, not resolved here. Never log the credential, never put it in a URL.

## Known upstream gap — an unanswered call and an empty result are the same value

Not fixable in this repo, recorded so nobody re-files it as an SDK defect.

`chooseFlags` returns `emptyList()` for **all** of: a non-2xx, a thrown exception, an unparseable
body, a missing `choices` key, and a genuinely empty `choices`. `variationDetailInternal` maps an
absent key to the default. So "paused", "not targeted", "unknown key" and "service unreachable" are
one value at the call site.

The platform owns this, and the spec already says so:

- Known limitation **37** — *"The evaluation response cannot express why a value is absent."*
  Governed by `EXP-SERVE-001` (H, unimplemented).
- Known limitation **39** — *"No off value exists. A paused or stopped experience drops out of the
  response entirely."* Governed by `EXP-SERVE-005` (H).

The one method that would carry the signal, `variationDetail`, is deliberately internal precisely
because the reason it would report is one the platform does not send. It becomes public when
`EXP-SERVE-001` ships — not before.

## Assignment identity — `FlagContext.userId` breaks stability, it does not preserve it

`audience-service`'s `experience/payload/Identification.anonymousId()` returns `userId` whenever one
is present and only falls back to `sourceId_profileId` otherwise; `VariantChooserService.choose()`
keys the anonymous assignment on that value. So supplying `userId` on one call and omitting it on
the next re-buckets the person mid-session — what `EXP-ASSIGN-005` (H) and Sid's 2026-08-24 ruling
forbid.

An earlier version of `Flags.kt` promised the opposite in a doc comment. That comment is corrected,
the sample no longer demonstrates the footgun, and the property carries the warning. **Whether the
SDK should send `userId` for evaluation at all is a product question on `EXP-ASSIGN-005`** and is
routed with D4 rather than answered here.

Caveat kept rather than buried: the *primary* assignment key is `entityId` from
`segmentation.getId()`; only the `anonymousKey` path is verified from the source above.

## Surface parity — the typed helpers are singleton-only, on purpose for now

`Intempt` carries all six flag methods; `IntemptInstance` carries only `variation` and `allFlags`,
so a multi-instance host gets the untyped surface. This is a real inconsistency and it is **left
open deliberately**: the flag surface is not in the contract yet (see the status table above), and
widening the public ABI before `intempt-swift` #8 lands adds to the divergence that section exists
to prevent. Close it in the same change that records the contract, not before.

## Android specifics

- **The Mixpanel Android SDK is vendored as a substrate and recorded in `NOTICE`.** Keep that
  attribution accurate to what is actually in the tree: a NOTICE describing code that was never
  vendored is worse than none, because a requirement can be signed off against it.
