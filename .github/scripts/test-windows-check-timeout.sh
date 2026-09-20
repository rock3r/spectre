#!/usr/bin/env bash
# Contract tests for #499: Windows check jobs must time out before the 6h runner
# limit and dump live JVM stacks when cancelled or failed.
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
windows_workflow="$repo_root/.github/workflows/windows.yml"
validation_workflow="$repo_root/.github/workflows/validation-windows.yml"
dump_script="$repo_root/.github/scripts/dump-jvm-stacks.sh"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

[[ -f "$windows_workflow" ]] || fail "missing $windows_workflow"
[[ -f "$validation_workflow" ]] || fail "missing $validation_workflow"
[[ -f "$dump_script" ]] || fail "missing $dump_script"
[[ -x "$dump_script" ]] || fail "dump-jvm-stacks.sh must be executable"

bash -n "$dump_script" || fail "dump-jvm-stacks.sh failed bash -n"

grep -F -q 'timeout-minutes:' "$windows_workflow" || fail "windows.yml missing timeout-minutes"
grep -E -q 'timeout-minutes:[[:space:]]*[1-9][0-9]*' "$windows_workflow" || fail "windows.yml timeout-minutes is not a positive integer"
grep -F -q 'check-windows:' "$windows_workflow" || fail "windows.yml missing check-windows job"
# Job-level timeout must sit on check-windows, not only helper-tests.
awk '
  $0 ~ /^  check-windows:/ { in_job=1; found=0; next }
  in_job && $0 ~ /^  [A-Za-z0-9_-]+:/ && $0 !~ /^  check-windows:/ { exit found==1 ? 0 : 1 }
  in_job && $0 ~ /^    timeout-minutes:/ { found=1 }
  END { exit found==1 ? 0 : 1 }
' "$windows_workflow" || fail "check-windows job is missing timeout-minutes"

grep -F -q 'timeout-minutes:' "$validation_workflow" || fail "validation-windows.yml missing timeout-minutes"
grep -E -q 'timeout-minutes:[[:space:]]*[1-9][0-9]*' "$validation_workflow" || fail "validation-windows.yml timeout-minutes is not a positive integer"

for workflow in "$windows_workflow" "$validation_workflow"; do
  grep -F -q 'dump-jvm-stacks.sh' "$workflow" || fail "$(basename "$workflow") does not invoke dump-jvm-stacks.sh"
  grep -F -q 'cancelled()' "$workflow" || fail "$(basename "$workflow") dump step is not gated on cancelled()"
  grep -F -q 'failure()' "$workflow" || fail "$(basename "$workflow") dump step is not gated on failure()"
done

grep -F -q 'Thread.print' "$dump_script" || fail "dump-jvm-stacks.sh does not dump Thread.print"
grep -F -q 'jcmd' "$dump_script" || fail "dump-jvm-stacks.sh does not use jcmd"

echo "OK: Windows check hang diagnostics (#499) are wired"
