#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
workflow="$repo_root/.github/workflows/macos-release-artifacts.yml"
verifier="$repo_root/.github/scripts/verify-macos-cli-bundle.sh"
seal_probe="$repo_root/.github/scripts/test-macos-cli-seal-preservation.sh"
workflow_contract="$repo_root/.github/scripts/test-macos-release-artifact-workflows.sh"
dual_package="$repo_root/.github/scripts/test-macos-roast-dual-package-graph.sh"

test -x "$verifier"
test -x "$seal_probe"
test -x "$workflow_contract"
test -x "$dual_package"
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
  echo "macos-release-artifacts.yml must deep+strict verify before and after stapling" >&2
  exit 1
fi
# Guard against reintroducing non-deep intermediate verifies on the app bundle.
if grep -E 'codesign --verify --strict --verbose=4 "\$app"' "$workflow" | grep -vq -- '--deep'; then
  echo "macos-release-artifacts.yml has a non-deep strict verify on \$app" >&2
  exit 1
fi

bash "$workflow_contract"
bash "$seal_probe"
