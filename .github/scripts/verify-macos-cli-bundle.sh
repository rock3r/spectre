#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: $0 <spectre-macos.zip> <release-version>" >&2
  exit 64
fi

archive="$1"
release_version="$2"
expected_root="spectre-cli-$release_version"

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "macOS bundle verification requires Darwin" >&2
  exit 69
fi

if [[ ! -f "$archive" ]]; then
  echo "archive does not exist: $archive" >&2
  exit 66
fi

workspace="$(mktemp -d "${TMPDIR:-/tmp}/spectre-cli-verify.XXXXXX")"
trap 'rm -rf "$workspace"' EXIT

echo "Verifying final Spectre CLI $release_version archive: $archive"
ditto -x -k "$archive" "$workspace"
app="$workspace/$expected_root/Spectre.app"
launcher="$app/Contents/MacOS/spectre"

test -d "$app"
test -f "$launcher"
test -x "$launcher"

# Verify the exact tree users receive after extracting the public archive. Do not repair modes
# here: a ZIP that needs mutation after extraction is not a valid release artifact.
codesign --verify --deep --strict --verbose=4 "$app"
xcrun stapler validate "$app"
spctl --assess --type execute --verbose=4 "$app"
