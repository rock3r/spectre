#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
fixture=fixtures/accesskit/target/debug/spectre-accesskit-fixture
"$fixture" >"${RUNNER_TEMP:-/tmp}/spectre-accesskit-fixture.log" 2>&1 &
pid=$!
trap 'kill "$pid" 2>/dev/null || true' EXIT
for _ in {1..100}; do grep -q SPECTRE_ACCESSKIT_FIXTURE_READY "${RUNNER_TEMP:-/tmp}/spectre-accesskit-fixture.log" && break; sleep .1; done
/usr/bin/python3 fixtures/accesskit/readers/linux.py --enable --golden fixtures/accesskit/golden/linux.json
# The refusal cell is real only after forcing the session setting off.
gdbus call --session --dest org.a11y.Bus --object-path /org/a11y/bus --method org.freedesktop.DBus.Properties.Set org.a11y.Status IsEnabled '<false>' >/dev/null
set +e
output=$('/usr/bin/python3' fixtures/accesskit/readers/linux.py --golden fixtures/accesskit/golden/linux.json 2>&1); status=$?
set -e
[[ $status -eq 2 && "$output" == *accessibility-disabled* ]] || { printf '%s\n' "$output"; exit 1; }
printf '%s\n' 'accesskit-fixture-linux-disabled: pass'
