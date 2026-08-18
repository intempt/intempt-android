# CLAUDE.md — Intempt Android SDK

Rules for working in this repository. Every one of them is here because it was
learned the expensive way: each has a specific defect behind it that reached
`main` or nearly did. The evidence is named so you can check it rather than trust
it.

## Output format

Bullets and small tables. One fact per line, numbers not adjectives. No prose
paragraphs in chat. Files, commits and PR bodies keep normal prose.

---

## 1. Test at the minSdk floor, never only at the target

`minSdk` is **23**. CI runs the instrumented suite on an **API 23 emulator and an
API 34 emulator**. Testing only the target is worthless for this SDK.

Five defects were invisible above the floor and would have shipped:

| Defect | Requires | Symptom below it |
|---|---|---|
`java.util.Base64` | API 26 | **every request failed** — it sat in the auth path |
`DatePicker.setOnDateChangedListener` | API 26 | crash on every DatePicker interaction |
`PackageInfo.longVersionCode` | API 28 | crash on every install/upgrade event |
Jackson 2.16+ → `BootstrapMethodError` | API 26 | **host app died before its first frame** |
`Map.putIfAbsent` (in our own test code) | API 24 | `NoSuchMethodError`, whole suite red |

The last one is the most instructive: it passed compilation, ktlint, `:app` lint
**and the API 34 emulator**. Only API 23 caught it.

## 2. Lint does not see everything

Three separate blind spots, all of which let a real defect through:

- **Lint does not scan third-party bytecode.** The Jackson crash passed lint
  cleanly while the app died on an API 24 device. When you change or bump a
  dependency, `:sample` on an API 23 emulator is the only real check.
- **Lint skips test sources by default.** Both modules now set
  `checkTestSources = true` and promote `NewApi` to `error`. A test that cannot
  run at minSdk is as broken as production that cannot.
- **Robolectric cannot catch API-level defects at all.** It runs on the JVM,
  where `BootstrapMethodError`, `putIfAbsent` and friends all exist. A
  `@Config(sdk = [23])` test passes while a real device crashes. Robolectric is
  for logic; emulators are for API levels.

## 3. Verify the artifact, not the build

`BUILD SUCCESSFUL` means the build ran. It does not mean the artifact contains
what you think.

- The generated `intempt-config.json` was wired so that `assembleDebug` and
  `assembleRelease` both succeeded and **the APK contained no config at all**.
  `unzip -l` would have shown zero matches in five seconds.
- `LICENSE` and `NOTICE` were generated into `intermediates/` and **never reached
  the AAR**, while the PR claimed Apache §4(d) compliance. AGP excludes
  `/META-INF/LICENSE` and `/META-INF/NOTICE` by default. They now ship as
  `META-INF/intempt_LICENSE.txt` / `intempt_NOTICE.txt` — distinct names, so they
  are not excluded and cannot collide with a transitive dependency's copy.

**Rule:** after any packaging change, `unzip -l` the APK or AAR and assert the
file is present. Assert bytes, not exit codes.

## 4. Check exit status, not grepped output

`./gradlew ... | grep -E "pattern"` reports on your pattern, not on the build. A
commit was pushed with `ktlintKotlinScriptCheck` failing because the grep was
narrow enough that `BUILD FAILED` was the only line shown, and it read as clean.

Run the command, capture `$?`, then look at the log. Never infer success from an
absence of matches. Never read an exit code through a pipe — `cmd | tail; echo $?`
gives you `tail`'s status.

## 5. Vendoring inherits assumptions, not just code

