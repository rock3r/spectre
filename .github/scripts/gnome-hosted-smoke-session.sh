#!/usr/bin/env bash
# Runs as the unprivileged PAM/logind user created by gnome-hosted-smoke.sh.
set -Eeuo pipefail

: "${SMOKE_ARTIFACTS:?}"
: "${SMOKE_REPO:?}"

export XDG_SESSION_TYPE=wayland
export XDG_CURRENT_DESKTOP=GNOME
export XDG_SESSION_DESKTOP=gnome
export JAVA_HOME="${SMOKE_JAVA_HOME:?}"
export PATH="$JAVA_HOME/bin:$PATH"
if [[ "${1:-}" != --inside-dbus ]]; then
  exec dbus-run-session -- "$0" --inside-dbus
fi

session_diagnostics() {
  local status=$?
  set +e
  loginctl session-status "$session_id" >"$SMOKE_ARTIFACTS/login-session.txt" 2>&1
  busctl --user list >"$SMOKE_ARTIFACTS/user-bus.txt" 2>&1
  pw-dump >"$SMOKE_ARTIFACTS/pw-dump.json" 2>"$SMOKE_ARTIFACTS/pw-dump.stderr"
  gnome-extensions list --enabled >"$SMOKE_ARTIFACTS/enabled-extensions.txt" 2>&1
  cp -a /tmp/spectre-mutter-window-smoke "$SMOKE_ARTIFACTS/" 2>/dev/null
  if [[ -s "$SMOKE_ARTIFACTS/helper.pid" ]]; then
    kill "$(cat "$SMOKE_ARTIFACTS/helper.pid")" 2>/dev/null || true
  fi
  jobs -p | xargs -r kill
  wait || true
  exit "$status"
}

session_id="$(loginctl list-sessions --no-legend | awk -v uid="$(id -u)" '$2 == uid {print $1; exit}')"
[[ -n "$session_id" ]] || { echo "No real logind session for uid $(id -u)" >&2; exit 1; }
[[ "$(id -u)" -ne 0 ]] || { echo "Refusing root desktop session" >&2; exit 1; }
trap session_diagnostics EXIT

export XDG_SESSION_TYPE=wayland
export XDG_CURRENT_DESKTOP=GNOME
export XDG_SESSION_DESKTOP=gnome
export LIBGL_ALWAYS_SOFTWARE=1
export GDK_BACKEND=wayland
export QT_QPA_PLATFORM=wayland
export SPECTRE_CAPTURE_BACKEND=wayland
export NO_AT_BRIDGE=0

if [[ "$SMOKE_MODE" == mutter-control ]]; then
  extension_uuid=spectre-mutter-control-verification@sebastiano.dev
  extension_source="$(dirname "$0")/../gnome-shell-extensions/$extension_uuid"
else
  extension_uuid=spectre-window-identity@sebastiano.dev
  extension_source="$SMOKE_REPO/recording/native/linux/gnome-shell-extension/$extension_uuid"
fi
extension_target="$HOME/.local/share/gnome-shell/extensions/$extension_uuid"
mkdir -p "$(dirname "$extension_target")"
cp -a "$extension_source" "$extension_target"
gsettings set org.gnome.shell disable-user-extensions false
gsettings set org.gnome.shell enabled-extensions "['$extension_uuid']"

pipewire >"$SMOKE_ARTIFACTS/pipewire.log" 2>&1 &
wireplumber >"$SMOKE_ARTIFACTS/wireplumber.log" 2>&1 &
gnome-shell --wayland --headless --virtual-monitor 1280x720 \
  >"$SMOKE_ARTIFACTS/gnome-shell.log" 2>&1 &
shell_pid=$!

for _ in {1..60}; do
  WAYLAND_DISPLAY="$(find "${XDG_RUNTIME_DIR:?}" -maxdepth 1 -type s -name 'wayland-*' -printf '%f\n' | head -1)"
  if [[ -n "$WAYLAND_DISPLAY" ]] &&
    timeout 1s gdbus wait --session org.gnome.Mutter.ScreenCast; then
    break
  fi
  kill -0 "$shell_pid" || { echo "gnome-shell exited before readiness" >&2; exit 1; }
  sleep 1
done
export WAYLAND_DISPLAY
# The JDK uses Xwayland for AWT. Mutter publishes its display sockets and cookie in the VM.
for _ in {1..30}; do
  display_socket=$(find /tmp/.X11-unix -maxdepth 1 -type s -name 'X[0-9]*' 2>/dev/null | sort | head -1 || :)
  xcookie=$(find "$XDG_RUNTIME_DIR" -maxdepth 1 -type f -iname '*xwaylandauth*' | head -1)
  if [[ -n "$display_socket" && -n "$xcookie" ]]; then
    export DISPLAY=":${display_socket##*/X}"
    export XAUTHORITY="$xcookie"
    xdpyinfo >"$SMOKE_ARTIFACTS/xwayland-display.txt" 2>&1 && break
  fi
  sleep 1
