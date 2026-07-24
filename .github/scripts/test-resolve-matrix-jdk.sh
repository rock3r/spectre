#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
resolve="$script_dir/resolve-matrix-jdk.sh"
pins="$script_dir/../jbr-pins.env"
workflow="$script_dir/../workflows/runtime-matrix.yml"
action="$script_dir/../actions/setup-matrix-jdk/action.yml"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

[[ -x "$resolve" || -f "$resolve" ]] || fail "resolve script missing"
[[ -f "$pins" ]] || fail "pins file missing"
[[ -f "$workflow" ]] || fail "runtime-matrix.yml missing"
[[ -f "$action" ]] || fail "setup-matrix-jdk action missing"

# Pins must declare the required keys.
for key in JBR_21_VERSION JBR_25_VERSION TEMURIN_LTS_VERSION JBR_JAVA_PACKAGE; do
  grep -qE "^${key}=" "$pins" || fail "pins missing $key"
done

# JBR flavor is deliberately JBRSDK (jdk), not jcef.
pkg="$(grep -E '^JBR_JAVA_PACKAGE=' "$pins" | cut -d= -f2-)"
[[ "$pkg" == "jdk" ]] || fail "expected JBR_JAVA_PACKAGE=jdk (JBRSDK), got '$pkg'"

expect_field() {
  local runtime="$1"
  local field="$2"
  local expected="$3"
  local actual
  actual="$(bash "$resolve" "$runtime" | grep -E "^${field}=" | cut -d= -f2-)"
  [[ "$actual" == "$expected" ]] || fail "$runtime $field: expected '$expected', got '$actual'"
}

expect_field jbr-21 distribution jetbrains
expect_field jbr-25 distribution jetbrains
expect_field temurin-lts distribution temurin

jbr21_ver="$(grep -E '^JBR_21_VERSION=' "$pins" | cut -d= -f2-)"
jbr25_ver="$(grep -E '^JBR_25_VERSION=' "$pins" | cut -d= -f2-)"
temurin_ver="$(grep -E '^TEMURIN_LTS_VERSION=' "$pins" | cut -d= -f2-)"

expect_field jbr-21 java-version "$jbr21_ver"
expect_field jbr-25 java-version "$jbr25_ver"
expect_field temurin-lts java-version "$temurin_ver"
expect_field jbr-21 java-package jdk
expect_field jbr-25 java-package jdk
expect_field temurin-lts java-package ""

if bash "$resolve" no-such-runtime >/dev/null 2>&1; then
  fail "unknown runtime should fail"
fi

# Workflow must cover the 3×3 product and gate suites.
for needle in jbr-21 jbr-25 temurin-lts ubuntu-latest macos-latest windows-latest \
  AgentContractCorpusTest AgentAttachIntegrationTest InProcessContractCorpusTest \
  runLinuxX11RecordingSmoke workflow_call schedule "runner.os != 'Windows'" \
  SPECTRE_MATRIX_JAVA_HOME gstreamer1.0-tools gstreamer1.0-plugins-base \
  gstreamer1.0-plugins-good gstreamer1.0-plugins-ugly gstreamer1.0-plugins-bad \
  x11-utils; do
  grep -q "$needle" "$workflow" || fail "runtime-matrix.yml missing expected token: $needle"
done
grep -q "SPECTRE_MATRIX_JAVA_HOME" "$script_dir/../../build.gradle.kts" ||
  fail "root build.gradle.kts must honour SPECTRE_MATRIX_JAVA_HOME for matrix Test workers"

# JBR install must retry (ECONNRESET on mixed fixture cells).
grep -q "Install JDK (JetBrains JBR) attempt 3" "$action" ||
  fail "setup-matrix-jdk must retry JetBrains JBR install (attempt 3 step missing)"

# Release workflow must depend on the matrix gate.
release="$script_dir/../workflows/release.yml"
grep -q 'runtime-matrix.yml' "$release" || fail "release.yml must call runtime-matrix.yml"

echo "OK: resolve-matrix-jdk + pins + workflow structural checks passed"
