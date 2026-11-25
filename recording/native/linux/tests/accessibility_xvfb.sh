#!/usr/bin/env bash
set -euo pipefail

: "${FIXTURE_CHECKOUT:?point FIXTURE_CHECKOUT at a separate feature/accesskit-fixture checkout}"
helper=${SPECTRE_HELPER:-target/debug/spectre-wayland-helper}
fixture="$FIXTURE_CHECKOUT/fixtures/accesskit/target/debug/spectre-accesskit-fixture"
log=${RUNNER_TEMP:-/tmp}/spectre-accesskit-fixture-helper.log
second_log=${RUNNER_TEMP:-/tmp}/spectre-accesskit-fixture-helper-second.log

"$fixture" >"$log" 2>&1 &
pid=$!
second_pid=
cleanup() {
  kill "$pid" 2>/dev/null || true
  [[ -z $second_pid ]] || kill "$second_pid" 2>/dev/null || true
}
trap cleanup EXIT
for _ in {1..100}; do grep -q SPECTRE_ACCESSKIT_FIXTURE_READY "$log" && break; sleep .1; done
grep -q SPECTRE_ACCESSKIT_FIXTURE_READY "$log" || { cat "$log" >&2; exit 1; }

set_status() {
  gdbus call --session --dest org.a11y.Bus --object-path /org/a11y/bus \
    --method org.freedesktop.DBus.Properties.Set org.a11y.Status IsEnabled "<$1>" >/dev/null
}
status() {
  gdbus call --session --dest org.a11y.Bus --object-path /org/a11y/bus \
    --method org.freedesktop.DBus.Properties.Get org.a11y.Status IsEnabled
}
request() {
  printf '{"target":{"type":"process-id","value":%s},"maximumDepth":64,"maximumNodeCount":10000,"timeoutMs":5000,"allowAccessibilityEnable":%s}' "$pid" "$1" | "$helper" accessibility-snapshot
}
title_request() {
  local owner=${1-}
  local owner_json=
  [[ -z $owner ]] || owner_json=",\"processId\":$owner"
  printf '{"target":{"type":"window-title","value":"Spectre AccessKit Fixture"%s},"maximumDepth":64,"maximumNodeCount":10000,"timeoutMs":5000,"allowAccessibilityEnable":true}' "$owner_json" | "$helper" accessibility-snapshot
}

set_status false
disabled=$(request false)
python3 -c 'import json,sys; r=json.load(sys.stdin); assert not r["ok"] and r["error"]["code"]=="accessibility-disabled"' <<<"$disabled"
[[ $(status) == *false* ]]

started=$(date +%s%3N)
snapshot=$(request true)
elapsed_ms=$(( $(date +%s%3N) - started ))
[[ $(status) == *false* ]]
SNAPSHOT="$snapshot" python3 - <<'PY'
import json,os
r=json.loads(os.environ["SNAPSHOT"]); assert r["ok"], r
s=r["snapshot"]; assert s["schemaVersion"] == 2 and s["windows"][0]["boundsSpace"] == "screen"
nodes=s["nodes"]; by_id={}
for n in nodes: by_id.setdefault(n["id"],[]).append(n)
assert by_id["primary-button"][0]["platformRole"] == "push button"
assert "checked" in by_id["newsletter"][0]["states"]
assert by_id["account-name"][0]["value"] is None
assert "disabled" in by_id["disabled-button"][0]["states"]
assert by_id["offscreen-button"][0]["onScreen"] is False
assert len(by_id["repeated"]) == 3
keys=[n["key"] for n in nodes]; assert len(keys)==len(set(keys))
print(f"accessibility-helper-xvfb: nodes={len(nodes)}")
PY
echo "accessibility-helper-xvfb: elapsed_ms=$elapsed_ms"

limited=$(printf '{"target":{"type":"process-id","value":%s},"maximumDepth":64,"maximumNodeCount":2,"timeoutMs":5000,"allowAccessibilityEnable":true}' "$pid" | "$helper" accessibility-snapshot)
python3 -c 'import json,sys; r=json.load(sys.stdin); assert r["error"]["code"]=="limit-reached" and len(r["error"]["partialSnapshot"]["nodes"])==2, r' <<<"$limited"
[[ $(status) == *false* ]]

timed_out=$(printf '{"target":{"type":"process-id","value":%s},"maximumDepth":64,"maximumNodeCount":10000,"timeoutMs":1,"allowAccessibilityEnable":true}' "$pid" | "$helper" accessibility-snapshot)
python3 -c 'import json,sys; r=json.load(sys.stdin); assert r["error"]["code"]=="timeout", r' <<<"$timed_out"
[[ $(status) == *false* ]]

# Two independent applications own the same title. An unbound title must be ambiguous, while a
# PID-bound title may only inspect that process and must never fall through to the other app.
set_status true
"$fixture" >"$second_log" 2>&1 &
second_pid=$!
for _ in {1..100}; do grep -q SPECTRE_ACCESSKIT_FIXTURE_READY "$second_log" && break; sleep .1; done
grep -q SPECTRE_ACCESSKIT_FIXTURE_READY "$second_log" || { cat "$second_log" >&2; exit 1; }
ambiguous=$(title_request)
python3 -c 'import json,sys; r=json.load(sys.stdin); assert not r["ok"] and r["error"]["code"]=="target-not-found" and "ambiguous" in r["error"]["message"], r' <<<"$ambiguous"
owned=$(title_request "$pid")
python3 -c 'import json,sys; r=json.load(sys.stdin); assert r["ok"] and r["snapshot"]["windows"][0]["title"]=="Spectre AccessKit Fixture", r' <<<"$owned"
wrong_owner=$(title_request $$)
python3 -c 'import json,sys; r=json.load(sys.stdin); assert not r["ok"] and r["error"]["code"]=="target-not-found" and "owned by process" in r["error"]["message"], r' <<<"$wrong_owner"
set_status false
[[ $(status) == *false* ]]
echo accessibility-helper-xvfb: pass
