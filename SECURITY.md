# Security

## Reporting a vulnerability

Email **security@intempt.com**. Please do not open a public issue for a security report.

Include the SDK version, the Android API level, and enough detail to reproduce. If you have a
proof of concept, a diff against the sample app in `sample/` is the fastest thing for us to
run.

We will acknowledge your report within 3 business days and tell you whether we can reproduce
it. If we can, you will get an assessment and a target fix version. We will credit you in the
release notes unless you would rather we did not.

## Supported versions

| Version | Supported |
|---|---|
| 3.0.x | yes |
| 2.0.x | security fixes only |
| < 2.0 | no |

## What this SDK does with data

Worth knowing when assessing it, and worth checking against your own privacy obligations:

- **Events are stored unencrypted on the device**, in a SQLite database inside the app's
  private data directory (`intempt_events`). They stay there until the server confirms
  receipt, up to 5 days. On a non-rooted device this is readable only by the host app, but it
  is not encrypted at rest.
- **Autocapture reads text from input fields.** Password inputs are masked before anything is
  written to the queue. Any other field a host app considers sensitive should be tagged with
  `Intempt.doNotCaptureText(view)`, or text capture disabled entirely via
  `isTextCaptureEnabled` in `assets/intempt-config.json`.
- **Credentials live in the app's assets.** `intempt-config.json` ships inside the APK, so
  the API key it holds is extractable from any published build. Use a key scoped to event
  ingestion only.
- **`logOut()` rotates the local profile id**, so a shared device does not attribute the next
  user's events to the previous one. Call it on sign-out.

## Scope

In scope: anything letting an attacker read another app's data, exfiltrate host-app data
through the SDK, execute code, or bypass the input masking described above.

Out of scope: findings that require a rooted device or physical access to unlocked hardware,
and the extractability of the ingestion key from the APK, which is inherent to shipping a
client-side key.
