#!/bin/bash
# Falsification harness.
#
# Breaks one line of production code, runs the tests that claim to cover it, and reports
# whether they noticed. A test that still PASSES against broken code is hollow — that is the
# whole signal. Restores the file unconditionally, including on interrupt.
#
# Usage: scripts/falsify.sh <label> <prod-file> "OLD===NEW" <gradle --tests filter>
#
#   OLD===NEW is a literal, first-occurrence replacement applied to <prod-file>.
#   <prod-file> is relative to the repo root.
#
# Example — does anything notice if the delete-versus-retry policy stops working?
#
#   scripts/falsify.sh "retry policy dead" \
#     app/src/main/java/com/intempt/core/queue/HttpStatusPolicy.java \
#     "return status == 408 || status == 429 || (status >= 500 && status <= 599) || status <= 0;===return false;" \
#     "*HttpStatusPolicyTest"
#
# Verdicts:
#   SOUND       the tests failed, so they are sensitive to this behaviour
#   HOLLOW      the tests PASSED against broken code — the finding
#   INVALID     the mutation did not compile, or exited non-zero with no test failure
#   ANCHOR-MISS the OLD text was not found, so nothing was mutated
#
# Why this exists: every hollow assertion this repo has had passed CI and showed as covered.
# Coverage says a line ran. This says the tests would notice if it changed. The five found on
# this branch include a status sweep that printed its findings instead of asserting them, and
# four retry tests that could not distinguish a correct retry from a worker that never ran.
#
# Serial by design: concurrent Gradle daemons contend for memory and are slower than one run
# twice, and a targeted single-suite run is ~1 min against ~7 min for the full suite.

set -uo pipefail

WT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG="${FALSIFY_LOG:-$WT/build/falsify-results.txt}"
mkdir -p "$(dirname "$LOG")"

LABEL="$1"; PROD="$2"; EXPR="$3"; FILTER="$4"
FULL="$WT/$PROD"
BACKUP="$(mktemp)"

cleanup() { cp "$BACKUP" "$FULL"; rm -f "$BACKUP"; }
trap cleanup EXIT INT TERM

cp "$FULL" "$BACKUP"

# Apply the mutation. Fail loudly if the anchor is absent — a silent no-op mutation would
# report every test as "hollow" when nothing was actually broken. That is the one way this
# harness could lie, so it is checked rather than assumed.
python3 - "$FULL" "$EXPR" <<'PY'
import sys
path, expr = sys.argv[1], sys.argv[2]
old, new = expr.split("===", 1)
s = open(path).read()
if old not in s:
    sys.stderr.write("ANCHOR NOT FOUND: %r\n" % old[:120])
    sys.exit(3)
open(path, "w").write(s.replace(old, new, 1))
PY
if [ $? -ne 0 ]; then
  echo "$LABEL | ANCHOR-MISS | mutation never applied, result meaningless" | tee -a "$LOG"
  exit 3
fi

cd "$WT" || exit 1
OUT="$(./gradlew :app:testDebugUnitTest --tests "$FILTER" 2>&1)"
STATUS=$?

FAILED="$(echo "$OUT" | grep -cE "^[a-zA-Z].* > .* FAILED")"
RAN="$(echo "$OUT" | grep -oE "[0-9]+ tests completed" | head -1)"

if echo "$OUT" | grep -qE "^e: |error: |Compilation error|Execution failed for task '.*compile"; then
  # A build failure exits non-zero exactly like a failing test. Without this branch the harness
  # reports "SOUND — caught it" for a mutation that never even compiled, which is the one way a
  # falsification run can flatter itself.
  VERDICT="INVALID   | mutation did not compile, proves nothing"
elif [ $STATUS -eq 0 ]; then
  VERDICT="HOLLOW    | tests PASSED against broken code ($RAN)"
elif [ "$FAILED" -eq 0 ]; then
  VERDICT="INVALID   | non-zero exit but no test reported FAILED ($RAN)"
else
  VERDICT="SOUND     | caught it ($FAILED of $RAN failed)"
fi

echo "$LABEL | $VERDICT" | tee -a "$LOG"
