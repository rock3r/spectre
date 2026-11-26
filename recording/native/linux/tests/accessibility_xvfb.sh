#!/usr/bin/env bash
set -euo pipefail

: "${FIXTURE_CHECKOUT:?point FIXTURE_CHECKOUT at the checkout containing fixtures/accesskit}"
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
import json,os,pathlib
r=json.loads(os.environ["SNAPSHOT"]); assert r["ok"], r
s=r["snapshot"]; assert s["schemaVersion"] == 2 and s["windows"][0]["boundsSpace"] == "screen"
nodes=s["nodes"]; by_id={}
for n in nodes: by_id.setdefault(n["id"],[]).append(n)
golden=json.loads((pathlib.Path(os.environ["FIXTURE_CHECKOUT"])/"fixtures/accesskit/golden/linux.json").read_text())
assert by_id["primary-button"][0]["platformRole"] == golden["buttonRole"]["name"]
assert by_id["primary-button"][0]["role"] == "button"
assert "checked" in by_id["newsletter"][0]["states"]
assert by_id["newsletter"][0]["platformRole"] == "check box"
assert by_id["newsletter"][0]["role"] == "checkbox"
assert by_id["account-name"][0]["platformRole"] == golden["textRole"]["name"]
assert by_id["account-name"][0]["role"] == "entry"
assert by_id["account-name"][0]["value"] is None
# Both nodes reported [1124073728, 0] and no Action interface in the direct D-Bus probe.
# Only the first is disabled in the fixture source. Preserve the states the provider exposes.
for identifier, name in [("disabled-button", "Unavailable action"), ("offscreen-button", "Scrolled away")]:
    node, = by_id[identifier]
    assert (node["name"], node["role"], set(node["states"])) == (
        name, "button", {"enabled", "sensitive", "showing", "visible"}
    ), node
assert by_id["offscreen-button"][0]["onScreen"] is False
assert len(by_id["repeated"]) == 3
keys=[n["key"] for n in nodes]; assert len(keys)==len(set(keys))
print(f"accessibility-helper-xvfb: nodes={len(nodes)}")
PY
echo "accessibility-helper-xvfb: elapsed_ms=$elapsed_ms"

root_only=$(printf '{"target":{"type":"process-id","value":%s},"maximumDepth":64,"maximumNodeCount":1,"timeoutMs":5000,"allowAccessibilityEnable":true}' "$pid" | "$helper" accessibility-snapshot)
python3 -c 'import json,sys; r=json.load(sys.stdin); assert r["error"]["code"]=="limit-reached" and len(r["error"]["partialSnapshot"]["nodes"])==1, r' <<<"$root_only"
[[ $(status) == *false* ]]

limited=$(printf '{"target":{"type":"process-id","value":%s},"maximumDepth":64,"maximumNodeCount":2,"timeoutMs":5000,"allowAccessibilityEnable":true}' "$pid" | "$helper" accessibility-snapshot)
python3 -c 'import json,sys; r=json.load(sys.stdin); assert r["error"]["code"]=="limit-reached" and len(r["error"]["partialSnapshot"]["nodes"])==2, r' <<<"$limited"
[[ $(status) == *false* ]]

timed_out=$(printf '{"target":{"type":"process-id","value":%s},"maximumDepth":64,"maximumNodeCount":10000,"timeoutMs":1,"allowAccessibilityEnable":true}' "$pid" | "$helper" accessibility-snapshot)
python3 -c 'import json,sys; r=json.load(sys.stdin); assert r["error"]["code"]=="timeout", r' <<<"$timed_out"
[[ $(status) == *false* ]]

# A normally enabled registry with one title remains immediately selectable without an owner.
set_status true
unique=$(title_request)
python3 -c 'import json,sys; r=json.load(sys.stdin); assert r["ok"] and r["snapshot"]["windows"][0]["title"]=="Spectre AccessKit Fixture", r' <<<"$unique"

# Exercise the near-deadline owner-absence result repeatedly while the registry is already enabled.
# A completed enumeration is authoritative and must not be replaced by a final expiring D-Bus call.
for _ in {1..30}; do
  wrong_owner=$(title_request $$)
  python3 -c 'import json,sys; r=json.load(sys.stdin); assert not r["ok"] and r["error"]["code"]=="target-not-found", r' <<<"$wrong_owner"
done

# Start an independent same-title provider after the cold request has begun. The first fixture
# registers first, but the unbound lookup must remain closed while the registry is still filling.
set_status false
( sleep .2; exec "$fixture" ) >"$second_log" 2>&1 &
second_pid=$!
ambiguous=$(title_request)
python3 -c 'import json,sys; r=json.load(sys.stdin); assert not r["ok"] and r["error"]["code"]=="target-not-found" and "ambiguous" in r["error"]["message"], r' <<<"$ambiguous"
for _ in {1..100}; do grep -q SPECTRE_ACCESSKIT_FIXTURE_READY "$second_log" && break; sleep .1; done
grep -q SPECTRE_ACCESSKIT_FIXTURE_READY "$second_log" || { cat "$second_log" >&2; exit 1; }
[[ $(status) == *false* ]]

# A PID-bound title may only inspect its owner. Repeat cold enable/registration/restore as well.
owned=$(title_request "$pid")
python3 -c 'import json,sys; r=json.load(sys.stdin); assert r["ok"] and r["snapshot"]["windows"][0]["title"]=="Spectre AccessKit Fixture", r' <<<"$owned"
[[ $(status) == *false* ]]
for _ in {1..30}; do
  wrong_owner=$(title_request $$)
  python3 -c 'import json,sys; r=json.load(sys.stdin); assert not r["ok"] and r["error"]["code"]=="target-not-found" and "owned by process" in r["error"]["message"], r' <<<"$wrong_owner"
  [[ $(status) == *false* ]]
done

set_status true
/usr/bin/python3 tests/accessibility_gtk.py "$helper"
set_status false
[[ $(status) == *false* ]]
echo accessibility-helper-xvfb: pass
