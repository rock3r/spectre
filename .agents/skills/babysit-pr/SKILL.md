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
metadata:
  internal: true
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
5. Process actionable review comments from trusted humans, Codex, and CodeRabbit.
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
| `stop_session_timeout` | `--max-session-minutes` elapsed (default 90 min) — stop and report |
| `diagnose_hung_check` | A pending check has exceeded its hung threshold (30 min) — stop and report |
| `diagnose_merge_conflict` | PR is merge-conflicted (`CONFLICTING` / `DIRTY`) — resolve conflicts before waiting on checks |
| `diagnose_branch_behind` | PR head is `BEHIND` the base branch — update the branch (rebase or merge base) before waiting on checks; `stop_ready_to_merge` is never emitted while behind |
| `diagnose_skipping_checks` | One or more checks completed with `neutral`/`skipping` — investigate why |
| `wait_codex` | Codex is still reviewing (👀 reaction present on the PR) — do not push or merge |
| `wait_coderabbit` | CodeRabbit is still reviewing (its check is pending, or its 👀 reaction is present) — do not push or merge |

Keep polling when CI is running (`idle`), when new review items arrive (`process_review_comment`),
when Codex is still reviewing (`wait_codex`), when CodeRabbit is still reviewing
(`wait_coderabbit`), or when CI is green but the PR is awaiting approval.

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

Each push triggers new Codex runs. **Never push until all of the following are true:**

1. `./gradlew check` passes locally (no CI failures to fix after the push).
2. No review bot is still reviewing — Codex is not `IN_PROGRESS`, and CodeRabbit (when active on the PR) is not reviewing — so their comments, if any, can be collected and fixed in the same push.
3. You have incorporated all currently visible actionable Codex and CodeRabbit comments into the pending local fix batch.

After pushing the fix batch, resolve all bot threads on GitHub (or reply + resolve when no code change is needed). No open bot threads should remain when the PR is merged.

**Workflow when fixes are needed:**

1. Collect all outstanding issues: failed CI logs + any Codex/CodeRabbit comments already posted.
2. Fix everything locally in one pass.
3. Run `./gradlew check` to confirm green.
4. Only then push — one push per fix cycle.

If a bot finishes while you are mid-fix and posts new comments, incorporate those fixes into the same commit before pushing.

## Conflict + review-bot batching strategy (use this when PR shows `CONFLICTING`/`DIRTY`)

When GitHub reports merge conflicts while Codex/CodeRabbit/CI is still running:

1. **Do not push immediately.** Wait until no review bot is still reviewing.
2. Snapshot latest status/comments.
3. If conflict remains, rebase branch onto `origin/main` (or merge main if repo policy prefers).
4. Resolve conflicts and **in the same fix cycle** apply all actionable Codex/CodeRabbit comments.
5. Run `./gradlew check`.
6. Push once.

Rationale: this avoids paying for multiple Codex reruns and prevents a ping-pong where a conflict-fix push is immediately followed by a second bot-fix push.

## Codex + CodeRabbit merge gate (mandatory)

**Never merge until Codex reports clean, and CodeRabbit — when active on the PR — has finished reviewing.**

> Cursor Bugbot has been retired and no longer gates anything. A leftover `Cursor Bugbot`
> check on an old PR is now treated like any other check: if it completes `neutral`/`skipping`
> it shows up under `diagnose_skipping_checks`, not as a dedicated gate.

### Codex (emoji reaction)

Codex does **not** use a CI check. Instead it uses emoji reactions on the PR:

- **👀 reaction present** from `chatgpt-codex-connector[bot]` → Codex is actively reviewing. The `codex_gate.reviewing` field will be `true` and a `wait_codex` action will be emitted. Do not push or merge.
- **👀 reaction removed, no new review comments** → Codex is satisfied. Proceed.
- **👀 reaction removed, review comments posted** → Codex found issues. Fix them locally and batch them into the next push (see push discipline).

The watcher automatically detects the 👀 reaction via the PR reactions API and surfaces `codex_gate` in the snapshot.

