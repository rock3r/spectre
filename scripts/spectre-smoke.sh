#!/bin/bash
# One-shot driver for #77 stage 2 smoke debug. Run from inside the GNOME session terminal so
# the portal dialog appears on the user's display.
#
# When the "Share your screen" dialog pops up, pick the monitor with the JFrame on it
# (titled "Spectre Wayland portal smoke") and click Share.
#
# When done, the log lives at /tmp/wayland-smoke.log — tell the host-side Claude session
# and it'll grab the file via SSH.
set -e
cd "$HOME/spectre"
git fetch origin feature/issue-77-pipewire-portal
git checkout feature/issue-77-pipewire-portal
git pull --ff-only
./gradlew --stop || true
./gradlew :recording:runWaylandPortalSmoke --console=plain --info 2>&1 | tee /tmp/wayland-smoke.log
echo "--- smoke done; log at /tmp/wayland-smoke.log ---"
