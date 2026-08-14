#!/usr/bin/env bash
# Fail-closed contract for packaging both macOS Roast targets in one Gradle graph.
#
# macos-release-artifacts.yml (notarize-macos.yml + tag release.yml) invokes
#   :cli:packageMacosX64 :cli:packageMacosArm64
# in a single Gradle invocation. Each package* is finalizedBy
# verifyRoastCliNativeLayout*, which reads the target zip. If both package tasks
# share construo/distributions as destinationDirectory, Gradle 9 reports an
# implicit-dependency and the CLI seal job dies before signing.
#
# This inspects the real :cli PackageTask destinationDirectory values — it does
# not mock the graph or re-implement Construo. Full dual Roast execution is not
# required.
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
workflow="$root/.github/workflows/macos-release-artifacts.yml"
init_script="$root/.github/scripts/inspect-macos-roast-package-dests.init.gradle.kts"
wrapper="$root/gradlew"

fail() {
  echo "test-macos-roast-dual-package-graph: $*" >&2
  exit 1
}

[[ -f "$workflow" ]] || fail "missing $workflow"
[[ -f "$init_script" ]] || fail "missing $init_script"
[[ -x "$wrapper" ]] || fail "missing executable $wrapper"

# The release path must still request both macOS package tasks (one graph is the
# intended, now-safe invocation once dest dirs are isolated).
if ! grep -q ':cli:packageMacosX64' "$workflow" || ! grep -q ':cli:packageMacosArm64' "$workflow"; then
  fail "macos-release-artifacts.yml must request both :cli:packageMacosX64 and :cli:packageMacosArm64"
fi

# Capture dest/finalizer lines even when the init script fails closed.
inspect_log="$(mktemp)"
trap 'rm -f "$inspect_log"' EXIT

set +e
(
  cd "$root"
  ./gradlew :cli:help \
    --init-script "$init_script" \
    --no-configuration-cache \
    --console=plain
) >"$inspect_log" 2>&1
status=$?
set -e

grep -E '^SPECTRE_(PACKAGE|VERIFY)_MACOS_' "$inspect_log" || true

if [[ "$status" -ne 0 ]]; then
  echo "----- gradle inspect log -----" >&2
  cat "$inspect_log" >&2
  fail "Gradle dual-macOS package graph is unsafe (see implicit-dependency / shared destinationDirectory above)"
fi

x64_dest="$(awk -F= '/^SPECTRE_PACKAGE_MACOS_X64_DEST=/{print $2}' "$inspect_log")"
arm_dest="$(awk -F= '/^SPECTRE_PACKAGE_MACOS_ARM64_DEST=/{print $2}' "$inspect_log")"
x64_zip="$(awk -F= '/^SPECTRE_VERIFY_MACOS_X64_ZIP=/{print $2}' "$inspect_log")"
arm_zip="$(awk -F= '/^SPECTRE_VERIFY_MACOS_ARM64_ZIP=/{print $2}' "$inspect_log")"

[[ -n "$x64_dest" && -n "$arm_dest" ]] || fail "init script did not print package dest dirs"
[[ "$x64_dest" != "$arm_dest" ]] || fail "package dest dirs must differ (shared $x64_dest)"

# Workflow archive= must resolve to the isolated zip the package task writes.
# The signing loop uses `target` in macosX64/macosArm64.
rel_x64="${x64_zip#"$root"/}"
rel_arm="${arm_zip#"$root"/}"
target_token='${target}'
expected_x64_template="${rel_x64//macosX64/$target_token}"
expected_arm_template="${rel_arm//macosArm64/$target_token}"
[[ "$expected_x64_template" == "$expected_arm_template" ]] \
  || fail "x64/arm verify zip paths are not the same \${target} template ($rel_x64 vs $rel_arm)"
grep -Fq "archive=\"$expected_x64_template\"" "$workflow" \
  || fail "macos-release-artifacts.yml archive= must be $expected_x64_template so signing reads the isolated package dest"

# Artifact upload must flatten to spectre-macosX64.zip / spectre-macosArm64.zip
# so B16 `gh run download -n mac-cli-bundles` keeps the documented zip names.
if ! grep -Fq 'spectre-macosX64.zip' "$workflow" || ! grep -Fq 'spectre-macosArm64.zip' "$workflow"; then
  fail "mac-cli-bundles upload must keep spectre-macosX64.zip and spectre-macosArm64.zip names"
fi
grep -Fq '$RUNNER_TEMP/mac-cli-bundles/spectre-${target}.zip' "$workflow" \
  || fail "workflow must flatten isolated dest zips to \$RUNNER_TEMP/mac-cli-bundles/spectre-\${target}.zip"

echo "test-macos-roast-dual-package-graph: OK"
