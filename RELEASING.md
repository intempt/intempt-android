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

2. **Move the `Unreleased` section of `CHANGELOG.md` under the new version heading**, with the
   release date, and open a fresh empty `Unreleased` above it. Update the two link definitions at the
   bottom of the file.

   This is a required step, not a courtesy. Consumers upgrading across the `minSdk` change and the
   defaults that changed behaviour have no other way to find out what moved, and reconstructing it
   from `git log` after the fact is how the SDK ended up with no changelog for its first two
   releases. The publish workflow checks that the tag you are about to cut has a matching heading and
   refuses otherwise.

3. **Bump the version.** Edit `VERSION` in `gradle.properties` (e.g. `VERSION=2.0.1`) and merge
   that to `main` too. Use [semver](https://semver.org/): patch for fixes, minor for additive
   features, major for breaking changes.

4. **Tag the release commit on `main` and push the tag:**

   ```bash
   git checkout main
   git pull origin main
   git tag v2.0.1            # tag name = v + the VERSION value
   git push origin v2.0.1    # this triggers the publish workflow
   ```

5. **Watch the run:** GitHub → **Actions** → "Publish to Maven Central". It will:
   - verify the commit is on `main`,
   - build + sign,
   - upload a **staged** deployment.

6. **Publish on the portal:** go to [central.sonatype.com](https://central.sonatype.com) →
   **Deployments**, review both `com.intempt.sdk:intempt-android:<version>` **and**
   `com.intempt.sdk:intempt-push:<version>`, and click **Publish** on each.

7. **Verify:** after it validates and syncs (~15–30 min) both versions appear at
   `https://repo1.maven.org/maven2/com/intempt/sdk/intempt-android/` and
   `https://repo1.maven.org/maven2/com/intempt/sdk/intempt-push/`.

## Two artifacts

Since the push-notification module split (see `docs/MIGRATION.md`), this repository publishes
**two** artifacts from the same tag/version:

- `com.intempt.sdk:intempt-android` — the core SDK (`:app`).
- `com.intempt.sdk:intempt-push` — the optional push-notification module (`:push`), which depends
  on `:app` and is what a host app adds to keep push notifications working.

Both share the same `VERSION` (`gradle.properties`) and are released together; there is no
independent versioning for `:push` today. `publish.yml`'s `publishToMavenCentral` Gradle
invocation is unqualified (not scoped to `:app`), so it already runs against every subproject with
a `mavenPublishing { }` block — no separate workflow step is needed to publish the second artifact.

> **Known issue, needs a fix before this actually works end-to-end:** running
> `./gradlew :push:tasks` (or a build that configures both `:app` and `:push` in the same
> invocation) currently fails with
> `IllegalArgumentException: Cannot set the value of task ':push:createStagingRepository'
> property 'buildService' ...` — a `com.vanniktech.maven.publish` (0.28.0) conflict when the
> plugin's Sonatype staging-repository build service is registered from two subprojects in the
> same build. This needs to be resolved (a plugin upgrade past 0.28.0 was tried and did not clear
> it locally; further investigation is needed) before `publishToMavenCentral` can be run with
> `:push` in the project.

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
