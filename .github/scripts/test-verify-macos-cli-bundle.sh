#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
workflow="$repo_root/.github/workflows/release.yml"
verifier="$repo_root/.github/scripts/verify-macos-cli-bundle.sh"

test -x "$verifier"
grep -Fq 'verify-macos-cli-bundle.sh "$archive" "$RELEASE_VERSION"' "$workflow"
grep -Fq 'ditto -c -k --sequesterRsrc --keepParent "$app" "$archive"' "$workflow"
grep -Fq 'codesign --verify --deep --strict --verbose=4 "$app"' "$verifier"
grep -Fq 'xcrun stapler validate "$app"' "$verifier"
grep -Fq 'spctl --assess --type execute --verbose=4 "$app"' "$verifier"
