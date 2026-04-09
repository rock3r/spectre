# GitHub CLI / API Notes For `babysit-pr`

## Primary commands used

### PR metadata

```bash
gh pr view --json number,url,state,mergedAt,closedAt,headRefName,headRefOid,headRepository,headRepositoryOwner,mergeable,mergeStateStatus,reviewDecision
```

Used to resolve PR number, URL, branch, head SHA, and closed/merged/mergeable state.

### PR checks summary

```bash
gh pr checks --json name,state,bucket,link,workflow,event,startedAt,completedAt
```

Used to compute pending/failed/passed counts and whether the current CI round is terminal.
`bucket` values: `pass`, `fail`, `pending`, `skipping`.

### Workflow runs for head SHA

```bash
gh api repos/{owner}/{repo}/actions/runs -X GET -f head_sha=<sha> -f per_page=100
```

Used to discover failed workflow runs and rerunnable run IDs.

### Failed log inspection

```bash
gh run view <run-id> --json jobs,name,workflowName,conclusion,status,url,headSha
gh run view <run-id> --log-failed
```

Used to classify branch-related vs. flaky/unrelated failures.

### Retry failed jobs only

```bash
gh run rerun <run-id> --failed
```

Reruns only failed jobs (and their dependencies) for a workflow run.

## Review-related endpoints

```bash
# Issue comments on PR
gh api repos/{owner}/{repo}/issues/<pr_number>/comments?per_page=100

# Inline PR review comments
gh api repos/{owner}/{repo}/pulls/<pr_number>/comments?per_page=100

# Review submissions
gh api repos/{owner}/{repo}/pulls/<pr_number>/reviews?per_page=100
```

## JSON fields consumed by the watcher

### `gh pr view`

| Field | Used for |
|---|---|
| `number` | Identifying the PR |
| `url` | Extracting `owner/repo` |
| `state` | Detecting closed PRs |
| `mergedAt` / `closedAt` | Terminal state detection |
| `headRefName` | Branch name |
| `headRefOid` | Head SHA for workflow run lookup |
| `mergeable` | Merge conflict detection |
| `mergeStateStatus` | Blocking state detection (`BLOCKED`, `DIRTY`, `DRAFT`) |
| `reviewDecision` | Approval gating (`REVIEW_REQUIRED`, `CHANGES_REQUESTED`) |

### `gh pr checks`

| Field | Used for |
|---|---|
| `bucket` | Pass/fail/pending classification |
| `state` | Supplementary pending detection |
| `name` / `workflow` | Human-readable failure reporting |
| `link` | Deep-linking to failed runs |

### Actions runs API (`workflow_runs[]`)

| Field | Used for |
|---|---|
| `id` | Run ID for rerun commands |
| `name` / `display_title` | Workflow name in failure report |
| `status` / `conclusion` | Failure classification |
| `html_url` | Link to run in CI report |
| `head_sha` | Filtering runs to current commit |
