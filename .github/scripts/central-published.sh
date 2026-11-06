#!/usr/bin/env bash
# Usage: central-published.sh <artifactId> <version>
# Exit 0 if com.intempt.sdk:<artifactId>:<version> is already published on Maven Central,
# exit 1 if it is not (or the answer is unknown, so the caller uploads and Central itself
# rejects a duplicate version). Used by publish.yml to make a re-run after a partial release safe.
set -uo pipefail
artifact="$1"
version="$2"
group="com.intempt.sdk"

# 1) Central Portal API: true as soon as the deployment is published, before the repo1 sync.
user="${ORG_GRADLE_PROJECT_mavenCentralUsername:-}"
pass="${ORG_GRADLE_PROJECT_mavenCentralPassword:-}"
if [ -n "$user" ] && [ -n "$pass" ]; then
  token=$(printf '%s:%s' "$user" "$pass" | base64 | tr -d '\n')
  body=$(curl -fsS --max-time 30 -H "Authorization: Bearer $token" \
    "https://central.sonatype.com/api/v1/publisher/published?namespace=${group}&name=${artifact}&version=${version}" 2>/dev/null) || body=""
  case "$body" in
    *'"published":true'*|*'"published": true'*) exit 0 ;;
  esac
fi

# 2) repo1 itself (covers releases that were published earlier or by hand).
code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 \
  "https://repo1.maven.org/maven2/${group//.//}/${artifact}/${version}/${artifact}-${version}.pom")
[ "$code" = "200" ] && exit 0
exit 1
