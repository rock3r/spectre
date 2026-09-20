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

dump_pid() {
  local pid="$1"
  local label="${2:-}"
  echo "===== Thread.print pid=${pid} ${label} ====="
  if command -v jcmd >/dev/null 2>&1; then
    jcmd "${pid}" Thread.print || true
    return
  fi
  if command -v jstack >/dev/null 2>&1; then
    jstack "${pid}" || true
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