`com.intempt.core.queue` is vendored from
[mixpanel-android](https://github.com/mixpanel/mixpanel-android) under Apache 2.0.
Copying is safer than translating — an earlier hand-translation lost four
hardening behaviours because you cannot drop a guard you never retyped — but
**every upstream assumption comes with it.**

The one that shipped: Mixpanel authenticates with a project token *inside the
event body*, so upstream correctly calls `performRequest(url, i, null, null, ...)`
with **null headers**. Intempt uses HTTP Basic. Vendoring the transport therefore
removed authentication entirely — every batch 401'd, `shouldDrop(401)` was true,
and `cleanupEvents` deleted it. **100% silent event loss, with the durable queue
working exactly as designed.**

**Rule:** for each vendored file, list what upstream assumes about auth,
identity, config and threading, and check each against Intempt's model. Review
vendored files as provenance — match upstream, de-branding complete, §4(b) change
notices intact. New logic lives only in `HttpStatusPolicy`,
`TrackPayloadBuilder`, `QueueConfig` and the `EventPoolManagerService` bridge.

## 6. Status taxonomies must fail safe

`HttpStatusPolicy` decides retry-vs-drop. A retried batch is **never deleted**, so
it parks at the queue head and blocks every event behind it.

The original enumerated droppable statuses and let everything else retry. An
adversarial sweep of `400..451` found **46 statuses** taking that path — including
**404**, which a typo in `org`, `project` or `sourceId` produces, silently costing
a customer every event they would ever send.

Default is now inverted: `>= 300` and not explicitly retryable means **drop**.
Only `408`, `429`, `5xx` and `<= 0` retry.

**Rule:** an unrecognised error must lose one batch, never block the queue. When
you add a status, add it to the sweep test in `HostileInputTest`.

## 7. A test that constructs the thing under test cannot test it

`ProdDeliveryTest` built its **own** `Authorization` header and posted to prod. It
passed for weeks. It was validating the gateway, not the SDK — production sent no
header at all.

The header now comes from `QueueConfig`, the same object production uses.

**Rule:** tests read configuration from production objects. If a test builds the
value, it is testing itself.

## 8. Pick an oracle that distinguishes success from failure

The device delivery test asserted "the row left the queue". Rows are also deleted
on `shouldDrop(400/401/402/403/422)` and on an unparseable batch. **Drained never
meant delivered** — the test reported success on total data loss.

**Rule:** name the failure your oracle cannot see. If you cannot name one, the
oracle is wrong.

## 9. Instrumented tests share one process — predicates must be unique

`SdkOnDeviceTest` and `SdkProdObjectsTest` run in one app process, against one
SQLite queue and one `SharedPreferences`, with no ordering guarantee, and state
survives between runs on the same device.

Matching on `type == "identify"` found *another test's* identify and compared it
against the wrong fixture. This bit three tests across two CI rounds because the
lesson was fixed in one place and not carried to its neighbours.

**Rule:** every predicate matches an identifier the test itself generated or was
given. `type` alone is never enough.

## 10. A working backend makes queue tests racy

The queue deletes a row as soon as the gateway confirms it. Before CI had
credentials every POST 401'd, rows accumulated, and sampling the table worked **by
accident**. Adding real credentials made delivery succeed and every
presence-check racy — `alias` failed on API 23 with `Queued: [Change event]` while
delivery was working perfectly.

Both suites now **accumulate** every row ever observed, keyed by `eventId`, and
poll at 50 ms. The property is "reached the queue at some instant", not "is still
there".

## 11. Baselines store line numbers

`app/config/ktlint/baseline.xml` freezes violations **by line**. Editing any
baselined file shifts the lines and fails the gate on violations that were
supposed to be frozen. This happened four times.

If ktlint fails on a file you edited and the violations look pre-existing:
regenerate the baseline. Prefer fixing the violation over baselining it.

## 12. Never run ktlintFormat across the repo

`./gradlew :app:ktlintFormat` reformats **every** Kotlin file — 68 files in one
case, 65 in another — burying the reviewable change. If you run it, classify the
diff and `git checkout HEAD --` every file whose change is format-only. Keep
formatting only in files you edited for a real reason.

## 13. Do not grade your own work

Self-assessment on this repository has been unreliable in a measurable way: a
plan self-scored 93 was scored **41** by an independent review, and three
compliance claims in a PR body — licence packaging, masking coverage, minSdk —
were false when checked.

For anything consequential, run an **independent adversarial review** with an
explicit lens (reliability / consumer contract / test falsification) and instruct
it to find defects rather than confirm the work. Three such reviews on PR #16
found a P0 that invalidated the PR's own premise.

Corollary: when a claim turns out false, **correct it in place and say so**. PR
#16 carries an addendum rather than a rewritten body, so the record of what was
claimed survives.

## 14. Prod-dependent tests must not gate a merge

`ProdDeliveryTest` and `recommendationReturnsFromTheFeed` hit live
`api.intempt.com`. They are opt-in behind `-Pintempt.prodTests=true` and run in a
separate `prod-e2e` job with `continue-on-error: true`.

A transient network fault failed a pull request about unrelated code once
already. An assertion that depends on someone else's availability or ingestion
timing reports, it does not block.

## 15. Credentials never enter this repository

The repo is **public**.

- Locally: gitignored `local.properties`.
- CI: the eight `INTEMPT_*` repository secrets.
- The sample's `intempt-config.json` is **generated at build time** from whichever
  is present, falling back to placeholders so the build never depends on a secret.

Never commit a key, encrypted or otherwise. Before every commit that touches
config: `git diff --cached | grep -c <key fragment>` and expect `0`.

The `Authorization` header is redacted in logs — it used to be printed in full
whenever logging was enabled, putting the ingestion credential in every bug
report.

---

## Repository shape

| Path | What |
|---|---|
`app/` | the SDK. `com.intempt.core.queue` is vendored Java; everything else Kotlin |
`sample/` | a host app that consumes the AAR like a customer. Not published |
`app/src/test/` | JVM + Robolectric. Logic only — cannot verify API levels |
`app/src/androidTest/` | `QueueDurabilityTest` — SQLite durability on a real device |
`sample/src/androidTest/` | the SDK driven through its public API on a real device |
`docs/FEATURES.md` | the locked feature spec. Keep it true or delete the claim |

## The public API is small on purpose

`Intempt` plus `Constants`, `Product`, the two event-prop types, and the push data
classes that cross a process boundary. Everything else is `internal`.

Two rules that produced real defects:

- **A type in the public API must come from an `api` dependency, not
  `implementation`.** `JsonElement`/`JsonObject` were exposed via
  `implementation`, so a consumer literally could not compile a call to
  `recommendation()`, `experiment` or `personalization`. Same class of bug leaked
  ktor's `HttpResponse` through `HttpManagerService`.
- **Never expose a credential.** `ConfigManagerService.token()` was public API
  returning the base64 ingestion key.

## Experiments and personalizations are not in this SDK

They are an intemptjs capability. `ModificationProvider`, `Intempt.experiment` and
`Intempt.personalization` were removed. Recommendation feeds
(`/feeds/{id}/data`) are unaffected and remain.

`recommendation()` needs a profile the platform has **already ingested** — the SDK
sends its device-generated `profileId`, which does not exist server-side on a
fresh install, so the call 400s until events for it have been ingested. The feed
returns the same `USER ... is not found` message for a wrong feed id and a wrong
profile, so those are indistinguishable from the client.

## Before opening a PR

```bash
./gradlew :app:testDebugUnitTest :sample:testDebugUnitTest \
          :app:lintDebug :sample:lintDebug \
          :app:ktlintCheck :sample:ktlintCheck \
          :app:jacocoCoverageVerification \
          :sample:assembleDebug :sample:assembleRelease
echo "exit: $?"          # read this, not the log
```

`jacocoCoverageVerification` is in that list because it was not: PR #29 went red in CI on the
coverage ratchet alone after a local run that covered tests, lint, ktlint and `apiCheck`. A gate
CI runs and the local checklist does not is a gate you discover from a red PR.

Then, if packaging or config changed, assert the artifact:

```bash
unzip -l sample/build/outputs/apk/debug/sample-debug.apk | grep intempt-config.json
unzip -p app/build/outputs/aar/app-release.aar classes.jar > /tmp/c.jar
unzip -l /tmp/c.jar | grep intempt_LICENSE
```

CI is the gate that matters: compile, unit, lint, **API 23**, **API 34**, and
non-gating prod delivery. A local pass is necessary, not sufficient — API 23 has
caught defects that everything else passed.

CI also runs jobs not covered above that this checklist should stay honest about,
including any it does not yet name (detekt, `apiCheck`, AnimalSniffer,
`jacocoCoverageVerification`, mutation testing, the method-count ceiling) — check
the workflow file, not this list, when in doubt:

- **`macrobenchmark-startup`** (non-gating): runs
  `:baselineprofile:connectedNonMinifiedReleaseAndroidTest`'s `StartupBenchmark`
  class on one API 34 emulator, measuring `:sample`'s real cold-start timing
  under `CompilationMode.None()` and `CompilationMode.Partial(Require)`.
  `continue-on-error: true`, same as `prod-e2e` — an emulator's absolute numbers
  are not representative of a real device and vary run to run, so this tracks
  variance over time rather than gating a merge. See
  `baselineprofile/cold-start-baseline.json` for the first recorded number.
