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
grep -q '"bin": "spectre-cli-1.2.3/spectre.exe"' "$tmp/out/bucket/spectre.json"
