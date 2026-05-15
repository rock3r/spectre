#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
script="$script_dir/derive-release-version.sh"

expect_version() {
  local tag="$1"
  local expected="$2"
  local actual

  actual="$(bash "$script" "$tag")"
  if [[ "$actual" != "$expected" ]]; then
    echo "Expected $tag to produce $expected, got $actual" >&2
    exit 1
  fi
}

expect_rejection() {
  local tag="$1"

  if bash "$script" "$tag" >/tmp/spectre-release-version-test.out 2>/tmp/spectre-release-version-test.err; then
    echo "Expected $tag to be rejected, but it succeeded" >&2
    cat /tmp/spectre-release-version-test.out >&2
    cat /tmp/spectre-release-version-test.err >&2
    exit 1
  fi
}

expect_version "v0.1.0" "0.1.0"
expect_version "v1.2.3" "1.2.3"
expect_version "v10.20.30-rc.1" "10.20.30-rc.1"

expect_rejection "0.1.0"
expect_rejection "v1"
expect_rejection "v1.2"
expect_rejection "v1.2.3.4"
expect_rejection "v01.2.3"
expect_rejection "v1.02.3"
expect_rejection "v1.2.03"
expect_rejection "v1.2.3-pre..1"
expect_rejection "vnext"
