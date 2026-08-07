#!/usr/bin/env bash
# Host-appropriate wrapper for Homebrew formula install-semantics (Ruby).
# Wired into ./gradlew check as verifyHomebrewFormulaInstallSemantics when Ruby
# is on PATH, or always under CI (fail-closed preflight if Ruby is missing).
#
# Structural/generator contracts stay in test-generate-cli-package-manifests.sh
# and do not require Ruby (#400).
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"

if ! command -v ruby >/dev/null 2>&1; then
  echo "error: 'ruby' is required for verifyHomebrewFormulaInstallSemantics" >&2
  echo "  (Homebrew formula install-semantics: dual-layout Spectre.app + wrapper bin)." >&2
  echo "  Install: apt install ruby  |  brew install ruby  |  https://www.ruby-lang.org/en/documentation/installation/" >&2
  echo "  Task: ./gradlew verifyHomebrewFormulaInstallSemantics" >&2
  echo "  Note: structural package-manifest checks do not need Ruby:" >&2
  echo "        ./gradlew verifyCliPackageManifests" >&2
  echo "  Docs: docs/TESTING.md (Package-channel contracts)" >&2
  exit 1
fi

formula_args=("$@")
if [[ ${#formula_args[@]} -eq 0 ]]; then
  # Match previous combined-script behaviour: exercise generated-layout contracts
  # against fixture archives plus the committed Formula/spectre.rb.
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  printf arm > "$tmp/spectre-macosArm64.zip"
  printf intel > "$tmp/spectre-macosX64.zip"
  printf windows > "$tmp/spectre-windowsX64.zip"
  if ! command -v python3 >/dev/null 2>&1; then
    echo "error: 'python3' is required to generate fixture formulas for install-semantics." >&2
    echo "  Install: apt install python3  |  brew install python3" >&2
    exit 1
  fi
  python3 "$root/.github/scripts/generate-cli-package-manifests.py" \
    --version 1.2.3 \
    --macos-arm64 "$tmp/spectre-macosArm64.zip" \
    --macos-x64 "$tmp/spectre-macosX64.zip" \
    --windows-x64 "$tmp/spectre-windowsX64.zip" \
    --output-dir "$tmp/out"
  # ruby -c: syntax-check generated formula before behavioral tests.
  ruby -c "$tmp/out/Formula/spectre.rb" >/dev/null
  formula_args=("$tmp/out/Formula/spectre.rb" "$root/Formula/spectre.rb")
fi

ruby "$root/.github/scripts/test-homebrew-formula-install-semantics.rb" "${formula_args[@]}"
echo "test-homebrew-formula-install-semantics: OK"
