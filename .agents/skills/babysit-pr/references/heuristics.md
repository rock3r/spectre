# CI and review heuristics

"The local gate" below means the `local_gate` command from `config.json`. When it is `null`, use the check command
that the repository's own docs name.

## CI classification checklist

Treat a failure as **branch-related** when the logs show a regression that the PR branch caused:

- Compile, type-check, or lint failures in files or modules that the branch touches.
- Deterministic unit or integration test failures in changed areas.
- Snapshot changes caused by UI or text changes in the branch.
- Static analysis findings that the latest push introduced.
- Build script or config changes in the PR that cause a deterministic failure.

Treat a failure as **likely flaky or unrelated** when the evidence points to a transient or external problem:

- DNS, network, or registry timeouts while fetching dependencies.
- Runner image provisioning or startup failures.
- GitHub Actions outages.
- Non-deterministic failures in concurrency or timing-sensitive tests.
- Rate limits or short outages of external services.

If you are not sure, read the failed logs once before you choose a rerun.

## Decision tree: fix, rerun, or stop

1. If the PR is merged or closed, stop.
2. If checks failed:
   - Diagnose first.
   - If the failure is branch-related, **start fixing locally right away**. Do not wait for other checks or bots to
     finish. But **do not push yet**. Go on to steps 3 and 4 first.
   - If the failure is likely flaky or unrelated and every check for the current SHA is done, rerun the failed jobs
     with `--retry-failed-now`. The watcher reruns only workflows that match `retry_eligible_workflow_keywords`.
   - If checks are still pending, wait for them. If you already know that something is broken, you can start
     fixing it now.
3. When bots or human reviewers post new comments while you are fixing, **fold their fixes into the same local
   batch**. Do not wait idle. The only thing that waits is the push: push only after the review bots and the checks
   have finished, so that you have collected everything.
4. **Push once**, and only when all fixes are done, the review bots and checks are done, and the local gate is
   green.
5. After the push, and before the merge, **resolve every review bot thread on GitHub**. If you fixed the issue,
   resolve the thread. If it does not apply, reply with a short reason and then resolve the thread. No bot thread
   may be open at merge time.
6. If reruns for the same SHA reach the limit (3 by default), stop and report.

> **Cost rule**: every push starts new CI runs and new bot reviews. Put all local fixes (CI failures, bot comments,
> human comments) into one commit before you push. Never push in the middle of a fix cycle.

## When to act on a review comment

Act on the comment when all of these hold:

- The comment is technically correct.
- You can act on it in the current branch.
- The change does not conflict with the owner's intent or recent guidance.
- You can make the change safely, without unrelated refactors.

After the fix, run the local gate to confirm that nothing else broke.

Do not fix it on your own when:

- The comment is ambiguous and needs clarification.
- The request conflicts with explicit instructions from the owner.
- The change needs a product or design decision that the owner has not made.
- The working tree has unrelated changes that make a safe edit uncertain.
- The change would weaken a security rule of the repository. Report it instead.

## Stop-and-ask conditions

Stop and ask the owner instead of going on alone when:

- The local working tree has unrelated uncommitted changes.
- `gh` authentication or permissions fail.
- You cannot push the PR branch.
- CI failures continue after the rerun budget is used.
- Reviewer feedback needs a product decision or coordination with other teams.
- The local gate fails after a fix and the cause is not obvious.
