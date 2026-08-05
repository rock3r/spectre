#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
workflow="$repo_root/.github/workflows/release.yml"
verifier="$repo_root/.github/scripts/verify-macos-cli-bundle.sh"
seal_probe="$repo_root/.github/scripts/test-macos-cli-seal-preservation.sh"

test -x "$verifier"
test -x "$seal_probe"
grep -Fq 'verify-macos-cli-bundle.sh "$archive" "$RELEASE_VERSION"' "$workflow"
grep -Fq 'app="$workspace/$expected_root/Spectre.app"' "$verifier"
grep -Fq 'codesign --verify --deep --strict --verbose=4 "$app"' "$verifier"
grep -Fq 'xcrun stapler validate "$app"' "$verifier"
grep -Fq 'spctl --assess --type execute --verbose=4 "$app"' "$verifier"

# Intermediate release signing must also deep-verify (not only --strict). A non-deep
# check can green-pass while nested jlink dylibs under Resources/runtime are already
# invalid relative to the outer seal (#390).
deep_strict_count="$(
  grep -c 'codesign --verify --deep --strict --verbose=4 "\$app"' "$workflow" || true
)"
if [[ "$deep_strict_count" -lt 2 ]]; then
  echo "release.yml must codesign --verify --deep --strict at least twice (pre- and post-staple)" >&2
  exit 1
fi
# Guard against reintroducing non-deep intermediate verifies on the app bundle.
if grep -E 'codesign --verify --strict --verbose=4 "\$app"' "$workflow" | grep -vq -- '--deep'; then
  echo "release.yml still has codesign --verify --strict without --deep on \$app" >&2
  exit 1
fi

bash "$seal_probe"
