#!/usr/bin/env bash
# Dump thread stacks for every live JVM before the runner kills orphans (#499).
#
# Used from Windows check/validation workflows on cancel or failure so the next
# hang names the stuck Gradle daemon or test worker instead of only the client.
set -u

echo "===== Live JVMs (#499 hang diagnosis) ====="

if command -v jps >/dev/null 2>&1; then
  jps -lm || true
elif command -v jcmd >/dev/null 2>&1; then
  jcmd -l || true
else
  echo "neither jps nor jcmd is on PATH; cannot list JVMs"
  exit 0
fi

# An unresponsive JVM can block attach forever. Bound each PID so later
# processes still dump inside the leftover job budget (#499).
DUMP_PID_TIMEOUT_SECONDS="${DUMP_PID_TIMEOUT_SECONDS:-20}"

run_with_timeout() {
  if command -v timeout >/dev/null 2>&1; then
    timeout --signal=KILL "${DUMP_PID_TIMEOUT_SECONDS}" "$@" || true
    return
  fi
  python3 - "${DUMP_PID_TIMEOUT_SECONDS}" "$@" <<'PY' || true
import subprocess
import sys

seconds = int(sys.argv[1])
cmd = sys.argv[2:]
try:
    subprocess.run(cmd, timeout=seconds, check=False)
except subprocess.TimeoutExpired:
    print(f"timed out after {seconds}s: {' '.join(cmd)}", file=sys.stderr)
except FileNotFoundError:
    pass
PY
}

dump_pid() {
  local pid="$1"
  local label="${2:-}"
  echo "===== Thread.print pid=${pid} ${label} ====="
  if command -v jcmd >/dev/null 2>&1; then
    run_with_timeout jcmd "${pid}" Thread.print
    return
  fi
  if command -v jstack >/dev/null 2>&1; then
    run_with_timeout jstack "${pid}"
    return
  fi
  echo "neither jcmd nor jstack is on PATH; cannot dump pid ${pid}"
}

if command -v jcmd >/dev/null 2>&1; then
  jcmd -l 2>/dev/null | while read -r pid rest; do
    case "${pid}" in
      ''|*[!0-9]*) continue ;;
    esac
    dump_pid "${pid}" "${rest}"
  done
elif command -v jps >/dev/null 2>&1; then
  jps -lm 2>/dev/null | while read -r pid rest; do
    case "${pid}" in
      ''|*[!0-9]*) continue ;;
    esac
    dump_pid "${pid}" "${rest}"
  done
fi
