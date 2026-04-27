# Worktree Pitfalls

Lessons learned from working in git worktrees on this project.

## ktfmt Version Mismatch

The project's Gradle plugin bundles a specific ktfmt formatter version. If you have a
different `ktfmt` CLI installed locally (e.g. via Homebrew), the two may format
differently. Specifically:

- The CLI may leave unused imports that the Gradle plugin removes.
- Line-wrapping thresholds may differ slightly.

**Always verify with `./gradlew check`, not the CLI.** The CI uses Gradle.

## Rebase Silently Reverts Fixes

When rebasing a long-lived branch, git may silently revert changes that conflict with
upstream. After every rebase:

1. Find the merge base: `git merge-base HEAD origin/main` (note the commit hash).
2. List your branch's changed files: `git diff --name-only <merge-base-hash>`.
3. Verify your changes survived: `git diff origin/main -- <those-files>`.
4. Re-run `./gradlew check` locally before pushing.
5. Pay special attention to files that were modified by both your branch and main.

Common casualties during rebase:

- Conditional guards (`if (x > 0)` wrappers around existing code)
- Import removals (git re-adds them from main's version)
- Label/text changes that main also touched

## Pre-Push Checklist

Before every push from a worktree:

- [ ] `./gradlew check` passes (this is the CI gate — detekt + ktfmt + tests)
- [ ] After rebase: verified key changes survived (see above)

For faster iteration *before* the final push, target individual checks:

- `./gradlew :test --tests "*TestName*"` — run a specific failing test
- `./gradlew ktfmtCheckMain` — verify formatting only
- `./gradlew detektMain` — verify detekt only

**Always run `./gradlew check` before the actual push.**
