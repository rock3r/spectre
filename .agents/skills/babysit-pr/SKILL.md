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
allowed-tools: Bash(python3 */skills/babysit-pr/scripts/*), Bash(gh pr *), Bash(gh run *), Bash(gh api *), Bash(git fetch *), Bash(git rebase *), Bash(git merge *), Bash(git checkout *), Bash(git switch *), Bash(git push *), Bash(git commit *), Bash(git diff *), Bash(git log *), Bash(git status), Bash(git branch *), Bash(git worktree *), Bash(./gradlew check *), Read, Edit
---

# PR Babysitter

Monitor a PR persistently until one of the terminal states is reached:
- PR merged or closed
- CI fully green, no unaddressed review comments, no merge conflicts
- A situation requiring user intervention

## Inputs

- No PR argument — infer from current branch (`--pr auto`)
- PR number — e.g. `123`
- PR URL — e.g. `https://github.com/ADUX-sandbox/Compose-Pi/pull/123`

## Core workflow

0. **Before running any script**, output a single line so the user knows which PR this conversation is tracking — e.g. `Babysitting PR [#123](https://github.com/ADUX-sandbox/Compose-Pi/pull/123)`. Resolve the PR number/URL from the user's input or the current branch first if needed.
1. Start with `--once` (default) — it blocks until something needs your attention, then returns.
2. Run the watcher to snapshot PR/CI/review state.
3. Inspect the `actions` list in the JSON output.
4. Diagnose CI failures — classify as branch-related (fix and push) vs. flaky (retry).
5. Process actionable review comments from trusted humans, Bugbot, and Codex.
6. Verify mergeability on each loop.
7. After any push, relaunch `--watch` in the same turn.
8. Continue until a terminal stop condition is reached.

## Key commands

```bash
# Wait until something needs attention, then return one snapshot (default)
python3 .agents/skills/babysit-pr/scripts/gh_pr_watch.py --pr auto --once

# Instant snapshot of current state (no waiting)
python3 .agents/skills/babysit-pr/scripts/gh_pr_watch.py --pr auto --snapshot

# Continuously poll, emitting JSONL snapshots (for streaming-capable consumers)
python3 .agents/skills/babysit-pr/scripts/gh_pr_watch.py --pr auto --watch

# Trigger a rerun of failed jobs for the current SHA
python3 .agents/skills/babysit-pr/scripts/gh_pr_watch.py --pr auto --retry-failed-now

# Explicit PR number or URL
python3 .agents/skills/babysit-pr/scripts/gh_pr_watch.py --pr 42 --once
python3 .agents/skills/babysit-pr/scripts/gh_pr_watch.py --pr https://github.com/ADUX-sandbox/Compose-Pi/pull/42 --snapshot
```

## Stop conditions

| `actions` value | Meaning |
|---|---|
| `stop_pr_closed` | PR was merged or closed — done |
| `stop_ready_to_merge` | CI green, no blocking reviews, no conflicts |
| `stop_exhausted_retries` | Flaky reruns hit the retry limit — user must investigate |
| `stop_non_retryable_failure` | Terminal failure is not in retry-eligible workflows — diagnose/fix before continuing |
| `stop_bugbot_not_green` | Cursor Bugbot is not SUCCESS (`neutral`, `skipped`, `failure`, or missing) — do not merge |
| `stop_session_timeout` | `--max-session-minutes` elapsed (default 90 min) — stop and report |
| `diagnose_hung_check` | A pending check has exceeded its hung threshold (Bugbot: 20 min, CI/E2E: 30 min) — stop and report |
| `diagnose_merge_conflict` | PR is merge-conflicted (`CONFLICTING` / `DIRTY`) — resolve conflicts before waiting on checks |
| `diagnose_skipping_checks` | One or more checks completed with `neutral`/`skipping` — investigate why |
| `wait_codex` | Codex is still reviewing (👀 reaction present on the PR) — do not push or merge |

Keep polling when CI is running (`idle`), when new review items arrive (`process_review_comment`),
when Bugbot is still running (`wait_bugbot`), when Codex is still reviewing (`wait_codex`),
or when CI is green but the PR is awaiting approval.

## Post-merge cleanup (when `stop_pr_closed` and PR is merged)

After a PR is merged, clean up the local environment automatically:

1. **If currently on the PR branch, switch away first** (for example to `main`):
   ```bash
   git checkout main
   ```

2. **Delete the local branch** (squash merges leave it unmerged by default):
   ```bash
   git branch -D <head_branch>
   ```

3. **Remove the git worktree**, if the branch was checked out in one:
   ```bash
   # Find worktrees for this branch
   git worktree list
   # Remove if found (adjust path as needed)
   git worktree remove /path/to/worktree
   ```

**How to detect a worktree:** run `git worktree list` and check if any entry's branch matches the PR's `head_branch`. If the current working directory IS the worktree, `cd` to the main checkout first before removing it.

**Only delete the local branch and worktree** — never touch remote branches (the remote is already deleted by GitHub's "delete branch on merge" setting or the `--delete-branch` flag used at merge time).

Skip silently if the branch or worktree doesn't exist locally.

## Push discipline — batch all fixes before pushing (cost control)

Each push triggers new Bugbot + Codex runs. **Never push until all of the following are true:**

1. `./gradlew check` passes locally (no CI failures to fix after the push).
2. Neither Bugbot nor Codex is still `IN_PROGRESS` — wait for both to finish so their comments, if any, can be collected and fixed in the same push.
3. You have incorporated all currently visible actionable Bugbot and Codex comments into the pending local fix batch.

After pushing the fix batch, resolve all bot threads on GitHub (or reply + resolve when no code change is needed). No open bot threads should remain when the PR is merged.

**Workflow when fixes are needed:**

1. Collect all outstanding issues: failed CI logs + any Bugbot/Codex comments already posted.
2. Fix everything locally in one pass.
3. Run `./gradlew check` to confirm green.
4. Only then push — one push per fix cycle.

If either bot finishes while you are mid-fix and posts new comments, incorporate those fixes into the same commit before pushing.

## Conflict + Bugbot batching strategy (use this when PR shows `CONFLICTING`/`DIRTY`)

When GitHub reports merge conflicts while Bugbot/Codex/CI is still running:

1. **Do not push immediately.** Wait until neither Bugbot nor Codex is `IN_PROGRESS`.
2. Snapshot latest status/comments.
3. If conflict remains, rebase branch onto `origin/main` (or merge main if repo policy prefers).
4. Resolve conflicts and **in the same fix cycle** apply all actionable Bugbot/Codex comments.
5. Run `./gradlew check`.
6. Push once.

Rationale: this avoids paying for multiple Bugbot/Codex reruns and prevents a ping-pong where a conflict-fix push is immediately followed by a second bot-fix push.

## Bugbot + Codex merge gate (mandatory)

**Never merge until both Cursor Bugbot and Codex report clean.**

### Bugbot (CI check)

For Bugbot, only `SUCCESS` is a green gate.

- If Bugbot is still `IN_PROGRESS` → keep polling, do not push.
- If Bugbot conclusion is `NEUTRAL` → it found potential issues. Read the inline PR review comments left by `cursor[bot]`, fix every reported issue locally, run `./gradlew check`, then push once (see push discipline above).
- If Bugbot conclusion is `SKIPPING` → treat as **not green**. Bugbot may have posted review comments before skipping. Always check `gh api repos/{owner}/{repo}/pulls/{pr}/comments` for `cursor[bot]` comments. If comments exist, fix them before merging. If no comments exist, re-request review or ask the user.
- If Bugbot conclusion is `SUCCESS` → proceed (Bugbot gate is clear).
- Do **not** merge on NEUTRAL or SKIPPING, even if all other checks pass.

### Codex (emoji reaction)

Codex does **not** use a CI check. Instead it uses emoji reactions on the PR:

- **👀 reaction present** from `chatgpt-codex-connector[bot]` → Codex is actively reviewing. The `codex_gate.reviewing` field will be `true` and a `wait_codex` action will be emitted. Do not push or merge.
- **👀 reaction removed, no new review comments** → Codex is satisfied. Proceed.
- **👀 reaction removed, review comments posted** → Codex found issues. Fix them the same way as Bugbot comments (see push discipline).

The watcher automatically detects the 👀 reaction via the PR reactions API and surfaces `codex_gate` in the snapshot.

## Decision rules

See `references/heuristics.md` for the full classification checklist:
- **Branch-related failure**: edit the code, collect all other pending issues (Bugbot, Codex, human reviews), fix everything, run `./gradlew check`, then push once.
- **Likely flaky/unrelated**: rerun via `--retry-failed-now`; retry budget defaults to 3 per SHA.
  - The watcher only auto-reruns retry-eligible workflows (currently E2E-style workflows).
  - CI/check workflow failures are treated as diagnose/fix-first by default.
- **Ambiguous or requires product decision**: stop and ask the user.

## Review bots

The watcher surfaces feedback from:
- **cursor[bot]** — Cursor Bugbot (CI check-based code review)
- **chatgpt-codex-connector[bot]** — OpenAI Codex (emoji reaction-based code review)
- Trusted humans: authors with `OWNER`, `MEMBER`, or `COLLABORATOR` association

> **Note**: if additional review bots are enabled on the repo (e.g. GitHub Actions summary
> bots), add their login keyword to `REVIEW_BOT_LOGIN_KEYWORDS` in `scripts/gh_pr_watch.py`.

## Worktree gotchas

When working from a git worktree, watch out for ktfmt CLI vs Gradle plugin version
mismatches and rebases silently reverting fixes. Always run `./gradlew check`
before pushing. See [docs/WORKTREE-GRADLE-PITFALLS.md](../../../docs/WORKTREE-GRADLE-PITFALLS.md)
for details and a pre-push checklist.

## Choosing a mode based on harness capabilities

The right mode depends on whether the harness can stream bash tool stdout back to the
model while the command is still running, or only delivers the final output after exit.

### Harness streams tool output to the model (e.g. Claude Code subagents)

Use `--watch`. The script runs continuously, emitting JSONL snapshots as events. The
model sees each snapshot as it arrives and can act on it (retry, fix, merge) without
waiting for the script to exit. The script exits on terminal stop conditions.

### Harness only returns output after tool exit (e.g. Pi, most tool-use loops)

Use `--once` (the default). The script blocks internally, polling every 30 seconds,
and only returns to the model when something needs agent attention — a CI failure to
diagnose, a review comment to process, a merge readiness signal, etc. The model never
sleeps blindly; the script handles all waiting. After acting on the result, the model
calls `--once` again to wait for the next event.

Typical agent loop:
1. Run `--once` → script blocks until CI finishes, review arrives, etc.
2. Model reads the snapshot, acts on `actions` (fix code, retry, merge).
3. If not terminal, run `--once` again → repeat.

### Quick debugging / one-off inspection

Use `--snapshot` for an instant point-in-time view with no waiting.

## Output format

All modes emit newline-delimited JSON.

- `--once` / `--snapshot` / `--retry-failed-now`: emit a top-level snapshot/result object where `actions` is directly available.
- `--watch`: emits event envelopes:
  - `{"event":"snapshot","payload":{"snapshot":{...},"state_file":"...","next_poll_seconds":30}}`
  - `{"event":"stop","payload":{...}}`

In `--watch`, read actions from `payload.snapshot.actions` for `snapshot` events and `payload.actions` for `stop` events.

`blocking_review_items` contains actionable unresolved inline review comments. When thread-resolution lookup is unavailable, inline blocking falls back to a 30-minute freshness heuristic. While non-empty, `stop_ready_to_merge` is not emitted.

Example snapshot payload shape (`--once` / `--snapshot`, or `--watch` under `payload.snapshot`):

```json
{
  "pr": { "number": 42, "head_sha": "abc123", "mergeable": "MERGEABLE", ... },
  "checks": { "pending_count": 0, "failed_count": 1, "passed_count": 8, "skipping_count": 0, "all_terminal": true },
  "failed_runs": [{ "run_id": 123, "workflow_name": "CI", "conclusion": "failure", "retry_eligible": false, ... }],
  "bugbot_gate": { "status": "completed", "conclusion": "success", "is_success": true, ... },
  "codex_gate": { "reviewing": false, "status": "idle" },
  "hung_checks": [{ "name": "CI", "elapsed_seconds": 1920, "threshold_seconds": 1800 }],
  "new_review_items": [],
  "blocking_review_items": [],
  "actions": ["diagnose_ci_failure", "stop_non_retryable_failure"],
  "retry_state": { "current_sha_retries_used": 0, "max_flaky_retries": 3 }
}
```
