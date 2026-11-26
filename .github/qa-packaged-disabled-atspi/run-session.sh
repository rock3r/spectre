#!/usr/bin/env bash
set -euo pipefail
: "${QA_DIR:?}" "${QA_ARTIFACTS:?}" "${PACKAGED_CLI:?}"
mkdir -p "$QA_ARTIFACTS" "$RUNNER_TEMP/qa-disabled-home" "$RUNNER_TEMP/qa-disabled-runtime" "$RUNNER_TEMP/qa-disabled-sockets"
chmod 700 "$RUNNER_TEMP/qa-disabled-home" "$RUNNER_TEMP/qa-disabled-runtime" "$RUNNER_TEMP/qa-disabled-sockets"
export HOME="$RUNNER_TEMP/qa-disabled-home" XDG_RUNTIME_DIR="$RUNNER_TEMP/qa-disabled-runtime"
export SPECTRE_DAEMON_SOCKET_DIR="$RUNNER_TEMP/qa-disabled-sockets"
export NO_AT_BRIDGE=0 GTK_MODULES=gail:atk-bridge

target_pid= decoy_pid= xvfb_pid= wm_pid=
cleanup() {
  set +e
  "$PACKAGED_CLI" daemon kill >"$QA_ARTIFACTS/daemon-kill.log" 2>&1
  kill ${target_pid:-} ${decoy_pid:-} ${wm_pid:-} ${xvfb_pid:-} 2>/dev/null
  wait ${target_pid:-} ${decoy_pid:-} ${wm_pid:-} ${xvfb_pid:-} 2>/dev/null
  rm -rf "$HOME" "$XDG_RUNTIME_DIR" "$SPECTRE_DAEMON_SOCKET_DIR"
}
trap cleanup EXIT INT TERM

Xvfb :91 -screen 0 1280x720x24 >"$QA_ARTIFACTS/xvfb.log" 2>&1 & xvfb_pid=$!
export DISPLAY=:91
for _ in {1..100}; do xdpyinfo >/dev/null 2>&1 && break; sleep .05; done
xdpyinfo >/dev/null
openbox >"$QA_ARTIFACTS/openbox.log" 2>&1 & wm_pid=$!

set_flag() {
  gdbus call --session --dest org.a11y.Bus --object-path /org/a11y/bus \
    --method org.freedesktop.DBus.Properties.Set org.a11y.Status IsEnabled "<$1>"
}
read_flag() {
  gdbus call --session --dest org.a11y.Bus --object-path /org/a11y/bus \
    --method org.freedesktop.DBus.Properties.Get org.a11y.Status IsEnabled
}
# Establish and record the disabled baseline before any fixture/provider setup.
set_flag false >"$QA_ARTIFACTS/pre-fixture-set-disabled.txt" 2>&1
read_flag >"$QA_ARTIFACTS/pre-fixture-read-disabled.txt" 2>&1
grep -Eq '^\(<false>,?\)$' "$QA_ARTIFACTS/pre-fixture-read-disabled.txt"

# This setup-only enable proves the independent real GTK providers register on the private bus.
set_flag true >"$QA_ARTIFACTS/fixture-activation.txt" 2>&1
/usr/bin/python3 "$QA_DIR/fixture.py" --title 'Spectre owned target' --marker 'SPECTRE_PRIVATE_TARGET_6f29' --ready "$QA_ARTIFACTS/target-ready.json" >"$QA_ARTIFACTS/target.log" 2>&1 & target_pid=$!
/usr/bin/python3 "$QA_DIR/fixture.py" --title 'Spectre live decoy' --marker 'SPECTRE_PRIVATE_DECOY_81c4' --ready "$QA_ARTIFACTS/decoy-ready.json" >"$QA_ARTIFACTS/decoy.log" 2>&1 & decoy_pid=$!
for _ in {1..200}; do [[ -s "$QA_ARTIFACTS/target-ready.json" && -s "$QA_ARTIFACTS/decoy-ready.json" ]] && break; sleep .05; done
kill -0 "$target_pid"; kill -0 "$decoy_pid"

/usr/bin/python3 - "$QA_ARTIFACTS/atspi-registration.json" <<'PY'
import json, sys, time
import pyatspi
wanted = {"Spectre owned target", "Spectre live decoy"}
deadline = time.monotonic() + 10
seen = set()
while time.monotonic() < deadline:
    desktop = pyatspi.Registry.getDesktop(0)
    seen = {node.name for app in desktop for node in (app, *tuple(app)) if node.name}
    if wanted <= seen:
        break
    time.sleep(.05)
if not wanted <= seen:
    raise SystemExit(f"fixtures did not register with AT-SPI: wanted={wanted!r}, seen={seen!r}")
with open(sys.argv[1], "w") as stream:
    json.dump({"wanted": sorted(wanted), "seen": sorted(seen)}, stream)
    stream.write("\n")
PY
# Every public invocation begins disabled; setup activation is not product evidence.
set_flag false >"$QA_ARTIFACTS/post-fixture-set-disabled.txt" 2>&1
read_flag >"$QA_ARTIFACTS/post-fixture-read-disabled.txt" 2>&1
grep -Eq '^\(<false>,?\)$' "$QA_ARTIFACTS/post-fixture-read-disabled.txt"
/usr/bin/python3 "$QA_DIR/verify.py" --cli "$PACKAGED_CLI" --target-ready "$QA_ARTIFACTS/target-ready.json" --decoy-ready "$QA_ARTIFACTS/decoy-ready.json" --artifacts "$QA_ARTIFACTS"
