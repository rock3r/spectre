#!/usr/bin/env bash
# Runs as the unprivileged PAM/logind user created by gnome-hosted-smoke.sh.
set -Eeuo pipefail

: "${SMOKE_ARTIFACTS:?}"
: "${SMOKE_REPO:?}"

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

extension_uuid=spectre-window-identity@sebastiano.dev
extension_source="$SMOKE_REPO/recording/native/linux/gnome-shell-extension/$extension_uuid"
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
[[ -n "$WAYLAND_DISPLAY" ]] || { echo "Mutter did not create a Wayland socket" >&2; exit 1; }
timeout 5s gdbus wait --session org.gnome.Mutter.ScreenCast
gnome-extensions enable "$extension_uuid"
gnome-extensions list --enabled | grep -Fx "$extension_uuid"
pw-cli info 0 >"$SMOKE_ARTIFACTS/pipewire-core.txt"

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
  SPECTRE_HELPER="$SMOKE_ARTIFACTS/reader-with-trace" bash "$SMOKE_ARTIFACTS/atspi-wayland.sh" 2>&1 | tee "$SMOKE_ARTIFACTS/atspi-live.log"
  exit 0
fi

cd "$SMOKE_REPO"
./gradlew --no-daemon :recording:runWaylandMutterWindowSmoke \
  2>&1 | tee "$SMOKE_ARTIFACTS/gradle-mutter-smoke.log"
