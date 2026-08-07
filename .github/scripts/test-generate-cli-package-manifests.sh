#!/usr/bin/env bash
# Structural + generator contracts for Homebrew/Scoop package manifests.
# Wired into ./gradlew check via verifyCliPackageManifests (Unix).
#
# No Ruby: install-semantics live in verifyHomebrewFormulaInstallSemantics
# (test-homebrew-formula-install-semantics.sh). See docs/TESTING.md (#400).
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"

require_cmd() {
  local cmd="$1"
  local hint="$2"
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "error: '$cmd' is required for verifyCliPackageManifests (CLI package-manifest structural checks)." >&2
    echo "  Install: $hint" >&2
    echo "  Task: ./gradlew verifyCliPackageManifests" >&2
    echo "  Docs: docs/TESTING.md (Package-channel contracts)" >&2
    exit 1
  fi
}

require_cmd python3 "apt install python3  |  brew install python3  |  https://www.python.org/downloads/"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

printf arm > "$tmp/spectre-macosArm64.zip"
printf intel > "$tmp/spectre-macosX64.zip"
printf windows > "$tmp/spectre-windowsX64.zip"

python3 "$root/.github/scripts/generate-cli-package-manifests.py" \
  --version 1.2.3 \
  --macos-arm64 "$tmp/spectre-macosArm64.zip" \
  --macos-x64 "$tmp/spectre-macosX64.zip" \
  --windows-x64 "$tmp/spectre-windowsX64.zip" \
  --output-dir "$tmp/out"

generated_formula="$tmp/out/Formula/spectre.rb"
committed_formula="$root/Formula/spectre.rb"

python3 -m json.tool "$tmp/out/bucket/spectre.json" >/dev/null

grep -q 'version "1.2.3"' "$generated_formula"
grep -q 'sha256 "ddf7ff5ebd9d66ce161466c1c0262430fa04de32b0e420ee3f489e2e2112e386"' "$generated_formula"
grep -q 'shell_output("#{bin}/spectre --help")' "$generated_formula"
grep -q '"bin": "spectre-cli-1.2.3/spectre.exe"' "$tmp/out/bucket/spectre.json"

# #390: Homebrew fix_dynamic_linkage must not leave a broken Spectre.app seal.
grep -q 'preserve_rpath' "$generated_formula"
grep -q 'def post_install' "$generated_formula"
grep -q 'restore_signed_app!' "$generated_formula"
grep -q 'cached_download' "$generated_formula"

# Dual-layout app discovery: Homebrew may leave spectre-cli-*/ or strip it to top-level Spectre.app
grep -q 'Dir\["spectre-cli-\*/Spectre.app"\]\.first || Dir\["Spectre.app"\]\.first' "$generated_formula"

# Wrapper entry point: raw Roast symlink breaks argv[0] config lookup under Homebrew bin/
if grep -q 'bin.install_symlink libexec/"Spectre.app/Contents/MacOS/spectre"' "$generated_formula"; then
  echo "generated formula must not use raw bin.install_symlink of the Roast binary as the CLI entry point" >&2
  exit 1
fi
grep -q '(bin/"spectre").write' "$generated_formula"
grep -q 'exec "#{libexec}/Spectre.app/Contents/MacOS/spectre" "$@"' "$generated_formula"
grep -q '(bin/"spectre").chmod 0755' "$generated_formula"

# Committed formula must carry the same install/entry contracts (release automation only rewrites
# version/url/sha256; a hand-edit or stale commit must not reintroduce the #283/#284 bugs).
if [[ ! -f "$committed_formula" ]]; then
  echo "missing committed formula: $committed_formula" >&2
  exit 1
fi
grep -q 'preserve_rpath' "$committed_formula"
grep -q 'def post_install' "$committed_formula"
grep -q 'restore_signed_app!' "$committed_formula"
grep -q 'Dir\["spectre-cli-\*/Spectre.app"\]\.first || Dir\["Spectre.app"\]\.first' "$committed_formula"
if grep -q 'bin.install_symlink libexec/"Spectre.app/Contents/MacOS/spectre"' "$committed_formula"; then
  echo "committed Formula/spectre.rb must not use raw bin.install_symlink of the Roast binary" >&2
  exit 1
fi
grep -q '(bin/"spectre").write' "$committed_formula"
grep -q 'exec "#{libexec}/Spectre.app/Contents/MacOS/spectre" "$@"' "$committed_formula"
grep -q '(bin/"spectre").chmod 0755' "$committed_formula"

# Install-method bodies (excluding version/url/sha) must stay aligned between generator output
# and the committed formula so regenerating manifests cannot silently diverge from main.
# Python (not Ruby) so clean Linux ./gradlew check has no undeclared Ruby dependency (#400).
extract_install_body() {
  python3 - "$1" <<'PY'
import re
import sys

path = sys.argv[1]
text = open(path, encoding="utf-8").read()
start = text.find("  def install\n")
if start < 0:
    sys.exit(f"no def install in {path}")
rest = text[start:]
# Method ends at the first line that is exactly "  end" after the def (formula style).
match = re.match(r"  def install\n.*?\n  end\n", rest, flags=re.DOTALL)
if not match:
    sys.exit(f"could not extract install method from {path}")
body = match.group(0)
# Drop Ruby comment lines (indent + "#" + space/end) so comment-only drift
# does not fail the gate. Keep shebangs inside the wrapper heredoc (#!/bin/sh).
for line in body.splitlines(keepends=True):
    if re.match(r"^\s+#(\s|$)", line):
        continue
    sys.stdout.write(line)
PY
}

generated_install="$(extract_install_body "$generated_formula")"
committed_install="$(extract_install_body "$committed_formula")"
if [[ "$generated_install" != "$committed_install" ]]; then
  echo "install method body differs between generated and committed Formula/spectre.rb" >&2
  echo "----- generated -----" >&2
  echo "$generated_install" >&2
  echo "----- committed -----" >&2
  echo "$committed_install" >&2
  exit 1
fi

echo "test-generate-cli-package-manifests: OK"
