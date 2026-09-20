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

# Job timeout cancels the whole job; dump after that is not reliable. The Gradle
# step that can hang must time out first so cancelled()/failure() still has job
# budget for dump-jvm-stacks.sh.
assert_step_timeout_below_job() {
  local workflow="$1"
  local job="$2"
  local step_name="$3"
  python3 - "$workflow" "$job" "$step_name" <<'PY' || fail "$3 step timeout is missing or not below the $2 job timeout"
import re
import sys

path, job, step = sys.argv[1], sys.argv[2], sys.argv[3]
lines = open(path, encoding="utf-8").read().splitlines()
in_job = False
job_timeout = None
in_target_step = False
step_timeout = None
job_re = re.compile(rf"^  {re.escape(job)}:")
next_job_re = re.compile(r"^  [A-Za-z0-9_-]+:")
job_timeout_re = re.compile(r"^    timeout-minutes:\s*(\d+)\s*$")
step_name_re = re.compile(r"^      - name:\s*(.+)\s*$")
step_timeout_re = re.compile(r"^        timeout-minutes:\s*(\d+)\s*$")

for line in lines:
    if job_re.match(line):
        in_job = True
        continue
    if in_job and next_job_re.match(line) and not job_re.match(line):
        break
    if not in_job:
        continue
    job_match = job_timeout_re.match(line)
    if job_match:
        job_timeout = int(job_match.group(1))
        continue
    step_match = step_name_re.match(line)
    if step_match:
        in_target_step = step_match.group(1).strip() == step
        continue
    if in_target_step:
        step_match = step_timeout_re.match(line)
        if step_match:
            step_timeout = int(step_match.group(1))

if job_timeout is None:
    sys.exit(f"{path} job {job} missing timeout-minutes")
if step_timeout is None:
    sys.exit(f"{path} step {step!r} missing timeout-minutes")
if step_timeout >= job_timeout:
    sys.exit(f"{path} step {step!r} timeout {step_timeout} must be < job {job_timeout}")
print(f"OK {job}/{step}: step {step_timeout} < job {job_timeout}")
PY
}

assert_step_timeout_below_job "$windows_workflow" "check-windows" "Run checks"
assert_step_timeout_below_job "$validation_workflow" "validation-windows" "Run validation tests"

echo "OK: Windows check hang diagnostics (#499) are wired"