done
xdpyinfo >/dev/null
dbus-update-activation-environment --systemd XDG_CURRENT_DESKTOP XDG_SESSION_TYPE WAYLAND_DISPLAY DISPLAY XAUTHORITY
[[ -n "$WAYLAND_DISPLAY" ]] || { echo "Mutter did not create a Wayland socket" >&2; exit 1; }
timeout 5s gdbus wait --session org.gnome.Mutter.ScreenCast
gnome-extensions enable "$extension_uuid"
gnome-extensions list --enabled | grep -Fx "$extension_uuid"
if [[ "$SMOKE_MODE" == mutter-control ]]; then
  timeout 10s gdbus wait --session dev.sebastiano.Spectre.MutterControlVerification
fi
pw-cli info 0 >"$SMOKE_ARTIFACTS/pipewire-core.txt"

# GTK4 portal dialogs expose their native Wayland tree only when the session's
# accessibility bus is enabled. Do this before either grant, and wait for both
# the public broker and GNOME implementation rather than relying on activation
# races hidden inside the test client.
if [[ "$SMOKE_MODE" == portal || "$SMOKE_MODE" == capture ]]; then
  gdbus call --session --dest org.a11y.Bus --object-path /org/a11y/bus \
    --method org.freedesktop.DBus.Properties.Set org.a11y.Status IsEnabled '<true>'
  timeout 15s gdbus wait --session --activate org.freedesktop.portal.Desktop
  timeout 15s gdbus wait --session --activate org.freedesktop.impl.portal.desktop.gnome
fi

if [[ "$SMOKE_MODE" == portal ]]; then
  /usr/bin/python3 "$(dirname "$0")/gnome-portal-dialog-warmup.py" \
    --mode portal --artifacts "$SMOKE_ARTIFACTS" \
    2>&1 | tee "$SMOKE_ARTIFACTS/portal-public-flow.log"
  exit 0
fi

if [[ "$SMOKE_MODE" == identity ]]; then
  export GDK_BACKEND=x11
  /usr/bin/python3 "$(dirname "$0")/gnome-window-identity-probe.py" \
    2>&1 | tee "$SMOKE_ARTIFACTS/identity-probe.log"
  exit 0
fi

if [[ "$SMOKE_MODE" == mutter-control ]]; then
  export GDK_BACKEND=x11
  /usr/bin/python3 "$(dirname "$0")/gnome-mutter-control-probe.py" \
    2>&1 | tee "$SMOKE_ARTIFACTS/mutter-control-probe.log"
  exit 0
fi

if [[ "$SMOKE_MODE" == accessibility ]]; then
  cd "$ATSPI_REPO/recording/native/linux"
  cargo build --locked --manifest-path "$FIXTURE_CHECKOUT/fixtures/accesskit/Cargo.toml"
  cargo build --locked
  python3 - tests/accessibility_xvfb.sh "$SMOKE_ARTIFACTS/atspi-wayland.sh" <<'PYTHON'
from pathlib import Path
import sys
src = Path(sys.argv[1]).read_text()
x11 = 's["windows"][0]["boundsSpace"] == "screen"'
assert src.count(x11) == 1
Path(sys.argv[2]).write_text(src.replace(x11, x11.replace('"screen"', '"window"')))
PYTHON
  cat > "$SMOKE_ARTIFACTS/reader-with-trace" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
trace=$(mktemp -d "$SMOKE_ARTIFACTS/atspi-call-XXXXXX")
tee "$trace/request.json" | "$ATSPI_REPO/recording/native/linux/target/debug/spectre-wayland-helper" "$@" | tee "$trace/result.json"
SH
  chmod +x "$SMOKE_ARTIFACTS/reader-with-trace"
  unset DISPLAY
  export GDK_BACKEND=wayland GTK_A11Y=atspi
  printf 'fixture transport: WAYLAND_DISPLAY=%s; DISPLAY=%s\n' "$WAYLAND_DISPLAY" "${DISPLAY-unset}" | tee "$SMOKE_ARTIFACTS/fixture-backend.txt"
  SPECTRE_HELPER="$SMOKE_ARTIFACTS/reader-with-trace" bash "$SMOKE_ARTIFACTS/atspi-wayland.sh" 2>&1 | tee "$SMOKE_ARTIFACTS/atspi-live.log"
  gdbus call --session --dest org.a11y.Bus --object-path /org/a11y/bus \
    --method org.freedesktop.DBus.Properties.Set org.a11y.Status IsEnabled '<true>'
  "$FIXTURE_CHECKOUT/fixtures/accesskit/target/debug/spectre-accesskit-fixture" >"$SMOKE_ARTIFACTS/compare-fixture.log" 2>&1 &
  fixture_pid=$!
  zenity --question --title="Spectre stock GTK proof" --text="Verify the stock GTK dialog" \
    --ok-label="Complete proof" --cancel-label="Cancel proof" >"$SMOKE_ARTIFACTS/stock-gtk-app.log" 2>&1 &
  gtk_pid=$!
  python3 - "$gtk_pid" "$SMOKE_ARTIFACTS" "$ATSPI_REPO" <<'PYTHON'
