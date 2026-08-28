#!/usr/bin/env bash
# Fail-closed ordering contract for verifyMacosCliBundleReleaseContract (#456).
#
# That task runs test-macos-cli-seal-preservation.sh, which reads
#   cli/build/construo/distributions/macos*/spectre-macos*.zip
# produced by :cli:packageMacosArm64 / :cli:packageMacosX64.
#
# An absent zip is handled gracefully — the seal script skips the mutation probe.
# A *partially written* one is not: `ditto -x -k` fails with "Couldn't read pkzip
# signature" and the build dies. That is what happens when Gradle schedules the
# verification alongside, or ahead of, the packaging that produces the archive,
# which it is free to do while no ordering is declared.
#
# Asserted against the build script rather than a --dry-run listing: --dry-run
# prints a sequential order that says nothing about parallel scheduling, and the
# failure this guards against was a parallel one. Same shape as the other
# source-level contracts in this directory.
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
build_script="$root/build.gradle.kts"

wiring="$(grep -n 'verifyMacosCliBundleReleaseContract\.configure' "$build_script" || true)"
if [[ -z "$wiring" ]]; then
  echo "test-macos-bundle-verify-ordering: FAIL — no ordering configured for" >&2
  echo "  verifyMacosCliBundleReleaseContract, so it can read a half-written zip (#456)." >&2
  exit 1
fi

# The configure block may span several lines, so read from the match to the end of the block.
line_no="${wiring%%:*}"
clause="$(sed -n "${line_no},$((line_no + 5))p" "$build_script")"

missing=()
for required in ':cli:packageMacosArm64' ':cli:packageMacosX64'; do
  if [[ "$clause" != *"$required"* ]]; then
    missing+=("$required")
  fi
done

if (( ${#missing[@]} > 0 )); then
  echo "test-macos-bundle-verify-ordering: FAIL — verifyMacosCliBundleReleaseContract is not" >&2
  echo "  ordered after ${missing[*]}, so it can read a half-written zip (#456)." >&2
  echo "  found: $clause" >&2
  exit 1
fi

echo "test-macos-bundle-verify-ordering: OK — ordered after macOS packaging"
