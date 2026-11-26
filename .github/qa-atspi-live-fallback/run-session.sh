#!/usr/bin/env bash
set -euo pipefail
: "${PREFETCH_HELPER:?}" "${ARTIFACTS:?}"
qa=${QA_ROOT:-$(cd "$(dirname "$0")" && pwd)}
mkdir -p "$ARTIFACTS"

run_provider() {
  local label=$1 mode=$2 helper=$3 expect=$4
  local ready="$ARTIFACTS/$label-ready.json" journal="$ARTIFACTS/$label-provider.jsonl"
  rm -f "$ready" "$journal"
  # The custom provider owns the GTK window's accessibility identity. Prevent GTK's
  # automatic bridge from registering a second owner for this same process.
  NO_AT_BRIDGE=1 GTK_MODULES= /usr/bin/python3 "$qa/provider.py" --ready "$ready" --journal "$journal" --late-mode "$mode" \
    >"$ARTIFACTS/$label-provider.stdout" 2>"$ARTIFACTS/$label-provider.stderr" &
  provider=$!
  trap 'kill ${monitor:-} ${provider:-} 2>/dev/null || true' EXIT
  for _ in {1..200}; do [[ -s $ready ]] && break; kill -0 "$provider"; sleep .05; done
  test -s "$ready"
  owner=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["owner"])' "$ready")
  dbus-monitor --address "$(gdbus call --session --dest org.a11y.Bus --object-path /org/a11y/bus --method org.a11y.Bus.GetAddress | sed "s/^('//;s/',)$//")" \
    "destination='$owner'" "sender='$owner'" >"$ARTIFACTS/$label-wire.log" 2>&1 &
  monitor=$!
  sleep .2
  /usr/bin/python3 "$qa/verify.py" --helper "$helper" --ready "$ready" --journal "$journal" \
    --artifacts "$ARTIFACTS" --label "$label" --expect "$expect" | tee "$ARTIFACTS/$label-final.stdout"
  kill "$monitor" "$provider" 2>/dev/null || true
  wait "$monitor" 2>/dev/null || true; wait "$provider" 2>/dev/null || true
  # Prove that the native reader itself (not only the independent raw probe) made
  # Cache.GetItems and received the real provider-side named failure.
  helper_sender=$(python3 -c 'import json,sys; r=json.load(open(sys.argv[1])); print(r["orderedProviderCalls"][0]["sender"])' "$ARTIFACTS/$label-receipt.json")
  grep -F "sender=$helper_sender -> destination=$owner" "$ARTIFACTS/$label-wire.log" | grep -F 'interface=org.a11y.atspi.Cache; member=GetItems' > "$ARTIFACTS/$label-reader-cache-call.txt"
  grep -E 'error .*org\.freedesktop\.DBus\.Error\.(UnknownObject|UnknownInterface|UnknownMethod)' "$ARTIFACTS/$label-wire.log" > "$ARTIFACTS/$label-cache-errors.txt"
  monitor= provider=
}

# Durable public reproduction is always produced first, before any candidate assertion.
run_provider public-fe9-normal normal "$PREFETCH_HELPER" prefetch
# The same public binary must visibly encounter both adversarial late siblings.
run_provider public-fe9-error error "$PREFETCH_HELPER" prefetch
run_provider public-fe9-slow slow "$PREFETCH_HELPER" prefetch
if [[ -n ${CORRECTED_HELPER:-} ]]; then
  run_provider corrected-normal-depth1 normal "$CORRECTED_HELPER" cutoff
  run_provider corrected-normal-full normal "$CORRECTED_HELPER" full
  run_provider corrected-error-depth1 error "$CORRECTED_HELPER" cutoff
  run_provider corrected-slow-depth1 slow "$CORRECTED_HELPER" cutoff
fi
