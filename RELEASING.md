# Releasing (maintainers)

Releases of `com.intempt.sdk:intempt-android` to Maven Central are **automated** via GitHub
Actions (`.github/workflows/publish.yml`). A release is triggered by pushing a **git tag**,
and the workflow **refuses to publish unless the tagged commit is on `main`** — so a release
can never be cut from a feature branch or a stale local checkout.

> Background: an earlier version (2.0.0) was published manually from a local checkout that
> predated a merge, which silently dropped the push-notification feature. This pipeline exists
> to make that impossible.

## How it works

- **Trigger:** pushing a tag matching `v*` (e.g. `v2.0.1`), or a manual run from the
  Actions tab (`workflow_dispatch`).
- **Guard:** the job fails unless the tagged/dispatched commit is contained in `origin/main`.
- **Build & sign:** builds the AAR and signs it in-memory using the release key.
- **Staged upload:** uploads a *staged* deployment to the Central Portal. **Nothing goes live
  automatically** — a human must click *Publish*.

## One-time setup (already done)

Four GitHub repository secrets power the workflow (Settings → Secrets and variables → Actions):

| Secret | What |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME` | Central Portal user-token username (central.sonatype.com → Generate User Token) |
| `MAVEN_CENTRAL_PASSWORD` | Central Portal user-token password |
| `SIGNING_KEY` | ASCII-armored GPG **private** key (whose public half is on a keyserver) |
| `SIGNING_PASSWORD` | passphrase for that key |

The version is read from `VERSION` in `gradle.properties` (overridable per-release by the tag).

## Cutting a release

1. **Land all changes on `main`** (via PR). Do not tag a feature branch — the guard will reject it.

2. **Bump the version.** Edit `VERSION` in `gradle.properties` (e.g. `VERSION=2.0.1`) and merge
   that to `main` too. Use [semver](https://semver.org/): patch for fixes, minor for additive
   features, major for breaking changes.

3. **Tag the release commit on `main` and push the tag:**

   ```bash
   git checkout main
   git pull origin main
   git tag v2.0.1            # tag name = v + the VERSION value
   git push origin v2.0.1    # this triggers the publish workflow
   ```

4. **Watch the run:** GitHub → **Actions** → "Publish to Maven Central". It will:
   - verify the commit is on `main`,
   - build + sign,
   - upload a **staged** deployment.

5. **Publish on the portal:** go to [central.sonatype.com](https://central.sonatype.com) →
   **Deployments**, review `com.intempt.sdk:intempt-android:<version>`, and click **Publish**.

6. **Verify:** after it validates and syncs (~15–30 min) the version appears at
   `https://repo1.maven.org/maven2/com/intempt/sdk/intempt-android/`.

## Notes & troubleshooting

- **"Refusing to publish … is NOT on main"** — you tagged a commit that isn't on `main`.
  Merge to `main` first, then tag the merged commit.
- **Tag name vs version** — the tag drives the published version (`v2.0.1` → `2.0.1`). Keep it
  in sync with `gradle.properties` `VERSION`.
- **Re-running a failed release** — fix the cause, delete and re-push the tag
  (`git push --delete origin v2.0.1` then re-tag), or use the manual `workflow_dispatch` run.
- **Auto-release instead of staged** — change the Gradle task in `publish.yml` from
  `publishToMavenCentral` to `publishAndReleaseToMavenCentral` (skips the manual Publish click;
  irreversible once live — not recommended).
- **Signing key** — the public key must be discoverable on a keyserver
  (`keyserver.ubuntu.com` / `keys.openpgp.org`) or Central can't verify the signature.
- **Local testing** — `./gradlew publishToMavenLocal -PVERSION=<v>-LOCAL -PSKIP_SIGNING=true`
  publishes to `~/.m2` without signing. Never use `-PSKIP_SIGNING=true` for a real release.
