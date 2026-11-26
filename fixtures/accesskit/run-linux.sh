#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
fixture=fixtures/accesskit/target/debug/spectre-accesskit-fixture
log=${RUNNER_TEMP:-/tmp}/spectre-accesskit-fixture.log
status_enabled() {
  gdbus call --session --dest org.a11y.Bus --object-path /org/a11y/bus \
    --method org.freedesktop.DBus.Properties.Get org.a11y.Status IsEnabled | grep -qi true
}
set_enabled() {
  gdbus call --session --dest org.a11y.Bus --object-path /org/a11y/bus \
    --method org.freedesktop.DBus.Properties.Set org.a11y.Status IsEnabled "<$1>" >/dev/null
}
set_enabled false
"$fixture" >"$log" 2>&1 &
pid=$!
trap 'kill "$pid" 2>/dev/null || true' EXIT
for _ in {1..100}; do grep -q SPECTRE_ACCESSKIT_FIXTURE_READY "$log" && break; sleep .1; done
grep -q SPECTRE_ACCESSKIT_FIXTURE_READY "$log" || { cat "$log"; exit 1; }
/usr/bin/python3 fixtures/accesskit/readers/linux.py --enable --golden fixtures/accesskit/golden/linux.json
if status_enabled; then echo "reader did not restore org.a11y.Status.IsEnabled=false" >&2; exit 1; fi
set_enabled false
set +e
output=$(/usr/bin/python3 fixtures/accesskit/readers/linux.py --golden fixtures/accesskit/golden/linux.json 2>&1); status=$?
set -e
[[ $status -eq 2 && "$output" == *accessibility-disabled* ]] || { printf '%s\n' "$output"; exit 1; }
if status_enabled; then echo "refusal path changed org.a11y.Status.IsEnabled" >&2; exit 1; fi
printf '%s\n' 'accesskit-fixture-linux-disabled: pass'