import json, pathlib, subprocess, sys, time
pid, artifacts, reader = sys.argv[1:]
request = {"target":{"type":"process-id","value":int(pid)},"maximumDepth":64,"maximumNodeCount":10000,"timeoutMs":5000,"allowAccessibilityEnable":False}
command = [reader + "/recording/native/linux/target/debug/spectre-wayland-helper", "accessibility-snapshot"]
for attempt in range(5):
    run = subprocess.run(command, input=json.dumps(request), capture_output=True, text=True, check=True)
    result = json.loads(run.stdout)
    pathlib.Path(artifacts, "stock-gtk-snapshot.json").write_text(run.stdout)
    if result["ok"]:
        break
    time.sleep(1)
assert result["ok"], result
snapshot = result["snapshot"]
window = snapshot["windows"][0]
assert window["title"] == "Spectre stock GTK proof" and window["boundsSpace"] == "window", window
assert any(n["role"] == "button" and n["name"] == "Complete proof" for n in snapshot["nodes"]), snapshot
print(f"stock GTK Wayland: pid={pid}, nodes={len(snapshot['nodes'])}, window={window}", flush=True)
PYTHON
  /usr/bin/python3 "$(dirname "$0")/gnome-observe-roles.py" "$fixture_pid" "$gtk_pid"
  exit 0
fi

if [[ "$SMOKE_MODE" == atspi-ownership ]]; then
  cd "$ATSPI_REPO/recording/native/linux"
  cargo build --locked --manifest-path "$FIXTURE_CHECKOUT/fixtures/accesskit/Cargo.toml"
  cargo build --locked
  unset DISPLAY
  export GDK_BACKEND=wayland GTK_A11Y=atspi
  /usr/bin/python3 "$(dirname "$0")/gnome-atspi-ownership-probe.py" \
    --helper "$ATSPI_REPO/recording/native/linux/target/debug/spectre-wayland-helper" \
    --fixture "$FIXTURE_CHECKOUT/fixtures/accesskit/target/debug/spectre-accesskit-fixture" \
    --artifacts "$SMOKE_ARTIFACTS"
  exit 0
fi

if [[ "$SMOKE_MODE" == atspi-distinct-content ]]; then
  cd "$ATSPI_REPO/recording/native/linux"
  cargo build --locked
  unset DISPLAY
  export GDK_BACKEND=wayland GTK_A11Y=atspi
  /usr/bin/python3 "$(dirname "$0")/gnome-atspi-distinct-content-probe.py" \
    --helper "$ATSPI_REPO/recording/native/linux/target/debug/spectre-wayland-helper" \
    --artifacts "$SMOKE_ARTIFACTS"
  exit 0
fi

cd "$SMOKE_REPO"
./gradlew --no-daemon :recording:assembleWaylandHelper --console=plain \
  2>&1 | tee "$SMOKE_ARTIFACTS/assemble-wayland-helper.log"
helper=$(find "$SMOKE_REPO/recording/build/generated/waylandHelper/native/linux" \
  -type f -name spectre-wayland-helper -perm /111 -print -quit)
[[ -n "$helper" ]] || { echo "Candidate did not install spectre-wayland-helper" >&2; exit 1; }
export SPECTRE_WAYLAND_SESSION_FOREGROUND=1
export SPECTRE_WAYLAND_SESSION_DIR="$XDG_RUNTIME_DIR/spectre-verification-session"
export SPECTRE_WAYLAND_RESTORE_TOKEN_DIR="$XDG_RUNTIME_DIR/spectre-verification-tokens"
mkdir -m 0700 "$SPECTRE_WAYLAND_SESSION_DIR" "$SPECTRE_WAYLAND_RESTORE_TOKEN_DIR"
/usr/bin/python3 "$(dirname "$0")/gnome-portal-dialog-warmup.py" \
  --mode helper --helper "$helper" --pid-file "$SMOKE_ARTIFACTS/helper.pid" \
  --artifacts "$SMOKE_ARTIFACTS" \
  2>&1 | tee "$SMOKE_ARTIFACTS/helper-portal-flow.log"
export SPECTRE_WAYLAND_CONSENT_PROMPT=never
./gradlew --no-daemon :recording:runWaylandMutterWindowSmoke \
  2>&1 | tee "$SMOKE_ARTIFACTS/gradle-mutter-smoke.log"
