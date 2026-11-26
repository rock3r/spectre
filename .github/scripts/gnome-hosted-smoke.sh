#!/usr/bin/env bash
# Root-side provisioning and real-logind launcher for the temporary ubuntu-24.04 smoke.
set -Eeuo pipefail

: "${ATSPI_REPO:?}"
: "${FIXTURE_CHECKOUT:?}"
: "${SMOKE_MODE:?}"
: "${SMOKE_USER:?}"
: "${RUST_BIN:?}"
: "${CANDIDATE_SHA:?}"
: "${JAVA_HOME:?}"
: "${SMOKE_ARTIFACTS:?}"
: "${SMOKE_REPO:?}"
: "${SMOKE_SESSION_SCRIPT:?}"

readonly SMOKE_USER
selected_jdk=${1:-$JAVA_HOME}
[[ -x "$selected_jdk/bin/java" ]]
mkdir -p "$SMOKE_ARTIFACTS"
chmod 0777 "$SMOKE_ARTIFACTS"

diagnostics() {
  local status=$?
  set +e
  {
    echo "exit_status=$status"
    echo "pid1=$(cat /proc/1/comm 2>&1)"
    systemd-detect-virt --vm 2>&1
    if [[ -e /dev/kvm ]]; then stat /dev/kvm; else echo "/dev/kvm unavailable (optional)"; fi
    loginctl list-sessions --no-legend 2>&1
    loginctl user-status "$SMOKE_USER" 2>&1
    systemctl status systemd-logind --no-pager 2>&1
  } >"$SMOKE_ARTIFACTS/host-diagnostics.txt"
  journalctl -b --no-pager -u systemd-logind >"$SMOKE_ARTIFACTS/systemd-logind.log" 2>&1
  journalctl -b --no-pager _UID="$(id -u "$SMOKE_USER" 2>/dev/null)" >"$SMOKE_ARTIFACTS/smoke-user-journal.log" 2>&1
  chmod -R a+rX "$SMOKE_ARTIFACTS"
  exit "$status"
}
trap diagnostics EXIT

[[ "$(cat /proc/1/comm)" == systemd ]] || { echo "PID 1 is not systemd" >&2; exit 1; }
systemctl is-active --quiet systemd-logind || { echo "systemd-logind is not active" >&2; exit 1; }

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends \
  at-spi2-core dbus-user-session ffmpeg git gnome-shell gnome-shell-extension-prefs \
  libdbus-1-dev libpipewire-0.3-dev mutter pipewire pipewire-audio-client-libraries \
  libspa-0.2-dev \
  pkg-config python3 python3-dbus zenity wireplumber pipewire-bin xwayland x11-utils x11-xserver-utils xauth xdotool \
  python3-gi python3-gi-cairo gir1.2-gtk-3.0 gir1.2-gtk-4.0 python3-pil python3-pyatspi \
  libgl1-mesa-dri libegl-mesa0 libxkbcommon-x11-0 xdg-desktop-portal-gnome \
  gstreamer1.0-tools gstreamer1.0-plugins-base gstreamer1.0-plugins-good \
  gstreamer1.0-plugins-bad gstreamer1.0-plugins-ugly gstreamer1.0-pipewire

id "$SMOKE_USER"
[[ "$CANDIDATE_SHA" =~ ^[0-9a-f]{40}$ ]] || { echo "CANDIDATE_SHA must be a full lowercase SHA" >&2; exit 1; }
test "$(git -c safe.directory="$SMOKE_REPO" -C "$SMOKE_REPO" rev-parse HEAD)" = "$CANDIDATE_SHA"
SMOKE_HOME=$(getent passwd "$SMOKE_USER" | cut -d: -f6)

# The service manager invokes PAM (including pam_systemd), unlike runuser/su. The session script
# independently requires loginctl to report this non-root user before starting a compositor.
chown "$SMOKE_USER" "$SMOKE_ARTIFACTS"
chmod 0755 "$SMOKE_SESSION_SCRIPT"
systemd-run \
  --wait --collect --pipe --service-type=simple --property=TimeoutStartSec=45min --property=TimeoutStopSec=45min \
  --uid="$SMOKE_USER" --property=PAMName=login \
  --setenv="HOME=$SMOKE_HOME" \
  --setenv="SMOKE_JAVA_HOME=$selected_jdk" \
  --setenv="PATH=$RUST_BIN:$selected_jdk/bin:/usr/local/bin:/usr/bin:/bin" \
  --setenv="SMOKE_ARTIFACTS=$SMOKE_ARTIFACTS" \
  --setenv="SMOKE_REPO=$SMOKE_REPO" \
  --setenv="SMOKE_MODE=$SMOKE_MODE" \
  --setenv="CANDIDATE_SHA=$CANDIDATE_SHA" \
  --setenv="ATSPI_REPO=$ATSPI_REPO" \
  --setenv="FIXTURE_CHECKOUT=$FIXTURE_CHECKOUT" \
  --setenv="BASELINE_REPO=${BASELINE_REPO:-}" \
  "$SMOKE_SESSION_SCRIPT" --outside-dbus "$selected_jdk"
