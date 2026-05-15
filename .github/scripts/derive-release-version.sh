#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -ne 1 ]]; then
  echo "Usage: $0 <tag>" >&2
  exit 2
fi

tag="$1"
semver_core="(0|[1-9][0-9]*)[.](0|[1-9][0-9]*)[.](0|[1-9][0-9]*)"
semver_identifier="[0-9A-Za-z-]+"
semver_suffix="(-${semver_identifier}([.]${semver_identifier})*)?([+]${semver_identifier}([.]${semver_identifier})*)?"

if [[ ! "$tag" =~ ^v(${semver_core}${semver_suffix})$ ]]; then
  echo "Release tags must match v<semver>, for example v0.2.0 or v0.2.0-rc.1." >&2
  exit 1
fi

version="${tag#v}"
echo "$version"
