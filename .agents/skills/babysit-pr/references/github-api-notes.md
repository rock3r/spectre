# GitHub CLI and API notes for `babysit-pr`

This page lists the `gh` calls that the watcher makes and the fields that it reads. Every call has a 60-second
timeout, and the watcher decodes all output as UTF-8.

## Commands

### PR metadata

```bash
gh pr view --json number,url,state,mergedAt,closedAt,headRefName,headRefOid,headRepository,headRepositoryOwner,baseRefName,mergeable,mergeStateStatus,reviewDecision,labels
```

The watcher uses this to find the PR number, URL, branch, head SHA, labels, and the closed, merged, and mergeable
state.

### PR checks

```bash
gh pr checks <n> --json name,state,bucket,link,workflow,event,startedAt,completedAt
```

The watcher uses this to count pending, failed, passed, and skipped checks, and to tell whether the current CI round
is done. `bucket` values are `pass`, `fail`, `pending`, `skipping`, and `cancel`. The watcher counts `cancel` as a
failure.

`gh pr checks` exits with code 1 when a check failed and with code 8 while checks are pending. In both cases it
still prints the requested JSON, so the watcher reads that JSON. A PR without any check also makes it exit with code
1, with no JSON and the message "no checks reported". The watcher reads that as an empty check list. Without JSON in
any other case, or with any other non-zero exit code, the call counts as failed.

### Workflow runs for the head SHA

```bash
gh api repos/{owner}/{repo}/actions/runs -X GET -f head_sha=<sha> -f per_page=100 -f event=pull_request
```

The watcher uses this to find failed workflow runs and the run IDs to rerun. It keeps only the latest run of each
workflow.

### Failed log inspection

```bash
gh run view <run-id> --json jobs,name,workflowName,conclusion,status,url,headSha
gh run view <run-id> --log-failed
```

Use these to tell a branch-related failure from a flaky or unrelated one.

### Rerun failed jobs only

```bash
gh run rerun <run-id> --failed
```

This reruns only the failed jobs of a run, and the jobs they depend on.

### Up-to-date requirement of the base branch

```bash
gh api repos/{owner}/{repo}/branches/<base>/protection/required_status_checks
gh api "repos/{owner}/{repo}/rules/branches/<base>?per_page=100&page=<p>"
```

The watcher makes these calls only for a PR whose `mergeStateStatus` is `BEHIND`, and only when
`require_up_to_date` is `"auto"`. It remembers the answer for the rest of the run. The base requires up-to-date
branches when `strict` is `true`, or when a `required_status_checks` rule has
`parameters.strict_required_status_checks_policy` set to `true`. A 403 or 404 answer means no such requirement.

## Review endpoints

```bash
# Issue comments on the PR, including the Codex review summary
gh api "repos/{owner}/{repo}/issues/<n>/comments?per_page=100&page=<p>"

# Inline review comments
gh api "repos/{owner}/{repo}/pulls/<n>/comments?per_page=100&page=<p>"

# Review submissions. When this call fails, the watcher lists reviews through GraphQL instead.
gh api "repos/{owner}/{repo}/pulls/<n>/reviews?per_page=100&page=<p>"

# Reactions on the PR (the Codex 👀 reaction), all pages
gh api "repos/{owner}/{repo}/issues/<n>/reactions?per_page=100&page=<p>"

# The authenticated login, so the watcher can skip its own comments
gh api user
```

The watcher reads unresolved review threads through GraphQL (`pullRequest.reviewThreads`, 100 threads per page,
100 comments per thread). A GraphQL response with `errors` or without data is a failed lookup, never "no open
threads". When the lookup fails, every actionable inline comment blocks.

With PR-AF enabled, the watcher also reads the last 20 label and unlabel events of a labelled PR through GraphQL
(`pullRequest.timelineItems`). It uses them to notice that the PR-AF label was removed and added again on the same
head.

## JSON fields the watcher reads

### `gh pr view`

| Field | Used for |
|---|---|
| `number` | Identifying the PR |
| `url` | Finding `owner/repo` |
| `state` | Detecting closed PRs |
| `mergedAt` / `closedAt` | Detecting the end state |
| `headRefName` | Branch name |
| `baseRefName` | Base branch, for the up-to-date requirement |
| `headRefOid` | Head SHA for the workflow run lookup |
| `mergeable` | Detecting conflicts (`CONFLICTING`) |
| `mergeStateStatus` | Detecting `BEHIND`, `BLOCKED`, `DIRTY`, `DRAFT`, and `UNKNOWN` |
| `reviewDecision` | Approval gating (`REVIEW_REQUIRED`, `CHANGES_REQUESTED`) |
| `labels` | The PR-AF label |

### `gh pr checks`

| Field | Used for |
|---|---|
| `bucket` | Pass, fail, pending, and skip classification |
| `state` | Extra pending detection |
| `name` / `workflow` | Reports, expected skips, required checks, and the PR-AF checks |
| `link` | Links to failed runs |
| `startedAt` / `completedAt` | Hung-check detection and the latest PR-AF run. GitHub reports `0001-01-01T00:00:00Z` for a check that has not started. The watcher ignores that value. |

### Actions runs API (`workflow_runs[]`)

| Field | Used for |
|---|---|
| `id` | Run ID for rerun commands |
| `workflow_id` | Keeping only the latest run of each workflow |
| `name` / `display_title` | Workflow name in the failure report |
| `status` / `conclusion` | Failure classification |
| `html_url` | Link to the run |
| `head_sha` | Keeping only runs for the current commit |
