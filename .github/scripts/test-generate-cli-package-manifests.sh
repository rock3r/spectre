#!/usr/bin/env bash
# Contract + behavioral tests for Homebrew/Scoop package manifest generation.
# Wired into ./gradlew check (verifyCliPackageManifests) and CI.
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

generated_formula="$tmp/out/Formula/spectre.rb"
committed_formula="$root/Formula/spectre.rb"

ruby -c "$generated_formula"
python3 -m json.tool "$tmp/out/bucket/spectre.json" >/dev/null

grep -q 'version "1.2.3"' "$generated_formula"
grep -q 'sha256 "ddf7ff5ebd9d66ce161466c1c0262430fa04de32b0e420ee3f489e2e2112e386"' "$generated_formula"
grep -q 'shell_output("#{bin}/spectre --help")' "$generated_formula"
grep -q '"bin": "spectre-cli-1.2.3/spectre.exe"' "$tmp/out/bucket/spectre.json"

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
extract_install_body() {
  # From "def install" through the matching "end" of that method (indent-aware enough for this formula).
  ruby -e '
    text = File.read(ARGV[0])
    start = text.index(/^  def install\n/)
    abort "no def install in #{ARGV[0]}" unless start
    rest = text[start..]
    # Method ends at the first line that is exactly "  end" after the def (formula style).
    body = rest[/\A  def install\n.*?\n  end\n/m]
    abort "could not extract install method from #{ARGV[0]}" unless body
    # Drop comment lines so comment-only drift does not fail the gate.
    puts body.lines.reject { |l| l.match?(/^\s*#/) }.join
  ' "$1"
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

# Behavioral semantics (layouts + wrapper vs symlink) against both formula texts.
ruby "$root/.github/scripts/test-homebrew-formula-install-semantics.rb" \
  "$generated_formula" \
  "$committed_formula"

echo "test-generate-cli-package-manifests: OK"
