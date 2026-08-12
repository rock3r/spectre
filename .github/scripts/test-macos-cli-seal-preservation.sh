#!/usr/bin/env bash
# Contract + behavioral proof for #390 (Homebrew post-install seal break).
#
# 1) Formula / release workflow must declare the protections that keep the sealed
#    Spectre.app intact under Homebrew (preserve_rpath) and under release signing
#    (codesign --verify --deep --strict).
# 2) On Darwin, when a real Spectre.app is available (SPECTRE_APP or a release zip
#    extract), prove that rewriting a nested jlink dylib ID + ad-hoc re-sign —
#    the mutation Homebrew's fix_dynamic_linkage performs without preserve_rpath —
#    makes codesign --verify --deep --strict fail.
#
# Wired into ./gradlew verifyMacosCliBundleReleaseContract (Unix check gate).
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
formula="$root/Formula/spectre.rb"
workflow="$root/.github/workflows/macos-release-artifacts.yml"
verifier="$root/.github/scripts/verify-macos-cli-bundle.sh"

test -f "$formula"
test -f "$workflow"
test -x "$verifier"

grep -Fq 'preserve_rpath' "$formula"
grep -Fq 'codesign --verify --deep --strict --verbose=4 "$app"' "$verifier"
deep_strict_count="$(
  grep -c 'codesign --verify --deep --strict --verbose=4 "\$app"' "$workflow" || true
)"
if [[ "$deep_strict_count" -lt 2 ]]; then
  echo "macos-release-artifacts.yml must deep+strict verify pre/post staple" >&2
  exit 1
fi

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "test-macos-cli-seal-preservation: non-Darwin host; structural contracts OK"
  exit 0
fi

app="${SPECTRE_APP:-}"
cleanup_workspace=""
if [[ -z "$app" ]]; then
  # Prefer a published-shaped extract if the operator left one; otherwise skip the
  # mutation probe (structural contracts above still run on every host).
  for candidate in \
    "${SPECTRE_CLI_ZIP:-}" \
    "$root/cli/build/construo/distributions/spectre-macosArm64.zip" \
    "$root/cli/build/construo/distributions/spectre-macosX64.zip"
  do
    if [[ -n "$candidate" && -f "$candidate" ]]; then
      workspace="$(mktemp -d "${TMPDIR:-/tmp}/spectre-seal-probe.XXXXXX")"
      cleanup_workspace="$workspace"
      ditto -x -k "$candidate" "$workspace"
      app="$(find "$workspace" -name 'Spectre.app' -type d | head -1 || true)"
      break
    fi
  done
fi

if [[ -z "${app:-}" || ! -d "$app" ]]; then
  echo "test-macos-cli-seal-preservation: no Spectre.app available; structural contracts OK"
  [[ -n "$cleanup_workspace" ]] && rm -rf "$cleanup_workspace"
  exit 0
fi

trap '[[ -n "${cleanup_workspace:-}" ]] && rm -rf "$cleanup_workspace"' EXIT

# Working copy so we never mutate the caller's artifact.
probe="$(mktemp -d "${TMPDIR:-/tmp}/spectre-seal-mut.XXXXXX")"
trap 'rm -rf "$probe"; [[ -n "${cleanup_workspace:-}" ]] && rm -rf "$cleanup_workspace"' EXIT
ditto "$app" "$probe/Spectre.app"
app="$probe/Spectre.app"

dylib=""
for candidate in \
  "$app/Contents/Resources/runtime/lib/libnet.dylib" \
  "$app/Contents/MacOS/runtime/lib/libnet.dylib"
do
  if [[ -f "$candidate" ]]; then
    dylib="$candidate"
    break
  fi
done

if [[ -z "$dylib" ]]; then
  echo "test-macos-cli-seal-preservation: Spectre.app has no jlink libnet.dylib; skip mutation probe"
  exit 0
fi

# Baseline: may be ad-hoc (local package) or Developer ID (release). Deep verify must
# still be runnable; if the tree is already unsigned/broken, skip mutation proof.
if ! codesign --verify --deep --strict "$app" >/dev/null 2>&1; then
  echo "test-macos-cli-seal-preservation: baseline deep verify failed on $app; skip mutation probe"
  exit 0
fi

# Reproduce Homebrew fix_dynamic_linkage's harmful step: absolute dylib id + ad-hoc re-sign.
install_name_tool -id "/opt/homebrew/opt/spectre/libexec/Spectre.app/Contents/Resources/runtime/lib/libnet.dylib" \
  "$dylib"
# Match Homebrew's arm64 ad-hoc re-sign after Mach-O rewrite (ruby-macho MachO.codesign!).
codesign --force --sign - --options runtime "$dylib"

if codesign --verify --deep --strict "$app" >/dev/null 2>&1; then
  echo "expected deep+strict verify to FAIL after jlink dylib rewrite + ad-hoc re-sign (#390)" >&2
  codesign --verify --deep --strict --verbose=2 "$app" >&2 || true
  exit 1
fi

echo "test-macos-cli-seal-preservation: OK (deep+strict fails after simulated brew mutation)"
