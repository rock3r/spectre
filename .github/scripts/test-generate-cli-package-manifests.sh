#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
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
ruby -c "$tmp/out/Formula/spectre.rb"
python3 -m json.tool "$tmp/out/bucket/spectre.json" >/dev/null
grep -q 'version "1.2.3"' "$tmp/out/Formula/spectre.rb"
grep -q 'sha256 "ddf7ff5ebd9d66ce161466c1c0262430fa04de32b0e420ee3f489e2e2112e386"' "$tmp/out/Formula/spectre.rb"
grep -q 'shell_output("#{bin}/spectre --help")' "$tmp/out/Formula/spectre.rb"
# Dual-layout app discovery: Homebrew may leave spectre-cli-*/ or strip it to top-level Spectre.app
grep -q 'Dir\["spectre-cli-\*/Spectre.app"\]\.first || Dir\["Spectre.app"\]\.first' "$tmp/out/Formula/spectre.rb"
# Wrapper entry point: do not expose a raw symlink of the Roast binary as the only bin entry
# (symlink argv[0] breaks Roast config lookup under bin/). Prefer a shell wrapper that execs the real path.
if grep -q 'bin.install_symlink libexec/"Spectre.app/Contents/MacOS/spectre"' "$tmp/out/Formula/spectre.rb"; then
  echo "formula must not use raw bin.install_symlink of the Roast binary as the CLI entry point" >&2
  exit 1
fi
grep -q '(bin/"spectre").write' "$tmp/out/Formula/spectre.rb"
grep -q 'exec "#{libexec}/Spectre.app/Contents/MacOS/spectre" "$@"' "$tmp/out/Formula/spectre.rb"
grep -q '(bin/"spectre").chmod 0755' "$tmp/out/Formula/spectre.rb"
grep -q '"bin": "spectre-cli-1.2.3/spectre.exe"' "$tmp/out/bucket/spectre.json"
