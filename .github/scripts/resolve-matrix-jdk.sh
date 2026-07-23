#!/usr/bin/env bash
# Resolve a Spectre matrix runtime id to actions/setup-java inputs.
#
# Usage:
#   resolve-matrix-jdk.sh <runtime-id>
#   resolve-matrix-jdk.sh --print-env <runtime-id>
#
# Runtime ids: jbr-21 | jbr-25 | temurin-lts
#
# Prints either KEY=value lines suitable for GITHUB_OUTPUT / eval, or (with --print-env)
# export-friendly DISTRIBUTION / JAVA_VERSION / JAVA_PACKAGE / RUNTIME_LABEL.

set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
pins_file="${SPECTRE_JBR_PINS_FILE:-$script_dir/../jbr-pins.env}"

if [[ ! -f "$pins_file" ]]; then
  echo "error: pins file not found: $pins_file" >&2
  exit 1
fi

# shellcheck disable=SC1090
source "$pins_file"

print_env=0
if [[ "${1:-}" == "--print-env" ]]; then
  print_env=1
  shift
fi

runtime="${1:-}"
if [[ -z "$runtime" ]]; then
  echo "usage: $0 [--print-env] <jbr-21|jbr-25|temurin-lts>" >&2
  exit 2
fi

case "$runtime" in
  jbr-21)
    distribution="jetbrains"
    java_version="${JBR_21_VERSION:?JBR_21_VERSION missing from pins}"
    java_package="${JBR_JAVA_PACKAGE:?JBR_JAVA_PACKAGE missing from pins}"
    runtime_label="JBR ${JBR_21_VERSION}"
    ;;
  jbr-25)
    distribution="jetbrains"
    java_version="${JBR_25_VERSION:?JBR_25_VERSION missing from pins}"
    java_package="${JBR_JAVA_PACKAGE:?JBR_JAVA_PACKAGE missing from pins}"
    runtime_label="JBR ${JBR_25_VERSION}"
    ;;
  temurin-lts)
    distribution="temurin"
    java_version="${TEMURIN_LTS_VERSION:?TEMURIN_LTS_VERSION missing from pins}"
    # Temurin ignores jetbrains package types; emit empty so callers can omit the input.
    java_package=""
    runtime_label="Temurin ${TEMURIN_LTS_VERSION} (LTS)"
    ;;
  *)
    echo "error: unknown runtime id '$runtime' (expected jbr-21|jbr-25|temurin-lts)" >&2
    exit 2
    ;;
esac

if [[ "$print_env" -eq 1 ]]; then
  printf 'DISTRIBUTION=%q\n' "$distribution"
  printf 'JAVA_VERSION=%q\n' "$java_version"
  printf 'JAVA_PACKAGE=%q\n' "$java_package"
  printf 'RUNTIME_LABEL=%q\n' "$runtime_label"
else
  echo "distribution=$distribution"
  echo "java-version=$java_version"
  echo "java-package=$java_package"
  echo "runtime-label=$runtime_label"
fi
