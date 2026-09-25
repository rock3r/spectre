---
name: babysit-pr
description: >
  Use when asked to watch, monitor, or babysit an open pull request: poll CI and reviews until it is ready to
  merge, fix branch-caused failures, retry flaky runs, and stop when a human decision is needed.
---

# PR babysitter

This skill watches a PR until one of three things happens:

- the PR is merged or closed;
- CI is green, no review threads are unresolved, and there are no conflicts;
- something needs the owner.

The watcher script does the polling. You diagnose, fix, and triage. The watcher is the only authority on
readiness. Use `gh pr checks`, `gh pr view`, and similar commands only to diagnose a state that the watcher
reported. Never use them to decide that a PR is green. If the watcher does not work, report that as a tooling
problem. Do not replace it with your own polling.

## Before you start

1. Read `config.json` in this skill folder, or run the watcher with `--print-config`. It tells you the project's
   local gate, the checks it expects, the review bots that gate a merge, and the cleanup rules. The
   [config table](#project-config) explains each key.
2. Check that the `gh` CLI is installed and authenticated: `gh auth status`.
3. Tell the owner which PR you are tracking, with its link.
4. Before you edit anything, check that you are in the right checkout. `HEAD` must be the PR's `head_branch` at
   its `head_sha` from the snapshot. If you started from another branch, check the PR branch out in its own
   worktree first.

## Project config

The file is `.agents/skills/babysit-pr/config.json`. The sync script creates it from `config.example.json` and
never overwrites it. A missing key uses its default. An unknown key prints a warning. A value of the wrong type
stops the watcher with an error that names the key.

| Key | Default | What it means for you |
|---|---|---|
| `local_gate` | `null` | The command to run before every push, for example `./gradlew check`. When it is `null`, use the check command that the repository's own docs name. |
| `expected_skipped_checks` | `[]` | Check names that are skipped on every PR by design. The watcher ignores their `skipping` result. |
| `required_checks` | `[]` | Check names that must pass before the PR is ready. |
| `retry_eligible_workflow_keywords` | `["e2e"]` | The watcher reruns failed workflows whose name contains one of these words. Every other failure needs a diagnosis first. |
| `hung_check_minutes` | `30` | A check that stays pending longer than this is reported as hung. |
| `trusted_author_associations` | `["OWNER", "MEMBER", "COLLABORATOR"]` | Comments from these authors are review items. |
| `review_bot_login_keywords` | `["codex"]` | Comments from `[bot]` accounts whose login contains one of these words are review items. |
| `max_session_minutes` | `90` | The default for `--max-session-minutes`. |
| `require_up_to_date` | `"auto"` | Whether a branch that is behind its base must be updated before merge. `"auto"` reads the base branch's protection and rulesets. `true` or `false` skips that lookup. |
| `codex.enabled` | `true` | Watch the Codex review bot. |
| `codex.required` | `false` | Require a Codex review of the head, even on a PR where Codex never posted. |
| `pr_af.enabled` | `false` | Watch the label-triggered PR-AF review. The other `pr_af` keys describe it. |
| `pr_af.label` | `"pr-af"` | The PR label that asks for a PR-AF review. |
| `pr_af.workflow_names`, `pr_af.check_names` | `[]` | The names of the PR-AF workflow and check. A name matches either list. |
| `pr_af.review_body_markers` | `[]` | Text that marks a comment as a PR-AF finding. |
| `pr_af.review_author_login` | `"github-actions[bot]"` | The login that PR-AF posts as. |
| `pr_af.missing_check_grace_minutes` | `5` | How long a labelled head waits for its PR-AF check to appear. |
| `cleanup.branch_delete_requires_approval` | `false` | When `true`, ask the owner before you delete a merged branch. |
| `sync.keep` | `[]` | Paths or globs in this skill folder that belong to the repository. `sync.py` never deletes or overwrites them. `skill-source.json` is always kept. Do not put your own files in this folder unless they are listed here. |

## The watcher

Run these from the repository root:

```bash
# Block until something needs attention, then return one snapshot (the default mode)
python3 .agents/skills/babysit-pr/scripts/gh_pr_watch.py --pr auto --once

# Take an instant snapshot, without waiting
python3 .agents/skills/babysit-pr/scripts/gh_pr_watch.py --pr auto --snapshot

# Stream snapshots as JSON lines until a terminal action
python3 .agents/skills/babysit-pr/scripts/gh_pr_watch.py --pr auto --watch

# Rerun the failed jobs of retry-eligible workflows for the current head SHA
python3 .agents/skills/babysit-pr/scripts/gh_pr_watch.py --pr auto --retry-failed-now

# Show the effective config
python3 .agents/skills/babysit-pr/scripts/gh_pr_watch.py --print-config
```

`--pr` takes `auto` (the PR of the current branch), a PR number, or a PR URL. `--config <path>` reads another
config file.

`--once` polls inside the script every 30 seconds. It returns only when `actions` contains something other than a
passive wait. Use it when the harness returns tool output only after the command exits, which is the usual case.
After you act on the actions, run `--once` again. `--watch` streams `{"event":"snapshot",...}` objects as the state
changes and ends with `{"event":"stop",...}`. Use it only when the harness can read streamed output while the
command runs. `--max-session-minutes` limits both modes.

The output is JSON lines. Where the actions are depends on the mode:

| Mode | Read the actions from |
|---|---|
| `--once`, `--snapshot` | top-level `actions` |
| `--retry-failed-now` | `snapshot.actions`. The top level reports the rerun: `rerun_attempted`, `rerun_count`, `reason`. |
| `--watch` | `payload.snapshot.actions` on `snapshot` events, `payload.actions` on `stop` events |

Other useful snapshot fields are `checks` (pending, failed, passed and skipping counts, `all_terminal`,
`required_missing`, and `check_count`), `failed_runs` (with `retry_eligible`), `codex_gate`, `pr_af_gate`,
`hung_checks`, `new_review_items`, `blocking_review_items`, and `retry_state`.

`blocking_review_items` lists unresolved inline comments. While it is not empty, the watcher never reports the PR
as ready. It includes open threads that the authenticated account started, although those never appear in
`new_review_items`. When the thread lookup fails, every actionable inline comment blocks until the lookup works
again.

## Actions

"Returns" means `--once` returns with this action. "Ends" means `--watch` stops on it.

| `actions` value | Meaning | Returns | Ends |
|---|---|---|---|
| `idle` | CI is running and there is nothing to do. | no | no |
| `wait_codex` | Codex is reviewing, or has not finished a review of the head yet. Do not push or merge. | no | no |
| `wait_pr_af` | A PR-AF check for the head is running, or has not appeared yet. | no | no |
| `process_review_comment` | There are new or unresolved review items. Triage them. | yes | no |
| `diagnose_ci_failure` | A check failed. Classify it before you act. | yes | no |
| `retry_failed_checks` | Only retry-eligible workflows failed. Rerun them with `--retry-failed-now`. | yes | no |
| `diagnose_codex_review` | Codex reports a failed or unknown review status for the head. | yes | no |
| `request_codex_review` | Codex is required but has not reviewed the head: it never posted on the PR, or its latest review is of an older commit. Comment `@codex review` on the PR. | yes | no |
| `diagnose_merge_conflict` | The PR is `CONFLICTING` or `DIRTY`. It waits while a review bot runs. | yes | yes |
| `diagnose_branch_behind` | The branch is behind its base, and the base requires up-to-date branches. It waits while a review bot runs. | yes | yes |
| `diagnose_merge_blocked` | Every check is green, but GitHub still says `BLOCKED` and nothing else explains it. | yes | yes |
| `diagnose_no_checks` | GitHub reports no check at all for the PR, even after the grace period. | yes | yes |
| `diagnose_missing_required_checks` | Every check is done, but a check from `required_checks` never passed. See `checks.required_missing`. | yes | yes |
| `diagnose_hung_check` | A check has been pending for longer than `hung_check_minutes`. | yes | yes |
| `diagnose_skipping_checks` | A check that should run was skipped or neutral. Find out why. | yes | yes |
| `stop_non_retryable_failure` | A workflow that the watcher does not rerun failed. Fix it first. | yes | yes |
| `stop_exhausted_retries` | Reruns used the budget for this SHA (3 by default). The owner must investigate. | yes | yes |
| `stop_ready_to_merge` | CI is green, no review blocks it, and there are no conflicts. | yes | yes |
| `stop_draft_pr` | CI is green but the PR is a draft. Ask the owner to mark it ready. | yes | yes |
| `stop_pr_closed` | The PR is merged or closed. | yes | yes |
| `stop_session_timeout` | The session limit has passed. It comes with the last snapshot's actions. Report and stop. | yes | yes |

What to do for the less obvious ones:

- `diagnose_merge_blocked`: the usual causes are a required status check that never reports, a required review
  from a code owner, a required signature, or a repository ruleset. Look at the branch rules with
  `gh api repos/{owner}/{repo}/rules/branches/<base>`. Tell the owner what blocks the merge. Never bypass it.
- `diagnose_no_checks`: find out why no workflow runs on this PR. Common causes are path or branch filters, a
  disabled workflow, a PR from a fork that needs approval to run workflows, or a repository without CI. Tell the
  owner. A PR without checks is never reported as ready.
- `diagnose_missing_required_checks`: find out why the check did not run. Common causes are path filters, a
  disabled workflow, or a trigger that does not fire on PRs.
- `diagnose_codex_review`: read the Codex summary comment on the PR. If the review failed, ask again with
  `@codex review`. If it fails again, tell the owner.
- `diagnose_hung_check` and `diagnose_skipping_checks`: look at the run with `gh run view`. Report what you find.

`references/heuristics.md` lists how to tell a branch-caused failure from a flaky one, and when to stop and ask.
`references/github-api-notes.md` documents the `gh` calls and JSON fields that the watcher uses.

## Review gates

**Codex** has no CI check. `chatgpt-codex-connector[bot]` adds a 👀 reaction to the PR while it reviews and removes
it when it is done. The watcher reads the reactions into `codex_gate.reviewing` and emits `wait_codex`.

- Reaction gone, no comments: Codex is satisfied.
- Reaction gone, comments posted: triage them like any other review finding.

A missing reaction is not proof on its own: right after a push, Codex may not have started. Codex keeps a "Codex
Review Summary" table on the PR with the status and commit of its latest review. When that table exists, the
watcher also requires a **Completed** review of the head commit (`codex_gate.head_reviewed`). A PR without the
table does not have Codex active, so this check does not apply, unless `codex.required` is `true`. With
`codex.required`, the watcher asks for a review (`request_codex_review`) once the checks are done and the grace period
has passed, when Codex is idle and has not reviewed the head. The table is a status, not a finding, so the watcher never reports it as a review item. When the reactions cannot be read, the
gate stays closed.

**PR-AF** runs when the PR has the `pr_af.label` label, and again on every push while the label stays. Its check is
advisory. A running PR-AF check on the head holds readiness with `wait_pr_af`. A failed, skipped, cancelled, or hung
PR-AF check never counts as a CI failure. Its comments are not advisory: unresolved PR-AF comments block like any
other review item. PR-AF usually posts as the shared `github-actions[bot]` login. A comment counts as a PR-AF
finding only when it carries a marker from `pr_af.review_body_markers`, or belongs to a review that does, and only
while a PR-AF check exists on the head. When the label is present but GitHub has not shown the check yet, the
watcher waits up to `pr_af.missing_check_grace_minutes`.

**Trusted humans** are authors with an association from `trusted_author_associations`. To surface another bot's
comments, add a login word to `review_bot_login_keywords`. Never add the shared `github-actions[bot]` login there.

"Clean" means that every finding is dispositioned: fixed, or filed as an issue with the link on the thread and the
thread resolved.

## Push discipline

Every push starts new bot reviews. Push once per fix cycle, when all of these hold:

1. Every known issue is fixed locally: failed CI logs, bot findings, and human comments.
2. No review bot is in the middle of a review, so its comments arrive in the same batch.
3. The local gate (`local_gate` in the config) is green.

Start fixing branch-caused failures as soon as you have diagnosed them. Only the push waits. If a bot posts while you
are fixing, fold its findings into the same batch. Follow the repository's own rules (for example in `AGENTS.md`)
about when you may push and merge.

After the push, resolve every bot thread on GitHub. If nothing changed for a comment, reply with the reason first.
No bot thread may be open at merge time.

## Updating the branch

`diagnose_merge_conflict` and `diagnose_branch_behind` both mean that the branch needs its base merged in. A branch
that is only behind its base is not a problem when the base does not require up-to-date branches. The watcher checks
that for you (`pr.up_to_date_required` in the snapshot) and then does not report it.

1. Wait until no review bot is running. The watcher already does this for you.
2. Find the real base with `gh pr view <n> --json baseRefName`. Never assume `main`. Fetch that base from the
   repository that the PR targets. For a PR from a fork, `origin` is the fork, so use the remote of the target
   repository.
3. Merge the updated base into the PR branch. Resolve any conflicts, and fold in the outstanding review fixes.
4. Run the local gate and push once. A merge keeps the branch history, so a normal push is enough.

Rebase only when the owner asks for it. A rebase rewrites the branch history and needs
`git push --force-with-lease`. Never use a plain force push. After a merge or a rebase, check that your earlier fixes
are still there.

## Triage every finding before fixing it

A true finding is not automatically a fix for this PR. Classify it on two axes, and say which box it landed in when
you reply:

| | Blocker | Improvement |
|---|---|---|
| In scope | Fix now. | Fix now if trivial, otherwise file an issue. |
| Out of scope | File an issue, and say so on the PR. | File an issue. |

A finding is in scope when it is about the behaviour this PR set out to change. Ask: would this defect exist on the
base branch without my change? If it would, it is pre-existing and belongs in an issue, however real it is.

A blocker means shipping would cause visible harm, data loss, or a security hole. Stricter validation, hardening an
adjacent path, and "this could also be wrong if" are improvements, however confidently a bot reports them. A fix
that would make something that works today stop working is a scope decision for the owner.

Stop the review loop and ask the owner when any of these hold:

- Three rounds in a row produce no in-scope blocker.
- Twice in a row, a finding is about code that only exists because of an earlier finding in this PR.
- The diff has grown well beyond the task. Check with `git diff --stat` against the base, not from memory.
- Every push produces new findings, so the "no outstanding findings" gate can never be met.

When you stop, bring numbers: commits, diff size, and files. Propose how to split the work. Before you revert
out-of-scope work, keep it on a branch (`git branch followup/<topic>`) and tell the owner about it.

## Post-merge cleanup

`stop_pr_closed` fires for a PR that was closed without merging, too. Clean up only when the PR was merged: check
`pr.merged` in the snapshot, or run `gh pr view <n> --json mergedAt` and confirm that `mergedAt` is set. For a PR
that was closed without merging, keep everything and tell the owner.

When `cleanup.branch_delete_requires_approval` is `true`, ask the owner before step 2, and skip it without their
approval. Deleting a branch is destructive.

Run every step from the main checkout, never from inside the PR's worktree. Skip any step whose worktree or branch
does not exist. Clean up local state only. Never delete remote branches.

1. If `git worktree list` shows the PR branch in a linked worktree, run `git worktree remove <path>`. Git refuses
   when the worktree has uncommitted changes. Do not force it; tell the owner instead. Remove the worktree before
   deleting the branch, because Git will not delete a branch that a worktree still uses.
2. Delete the local branch only when nothing would be lost. Squash merges leave the branch looking unmerged, so
   `git branch -d` refuses and `git branch -D` is needed. Force-deleting a branch is a destructive history change,
   so first prove that the local tip is exactly the head that was merged:

   ```bash
   merged_head=$(gh pr view <n> --json headRefOid --jq .headRefOid)
   test "$(git rev-parse <head_branch>)" = "$merged_head" && git branch -D <head_branch>
   ```

   If the local tip differs, it has commits that were never merged. Keep the branch, and tell the owner.
3. Update the base branch in the main checkout with `git pull --ff-only`.
