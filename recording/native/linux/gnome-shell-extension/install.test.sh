#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "$0")" && pwd)
uuid=spectre-window-identity@sebastiano.dev
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

run_install() {
  local installer=$1
  local version=$2
  local expected=$3
  mkdir -p "$tmp/bin" "$tmp/home"
  printf '#!/bin/sh\necho "GNOME Shell %s"\n' "$version" > "$tmp/bin/gnome-shell"
  chmod +x "$tmp/bin/gnome-shell"
  PATH="$tmp/bin:$PATH" XDG_DATA_HOME="$tmp/home" bash "$installer"
  local installed="$tmp/home/gnome-shell/extensions/$uuid"
  for file in metadata.json windowIdentity.js windowIdentityCore.js extension.js; do
    test -f "$installed/$file"
  done
  cmp "$(dirname "$installer")/$expected" "$installed/extension.js"
  rm -rf "$tmp/home/gnome-shell"
}

run_install "$root/install.sh" 42.9 "$uuid/extension-legacy.js"
run_install "$root/install.sh" 45.0 "$uuid/extension.js"
run_install "$root/install.sh" 50.rc "$uuid/extension.js"

jar_path=${1:?expected packaged recording-linux jar path}
docs_path=${2:?expected troubleshooting guide path}
extract="$tmp/extracted"
mkdir -p "$extract"
(
  cd "$extract"
  jar xf "$jar_path" native/linux/gnome-shell-extension
)
extracted_installer="$extract/native/linux/gnome-shell-extension/install.sh"
[[ ! -x "$extracted_installer" ]]
grep -Fq 'bash native/linux/gnome-shell-extension/install.sh' "$docs_path"
run_install "$extracted_installer" 44.8 "$uuid/extension-legacy.js"
