#!/usr/bin/env bash
# Usage: central-published.sh <artifactId> <version>
#        central-published.sh --wait <artifactId> <version> [timeout-seconds, default 2400]
#
# Check mode: exit 0 if com.intempt.sdk:<artifactId>:<version> is already published on Maven
# Central, 1 if it is not (or the answer is unknown: the caller then uploads, and Central itself
# rejects a duplicate version). publish.yml uses it to make a re-run after a partial release safe.
# Wait mode: poll until it is published; exit 0 when it is, 1 on timeout. publish.yml uses it after
# the upload, because the Central Portal validates and publishes asynchronously: without the wait,
# a green run could still end in a deployment that failed validation.
# Any usage error exits 2, which publish.yml treats as a failure (not as "not published").
set -uo pipefail

usage() { echo "usage: $0 [--wait] <artifactId> <version> [timeout-seconds]" >&2; exit 2; }

wait_mode=false
if [ "${1:-}" = "--wait" ]; then wait_mode=true; shift; fi
[ $# -ge 2 ] && [ -n "$1" ] && [ -n "$2" ] || usage
artifact="$1"
version="$2"
timeout="${3:-2400}"
case "$timeout" in ""|*[!0-9]*) usage ;; esac
group="com.intempt.sdk"

user="${ORG_GRADLE_PROJECT_mavenCentralUsername:-}"
pass="${ORG_GRADLE_PROJECT_mavenCentralPassword:-}"
token=""
if [ -n "$user" ] && [ -n "$pass" ]; then
  token=$(printf '%s:%s' "$user" "$pass" | base64 | tr -d '\n')
  # The derived token is not a GitHub secret, so mask it explicitly in the log.
  [ -n "${GITHUB_ACTIONS:-}" ] && echo "::add-mask::$token"
fi

is_published() {
  # 1) Central Portal API: true as soon as the deployment is published, before the repo1 sync.
  #    The header goes in on stdin (-H @-), so the token never appears on the process argv.
  if [ -n "$token" ]; then
    local body
    body=$(printf 'Authorization: Bearer %s\n' "$token" | curl -fsS --max-time 30 -H @- \
      "https://central.sonatype.com/api/v1/publisher/published?namespace=${group}&name=${artifact}&version=${version}" 2>/dev/null) || body=""
    case "$body" in
      *'"published":true'*|*'"published": true'*) return 0 ;;
    esac
  fi
  # 2) repo1 itself (covers releases that were published earlier or by hand).
  local code
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 \
    "https://repo1.maven.org/maven2/${group//.//}/${artifact}/${version}/${artifact}-${version}.pom")
  [ "$code" = "200" ]
}

if ! $wait_mode; then
  is_published && exit 0
  exit 1
fi

deadline=$(( $(date +%s) + timeout ))
while :; do
  if is_published; then
    echo "${group}:${artifact}:${version} is published on Maven Central."
    exit 0
  fi
  if [ "$(date +%s)" -ge "$deadline" ]; then
    echo "::error::${group}:${artifact}:${version} was uploaded but is not published after ${timeout}s. Check the deployment on central.sonatype.com (Deployments): it either failed validation or is still publishing. Re-run only once it has settled (see RELEASING.md)."
    exit 1
  fi
  sleep 30
done
