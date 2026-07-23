#!/usr/bin/env bash
# Open (or comment on) a GitHub issue for a failed runtime-matrix cell.
# Intended for scheduled runs so failures are not lost in workflow noise (#216).
#
# Required env:
#   GITHUB_REPOSITORY, GITHUB_RUN_ID, GITHUB_SERVER_URL (Actions defaults)
#   MATRIX_OS, MATRIX_RUNTIME, MATRIX_FIXTURE_RUNTIME (optional)
#   GH_TOKEN or GITHUB_TOKEN with issues:write
set -euo pipefail

: "${GITHUB_REPOSITORY:?}"
: "${GITHUB_RUN_ID:?}"
: "${GITHUB_SERVER_URL:=https://github.com}"
: "${MATRIX_OS:?}"
: "${MATRIX_RUNTIME:?}"

fixture="${MATRIX_FIXTURE_RUNTIME:-same}"
title="[runtime-matrix] ${MATRIX_RUNTIME} on ${MATRIX_OS} (fixture=${fixture})"
run_url="${GITHUB_SERVER_URL}/${GITHUB_REPOSITORY}/actions/runs/${GITHUB_RUN_ID}"
body="$(
  cat <<EOF
Automated report from the scheduled **Runtime matrix** workflow (#216 / epic #215).

| Cell | Value |
| --- | --- |
| OS | \`${MATRIX_OS}\` |
| Attacher / suite runtime | \`${MATRIX_RUNTIME}\` |
| Fixture runtime | \`${fixture}\` |
| Run | ${run_url} |

## Routing

- Label \`runtime-matrix\` so capability-matrix / #198 follow-up can find the cell.
- Do not silently re-green the matrix without triaging this issue.
- When fixed, close this issue and re-run the matrix via \`workflow_dispatch\`.

EOF
)"

# Prefer an open issue with the same title to avoid spam; otherwise create one.
existing="$(
  gh issue list \
    --repo "$GITHUB_REPOSITORY" \
    --label runtime-matrix \
    --state open \
    --search "in:title ${title}" \
    --json number,title \
    --jq ".[] | select(.title == \"${title}\") | .number" \
    | head -n1 || true
)"

if [[ -n "$existing" ]]; then
  gh issue comment "$existing" --repo "$GITHUB_REPOSITORY" --body "Re-failed: ${run_url}"
  echo "Commented on existing issue #$existing"
else
  gh issue create \
    --repo "$GITHUB_REPOSITORY" \
    --title "$title" \
    --label "runtime-matrix" \
    --label "area/testing" \
    --body "$body"
  echo "Opened new runtime-matrix failure issue"
fi
