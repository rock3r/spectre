#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
release="$root/.github/workflows/release.yml"
reusable="$root/.github/workflows/macos-release-artifacts.yml"
dispatch="$root/.github/workflows/notarize-macos.yml"
release_smoke="$root/docs/RELEASE-SMOKE.md"
publishing="$root/docs/PUBLISHING.md"

fail() {
  echo "test-macos-release-artifact-workflows: $*" >&2
  exit 1
}

[[ -f "$reusable" ]] || fail "missing reusable macOS release-artifact workflow"
[[ -f "$dispatch" ]] || fail "missing on-demand notarization workflow"

# The tag release and manual pre-tag path must share one signing/notarization implementation.
grep -Fq 'uses: ./.github/workflows/macos-release-artifacts.yml' "$release" \
  || fail "release.yml must call the reusable macOS artifact workflow"
grep -Fq 'uses: rock3r/spectre/.github/workflows/macos-release-artifacts.yml@main' "$dispatch" \
  || fail "manual workflow must call the reusable workflow from trusted main"
grep -Fq 'workflow_call:' "$reusable" || fail "reusable workflow must expose workflow_call"
grep -Fq 'workflow_dispatch:' "$dispatch" || fail "on-demand workflow must expose workflow_dispatch"

# The trusted reusable workflow resolves and validates the requested ref before either privileged
# job runs. Both privileged jobs must use its full-SHA output, never the caller's raw ref.
grep -Fq 'ref: ${{ inputs.ref }}' "$reusable" \
  || fail "validation job must resolve inputs.ref"
checkout_count="$(grep -Fc 'ref: ${{ needs.validate.outputs.sha }}' "$reusable")"
[[ "$checkout_count" -eq 2 ]] \
  || fail "both privileged jobs must check out the validated SHA (found $checkout_count)"
grep -Fq 'git merge-base --is-ancestor "$sha" "origin/$trusted_branch"' "$reusable" \
  || fail "reusable workflow must constrain ref to trusted branch history"
grep -Fq 'derive-release-version.sh "v$REQUESTED_VERSION"' "$reusable" \
  || fail "reusable workflow must use the release SemVer validator"
grep -Fq 'ref: ${{ github.sha }}' "$release" \
  || fail "tag release must pass github.sha"
grep -Fq 'ref: ${{ inputs.ref }}' "$dispatch" \
  || fail "manual workflow must pass the requested ref to trusted validation"
grep -Fq 'version: ${{ inputs.version }}' "$dispatch" \
  || fail "manual workflow must pass its requested smoke version"

# Callers expose only the seven Apple credentials required by the reusable interface.
if grep -Fq 'secrets: inherit' "$release" "$dispatch"; then
  fail "callers must not inherit every repository secret"
fi
for secret in \
  APPLE_DEVELOPER_ID_P12 \
  APPLE_DEVELOPER_ID_P12_PASSWORD \
  APPLE_SIGNING_KEYCHAIN_PASSWORD \
  APPLE_DEVELOPER_IDENTITY \
  APPLE_NOTARY_API_KEY \
  APPLE_NOTARY_API_KEY_ID \
  APPLE_NOTARY_API_ISSUER
do
  expected="$secret: \${{ secrets.$secret }}"
  grep -Fq "$expected" "$release" || fail "release.yml must explicitly pass $secret"
  grep -Fq "$expected" "$dispatch" || fail "notarize-macos.yml must explicitly pass $secret"
done

# Manual runs are artifact-only: never publish to Central or mutate a GitHub release.
if grep -Eq 'publishToMavenCentral|gh release (create|upload|edit)' "$dispatch" "$reusable"; then
  fail "manual/reusable notarization path must not publish or mutate releases"
fi

# Preserve the release artifacts and seal checks needed by B8/B16.
grep -Fq 'name: mac-helper' "$reusable" || fail "reusable workflow must upload mac-helper"
grep -Fq 'name: mac-cli-bundles' "$reusable" || fail "reusable workflow must upload mac-cli-bundles"
grep -Fq 'xcrun stapler staple "$app"' "$reusable" || fail "CLI app must be stapled"
grep -Fq 'xcrun stapler validate "$app"' "$reusable" || fail "CLI app staple must be validated"
verify_count="$(grep -Fc 'codesign --verify --deep --strict --verbose=4 "$app"' "$reusable")"
[[ "$verify_count" -ge 2 ]] \
  || fail "CLI app must be deep+strict verified before and after stapling"

# Operators need a copy/paste pre-tag recipe and an explicit artifact-only safety boundary.
grep -Fq 'gh workflow run notarize-macos.yml' "$release_smoke" \
  || fail "RELEASE-SMOKE.md must document the on-demand command"
grep -Fq 'does not publish to Central or create a GitHub release' "$publishing" \
  || fail "PUBLISHING.md must document the artifact-only boundary"

echo "test-macos-release-artifact-workflows: OK"
