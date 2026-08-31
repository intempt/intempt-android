# Testing

## Gates, as CI actually runs them

| Gate | Command |
|---|---|
| Tests | `./gradlew :app:testDebugUnitTest` |
| Mutation | `./gradlew :mutation:pitest` — **see the mutation module's config** |

**The mutation gate is the real bar, not coverage.** Coverage says a line executed; mutation says an
assertion would have noticed if that line were wrong. A test asserting a value that was already true
before the code ran executes the line and kills no mutant.

## What each gate can and cannot see

**`:mutation` is an ALLOWLIST, and a green score says nothing about code outside it.** This is the
single easiest thing to misread here. `targetClasses` names classes explicitly and `sourceSets`
names files explicitly, so a new class is **outside the gate by default** — 85/85 stays green while
brand-new logic has no test at all. That happened: the first version of the flag change touched six
classes and `targetClasses` matched none of them.

The rule that follows: **when a change adds pure decision logic, put it in a file `:mutation` can
compile and add it to both lists in the same commit.** "Pure" means no `android.*` import — the
module's compile fails otherwise, which is the intended guard. `types/Flags.kt` is the flag
surface's home for exactly this reason; `buildChooseBody`, `unwrapFlagValue`, `flagNameOf`,
`flagReasonOf` and `selectChoice` were extracted out of Android-coupled classes so the gate could
reach them.

**Jacoco is a RATCHET, not a per-change gate, and it is still one.** `jacocoCoverageVerification`
covers the changed files by glob, but its rule is a **whole-bundle ratio** (`LINE >= 0.66`,
`BRANCH >= 0.44`), so a large module absorbs a block of 0%-covered new code and stays green.
Deliberately **not** changed here: raising the floor or adding per-class rules blind — without a run
to read the new ratio from — either fails the build for the wrong reason or sets a floor that is
already met and gates nothing. Move it when a CI run gives a number to move it to. Until then treat
the whole-bundle figure as a floor against regression and `:mutation` as the gate on new logic.

**Neither gate covers the Android-coupled half.** `Intempt`, `IntemptInstance`,
`CustomCaptureComponent`, `IntemptEventManagerService` and `EventPoolManagerService` cannot enter
`:mutation` — they import `android.*` and Dagger. They are covered by `:app` unit tests
(`IntemptInstanceDelegationTest` asserts the flag delegates' wiring) and by the instrumented suite.
Stated rather than left for a reader to assume the allowlist is exhaustive.

## Rules

- **A test that has never failed has never been tested.** Before trusting a new one, break the line
  it covers and watch it go red.
- **Assert a deliberate absence too.** A branch meant to do nothing — an ignored header, a producer
  that must not earn a widening — is exactly what a mutant flips without any existing test noticing.
- **Read the score from CI, never locally.** Local toolchains drift from CI's, and a timed-out mutant
  is counted as killed, so a loaded machine reports a higher score than the truth.
- `ktlintCheck` and `lintDebug` gate both `:app` and `:sample`. A multiline expression may not start
  on its declaration's line — that rule fires often and fails the build.
- **`:sample:testDebugUnitTest` runs in CI**, for the same reason the Swift demo typechecks.
