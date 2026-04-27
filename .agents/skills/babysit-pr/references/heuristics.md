# CI / Review Heuristics

## CI classification checklist

Treat as **branch-related** when logs clearly indicate a regression caused by the PR branch:

- Compile/typecheck/lint failures in files or modules touched by the branch
- Deterministic unit/integration test failures in changed areas
- Snapshot output changes caused by UI/text changes in the branch
- Static analysis violations (Detekt findings) introduced by the latest push
- Build script/config changes in the PR causing a deterministic failure

Treat as **likely flaky or unrelated** when evidence points to transient or external issues:

- DNS/network/registry timeout errors while fetching Gradle/Maven dependencies
- Runner image provisioning or startup failures
- GitHub Actions infrastructure/service outages
- Non-deterministic failures in concurrency or timing-sensitive tests
- Cloud/service rate limits or transient API outages

If uncertain, inspect the failed logs once before choosing rerun.

## Decision tree (fix vs rerun vs stop)

1. If PR is merged/closed: stop.
2. If there are failed checks:
   - Diagnose first.
   - If branch-related: **start fixing locally right away** — do not wait for other checks or bots to finish. But **do not push yet** — continue to steps 3 and 4 first.
   - If likely flaky/unrelated and all checks for the current SHA are terminal: rerun failed jobs.
   - If checks are still pending: wait for them, but if you already know something is broken you can start fixing it now.
3. As Bugbot, Codex, or human reviewers post new comments while you are mid-fix, **incorporate their fixes into the same local batch**. Do not wait idle — keep fixing as issues come in. The only thing to wait for is pushing: do not push until all bots and checks have finished running so you can be sure you have collected everything.
4. **Push once** only when: all fixes are done AND all review bots are done AND `./gradlew check` is green.
5. After the push (and before merging), **resolve every review bot comment thread on GitHub**: if the issue was fixed, resolve the thread; if it does not apply, post a short reply explaining why and then resolve the thread. The PR must have no open bot threads at merge time.
6. If flaky reruns for the same SHA reach the configured limit (default 3): stop and report.

> **Cost rule**: every push triggers Bugbot + Codex runs. Batch all local fixes — CI failures,
> Bugbot comments, Codex comments, human review comments — into a single commit before pushing.
> Never push speculatively mid-fix-cycle.

## Review comment agreement criteria

Address the comment when:

- The comment is technically correct.
- The change is actionable in the current branch.
- The requested change does not conflict with the user's intent or recent guidance.
- The change can be made safely without unrelated refactors.
- After fixing, run `./gradlew check` to confirm no regressions.

Do not auto-fix when:

- The comment is ambiguous and needs clarification.
- The request conflicts with explicit user instructions.
- The proposed change requires product/design decisions the user has not made.
- The codebase is in a dirty/unrelated state that makes safe editing uncertain.

## Stop-and-ask conditions

Stop and ask the user instead of continuing automatically when:

- The local worktree has unrelated uncommitted changes.
- `gh` auth/permissions fail.
- The PR branch cannot be pushed.
- CI failures persist after the flaky retry budget.
- Reviewer feedback requires a product decision or cross-team coordination.
- `./gradlew check` fails after an attempted fix and the cause is not obvious.
