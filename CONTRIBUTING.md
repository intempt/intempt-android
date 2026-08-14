# Contributing

## Building and testing

```bash
./gradlew :app:testDebugUnitTest   # unit tests
./gradlew :app:ktlintCheck         # style
./gradlew :app:lintRelease         # Android lint
./gradlew :mutation:pitest         # mutation testing on pure-JVM logic (see below)
```

All four run in CI on every PR.

## Style

Formatting is enforced by ktlint (`./gradlew :app:ktlintFormat` to auto-fix). New violations
fail the build; existing ones are tracked against a shrinking baseline in
`app/config/ktlint-baseline.xml` — don't add to it.

## Tests

- Prefer plain JVM unit tests over Robolectric/instrumented tests where the logic doesn't
  touch the Android framework; they run faster and mutation-test cleanly.
- Pure-JVM logic that's easy to falsify (parsers, policy decisions, payload builders) belongs
  in the `:mutation` sidecar module, gated at an 85% PIT mutation-kill threshold. See
  `HttpStatusPolicy`, `TrackPayloadBuilder`, `QueueConfig`, and `QueueLog` for the pattern.
- A test that can't fail is worse than no test — if you can comment out the code under test
  and the test still passes, it isn't asserting anything real.

## Public API changes

`com.intempt.core.Intempt` is the SDK's entire public surface. Any change to it:

- Needs a KDoc comment (the object is consumed by Java hosts too — see the `@JvmStatic`/
  `@JvmOverloads` annotations already on it).
- Needs a `CHANGELOG.md` entry under `Unreleased`.
- Should stay backward compatible where possible; this is a library other apps depend on,
  not an app you can migrate in lockstep.

## Releasing

Maintainers only — see `RELEASING.md`. Contributors don't need to think about this; land
changes on `main` via PR and someone else cuts the release.
