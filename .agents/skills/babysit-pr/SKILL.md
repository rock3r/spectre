---
name: babysit-pr
description: >
  Babysit a GitHub pull request after creation by continuously polling CI checks/workflow
  runs, new review comments, and mergeability state until the PR is ready to merge (or
  merged/closed). Diagnose failures, retry likely flaky failures up to 3 times, auto-fix
  and push branch-related issues when appropriate, and stop only when user help is required
  (e.g. CI infrastructure issues, exhausted flaky retries, or ambiguous/blocking review
  feedback). Use when the user asks to monitor a PR, watch CI, handle review comments, or
  keep an eye on failures and feedback on an open PR.
allowed-tools: Bash(python3 */skills/babysit-pr/scripts/*), Bash(gh pr *), Bash(gh run *), Bash(gh api *), Bash(git push *), Bash(git commit *), Bash(git diff *), Bash(git log *), Bash(git status), Bash(git branch *), Bash(git worktree *), Bash(./gradlew check *), Read, Edit
---

# PR Babysitter

Monitor a PR persistently until one of the terminal states is reached:
- PR merged or closed
- CI fully green, no unaddressed review comments, no merge conflicts
- A situation requiring user intervention

## Inputs

- No PR argument — infer from current branch (`--pr auto`)
- PR number — e.g. `123`
- PR URL — e.g. `https://github.com/rock3r/spectre/pull/123`

## Core workflow

0. **Before running any script**, output a single line so the user knows which PR this conversation is tracking — e.g. `Babysitting PR [#1](https://github.com/rock3r/spectre/pull/1)`. Resolve the PR number/URL from the user's input or the current branch first if needed.
1. Start with `--watch` for monitoring requests; `--once` for a single snapshot.
2. Run the watcher to snapshot PR/CI/review state.
3. Inspect the `actions` list in the JSON output.
4. Diagnose CI failures — classify as branch-related (fix and push) vs. flaky (retry).
5. Process actionable review comments from trusted humans, Bugbot, and Codex.
6. Verify mergeability on each loop.
7. After any push, relaunch `--watch` in the same turn.
8. Continue until a terminal stop condition is reached.

## Key commands

```bash
# Single snapshot of current state
python3 .agents/skills/babysit-pr/scripts/gh_pr_watch.py --pr auto --once

# Continuously poll until terminal
python3 .agents/skills/babysit-pr/scripts/gh_pr_watch.py --pr auto --watch

# Trigger a rerun of failed jobs for the current SHA
python3 .agents/skills/babysit-pr/scripts/gh_pr_watch.py --pr auto --retry-failed-now

# Explicit PR number or URL
python3 .agents/skills/babysit-pr/scripts/gh_pr_watch.py --pr 42 --watch
python3 .agents/skills/babysit-pr/scripts/gh_pr_watch.py --pr https://github.com/rock3r/spectre/pull/42 --once
```

## Stop conditions

| `actions` value | Meaning |
|---|---|
| `stop_pr_closed` | PR was merged or closed — done |
| `stop_ready_to_merge` | CI green, no blocking reviews, no conflicts |
| `stop_exhausted_retries` | Flaky reruns hit the retry limit — user must investigate |
| `stop_non_retryable_failure` | Terminal failure is not in retry-eligible workflows — diagnose/fix before continuing |
| `stop_session_timeout` | `--max-session-minutes` elapsed (default 90 min) — stop and report |
| `diagnose_hung_check` | A pending check has exceeded its hung threshold (Bugbot: 20 min, CI: 30 min) — stop and report |

Keep polling when CI is running (`idle`), when new review items arrive (`process_review_comment`),
or when CI is green but the PR is awaiting approval.

## Post-merge cleanup (when `stop_pr_closed` and PR is merged)

After a PR is merged, clean up the local environment automatically:

1. **Delete the local branch**:
   ```bash
   git branch -D <head_branch>
   ```

2. **Remove the git worktree**, if the branch was checked out in one:
   ```bash
   git worktree list
   git worktree remove /path/to/worktree
   ```

**Only delete the local branch and worktree** — never touch remote branches.

Skip silently if the branch or worktree doesn't exist locally.

## Push discipline — batch all fixes before pushing (cost control)

Each push triggers new Bugbot + Codex runs. **Never push until all of the following are true:**

1. `./gradlew check` passes locally (no CI failures to fix after the push).
2. Every Bugbot and Codex comment thread from the current review round is **resolved on GitHub** — either the fix was pushed and the thread resolved, or a short reply was posted explaining why it does not apply and the thread was then resolved.
3. Neither Bugbot nor Codex is still `IN_PROGRESS` — wait for both to finish so their comments can be collected and fixed in the same push.

**Workflow when fixes are needed:**

1. Collect all outstanding issues: failed CI logs + any Bugbot/Codex comments already posted.
2. Fix everything locally in one pass.
3. Run `./gradlew check` to confirm green.
4. Only then push — one push per fix cycle.

If either bot finishes while you are mid-fix and posts new comments, incorporate those fixes into the same commit before pushing.

## Bugbot + Codex merge gate (mandatory)

**Never merge until both Cursor Bugbot and Codex report clean.** For Bugbot, only `SUCCESS` is green.

- If either bot is still `IN_PROGRESS` → keep polling, do not push.
- If Bugbot conclusion is `NEUTRAL` or `SKIPPING` → it may have posted comments. Always check `gh api repos/{owner}/{repo}/pulls/{pr}/comments` for `cursor[bot]` and `chatgpt-codex-connector[bot]` comments.
- If Bugbot conclusion is `SUCCESS` and Codex posted no new comments → proceed to merge.
- Do **not** merge on NEUTRAL or SKIPPING, even if all other checks pass.

## Review bots

The watcher surfaces feedback from:
- **cursor[bot]** — Cursor Bugbot
- **chatgpt-codex-connector[bot]** — OpenAI Codex
- Trusted humans: authors with `OWNER`, `MEMBER`, or `COLLABORATOR` association

## Decision rules

See `references/heuristics.md` for the full classification checklist:
- **Branch-related failure**: edit the code, collect all other pending issues (Bugbot, Codex, human reviews), fix everything, run `./gradlew check`, then push once.
- **Likely flaky/unrelated**: rerun via `--retry-failed-now`; retry budget defaults to 3 per SHA.
- **Ambiguous or requires product decision**: stop and ask the user.

## Output format

All modes emit newline-delimited JSON. The `actions` array drives what to do next.
`blocking_review_items` contains actionable inline review comments still attached to the current head SHA:

```json
{
  "pr": { "number": 1, "head_sha": "abc123", "mergeable": "MERGEABLE", ... },
  "checks": { "pending_count": 0, "failed_count": 1, "passed_count": 1, "all_terminal": true },
  "failed_runs": [{ "run_id": 123, "workflow_name": "CI", "conclusion": "failure", "retry_eligible": false, ... }],
  "hung_checks": [],
  "new_review_items": [],
  "blocking_review_items": [],
  "actions": ["diagnose_ci_failure", "stop_non_retryable_failure"],
  "retry_state": { "current_sha_retries_used": 0, "max_flaky_retries": 3 }
}
```
