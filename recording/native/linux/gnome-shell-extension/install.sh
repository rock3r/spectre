#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "$0")" && pwd)
uuid=spectre-window-identity@sebastiano.dev
major=$(gnome-shell --version | sed -E 's/^[^0-9]*([0-9]+).*/\1/')
case "$major" in
  42|43|44) entry=extension-legacy.js ;;
  45|46|47|48|49|50) entry=extension.js ;;
  *) echo "Unsupported GNOME Shell version: $major" >&2; exit 1 ;;
esac

destination=${XDG_DATA_HOME:-"$HOME/.local/share"}/gnome-shell/extensions/$uuid
mkdir -p "$destination"
cp "$root/$uuid/metadata.json" "$root/$uuid/windowIdentity.js" \
  "$root/$uuid/windowIdentityCore.js" "$destination/"
cp "$root/$uuid/$entry" "$destination/extension.js"
printf 'Installed %s for GNOME Shell %s at %s\n' "$uuid" "$major" "$destination"