### CodeRabbit (presence-conditional)

CodeRabbit (`coderabbitai[bot]`) is **not** assumed to be present on a PR. It only gates the merge when it shows signs of life — a CodeRabbit CI check, or a reaction from the CodeRabbit bot. When dormant, the gate is inert and the watcher behaves as a Codex-only gate, so nothing breaks if CodeRabbit is removed from the repo.

- **CodeRabbit check pending, or 👀 reaction present** from `coderabbitai[bot]` → still reviewing. `coderabbit_gate.reviewing` is `true` and a `wait_coderabbit` action is emitted. Do not push or merge.
- **Check completed / non-eyes reaction only** → CodeRabbit is active but done (`"status": "active"`). Proceed; any review comments it posted are surfaced and block merge via the normal review-item path.
- **No check, no reactions, no comments** → dormant (`"status": "idle"`). The gate does not block.

The snapshot surfaces this as `coderabbit_gate` with `active`, `present_check`, `reviewing`, and `status` fields.

## Decision rules

See `references/heuristics.md` for the full classification checklist:
- **Branch-related failure**: edit the code, collect all other pending issues (Codex, CodeRabbit, human reviews), fix everything, run `./gradlew check`, then push once.
- **Likely flaky/unrelated**: rerun via `--retry-failed-now`; retry budget defaults to 3 per SHA.
  - The watcher only auto-reruns retry-eligible workflows (currently E2E-style workflows).
  - CI/check workflow failures are treated as diagnose/fix-first by default.
- **Ambiguous or requires product decision**: stop and ask the user.

## Review bots

The watcher surfaces feedback from:
- **chatgpt-codex-connector[bot]** — OpenAI Codex (emoji reaction-based code review)
- **cursor[bot]** — retired Cursor Bugbot; its login stays in `REVIEW_BOT_LOGIN_KEYWORDS` so any leftover comment is still surfaced, but it no longer gates anything
- **coderabbitai[bot]** — CodeRabbit (presence-conditional: gates only when its check or a reaction shows it is active on the PR; see the CodeRabbit gate section)
- Trusted humans: authors with `OWNER`, `MEMBER`, or `COLLABORATOR` association

> **Note**: if additional review bots are enabled on the repo (e.g. GitHub Actions summary
> bots), add their login keyword to `REVIEW_BOT_LOGIN_KEYWORDS` in `scripts/gh_pr_watch.py`.

## Worktree gotchas

When working from a git worktree, watch out for ktfmt CLI vs Gradle plugin version
mismatches and rebases silently reverting fixes. Always run `./gradlew check`
before pushing — see the
[pre-push checklist](../../../AGENTS.md#pre-push-checklist) in AGENTS.md.

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

`blocking_review_items` contains actionable unresolved inline review comments. When thread-resolution lookup is unavailable, the watcher fails closed: comments whose resolution state is unknown stay blocking regardless of age or commit. While non-empty, `stop_ready_to_merge` is not emitted.

Example snapshot payload shape (`--once` / `--snapshot`, or `--watch` under `payload.snapshot`):

```json
{
  "pr": { "number": 42, "head_sha": "abc123", "mergeable": "MERGEABLE", ... },
  "checks": { "pending_count": 0, "failed_count": 1, "passed_count": 8, "skipping_count": 0, "all_terminal": true },
  "failed_runs": [{ "run_id": 123, "workflow_name": "CI", "conclusion": "failure", "retry_eligible": false, ... }],
  "codex_gate": { "reviewing": false, "status": "idle" },
  "coderabbit_gate": { "active": false, "present_check": false, "reviewing": false, "status": "idle" },
  "hung_checks": [{ "name": "CI", "elapsed_seconds": 1920, "threshold_seconds": 1800 }],
  "new_review_items": [],
  "blocking_review_items": [],
  "actions": ["diagnose_ci_failure", "stop_non_retryable_failure"],
  "retry_state": { "current_sha_retries_used": 0, "max_flaky_retries": 3 }
}
```
