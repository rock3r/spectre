#!/usr/bin/env python3
"""Watch GitHub PR CI and review activity for PR babysitting workflows."""

import argparse
import copy
import json
import os
import re
import subprocess
import sys
import tempfile
import time
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import quote, urlencode, urlparse

# Project settings live in `config.json` next to the `scripts/` directory. A missing
# file or a missing key means "use the default". The defaults match a repository with
# one CI workflow, Codex as the only review bot, and nothing that is skipped by design.
CONFIG_VERSION = 1
DEFAULT_CONFIG = {
    "version": CONFIG_VERSION,
    # The command the agent runs locally before every push, e.g. "./gradlew check".
    "local_gate": None,
    # Check names whose `skipping` result is expected on PRs (e.g. a deploy job).
    "expected_skipped_checks": [],
    # Check names that must be present and passed before the PR counts as ready.
    "required_checks": [],
    # Workflow name fragments whose failures are worth an automatic rerun.
    "retry_eligible_workflow_keywords": ["e2e"],
    # A check that stays pending longer than this is reported as hung.
    "hung_check_minutes": 30,
    # Comments from these author associations count as trusted human review.
    "trusted_author_associations": ["OWNER", "MEMBER", "COLLABORATOR"],
    # Login fragments of `[bot]` accounts whose comments are review findings.
    "review_bot_login_keywords": ["codex"],
    # Default for --max-session-minutes.
    "max_session_minutes": 90,
    # Whether a PR that is behind its base must be updated before merge: "auto" reads the
    # base branch's protection and rulesets, true or false skips that lookup.
    "require_up_to_date": "auto",
    "codex": {
        # Watch the Codex review bot (its 👀 reaction and its review summary).
        "enabled": True,
        # Require a Codex review of the head even when Codex never posted on the PR.
        "required": False,
        # Without `required`: how long to wait, after the checks finish, for Codex to start
        # a review of the head before the missing review stops blocking readiness.
        "idle_wait_minutes": 10,
    },
    "pr_af": {
        # Watch the label-triggered PR-AF review workflow.
        "enabled": False,
        "label": "pr-af",
        "workflow_names": [],
        "check_names": [],
        "review_body_markers": [],
        "review_author_login": "github-actions[bot]",
        "missing_check_grace_minutes": 5,
    },
    "cleanup": {
        # When true, the agent must ask the owner before deleting a merged branch.
        "branch_delete_requires_approval": False,
    },
    "sync": {
        # Paths or globs in the vendored skill folder that belong to the repository.
        # sync.py never deletes or overwrites them. The watcher itself does not use this.
        "keep": [],
    },
}


class ConfigError(ValueError):
    pass


def _is_int(value):
    return isinstance(value, int) and not isinstance(value, bool)


def _check_positive_int(value):
    return _is_int(value) and value > 0, "a whole number greater than 0"


def _check_non_negative_int(value):
    return _is_int(value) and value >= 0, "a whole number of 0 or more"


def _check_bool(value):
    return isinstance(value, bool), "true or false"


def _check_string(value):
    return isinstance(value, str) and bool(value.strip()), "a non-empty string"


def _check_optional_string(value):
    return value is None or (isinstance(value, str) and bool(value.strip())), "a non-empty string or null"


def _check_up_to_date_setting(value):
    return value is True or value is False or value == "auto", 'true, false, or "auto"'


def _check_string_list(value):
    ok = isinstance(value, list) and all(isinstance(item, str) and item.strip() for item in value)
    return ok, "a list of non-empty strings"


CONFIG_VALIDATORS = {
    "version": _check_positive_int,
    "local_gate": _check_optional_string,
    "expected_skipped_checks": _check_string_list,
    "required_checks": _check_string_list,
    "retry_eligible_workflow_keywords": _check_string_list,
    "hung_check_minutes": _check_positive_int,
    "trusted_author_associations": _check_string_list,
    "review_bot_login_keywords": _check_string_list,
    "max_session_minutes": _check_positive_int,
    "require_up_to_date": _check_up_to_date_setting,
    "codex": {
        "enabled": _check_bool,
        "required": _check_bool,
        "idle_wait_minutes": _check_non_negative_int,
    },
    "pr_af": {
        "enabled": _check_bool,
        "label": _check_string,
        "workflow_names": _check_string_list,
        "check_names": _check_string_list,
        "review_body_markers": _check_string_list,
        "review_author_login": _check_string,
        "missing_check_grace_minutes": _check_non_negative_int,
    },
    "cleanup": {
        "branch_delete_requires_approval": _check_bool,
    },
    "sync": {
        "keep": _check_string_list,
    },
}


def build_config(raw):
    """Merge `raw` over the defaults. Returns (config, warnings).

    Unknown keys produce a warning, not an error, so an older watcher can read a newer
    file. A value of the wrong type raises ConfigError: guessing could change what the
    watcher treats as safe to merge.
    """
    if not isinstance(raw, dict):
        raise ConfigError("the config file must contain a JSON object")
    config = copy.deepcopy(DEFAULT_CONFIG)
    warnings = []
    for key, value in raw.items():
        validator = CONFIG_VALIDATORS.get(key)
        if validator is None:
            warnings.append(f"unknown config key '{key}' is ignored")
            continue
        if isinstance(validator, dict):
            if not isinstance(value, dict):
                raise ConfigError(f"config key '{key}' must be a JSON object")
            for sub_key, sub_value in value.items():
                sub_validator = validator.get(sub_key)
                if sub_validator is None:
                    warnings.append(f"unknown config key '{key}.{sub_key}' is ignored")
                    continue
                ok, expected = sub_validator(sub_value)
                if not ok:
                    raise ConfigError(f"config key '{key}.{sub_key}' must be {expected}")
                config[key][sub_key] = copy.deepcopy(sub_value)
            continue
        ok, expected = validator(value)
        if not ok:
            raise ConfigError(f"config key '{key}' must be {expected}")
        config[key] = copy.deepcopy(value)
    if config["version"] > CONFIG_VERSION:
        raise ConfigError(
            f"config version {config['version']} is newer than this watcher supports "
            f"({CONFIG_VERSION}); update the skill"
        )
    pr_af = config["pr_af"]
    if pr_af["enabled"] and not (pr_af["workflow_names"] or pr_af["check_names"]):
        warnings.append(
            "pr_af is enabled but pr_af.workflow_names and pr_af.check_names are empty, "
            "so no check can be recognised as PR-AF"
        )
    return config, warnings


def default_config_path():
    return Path(__file__).resolve().parent.parent / "config.json"


def load_config(path, explicit):
    """Read and validate a config file. Returns (config, warnings).

    A missing file is fine when the watcher looks in its default place, and an error
    when the caller named the file with --config.
    """
    path = Path(path)
    if not path.exists():
        if explicit:
            raise ConfigError(f"config file not found: {path}")
        return build_config({})
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as err:
        raise ConfigError(f"config file is not valid JSON: {path}: {err}") from err
    return build_config(raw)


CONFIG = build_config({})[0]

FAILED_RUN_CONCLUSIONS = {
    "failure",
    "timed_out",
    "cancelled",
    "action_required",
    "startup_failure",
    "stale",
}
PENDING_CHECK_STATES = {
    "QUEUED",
    "IN_PROGRESS",
    "PENDING",
    "WAITING",
    "REQUESTED",
}
MERGE_BLOCKING_REVIEW_DECISIONS = {
    "REVIEW_REQUIRED",
    "CHANGES_REQUESTED",
}
MERGE_CONFLICT_OR_BLOCKING_STATES = {
    "BEHIND",
    "BLOCKED",
    "DIRTY",
    "DRAFT",
    "UNKNOWN",
}
# Merge state values that indicate a real content conflict which will not
# self-resolve by waiting and should be surfaced immediately.
MERGE_CONFLICT_STATES = {
    "DIRTY",
}
# `BEHIND` means branch protection wants the branch updated with its base before merge.
MERGE_BEHIND_STATES = {
    "BEHIND",
}
GREEN_STATE_MAX_POLL_SECONDS = 60

# Minimum seconds to wait after all checks go terminal before declaring the PR
# ready to merge. Some review workflows complete their CI check run first, then
# post inline review comments to the PR a few seconds later via a separate API
# call. Without this grace period the watcher can emit stop_ready_to_merge in
# that narrow window, causing the agent to merge before findings are ever seen.
CHECKS_TERMINAL_GRACE_PERIOD_SECONDS = 60

# Codex keeps one "Codex Review Summary" status table on the PR and edits it on every review.
# It never carries a finding, so it must not surface as a review item.
STATUS_ONLY_BOT_COMMENT_MARKER = "<!-- codex-pull-request-review-summary -->"

GH_PR_CHECKS_STATE_EXIT_CODES = (0, 1, 8)
# A hung `gh` call (network trouble, an auth prompt) must not freeze the watcher.
GH_COMMAND_TIMEOUT_SECONDS = 60

# The exact Codex bot identity, used for the 👀 reaction gate and the review summary.
# Codex signals it is reviewing a PR by adding a 👀 reaction; it either posts a
# review with comments (issues found) or removes the reaction silently (clean).
# The REST API reports the login with a `[bot]` suffix, GraphQL without it. A loose
# substring match would let any account with "codex" in its name hold or open the gate.
CODEX_BOT_LOGINS = {
    "chatgpt-codex-connector[bot]",
    "chatgpt-codex-connector",
}

STATE_STALENESS_RESET_SECONDS = 2 * 60 * 60

_AUTHENTICATED_LOGIN_CACHE = None
# (repo, base branch) -> whether the base requires up-to-date branches.
_UP_TO_DATE_CACHE = {}


class GhCommandError(RuntimeError):
    pass


def parse_args():
    parser = argparse.ArgumentParser(
        description=(
            "Normalize PR/CI/review state for PR babysitting and optionally "
            "trigger flaky reruns."
        )
    )
    parser.add_argument("--pr", default="auto", help="auto, PR number, or PR URL")
    parser.add_argument("--repo", help="Optional OWNER/REPO override")
    parser.add_argument("--poll-seconds", type=int, default=30, help="Watch poll interval")
    parser.add_argument(
        "--max-flaky-retries",
        type=int,
        default=3,
        help="Max rerun cycles per head SHA before stop recommendation",
    )
    parser.add_argument("--state-file", help="Path to state JSON file")
    parser.add_argument(
        "--once",
        action="store_true",
        help="Poll until something needs agent attention, then emit one snapshot and exit",
    )
    parser.add_argument(
        "--snapshot",
        action="store_true",
        help="Emit one instant snapshot of current state and exit (no waiting)",
    )
    parser.add_argument("--watch", action="store_true", help="Continuously emit JSONL snapshots")
    parser.add_argument(
        "--retry-failed-now",
        action="store_true",
        help="Rerun failed jobs for current failed workflow runs when policy allows",
    )
    parser.add_argument(
        "--max-session-minutes",
        type=int,
        default=None,
        help=(
            "Stop with stop_session_timeout after this many minutes "
            "(default: max_session_minutes from config.json, else 90)"
        ),
    )
    parser.add_argument(
        "--config",
        help="Path to the project config file (default: config.json next to scripts/)",
    )
    parser.add_argument(
        "--print-config",
        action="store_true",
        help="Print the effective config as JSON and exit",
    )
    parser.add_argument(
        "--json",
        action="store_true",
        help="Emit machine-readable output (default behavior for --once and --retry-failed-now)",
    )
    args = parser.parse_args()

    if args.max_session_minutes is None:
        args.max_session_minutes = CONFIG["max_session_minutes"]
    if args.poll_seconds <= 0:
        parser.error("--poll-seconds must be > 0")
    if args.max_flaky_retries < 0:
        parser.error("--max-flaky-retries must be >= 0")
    if args.max_session_minutes <= 0:
        parser.error("--max-session-minutes must be > 0")
    if args.watch and args.retry_failed_now:
        parser.error("--watch cannot be combined with --retry-failed-now")
    mode_count = sum([args.once, args.snapshot, args.watch, args.retry_failed_now])
    if mode_count > 1:
        parser.error("only one of --once, --snapshot, --watch, --retry-failed-now can be used")
    if mode_count == 0:
        args.once = True
    return args


def _format_gh_error(cmd, err):
    stdout = (err.stdout or "").strip()
    stderr = (err.stderr or "").strip()
    parts = [f"GitHub CLI command failed: {' '.join(cmd)}"]
    if stdout:
        parts.append(f"stdout: {stdout}")
    if stderr:
        parts.append(f"stderr: {stderr}")
    return "\n".join(parts)


def gh_text(args, repo=None, ok_exit_codes=(0,), empty_result_stderr=()):
    cmd = ["gh"]
    # `gh api` does not accept `-R/--repo` on all gh versions. The watcher's
    # API calls use explicit endpoints (e.g. repos/{owner}/{repo}/...), so the
    # repo flag is unnecessary there.
    if repo and (not args or args[0] != "api"):
        cmd.extend(["-R", repo])
    cmd.extend(args)
    try:
        proc = subprocess.run(
            cmd,
            check=True,
            capture_output=True,
            text=True,
            # GitHub output is UTF-8. Without an explicit encoding Python decodes with the
            # locale default (cp1252 on Windows), which fails on emoji in review comments.
            encoding="utf-8",
            errors="replace",
            timeout=GH_COMMAND_TIMEOUT_SECONDS,
        )
    except FileNotFoundError as err:
        raise GhCommandError("`gh` command not found") from err
    except subprocess.TimeoutExpired as err:
        raise GhCommandError(f"GitHub CLI command timed out: {' '.join(cmd)}") from err
    except subprocess.CalledProcessError as err:
        if err.returncode in ok_exit_codes and (err.stdout or "").strip():
            return err.stdout
        # Some "nothing to report" answers come as an error with a known message.
        if err.returncode in ok_exit_codes and any(
            marker in (err.stderr or "") for marker in empty_result_stderr
        ):
            return ""
        raise GhCommandError(_format_gh_error(cmd, err)) from err
    if proc.stdout is None:
        # `subprocess` leaves stdout as None when its reader thread dies, which would
        # otherwise surface later as a confusing AttributeError.
        raise GhCommandError(f"No output captured from GitHub CLI command: {' '.join(cmd)}")
    return proc.stdout


def gh_json(args, repo=None, ok_exit_codes=(0,), empty_result_stderr=()):
    raw = gh_text(
        args, repo=repo, ok_exit_codes=ok_exit_codes, empty_result_stderr=empty_result_stderr
    ).strip()
    if not raw:
        return None
    try:
        return json.loads(raw)
    except json.JSONDecodeError as err:
        raise GhCommandError(f"Failed to parse JSON from gh output for {' '.join(args)}") from err


def parse_pr_spec(pr_spec):
    if pr_spec == "auto":
        return {"mode": "auto", "value": None}
    if re.fullmatch(r"\d+", pr_spec):
        return {"mode": "number", "value": pr_spec}
    parsed = urlparse(pr_spec)
    if parsed.scheme and parsed.netloc and "/pull/" in parsed.path:
        return {"mode": "url", "value": pr_spec}
    raise ValueError("--pr must be 'auto', a PR number, or a PR URL")


def pr_view_fields():
    return (
        "number,url,state,mergedAt,closedAt,headRefName,headRefOid,"
        "headRepository,headRepositoryOwner,baseRefName,mergeable,mergeStateStatus,reviewDecision,labels"
    )


def checks_fields():
    return "name,state,bucket,link,workflow,event,startedAt,completedAt"


def resolve_pr(pr_spec, repo_override=None):
    parsed = parse_pr_spec(pr_spec)
    cmd = ["pr", "view"]
    if parsed["value"] is not None:
        cmd.append(parsed["value"])
    cmd.extend(["--json", pr_view_fields()])
    data = gh_json(cmd, repo=repo_override)
    if not isinstance(data, dict):
        raise GhCommandError("Unexpected PR payload from `gh pr view`")

    pr_url = str(data.get("url") or "")
    repo = (
        repo_override
        or extract_repo_from_pr_url(pr_url)
        or extract_repo_from_pr_view(data)
    )
    if not repo:
        raise GhCommandError("Unable to determine OWNER/REPO for the PR")

    state = str(data.get("state") or "")
    merged = bool(data.get("mergedAt"))
    closed = bool(data.get("closedAt")) or state.upper() == "CLOSED"

    return {
        "number": int(data["number"]),
        "url": pr_url,
        "repo": repo,
        "head_sha": str(data.get("headRefOid") or ""),
        "head_branch": str(data.get("headRefName") or ""),
        "base_branch": str(data.get("baseRefName") or ""),
        "state": state,
        "merged": merged,
        "closed": closed,
        "mergeable": str(data.get("mergeable") or ""),
        "merge_state_status": str(data.get("mergeStateStatus") or ""),
        "review_decision": str(data.get("reviewDecision") or ""),
        "labels": normalize_pr_labels(data.get("labels")),
    }


def normalize_pr_labels(raw_labels):
    if not isinstance(raw_labels, list):
        return []
    labels = []
    for label in raw_labels:
        name = str(label.get("name") or "") if isinstance(label, dict) else str(label or "")
        if name:
            labels.append(name)
    return labels


def pr_has_label(pr, label_name):
    wanted = str(label_name or "").lower()
    return any(str(label).lower() == wanted for label in pr.get("labels") or [])


def extract_repo_from_pr_view(data):
    head_repo = data.get("headRepository")
    head_owner = data.get("headRepositoryOwner")
    owner = None
    name = None
    if isinstance(head_owner, dict):
        owner = head_owner.get("login") or head_owner.get("name")
    elif isinstance(head_owner, str):
        owner = head_owner
    if isinstance(head_repo, dict):
        name = head_repo.get("name")
        repo_owner = head_repo.get("owner")
        if not owner and isinstance(repo_owner, dict):
            owner = repo_owner.get("login") or repo_owner.get("name")
    elif isinstance(head_repo, str):
        name = head_repo
    if owner and name:
        return f"{owner}/{name}"
    return None


def extract_repo_from_pr_url(pr_url):
    parsed = urlparse(pr_url)
    parts = [p for p in parsed.path.split("/") if p]
    if len(parts) >= 4 and parts[2] == "pull":
        return f"{parts[0]}/{parts[1]}"
    return None


def reset_seen_tracking_state(state):
    state["seen_issue_comment_ids"] = []
    state["seen_review_comment_ids"] = []
    state["seen_review_ids"] = []
    state["seen_issue_comment_updated_at"] = {}
    state["seen_review_comment_updated_at"] = {}
    state["seen_review_updated_at"] = {}
    state["last_review_poll_at"] = None
    state["pending_checks_first_seen_at"] = {}
    state["checks_went_terminal_at"] = None
    state["checks_terminal_sha"] = None
    state["pr_af_missing_since_at"] = None
    state["pr_af_missing_sha"] = None
    state["pr_af_label_absent_sha"] = None
    state["pr_af_label_rerun_sha"] = None
    state["pr_af_label_rerun_seen_at"] = None


def is_state_stale(state, now_seconds=None):
    now = float(now_seconds) if now_seconds is not None else time.time()
    last_snapshot_at = state.get("last_snapshot_at")
    try:
        last_snapshot = float(last_snapshot_at)
    except (TypeError, ValueError):
        return True
    return now - last_snapshot > STATE_STALENESS_RESET_SECONDS


def load_state(path):
    if path.exists():
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as err:
            raise RuntimeError(f"State file is not valid JSON: {path}") from err
        if not isinstance(data, dict):
            raise RuntimeError(f"State file must contain an object: {path}")
        if is_state_stale(data):
            reset_seen_tracking_state(data)
            return data, True
        return data, False
    return {
        "pr": {},
        "started_at": None,
        "last_seen_head_sha": None,
        "retries_by_sha": {},
        "seen_issue_comment_ids": [],
        "seen_review_comment_ids": [],
        "seen_review_ids": [],
        "seen_issue_comment_updated_at": {},
        "seen_review_comment_updated_at": {},
        "seen_review_updated_at": {},
        "last_snapshot_at": None,
        # Timestamp (unix seconds) when checks first went all_terminal for the
        # current head SHA.  Used to enforce a grace period before emitting
        # stop_ready_to_merge so that review bots have time to post their
        # inline review comments after completing their check run.
        "checks_went_terminal_at": None,
        # The head SHA for which checks_went_terminal_at was recorded.
        "checks_terminal_sha": None,
        # ISO timestamp for the last successful review/comment poll cycle.
        "last_review_poll_at": None,
        # First-seen unix seconds for pending checks keyed by a stable check
        # identity. This allows hung detection even when `startedAt` is not
        # provided by GitHub for queued/blocked checks.
        "pending_checks_first_seen_at": {},
        # First-seen unix seconds for a labelled head whose PR-AF check has not
        # appeared yet. This closes the label-on-green race and still ends the
        # wait when GitHub never starts a run.
        "pr_af_missing_since_at": None,
        "pr_af_missing_sha": None,
        # Same-SHA relabel tracking. When the PR-AF label goes away and comes back
        # on the same head, a PR-AF check from before that must not count for the
        # newly requested audit.
        "pr_af_label_absent_sha": None,
        "pr_af_label_rerun_sha": None,
        "pr_af_label_rerun_seen_at": None,
    }, True


def save_state(path, state):
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = json.dumps(state, indent=2, sort_keys=True) + "\n"
    fd, tmp_name = tempfile.mkstemp(prefix=f"{path.name}.", suffix=".tmp", dir=path.parent)
    tmp_path = Path(tmp_name)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as tmp_file:
            tmp_file.write(payload)
        os.replace(tmp_path, path)
    except Exception:
        try:
            tmp_path.unlink(missing_ok=True)
        except OSError:
            pass
        raise


def default_state_file_for(pr):
    repo_slug = pr["repo"].replace("/", "-")
    return Path(f"/tmp/babysit-pr-{repo_slug}-pr{pr['number']}.json")


def get_pr_checks(pr_spec, repo):
    parsed = parse_pr_spec(pr_spec)
    cmd = ["pr", "checks"]
    if parsed["value"] is not None:
        cmd.append(parsed["value"])
    cmd.extend(["--json", checks_fields()])
    # `gh pr checks` exits 1 when a check failed and 8 while checks are pending, and still
    # prints the requested JSON. Those are states to report, not command failures.
    # A PR without any check makes gh exit 1 with "no checks reported" and no JSON.
    # That is an empty check list. Any other exit 1 without JSON is still a failure.
    data = gh_json(
        cmd,
        repo=repo,
        ok_exit_codes=GH_PR_CHECKS_STATE_EXIT_CODES,
        empty_result_stderr=("no checks reported",),
    )
    if data is None:
        return []
    if not isinstance(data, list):
        raise GhCommandError("Unexpected payload from `gh pr checks`")
    return data


def is_pending_check(check):
    bucket = str(check.get("bucket") or "").lower()
    state = str(check.get("state") or "").upper()
    return bucket == "pending" or state in PENDING_CHECK_STATES


def is_expected_skipped_check(check):
    name = str(check.get("name") or "").strip().lower()
    return name in {str(item).strip().lower() for item in CONFIG["expected_skipped_checks"]}


def summarize_checks(checks):
    pending_count = 0
    failed_count = 0
    passed_count = 0
    skipping_count = 0
    check_count = 0
    for check in checks:
        # PR-AF is advisory: its own gate reports it, and its result is never a CI failure.
        if is_optional_review_check(check):
            continue
        bucket = str(check.get("bucket") or "").lower()
        if is_pending_check(check):
            pending_count += 1
        elif bucket in ("fail", "cancel"):
            failed_count += 1
        elif bucket == "pass":
            passed_count += 1
        elif bucket in ("neutral", "skipping"):
            # A job that is skipped on every PR by design (for example one that only runs
            # on pushes to main) is not a blocker. A neutral result is not a skip, so it
            # still needs a look.
            if bucket == "skipping" and is_expected_skipped_check(check):
                continue
            skipping_count += 1
        check_count += 1
    return {
        "pending_count": pending_count,
        "failed_count": failed_count,
        "passed_count": passed_count,
        "skipping_count": skipping_count,
        "all_terminal": pending_count == 0,
        "required_missing": missing_required_checks(checks),
        # The checks that these totals judge. Advisory checks and expected skips are left
        # out: they can never make a PR ready, so they must not hide "no checks".
        "check_count": check_count,
    }


def missing_required_checks(checks):
    """Names from `required_checks` that have no passing check on the PR.

    Without this, a lone third-party check that passes could make the PR look
    ready before the project's own CI has even registered.
    """
    passed = {
        str(check.get("name") or "").strip().lower()
        for check in checks
        if isinstance(check, dict) and str(check.get("bucket") or "").lower() == "pass"
    }
    return [name for name in CONFIG["required_checks"] if name.strip().lower() not in passed]


def has_green_check_set(checks_summary):
    """At least one check passed and every required check is among them.

    An empty check set usually means GitHub has not registered the checks for a new
    push yet. It must never read as green.
    """
    if int(checks_summary.get("passed_count") or 0) <= 0:
        return False
    return not checks_summary.get("required_missing")


def get_workflow_runs_for_sha(repo, head_sha):
    endpoint = f"repos/{repo}/actions/runs"
    page = 1
    all_runs = []
    while True:
        data = gh_json(
            [
                "api", endpoint, "-X", "GET",
                "-f", f"head_sha={head_sha}",
                "-f", "per_page=100",
                "-f", f"page={page}",
                "-f", "event=pull_request",
            ],
            repo=repo,
        )
        if not isinstance(data, dict):
            raise GhCommandError("Unexpected payload from actions runs API")
        runs = data.get("workflow_runs") or []
        if not isinstance(runs, list):
            raise GhCommandError("Expected `workflow_runs` to be a list")
        all_runs.extend(runs)
        if len(runs) < 100:
            break
        page += 1
    return all_runs


def is_retry_eligible_workflow_name(workflow_name):
    lower = str(workflow_name or "").lower()
    return any(keyword.lower() in lower for keyword in CONFIG["retry_eligible_workflow_keywords"])


def is_retry_eligible_failed_run(run):
    if not isinstance(run, dict):
        return False
    explicit = run.get("retry_eligible")
    if isinstance(explicit, bool):
        return explicit
    return is_retry_eligible_workflow_name(run.get("workflow_name") or "")


def failed_runs_from_workflow_runs(runs, head_sha):
    # Keep only the latest run per workflow (highest run_id) to ignore
    # superseded reruns that are no longer the active failure.
    latest_by_workflow = {}
    for run in runs:
        if not isinstance(run, dict):
            continue
        if str(run.get("head_sha") or "") != head_sha:
            continue
        workflow_id = run.get("workflow_id") or run.get("name") or run.get("display_title") or ""
        run_id = run.get("id") or 0
        prev = latest_by_workflow.get(workflow_id)
        if prev is None or run_id > (prev.get("id") or 0):
            latest_by_workflow[workflow_id] = run

    failed_runs = []
    for _workflow_id, run in latest_by_workflow.items():
        conclusion = str(run.get("conclusion") or "")
        if conclusion not in FAILED_RUN_CONCLUSIONS:
            continue
        workflow_name = run.get("name") or run.get("display_title") or ""
        if is_optional_review_name(workflow_name):
            continue
        failed_runs.append(
            {
                "run_id": run.get("id"),
                "workflow_name": workflow_name,
                "status": str(run.get("status") or ""),
                "conclusion": conclusion,
                "html_url": str(run.get("html_url") or ""),
                "retry_eligible": is_retry_eligible_workflow_name(workflow_name),
            }
        )
    failed_runs.sort(
        key=lambda item: (str(item.get("workflow_name") or ""), str(item.get("run_id") or ""))
    )
    return failed_runs


def normalize_review_name(name):
    return " ".join(str(name or "").lower().split())


def is_pr_af_name(name):
    """Whether a check, job, or workflow name belongs to PR-AF.

    The match is exact after normalising case and spaces, so a job such as
    "verify-pr-after-rebase" never matches by accident.
    """
    pr_af = CONFIG["pr_af"]
    if not pr_af["enabled"]:
        return False
    names = {normalize_review_name(item) for item in pr_af["workflow_names"] + pr_af["check_names"]}
    return normalize_review_name(name) in names


def is_optional_review_name(name):
    return is_pr_af_name(name)


def is_optional_review_check(check):
    return is_optional_review_name(check.get("name")) or is_optional_review_name(check.get("workflow"))


def review_check_activity_sort_key(check):
    started = str(check.get("startedAt") or "")
    completed = str(check.get("completedAt") or "")
    return (max(started, completed), started, completed, str(check.get("name") or ""))


def summarize_pr_af_gate_from_checks(checks):
    """Summarise the latest PR-AF check on the current head."""
    pr_af_checks = [
        check for check in checks
        if isinstance(check, dict)
        and (is_pr_af_name(check.get("name")) or is_pr_af_name(check.get("workflow")))
    ]
    if not pr_af_checks:
        return {
            "required": False,
            "present": False,
            "status": "missing",
            "conclusion": "",
            "is_success": False,
            "workflow_name": "",
            "html_url": "",
            "source": "checks",
        }

    # A pending rerun wins over an older completed run.
    pending = [check for check in pr_af_checks if is_pending_check(check)]
    candidates = pending or pr_af_checks
    latest = sorted(candidates, key=review_check_activity_sort_key)[-1]
    state = str(latest.get("state") or "").upper()
    bucket = str(latest.get("bucket") or "").lower()

    if is_pending_check(latest):
        status, conclusion = "in_progress", ""
    elif bucket == "pass" or state == "SUCCESS":
        status, conclusion = "completed", "success"
    elif bucket == "skipping" or state == "SKIPPING":
        status, conclusion = "completed", "skipped"
    elif state == "NEUTRAL":
        status, conclusion = "completed", "neutral"
    elif bucket == "fail":
        status, conclusion = "completed", "failure"
    elif state:
        status, conclusion = "completed", state.lower()
    else:
        status, conclusion = "in_progress", ""

    return {
        "required": False,
        "present": True,
        "status": status,
        "conclusion": conclusion,
        "is_success": status == "completed" and conclusion == "success",
        "workflow_name": str(latest.get("name") or ""),
        "html_url": str(latest.get("link") or ""),
        "started_at": str(latest.get("startedAt") or ""),
        "completed_at": str(latest.get("completedAt") or ""),
        "source": "checks",
    }


def get_recent_pr_label_events(repo, pr_number):
    """The last 20 label and unlabel events of the PR."""
    query = """
    query($owner:String!, $name:String!, $number:Int!) {
      repository(owner:$owner, name:$name) {
        pullRequest(number:$number) {
          timelineItems(last:20, itemTypes:[LABELED_EVENT, UNLABELED_EVENT]) {
            nodes {
              __typename
              ... on LabeledEvent { createdAt label { name } }
              ... on UnlabeledEvent { createdAt label { name } }
            }
          }
        }
      }
    }
    """
    owner, name = repo.split("/", 1)
    data = gh_json(
        [
            "api", "graphql",
            "-f", f"owner={owner}",
            "-f", f"name={name}",
            "-F", f"number={int(pr_number)}",
            "-f", f"query={query}",
        ],
        repo=repo,
    )
    try:
        nodes = data["data"]["repository"]["pullRequest"]["timelineItems"]["nodes"]
    except (TypeError, KeyError):
        return []
    return [node for node in nodes or [] if isinstance(node, dict)]


def parse_github_time_seconds(value):
    text = str(value or "")
    if not text:
        return None
    try:
        return int(datetime.fromisoformat(text.replace("Z", "+00:00")).timestamp())
    except ValueError:
        return None


def latest_pr_af_label_event_seconds(label_events, typename):
    wanted = str(CONFIG["pr_af"]["label"]).lower()
    latest = None
    for event in label_events or []:
        if str(event.get("__typename") or "") != typename:
            continue
        label = event.get("label") or {}
        if str(label.get("name") or "").lower() != wanted:
            continue
        event_seconds = parse_github_time_seconds(event.get("createdAt"))
        if event_seconds is None:
            continue
        latest = event_seconds if latest is None else max(latest, event_seconds)
    return latest


def pr_af_label_events_needed(pr, state):
    """Label events matter only for a labelled head that was never seen without the label."""
    return (
        pr_has_label(pr, CONFIG["pr_af"]["label"])
        and state.get("pr_af_label_absent_sha") != str(pr.get("head_sha") or "")
    )


def update_pr_af_label_rerun_tracking(pr, state, now_seconds, label_events=None):
    """Track a PR-AF label that was removed and added again on the same head SHA."""
    head_sha = str(pr.get("head_sha") or "")
    if not head_sha:
        return
    if not pr_has_label(pr, CONFIG["pr_af"]["label"]):
        state["pr_af_label_absent_sha"] = head_sha
        state["pr_af_label_rerun_sha"] = None
        state["pr_af_label_rerun_seen_at"] = None
        return
    if state.get("pr_af_label_absent_sha") != head_sha:
        # The watcher never saw this head without the label. The timeline tells
        # whether the label was re-added between polls or before the watch started.
        latest_labeled_at = latest_pr_af_label_event_seconds(label_events, "LabeledEvent")
        latest_unlabeled_at = latest_pr_af_label_event_seconds(label_events, "UnlabeledEvent")
        if latest_labeled_at is None or (
            latest_unlabeled_at is not None and latest_unlabeled_at > latest_labeled_at
        ):
            return
        state["pr_af_label_absent_sha"] = head_sha
        state["pr_af_label_rerun_sha"] = head_sha
        state["pr_af_label_rerun_seen_at"] = latest_labeled_at
        return
    if state.get("pr_af_label_rerun_sha") != head_sha:
        state["pr_af_label_rerun_sha"] = head_sha
        state["pr_af_label_rerun_seen_at"] = int(now_seconds)


def apply_pr_af_label_rerun_grace(pr, pr_af_gate, state):
    """Ignore a completed PR-AF check that started before the label was re-added."""
    if not pr_has_label(pr, CONFIG["pr_af"]["label"]):
        return pr_af_gate
    if str(pr_af_gate.get("status") or "") != "completed":
        return pr_af_gate
    if state.get("pr_af_label_rerun_sha") != str(pr.get("head_sha") or ""):
        return pr_af_gate
    try:
        rerun_seen_at = int(state.get("pr_af_label_rerun_seen_at") or 0)
    except (TypeError, ValueError):
        return pr_af_gate
    started_at = parse_github_time_seconds(pr_af_gate.get("started_at"))
    if started_at is None or started_at >= rerun_seen_at:
        return pr_af_gate

    gate = dict(pr_af_gate)
    gate.update({
        "present": False,
        "status": "missing",
        "conclusion": "",
        "is_success": False,
        "stale_started_at": pr_af_gate.get("started_at"),
    })
    return gate


def apply_pr_af_missing_check_grace(pr, pr_af_gate, state, now_seconds):
    """Wait a bounded time for the PR-AF check of a labelled head to appear."""
    if not pr_has_label(pr, CONFIG["pr_af"]["label"]) or str(pr_af_gate.get("status") or "") != "missing":
        state["pr_af_missing_since_at"] = None
        state["pr_af_missing_sha"] = None
        return pr_af_gate

    head_sha = str(pr.get("head_sha") or "")
    if state.get("pr_af_missing_sha") != head_sha:
        state["pr_af_missing_sha"] = head_sha
        state["pr_af_missing_since_at"] = int(now_seconds)
    try:
        first_seen = int(state.get("pr_af_missing_since_at") or now_seconds)
    except (TypeError, ValueError):
        first_seen = int(now_seconds)
        state["pr_af_missing_since_at"] = first_seen

    elapsed = max(0, int(now_seconds) - first_seen)
    gate = dict(pr_af_gate)
    gate["missing_elapsed_seconds"] = elapsed
    grace_seconds = int(CONFIG["pr_af"]["missing_check_grace_minutes"]) * 60
    gate["status"] = "missing_wait" if elapsed < grace_seconds else "missing_timeout"
    return gate


def pr_af_holds_readiness(pr_af_gate):
    return bool(pr_af_gate) and str(pr_af_gate.get("status") or "") in {"in_progress", "missing_wait"}


def collect_pr_af_gate(pr, checks, state, now_seconds):
    label_events = None
    if pr_af_label_events_needed(pr, state):
        label_events = get_recent_pr_label_events(pr["repo"], pr["number"])
    update_pr_af_label_rerun_tracking(pr, state, now_seconds, label_events=label_events)
    gate = summarize_pr_af_gate_from_checks(checks)
    gate = apply_pr_af_label_rerun_grace(pr, gate, state)
    return apply_pr_af_missing_check_grace(pr, gate, state, now_seconds)


def is_codex_bot_login(login):
    return str(login or "").lower() in CODEX_BOT_LOGINS


def get_pr_issue_reactions(repo, pr_number):
    """Fetch the PR's issue-level reactions (all pages), or None if unavailable.

    The Codex gate inspects these reactions. Pagination matters: a 👀 reaction
    from a review bot can land on a later page on a busy PR, and missing it
    would let the watcher declare merge-readiness (`stop_ready_to_merge`) while
    a review is still in progress.
    """
    try:
        return gh_api_list_paginated(
            f"repos/{repo}/issues/{pr_number}/reactions",
            repo=repo,
        )
    except GhCommandError:
        return None


def _bot_has_eyes_reaction(reactions, login_predicate):
    if not isinstance(reactions, list):
        return False
    for reaction in reactions:
        if not isinstance(reaction, dict):
            continue
        if str(reaction.get("content") or "") != "eyes":
            continue
        user = reaction.get("user") or {}
        if login_predicate(str(user.get("login") or "")):
            return True
    return False


def summarize_codex_gate(reactions):
    """Detect Codex review status via PR emoji reactions.

    Codex signals it is reviewing a PR by adding a 👀 (eyes) reaction.
    It either posts a review with comments (issues found) or removes the
    reaction silently (clean).  A present 👀 reaction from a Codex bot
    account means review is still in progress.

    `reactions` is the pre-fetched issue-reactions list (or None when the
    lookup failed).
    """
    if reactions is None:
        # A failed reactions lookup cannot prove that Codex removed its 👀 reaction.
        # Keep the gate closed and let the next poll retry the lookup.
        return {"reviewing": True, "status": "unknown"}
    if _bot_has_eyes_reaction(reactions, is_codex_bot_login):
        return {"reviewing": True, "status": "in_progress"}
    return {"reviewing": False, "status": "idle"}


# One row of Codex's review-summary table, e.g.
# | 📝 **Code Review** | ✅ **Completed** <relative-time ...> | `b5d394b` | New commits |
_CODEX_SUMMARY_ROW = re.compile(
    r"^\|\s*📝\s*\*\*Code Review\*\*\s*\|(?P<status>[^|]*)\|\s*`(?P<sha>[0-9a-f]{7,40})`",
    re.MULTILINE,
)


# Codex's comment when a review finds nothing, e.g.
# "Codex Review: Didn't find any major issues. ..." followed by "**Reviewed commit:** `bb9ad50596`".
_CODEX_CLEAN_REVIEW_PHRASE = re.compile(r"didn[\'\u2019]t find any major issues", re.IGNORECASE)
_CODEX_REVIEWED_COMMIT = re.compile(r"\*\*Reviewed commit:\*\*\s*`(?P<sha>[0-9a-f]{7,40})`")


def is_codex_clean_review_comment(item):
    """Codex's "no major issues" comment. It carries no finding, like the summary table."""
    author = item.get("author")
    if author is None:
        author = extract_login(item.get("user"))
    return is_codex_bot_login(author) and bool(_CODEX_CLEAN_REVIEW_PHRASE.search(str(item.get("body") or "")))


def codex_clean_review_commit(comment):
    """The commit that a Codex "no major issues" comment reviewed, or None."""
    if not is_codex_clean_review_comment(comment):
        return None
    match = _CODEX_REVIEWED_COMMIT.search(str(comment.get("body") or ""))
    return match.group("sha") if match else None


def summarize_codex_head_review(issue_comments, head_sha):
    """Tell whether Codex finished a review of `head_sha`.

    A missing 👀 reaction only says Codex is not reviewing right now: on a fresh push it may
    simply not have started. Codex's review-summary comment records the status and the commit
    of its latest review, which is proof that the current head was reviewed. When the PR has
    no such comment, Codex is not active on it and this check does not apply.
    """
    active = False
    head_statuses = set()
    for comment in issue_comments or []:
        if not isinstance(comment, dict):
            continue
        if not is_codex_bot_login(extract_login(comment.get("user"))):
            continue
        # A "no major issues" comment for the head proves a completed review of it. One
        # for an older commit proves nothing, and does not make Codex count as active.
        clean_sha = codex_clean_review_commit(comment)
        if clean_sha and str(head_sha or "").startswith(clean_sha):
            active = True
            head_statuses.add("completed")
            continue
        body = str(comment.get("body") or "")
        if STATUS_ONLY_BOT_COMMENT_MARKER not in body:
            continue
        active = True
        for row in _CODEX_SUMMARY_ROW.finditer(body):
            if str(head_sha or "").startswith(row.group("sha")):
                head_statuses.add(classify_codex_review_status(row.group("status")))
    if "completed" in head_statuses:
        head_status = "completed"
    elif "running" in head_statuses:
        head_status = "running"
    elif "failed" in head_statuses:
        head_status = "failed"
    else:
        head_status = "none"
    return {"active": active, "head_reviewed": head_status == "completed", "head_status": head_status}


# Words in the status column of Codex's summary table that mean "still working".
_CODEX_IN_PROGRESS_WORDS = ("running", "queued", "pending", "in progress", "started")


def classify_codex_review_status(status_text):
    """Map one status cell of the Codex summary table to completed, running, or failed.

    Anything else, such as Failed, Cancelled, or a status this watcher does not know,
    counts as failed. Waiting on it would only end at the session timeout.
    """
    lower = str(status_text or "").lower()
    if "completed" in lower:
        return "completed"
    if any(word in lower for word in _CODEX_IN_PROGRESS_WORDS):
        return "running"
    return "failed"


def collect_codex_gate(pr):
    """Codex's review state for the PR: the 👀 reaction plus proof of a review of the head."""
    codex_gate = summarize_codex_gate(get_pr_issue_reactions(pr["repo"], pr["number"]))
    try:
        issue_comments = gh_api_list_paginated(comment_endpoints(pr["repo"], pr["number"])["issue_comment"])
        codex_gate.update(summarize_codex_head_review(issue_comments, pr["head_sha"]))
    except GhCommandError:
        # Without the summary comment we cannot prove the head was reviewed: treat as unknown.
        codex_gate.update({"status": "unknown", "active": True, "head_reviewed": False, "head_status": "none"})
    return codex_gate


def get_authenticated_login():
    global _AUTHENTICATED_LOGIN_CACHE
    if _AUTHENTICATED_LOGIN_CACHE:
        return _AUTHENTICATED_LOGIN_CACHE

    data = gh_json(["api", "user"])
    if not isinstance(data, dict) or not data.get("login"):
        raise GhCommandError("Unable to determine authenticated GitHub login from `gh api user`")

    _AUTHENTICATED_LOGIN_CACHE = str(data["login"])
    return _AUTHENTICATED_LOGIN_CACHE


def comment_endpoints(repo, pr_number):
    return {
        "issue_comment": f"repos/{repo}/issues/{pr_number}/comments",
        "review_comment": f"repos/{repo}/pulls/{pr_number}/comments",
        "review": f"repos/{repo}/pulls/{pr_number}/reviews",
    }


def get_unresolved_review_comment_ids(repo, pr_number):
    owner, name = repo.split("/", 1)
    query = (
        "query($owner:String!, $name:String!, $number:Int!, $cursor:String) {"
        " repository(owner:$owner, name:$name) {"
        "   pullRequest(number:$number) {"
        "     reviewThreads(first:100, after:$cursor) {"
        "       pageInfo { hasNextPage endCursor }"
        "       nodes {"
        "         isResolved"
        "         comments(first:100) { totalCount nodes { databaseId } }"
        "       }"
        "     }"
        "   }"
        " }"
        "}"
    )

    unresolved_ids = set()
    truncated_unresolved_threads = False
    cursor = None
    while True:
        args = [
            "api",
            "graphql",
            "-f",
            f"query={query}",
            "-F",
            f"owner={owner}",
            "-F",
            f"name={name}",
            "-F",
            f"number={pr_number}",
        ]
        if cursor is not None:
            args.extend(["-F", f"cursor={cursor}"])

        payload = gh_json(args)
        if not isinstance(payload, dict):
            raise GhCommandError("Unexpected GraphQL payload for review threads")

        # A GraphQL error comes back with exit code 0 and no data. Reading it as "no
        # unresolved threads" would let the watcher report a blocked PR as ready.
        data = payload.get("data")
        if payload.get("errors") or not isinstance(data, dict):
            raise GhCommandError("GraphQL review-thread lookup returned errors or no data")
        repository = data.get("repository")
        if not isinstance(repository, dict):
            raise GhCommandError("Unexpected GraphQL repository payload for review threads")
        pull_request = repository.get("pullRequest")
        if not isinstance(pull_request, dict):
            raise GhCommandError("Unexpected GraphQL pull-request payload for review threads")
        review_threads = pull_request.get("reviewThreads")
        if not isinstance(review_threads, dict):
            raise GhCommandError("Unexpected GraphQL reviewThreads payload")
        nodes = review_threads.get("nodes")

        if not isinstance(nodes, list):
            raise GhCommandError("Unexpected reviewThreads.nodes payload")

        for thread in nodes:
            if not isinstance(thread, dict):
                continue
            if bool(thread.get("isResolved")):
                continue
            comments_payload = thread.get("comments") or {}
            comments = comments_payload.get("nodes") or []
            total_count = comments_payload.get("totalCount")
            try:
                total_count_int = int(total_count)
            except (TypeError, ValueError):
                total_count_int = len(comments)
            if total_count_int > len(comments):
                truncated_unresolved_threads = True

            for comment in comments:
                if not isinstance(comment, dict):
                    continue
                database_id = comment.get("databaseId")
                if database_id is None:
                    continue
                unresolved_ids.add(str(database_id))

        page_info = review_threads.get("pageInfo") or {}
        has_next = bool(page_info.get("hasNextPage"))
        if not has_next:
            break
        cursor = page_info.get("endCursor")
        if not cursor:
            break

    return {
        "ids": unresolved_ids,
        "truncated": truncated_unresolved_threads,
    }


def gh_api_list_paginated(endpoint, repo=None, per_page=100, query_params=None):
    items = []
    page = 1
    base_params = dict(query_params or {})
    while True:
        sep = "&" if "?" in endpoint else "?"
        params = dict(base_params)
        params["per_page"] = per_page
        params["page"] = page
        page_endpoint = f"{endpoint}{sep}{urlencode(params)}"
        payload = gh_json(["api", page_endpoint], repo=repo)
        if payload is None:
            break
        if not isinstance(payload, list):
            raise GhCommandError(f"Unexpected paginated payload from gh api {endpoint}")
        items.extend(payload)
        if len(payload) < per_page:
            break
        page += 1
    return items


def gh_graphql_list_reviews(repo, pr_number):
    """List PR reviews through GraphQL, shaped like the REST review list."""
    owner, name = repo.split("/", 1)
    query = (
        "query($owner:String!, $name:String!, $number:Int!, $cursor:String) {"
        " repository(owner:$owner, name:$name) {"
        "   pullRequest(number:$number) {"
        "     reviews(first:100, after:$cursor) {"
        "       pageInfo { hasNextPage endCursor }"
        "       nodes {"
        "         id: databaseId"
        "         user: author { login type: __typename }"
        "         author_association: authorAssociation"
        "         submitted_at: submittedAt"
        "         body"
        "         state"
        "         html_url: url"
        "       }"
        "     }"
        "   }"
        " }"
        "}"
    )

    items = []
    cursor = None
    while True:
        args = [
            "api", "graphql",
            "-f", f"query={query}",
            "-F", f"owner={owner}",
            "-F", f"name={name}",
            "-F", f"number={pr_number}",
        ]
        if cursor is not None:
            args.extend(["-F", f"cursor={cursor}"])

        payload = gh_json(args, repo=repo)
        if not isinstance(payload, dict):
            raise GhCommandError("Unexpected GraphQL payload for reviews")
        if payload.get("errors"):
            raise GhCommandError("GraphQL reviews query returned errors")
        data = payload.get("data")
        repository = data.get("repository") if isinstance(data, dict) else None
        pull_request = repository.get("pullRequest") if isinstance(repository, dict) else None
        reviews = pull_request.get("reviews") if isinstance(pull_request, dict) else None
        nodes = reviews.get("nodes") if isinstance(reviews, dict) else None
        if not isinstance(nodes, list):
            raise GhCommandError("Unexpected GraphQL reviews payload")
        for node in nodes:
            if not isinstance(node, dict):
                raise GhCommandError("Unexpected GraphQL review node payload")
            user = node.get("user")
            # GraphQL reports bot logins without the `[bot]` suffix that REST uses.
            if isinstance(user, dict) and user.get("type") == "Bot":
                login = user.get("login")
                if isinstance(login, str) and login and not login.endswith("[bot]"):
                    node = dict(node, user=dict(user, login=f"{login}[bot]"))
            items.append(node)

        page_info = reviews.get("pageInfo")
        if not isinstance(page_info, dict):
            raise GhCommandError("Unexpected GraphQL reviews pageInfo payload")
        if not bool(page_info.get("hasNextPage")):
            break
        cursor = page_info.get("endCursor")
        if not cursor:
            raise GhCommandError("Missing GraphQL reviews pagination cursor")

    return items


def get_review_payload(repo, pr_number):
    """List PR reviews through REST, and through GraphQL when REST fails.

    The REST review list sometimes fails for a PR while GraphQL still works. If both
    fail, the error propagates and the poll counts as failed: no review state is
    never read as "no reviews".
    """
    try:
        return gh_api_list_paginated(comment_endpoints(repo, pr_number)["review"], repo=repo)
    except GhCommandError:
        return gh_graphql_list_reviews(repo, pr_number)


def normalize_issue_comments(items):
    out = []
    for item in items:
        if not isinstance(item, dict):
            continue
        out.append(
            {
                "kind": "issue_comment",
                "id": str(item.get("id") or ""),
                "author": extract_login(item.get("user")),
                "author_association": str(item.get("author_association") or ""),
                "created_at": str(item.get("created_at") or ""),
                "updated_at": str(item.get("updated_at") or item.get("created_at") or ""),
                "body": str(item.get("body") or ""),
                "path": None,
                "line": None,
                "commit_id": None,
                "url": str(item.get("html_url") or ""),
            }
        )
    return out


def normalize_review_comments(items):
    out = []
    for item in items:
        if not isinstance(item, dict):
            continue
        line = item.get("line")
        if line is None:
            line = item.get("original_line")
        out.append(
            {
                "kind": "review_comment",
                "id": str(item.get("id") or ""),
                "author": extract_login(item.get("user")),
                "author_association": str(item.get("author_association") or ""),
                "created_at": str(item.get("created_at") or ""),
                "updated_at": str(item.get("updated_at") or item.get("created_at") or ""),
                "body": str(item.get("body") or ""),
                "path": item.get("path"),
                "line": line,
                "commit_id": str(item.get("commit_id") or ""),
                "review_id": str(item.get("pull_request_review_id") or ""),
                "url": str(item.get("html_url") or ""),
            }
        )
    return out


def normalize_reviews(items):
    out = []
    for item in items:
        if not isinstance(item, dict):
            continue
        out.append(
            {
                "kind": "review",
                "id": str(item.get("id") or ""),
                "author": extract_login(item.get("user")),
                "author_association": str(item.get("author_association") or ""),
                "created_at": str(item.get("submitted_at") or item.get("created_at") or ""),
                "updated_at": str(
                    item.get("updated_at")
                    or item.get("submitted_at")
                    or item.get("created_at")
                    or ""
                ),
                "body": str(item.get("body") or ""),
                "review_state": str(item.get("state") or "").upper(),
                "path": None,
                "line": None,
                "commit_id": None,
                "url": str(item.get("html_url") or ""),
            }
        )
    return out


def extract_login(user_obj):
    if isinstance(user_obj, dict):
        return str(user_obj.get("login") or "")
    return ""


def is_bot_login(login):
    return bool(login) and login.endswith("[bot]")


def is_actionable_review_bot_login(login):
    if not is_bot_login(login):
        return False
    lower_login = login.lower()
    return any(keyword.lower() in lower_login for keyword in CONFIG["review_bot_login_keywords"])


def is_pr_af_review_item(item, pr_af_review_ids=None, pr_af_check_present=True):
    """A PR-AF finding: the configured author, plus a known marker or a marked parent review.

    PR-AF usually posts as the shared github-actions[bot] login. A marker counts only
    while a PR-AF check exists on the current head, so an ordinary workflow comment
    cannot become a finding by echoing the marker text.
    """
    pr_af = CONFIG["pr_af"]
    if not pr_af["enabled"] or not pr_af_check_present:
        return False
    if str(item.get("author") or "").lower() != str(pr_af["review_author_login"]).lower():
        return False
    review_id = str(item.get("review_id") or "")
    if review_id and review_id in (pr_af_review_ids or set()):
        return True
    body = str(item.get("body") or "").lower()
    return any(marker.lower() in body for marker in pr_af["review_body_markers"])


def is_pr_af_author(item):
    pr_af = CONFIG["pr_af"]
    return bool(pr_af["enabled"]) and (
        str(item.get("author") or "").lower() == str(pr_af["review_author_login"]).lower()
    )


def is_actionable_review_bot_item(item, pr_af_review_ids=None, pr_af_check_present=True):
    author = str(item.get("author") or "")
    if STATUS_ONLY_BOT_COMMENT_MARKER in str(item.get("body") or ""):
        return False
    if is_codex_clean_review_comment(item):
        return False
    if is_pr_af_author(item):
        return is_pr_af_review_item(
            item, pr_af_review_ids=pr_af_review_ids, pr_af_check_present=pr_af_check_present
        )
    return is_actionable_review_bot_login(author)


def is_trusted_human_review_author(item, authenticated_login):
    _ = authenticated_login
    author = str(item.get("author") or "")
    if not author:
        return False
    association = str(item.get("author_association") or "").upper()
    return association in {str(item).upper() for item in CONFIG["trusted_author_associations"]}


def fetch_new_review_items(pr, state, fresh_state, authenticated_login=None, pr_af_gate=None):
    repo = pr["repo"]
    pr_number = pr["number"]
    head_sha = str(pr.get("head_sha") or "")
    endpoints = comment_endpoints(repo, pr_number)

    if fresh_state:
        # Force a full review scan on fresh state files and clear any stale
        # review cursor inherited from prior sessions.
        state["last_review_poll_at"] = None

    now_utc = datetime.now(timezone.utc)

    issue_payload = gh_api_list_paginated(
        endpoints["issue_comment"],
        repo=repo,
    )
    review_comment_payload = gh_api_list_paginated(
        endpoints["review_comment"],
        repo=repo,
    )
    review_payload = get_review_payload(repo, pr_number)

    issue_items = normalize_issue_comments(issue_payload)
    review_comment_items = normalize_review_comments(review_comment_payload)
    review_items = normalize_reviews(review_payload)
    all_items = issue_items + review_comment_items + review_items
    pr_af_check_present = bool((pr_af_gate or {}).get("present"))
    pr_af_review_ids = {
        str(item.get("id") or "")
        for item in review_items
        if is_pr_af_review_item(item, pr_af_check_present=pr_af_check_present)
    }

    # Look up unresolved review threads via GraphQL when there are any review
    # comments at all.  Unresolved threads block merge regardless of which
    # commit they were posted on.
    unresolved_review_comment_ids = None
    unresolved_lookup_truncated = False
    if review_comment_items:
        try:
            unresolved_result = get_unresolved_review_comment_ids(repo, pr_number)
            if isinstance(unresolved_result, dict):
                unresolved_review_comment_ids = set(unresolved_result.get("ids") or [])
                unresolved_lookup_truncated = bool(unresolved_result.get("truncated"))
            else:
                unresolved_review_comment_ids = set()
        except GhCommandError:
            unresolved_review_comment_ids = None

    seen_issue = {str(x) for x in state.get("seen_issue_comment_ids") or []}
    seen_review_comment = {str(x) for x in state.get("seen_review_comment_ids") or []}
    seen_review = {str(x) for x in state.get("seen_review_ids") or []}
    seen_issue_updated_at = {
        str(key): str(value)
        for key, value in (state.get("seen_issue_comment_updated_at") or {}).items()
    }
    seen_review_comment_updated_at = {
        str(key): str(value)
        for key, value in (state.get("seen_review_comment_updated_at") or {}).items()
    }
    seen_review_updated_at = {
        str(key): str(value)
        for key, value in (state.get("seen_review_updated_at") or {}).items()
    }

    # On a brand-new state file, surface existing review activity instead of
    # silently treating it as seen. This avoids missing already-pending review
    # feedback when monitoring starts after comments were posted.

    new_items = []
    blocking_items = []
    for item in all_items:
        item_id = item.get("id")
        if not item_id:
            continue
        author = item.get("author") or ""
        if not author:
            continue
        is_review_comment = str(item.get("kind") or "") == "review_comment"
        if authenticated_login and author == authenticated_login:
            # The agent usually authenticates as the owner. Never surface its own comments as
            # new items, but an owner's inline thread that is still open must keep blocking.
            if is_review_comment and (
                unresolved_review_comment_ids is None
                or item_id in unresolved_review_comment_ids
                or unresolved_lookup_truncated
            ):
                blocking_items.append(item)
            continue
        if is_bot_login(author):
            if not is_actionable_review_bot_item(
                item, pr_af_review_ids=pr_af_review_ids, pr_af_check_present=pr_af_check_present
            ):
                continue
        elif not is_trusted_human_review_author(item, authenticated_login):
            continue

        is_blocking = False
        if unresolved_review_comment_ids is not None:
            # Block on any inline comment whose thread is unresolved, regardless
            # of which commit it was posted on.
            is_blocking = (
                is_review_comment
                and (item_id in unresolved_review_comment_ids or unresolved_lookup_truncated)
            )
        else:
            # Without thread state, an inline comment of any age may still be open. Fail closed:
            # block on every inline comment until the lookup works again.
            is_blocking = is_review_comment

        if is_blocking:
            blocking_items.append(item)

        kind = item["kind"]
        item_updated_at = str(item.get("updated_at") or item.get("created_at") or "")

        if kind == "review" and str(item.get("review_state") or "") == "APPROVED":
            seen_review.add(item_id)
            seen_review_updated_at[item_id] = item_updated_at
            continue
        if kind == "issue_comment" and item_id in seen_issue and seen_issue_updated_at.get(item_id) == item_updated_at:
            continue
        if (
            kind == "review_comment"
            and item_id in seen_review_comment
            and seen_review_comment_updated_at.get(item_id) == item_updated_at
        ):
            continue
        if kind == "review" and item_id in seen_review and seen_review_updated_at.get(item_id) == item_updated_at:
            continue

        new_items.append(item)
        if kind == "issue_comment":
            seen_issue.add(item_id)
            seen_issue_updated_at[item_id] = item_updated_at
        elif kind == "review_comment":
            seen_review_comment.add(item_id)
            seen_review_comment_updated_at[item_id] = item_updated_at
        elif kind == "review":
            seen_review.add(item_id)
            seen_review_updated_at[item_id] = item_updated_at

    new_items.sort(
        key=lambda item: (
            item.get("created_at") or "",
            item.get("kind") or "",
            item.get("id") or "",
        )
    )
    blocking_items.sort(
        key=lambda item: (
            item.get("created_at") or "",
            item.get("kind") or "",
            item.get("id") or "",
        )
    )

    state["seen_issue_comment_ids"] = sorted(seen_issue)
    state["seen_review_comment_ids"] = sorted(seen_review_comment)
    state["seen_review_ids"] = sorted(seen_review)
    state["seen_issue_comment_updated_at"] = seen_issue_updated_at
    state["seen_review_comment_updated_at"] = seen_review_comment_updated_at
    state["seen_review_updated_at"] = seen_review_updated_at
    state["last_review_poll_at"] = now_utc.isoformat().replace("+00:00", "Z")
    return new_items, blocking_items


def current_retry_count(state, head_sha):
    retries = state.get("retries_by_sha") or {}
    value = retries.get(head_sha, 0)
    try:
        return int(value)
    except (TypeError, ValueError):
        return 0


def set_retry_count(state, head_sha, count):
    retries = state.get("retries_by_sha")
    if not isinstance(retries, dict):
        retries = {}
    retries[head_sha] = int(count)
    state["retries_by_sha"] = retries


def unique_actions(actions):
    out = []
    seen = set()
    for action in actions:
        if action not in seen:
            out.append(action)
            seen.add(action)
    return out


def codex_waiting_for_head_review(codex_gate):
    """Codex is active on the PR but has not finished a review of the current head yet."""
    return (
        bool(codex_gate)
        and not bool(codex_gate.get("idle_wait_expired"))
        and bool(codex_gate.get("active"))
        and not bool(codex_gate.get("head_reviewed"))
        and str(codex_gate.get("head_status") or "") != "failed"
    )


def codex_review_failed(codex_gate):
    """Codex's summary reports a failed or unknown review status for the current head."""
    return (
        bool(codex_gate)
        and not bool(codex_gate.get("reviewing"))
        and bool(codex_gate.get("active"))
        and not bool(codex_gate.get("head_reviewed"))
        and str(codex_gate.get("head_status") or "") == "failed"
    )


def codex_required():
    return bool(CONFIG["codex"]["enabled"]) and bool(CONFIG["codex"]["required"])


def apply_codex_idle_wait(codex_gate, checks_summary, checks_terminal_elapsed):
    """Stop waiting for a Codex review of the head that never starts.

    Codex can be active on a PR (its summary table exists) and still never review a new
    head. Without `codex.required`, the watcher waits `codex.idle_wait_minutes` after the
    checks finish. When Codex has not started by then (no 👀 reaction, no running or
    completed row for the head), the missing review stops blocking readiness, and the
    gate says so in `idle_wait_expired` and `note`. A running review still blocks, an
    unreadable Codex state still waits, and with `codex.required` the watcher asks for a
    review instead.
    """
    if not codex_gate or codex_required():
        return codex_gate
    if (
        not codex_gate.get("active")
        or codex_gate.get("reviewing")
        or codex_gate.get("head_reviewed")
        or str(codex_gate.get("head_status") or "") != "none"
        or str(codex_gate.get("status") or "") == "unknown"
    ):
        return codex_gate
    if not checks_summary.get("all_terminal") or checks_terminal_elapsed is None:
        return codex_gate
    minutes = int(CONFIG["codex"]["idle_wait_minutes"])
    limit = max(minutes * 60, CHECKS_TERMINAL_GRACE_PERIOD_SECONDS)
    if checks_terminal_elapsed < limit:
        return codex_gate
    gate = dict(codex_gate)
    gate["idle_wait_expired"] = True
    gate["note"] = (
        f"Codex did not review this head within {minutes} minutes after the checks finished, "
        "so the missing review no longer blocks readiness."
    )
    return gate


def codex_review_stale_but_required(codex_gate):
    """The config requires Codex, and its latest review is of another commit.

    Codex is active on the PR, is not reviewing now, and has no row at all for the head
    in its summary table.
    """
    return (
        codex_required()
        and bool(codex_gate)
        and bool(codex_gate.get("active"))
        and not bool(codex_gate.get("reviewing"))
        and not bool(codex_gate.get("head_reviewed"))
        and str(codex_gate.get("head_status") or "") == "none"
        and str(codex_gate.get("status") or "") != "unknown"
    )


def codex_missing_but_required(codex_gate):
    """The config requires Codex, but Codex has not shown up on this PR at all."""
    if not codex_required():
        return False
    if not codex_gate:
        return True
    return not bool(codex_gate.get("active")) and not bool(codex_gate.get("reviewing"))


def is_pr_ready_to_merge(
    pr,
    checks_summary,
    new_review_items,
    checks_terminal_elapsed=None,
    blocking_review_items=None,
    codex_gate=None,
    pr_af_gate=None,
):
    if pr["closed"] or pr["merged"]:
        return False
    if not checks_summary["all_terminal"]:
        return False
    if (
        checks_summary["failed_count"] > 0
        or checks_summary["pending_count"] > 0
        or checks_summary.get("skipping_count", 0) > 0
    ):
        return False
    if not has_green_check_set(checks_summary):
        return False
    if new_review_items:
        return False
    if blocking_review_items:
        return False
    if str(pr.get("mergeable") or "") != "MERGEABLE":
        return False
    if merge_state_blocks_readiness(pr):
        return False
    if str(pr.get("review_decision") or "") in MERGE_BLOCKING_REVIEW_DECISIONS:
        return False
    if codex_gate and bool(codex_gate.get("reviewing")):
        return False
    if codex_waiting_for_head_review(codex_gate) or codex_review_failed(codex_gate):
        return False
    if codex_required() and not (codex_gate and codex_gate.get("head_reviewed")):
        return False
    if pr_af_holds_readiness(pr_af_gate):
        return False
    # A failed reactions lookup means we cannot tell whether Codex is still reviewing.
    if codex_gate and str(codex_gate.get("status") or "") == "unknown":
        return False
    # Enforce a grace period after checks go terminal.  Review bots complete
    # their CI check run first and post inline PR review comments a few seconds
    # later via a separate API call.  Declaring the PR ready before the grace
    # period elapses risks missing those comments and merging prematurely.
    if checks_terminal_elapsed is not None and checks_terminal_elapsed < CHECKS_TERMINAL_GRACE_PERIOD_SECONDS:
        return False
    return True


def is_branch_behind(pr):
    """The branch is behind its base and the base requires it to be up to date.

    GitHub reports BEHIND for any out-of-date head. It blocks the merge only when the
    base branch requires strict (up-to-date) status checks. Without the lookup result
    the watcher assumes it does.
    """
    if str(pr.get("merge_state_status") or "") not in MERGE_BEHIND_STATES:
        return False
    return pr.get("up_to_date_required", True) is not False


def merge_state_blocks_readiness(pr):
    state = str(pr.get("merge_state_status") or "")
    if state in MERGE_BEHIND_STATES:
        return is_branch_behind(pr)
    return state in MERGE_CONFLICT_OR_BLOCKING_STATES


# 404 answers of the protection endpoint that say for sure that no strict requirement
# exists. Any other 404 (for example a plain "Not Found") and every 403 prove nothing.
_NO_PROTECTION_MESSAGES = ("Branch not protected", "Required status checks not enabled")


def _is_definitive_no_protection(err):
    text = str(err)
    return "HTTP 404" in text and any(message in text for message in _NO_PROTECTION_MESSAGES)


def branch_protection_requires_up_to_date(repo, branch):
    """True or False from branch protection. Raises GhCommandError when it cannot tell.

    The endpoint needs repository administration access, so a collaborator token gets a
    403 even when strict checks are required. A 403, including the "Upgrade to GitHub Pro"
    answer on free private repositories, is therefore unknown, not "not required".
    """
    endpoint = f"repos/{repo}/branches/{quote(branch, safe='')}/protection/required_status_checks"
    try:
        data = gh_json(["api", endpoint])
    except GhCommandError as err:
        if _is_definitive_no_protection(err):
            return False
        raise
    if not isinstance(data, dict) or not isinstance(data.get("strict"), bool):
        raise GhCommandError(f"Unexpected payload from gh api {endpoint}")
    return data["strict"]


def rulesets_require_up_to_date(repo, branch):
    """True or False from the rulesets. Raises GhCommandError when it cannot tell."""
    endpoint = f"repos/{repo}/rules/branches/{quote(branch, safe='')}"
    rules = gh_api_list_paginated(endpoint)
    for rule in rules or []:
        if not isinstance(rule, dict) or rule.get("type") != "required_status_checks":
            continue
        parameters = rule.get("parameters") or {}
        if parameters.get("strict_required_status_checks_policy") is True:
            return True
    return False


def base_requires_up_to_date(repo, branch):
    """Whether the base branch requires a PR to be up to date before it can merge.

    The config can answer directly with true or false. With "auto" the watcher reads the
    branch protection and the rulesets. Only definitive answers say "not required": a
    404 that says the branch has no protection or no required checks, or a successful
    answer with strict set to false, and in both cases rulesets without a strict rule.
    Anything else, such as a 403 or a failed call, counts as "required", so the watcher
    never reports an unmergeable PR as ready. Only definitive answers are cached.
    """
    setting = CONFIG["require_up_to_date"]
    if setting is True or setting is False:
        return setting
    if not branch:
        return True
    key = (repo, branch)
    if key not in _UP_TO_DATE_CACHE:
        try:
            _UP_TO_DATE_CACHE[key] = (
                rulesets_require_up_to_date(repo, branch)
                or branch_protection_requires_up_to_date(repo, branch)
            )
        except GhCommandError:
            return True
    return _UP_TO_DATE_CACHE[key]


def is_merge_blocked_without_reason(pr, checks_summary, checks_terminal_elapsed):
    """GitHub says BLOCKED although every check the watcher can see is green.

    Typical causes are a required status check that never reported, a required
    signature, or a ruleset. None of them resolves by waiting, so the watcher hands
    the PR to the agent instead of idling until the session timeout. The caller only
    asks when no other action already explains the state.
    """
    if str(pr.get("merge_state_status") or "") != "BLOCKED":
        return False
    if str(pr.get("mergeable") or "") == "CONFLICTING":
        return False
    # Waiting for a human approval is a normal reason to be blocked.
    if str(pr.get("review_decision") or "") in MERGE_BLOCKING_REVIEW_DECISIONS:
        return False
    if not checks_summary.get("all_terminal"):
        return False
    if (
        int(checks_summary.get("failed_count") or 0) > 0
        or int(checks_summary.get("pending_count") or 0) > 0
        or int(checks_summary.get("skipping_count") or 0) > 0
    ):
        return False
    if not has_green_check_set(checks_summary):
        return False
    # GitHub can lag behind the checks for a moment. Give it the same grace period
    # that review bots get.
    return grace_period_elapsed(checks_terminal_elapsed)


def grace_period_elapsed(checks_terminal_elapsed):
    return (
        checks_terminal_elapsed is not None
        and checks_terminal_elapsed >= CHECKS_TERMINAL_GRACE_PERIOD_SECONDS
    )


def has_no_checks(checks_summary, checks_terminal_elapsed):
    """GitHub reports no check at all for the PR, even after the grace period.

    Right after a push the checks may not be registered yet, so the grace period comes
    first. After that, an empty check set means no workflow runs on this PR. Waiting
    would only end at the session timeout.
    """
    if checks_summary.get("check_count") != 0:
        return False
    return grace_period_elapsed(checks_terminal_elapsed)


def is_required_check_missing(checks_summary, checks_terminal_elapsed):
    """Every check is done, but a required check never passed or never appeared."""
    if not checks_summary.get("required_missing"):
        return False
    if not checks_summary.get("all_terminal"):
        return False
    return grace_period_elapsed(checks_terminal_elapsed)


def is_merge_conflicted(pr):
    mergeable = str(pr.get("mergeable") or "")
    merge_state_status = str(pr.get("merge_state_status") or "")
    if mergeable == "CONFLICTING":
        return True
    return merge_state_status in MERGE_CONFLICT_STATES


def pending_check_key(check):
    """Return a stable identity key for a pending check across snapshots."""
    name = str(check.get("name") or "")
    workflow = str(check.get("workflow") or "")
    link = str(check.get("link") or "")
    return f"{name.lower()}|{workflow}|{link}"


def reset_state_for_new_head_sha(state, head_sha):
    """Clear SHA-scoped tracking when the PR head SHA changes."""
    previous_sha = str(state.get("last_seen_head_sha") or "")
    current_sha = str(head_sha or "")
    if previous_sha and current_sha and previous_sha != current_sha:
        state["pending_checks_first_seen_at"] = {}
        state["pr_af_missing_since_at"] = None
        state["pr_af_missing_sha"] = None
        state["pr_af_label_absent_sha"] = None
        state["pr_af_label_rerun_sha"] = None
        state["pr_af_label_rerun_seen_at"] = None


def update_pending_checks_first_seen(state, checks, now_seconds):
    existing = state.get("pending_checks_first_seen_at")
    if not isinstance(existing, dict):
        existing = {}

    current_pending_keys = set()
    now_int = int(now_seconds)
    for check in checks:
        if not is_pending_check(check):
            continue
        key = pending_check_key(check)
        current_pending_keys.add(key)
        if key not in existing:
            existing[key] = now_int

    for key in list(existing.keys()):
        if key not in current_pending_keys:
            existing.pop(key, None)

    state["pending_checks_first_seen_at"] = existing
    return existing


def hung_threshold_for_check(check_name):
    """Return the hung-detection threshold in seconds for a given check name."""
    _ = check_name
    return int(CONFIG["hung_check_minutes"]) * 60


def hung_checks_from_checks(checks, pending_checks_first_seen_at):
    """Return checks that have been pending longer than their hung threshold."""
    hung = []
    now = time.time()
    for check in checks:
        if not is_pending_check(check):
            continue
        if is_optional_review_check(check):
            continue

        started_at = None
        started_at_str = check.get("startedAt") or ""
        if started_at_str:
            try:
                parsed = datetime.fromisoformat(
                    started_at_str.replace("Z", "+00:00")
                ).timestamp()
            except (ValueError, OverflowError, OSError):
                parsed = None
            # GitHub can report a zero-time sentinel (Go's `0001-01-01T00:00:00Z`
            # or the Unix epoch) as the `startedAt` for checks that are queued
            # but not yet started. Those parse to a non-positive timestamp,
            # which would make `elapsed` billions of seconds and falsely trip
            # hung detection. Treat any non-positive start time as "no real
            # start time" and fall back to first-seen tracking instead.
            if parsed is not None and parsed > 0:
                started_at = parsed

        if started_at is None:
            key = pending_check_key(check)
            first_seen = pending_checks_first_seen_at.get(key)
            try:
                started_at = float(first_seen)
            except (TypeError, ValueError):
                continue

        elapsed = now - started_at
        threshold = hung_threshold_for_check(check.get("name") or "")
        if elapsed > threshold:
            hung.append(
                {
                    "name": check.get("name") or "",
                    "elapsed_seconds": int(elapsed),
                    "threshold_seconds": threshold,
                }
            )
    return hung


def recommend_actions(
    pr,
    checks_summary,
    failed_runs,
    new_review_items,
    hung_checks,
    retries_used,
    max_retries,
    checks_terminal_elapsed=None,
    blocking_review_items=None,
    codex_gate=None,
    pr_af_gate=None,
):
    actions = []
    if pr["closed"] or pr["merged"]:
        if new_review_items:
            actions.append("process_review_comment")
        actions.append("stop_pr_closed")
        return unique_actions(actions)

    if is_merge_conflicted(pr):
        actions.append("diagnose_merge_conflict")

    if is_branch_behind(pr):
        actions.append("diagnose_branch_behind")

    # A draft can never become ready on its own. Once its checks are green, stop and hand it to the
    # owner instead of idling until the session timeout.
    if (
        str(pr.get("merge_state_status") or "") == "DRAFT"
        and checks_summary["all_terminal"]
        and checks_summary["failed_count"] == 0
    ):
        actions.append("stop_draft_pr")

    if is_pr_ready_to_merge(
        pr,
        checks_summary,
        new_review_items,
        checks_terminal_elapsed=checks_terminal_elapsed,
        blocking_review_items=blocking_review_items,
        codex_gate=codex_gate,
        pr_af_gate=pr_af_gate,
    ):
        actions.append("stop_ready_to_merge")
        return unique_actions(actions)

    if new_review_items:
        actions.append("process_review_comment")
    elif blocking_review_items:
        actions.append("process_review_comment")

    if codex_review_stale_but_required(codex_gate) and checks_summary["all_terminal"] and grace_period_elapsed(
        checks_terminal_elapsed
    ):
        # Codex reviewed an older head and is idle. Where Codex does not review every push
        # by itself, only a request brings a review of this head.
        actions.append("request_codex_review")
    elif codex_gate and (bool(codex_gate.get("reviewing")) or codex_waiting_for_head_review(codex_gate)):
        actions.append("wait_codex")
    elif codex_review_failed(codex_gate):
        actions.append("diagnose_codex_review")
    elif codex_missing_but_required(codex_gate):
        # Give Codex until the checks finish to show up on its own, then ask for a review.
        if checks_summary["all_terminal"] and grace_period_elapsed(checks_terminal_elapsed):
            actions.append("request_codex_review")
        else:
            actions.append("wait_codex")

    if pr_af_holds_readiness(pr_af_gate):
        actions.append("wait_pr_af")

    if hung_checks:
        actions.append("diagnose_hung_check")

    if checks_summary.get("skipping_count", 0) > 0 and checks_summary["all_terminal"]:
        actions.append("diagnose_skipping_checks")

    has_failed_pr_checks = checks_summary["failed_count"] > 0
    if has_failed_pr_checks:
        retry_eligible_failed_runs = [run for run in failed_runs if is_retry_eligible_failed_run(run)]
        non_retry_eligible_failed_runs = [run for run in failed_runs if not is_retry_eligible_failed_run(run)]
        actions.append("diagnose_ci_failure")
        if checks_summary["all_terminal"]:
            if non_retry_eligible_failed_runs:
                actions.append("stop_non_retryable_failure")
            elif retry_eligible_failed_runs:
                if retries_used >= max_retries:
                    actions.append("stop_exhausted_retries")
                else:
                    actions.append("retry_failed_checks")
            else:
                actions.append("stop_non_retryable_failure")

    if not actions and has_no_checks(checks_summary, checks_terminal_elapsed):
        actions.append("diagnose_no_checks")

    if not actions and is_required_check_missing(checks_summary, checks_terminal_elapsed):
        actions.append("diagnose_missing_required_checks")

    if not actions and is_merge_blocked_without_reason(pr, checks_summary, checks_terminal_elapsed):
        actions.append("diagnose_merge_blocked")

    if not actions:
        actions.append("idle")
    return unique_actions(actions)


def collect_snapshot(args):
    pr = resolve_pr(args.pr, repo_override=args.repo)
    state_path = Path(args.state_file) if args.state_file else default_state_file_for(pr)
    state, fresh_state = load_state(state_path)

    if not state.get("started_at"):
        state["started_at"] = int(time.time())

    now = int(time.time())
    reset_state_for_new_head_sha(state, pr["head_sha"])
    if str(pr.get("merge_state_status") or "") in MERGE_BEHIND_STATES:
        pr["up_to_date_required"] = base_requires_up_to_date(pr["repo"], pr.get("base_branch") or "")

    # `gh pr checks -R <repo>` requires an explicit PR/branch/url argument.
    # After resolving `--pr auto`, reuse the concrete PR number.
    checks = get_pr_checks(str(pr["number"]), repo=pr["repo"])
    checks_summary = summarize_checks(checks)
    pending_checks_first_seen_at = update_pending_checks_first_seen(state, checks, now)
    hung_checks = hung_checks_from_checks(checks, pending_checks_first_seen_at)
    pr_af_gate = collect_pr_af_gate(pr, checks, state, now) if CONFIG["pr_af"]["enabled"] else None

    workflow_runs = []
    failed_runs = []
    needs_failed_run_lookup = checks_summary["failed_count"] > 0
    if needs_failed_run_lookup:
        workflow_runs = get_workflow_runs_for_sha(pr["repo"], pr["head_sha"])
        failed_runs = failed_runs_from_workflow_runs(workflow_runs, pr["head_sha"])

    try:
        authenticated_login = get_authenticated_login()
    except GhCommandError:
        authenticated_login = None
    # Read Codex's state before scanning review comments. Codex posts its findings before it
    # marks the head reviewed, so this order can never pair "reviewed" with a stale scan.
    codex_gate = collect_codex_gate(pr) if CONFIG["codex"]["enabled"] else None
    new_review_items, blocking_review_items = fetch_new_review_items(
        pr,
        state,
        fresh_state=fresh_state,
        authenticated_login=authenticated_login,
        pr_af_gate=pr_af_gate,
    )

    # Track when checks first went all_terminal for the current head SHA.
    # This timestamp is used to enforce a grace period before emitting
    # stop_ready_to_merge, preventing a race where the script declares the PR
    # ready before review bots have finished posting their inline comments.
    head_sha = pr["head_sha"]
    if checks_summary["all_terminal"]:
        if state.get("checks_terminal_sha") != head_sha:
            # First time we see all_terminal for this SHA — record the timestamp.
            state["checks_terminal_sha"] = head_sha
            state["checks_went_terminal_at"] = now
        recorded_at = state.get("checks_went_terminal_at")
        if recorded_at is None:
            # SHA matched but timestamp is absent (e.g. manually edited state
            # file). Initialize to now so the grace period starts from this poll.
            state["checks_went_terminal_at"] = now
            checks_terminal_elapsed = 0
        else:
            checks_terminal_elapsed = max(0, now - int(recorded_at))
    else:
        # Checks are still running — reset so the grace period starts fresh
        # once they become terminal.
        state["checks_terminal_sha"] = None
        state["checks_went_terminal_at"] = None
        checks_terminal_elapsed = None


    codex_gate = apply_codex_idle_wait(codex_gate, checks_summary, checks_terminal_elapsed)
    retries_used = current_retry_count(state, pr["head_sha"])
    actions = recommend_actions(
        pr,
        checks_summary,
        failed_runs,
        new_review_items,
        hung_checks,
        retries_used,
        args.max_flaky_retries,
        checks_terminal_elapsed=checks_terminal_elapsed,
        blocking_review_items=blocking_review_items,
        codex_gate=codex_gate,
        pr_af_gate=pr_af_gate,
    )

    state["pr"] = {"repo": pr["repo"], "number": pr["number"]}
    state["last_seen_head_sha"] = pr["head_sha"]
    state["last_snapshot_at"] = now
    save_state(state_path, state)

    snapshot = {
        "pr": pr,
        "checks": checks_summary,
        "failed_runs": failed_runs,
        "codex_gate": codex_gate,
        "pr_af_gate": pr_af_gate,
        "hung_checks": hung_checks,
        "new_review_items": new_review_items,
        "blocking_review_items": blocking_review_items,
        "actions": actions,
        "retry_state": {
            "current_sha_retries_used": retries_used,
            "max_flaky_retries": args.max_flaky_retries,
        },
        "checks_terminal_elapsed_seconds": checks_terminal_elapsed,
    }
    return snapshot, state_path


def retry_failed_now(args):
    snapshot, state_path = collect_snapshot(args)
    pr = snapshot["pr"]
    checks_summary = snapshot["checks"]
    failed_runs = snapshot["failed_runs"]
    retries_used = snapshot["retry_state"]["current_sha_retries_used"]
    max_retries = snapshot["retry_state"]["max_flaky_retries"]

    result = {
        "snapshot": snapshot,
        "state_file": str(state_path),
        "rerun_attempted": False,
        "rerun_count": 0,
        "rerun_run_ids": [],
        "reason": None,
    }

    if pr["closed"] or pr["merged"]:
        result["reason"] = "pr_closed"
        return result
    if checks_summary["failed_count"] <= 0:
        result["reason"] = "no_failed_pr_checks"
        return result
    if not failed_runs:
        result["reason"] = "no_failed_runs"
        return result
    if not checks_summary["all_terminal"]:
        result["reason"] = "checks_still_pending"
        return result

    retry_eligible_failed_runs = [run for run in failed_runs if is_retry_eligible_failed_run(run)]
    non_retry_eligible_failed_runs = [run for run in failed_runs if not is_retry_eligible_failed_run(run)]
    if non_retry_eligible_failed_runs:
        result["reason"] = "contains_non_retryable_failed_runs"
        return result
    if not retry_eligible_failed_runs:
        result["reason"] = "no_retry_eligible_failed_runs"
        return result
    if retries_used >= max_retries:
        result["reason"] = "retry_budget_exhausted"
        return result

    for run in retry_eligible_failed_runs:
        run_id = run.get("run_id")
        if run_id in (None, ""):
            continue
        gh_text(["run", "rerun", str(run_id), "--failed"], repo=pr["repo"])
        result["rerun_run_ids"].append(run_id)

    if result["rerun_run_ids"]:
        state, _ = load_state(state_path)
        new_count = current_retry_count(state, pr["head_sha"]) + 1
        set_retry_count(state, pr["head_sha"], new_count)
        state["last_snapshot_at"] = int(time.time())
        save_state(state_path, state)
        result["rerun_attempted"] = True
        result["rerun_count"] = len(result["rerun_run_ids"])
        result["reason"] = "rerun_triggered"
    else:
        result["reason"] = "failed_runs_missing_ids"

    return result


def print_json(obj):
    sys.stdout.write(json.dumps(obj, sort_keys=True) + "\n")
    sys.stdout.flush()


def print_event(event, payload):
    print_json({"event": event, "payload": payload})


def is_ci_green(snapshot):
    """Return True only when it is safe to start backing off the poll interval.

    Requirements:
    - All checks are terminal with no failures.
    - The PR review decision is not actively blocking (REVIEW_REQUIRED /
      CHANGES_REQUESTED): those states mean we are explicitly waiting for a
      reviewer, so backing off would delay detecting their response.
    - The checks-terminal grace period has fully elapsed.  During the grace
      window we are explicitly waiting for review bots to post their inline
      comments, so backing off would delay detecting them.
    """
    if _grace_period_active(snapshot):
        return False
    checks = snapshot.get("checks") or {}
    pr = snapshot.get("pr") or {}
    blocking_review_items = snapshot.get("blocking_review_items") or []
    review_decision = str(pr.get("review_decision") or "")
    codex_gate = snapshot.get("codex_gate") or {}
    codex_reviewing = bool(codex_gate.get("reviewing"))
    pr_af_running = str((snapshot.get("pr_af_gate") or {}).get("status") or "") == "in_progress"
    return (
        bool(checks.get("all_terminal"))
        and has_green_check_set(checks)
        and int(checks.get("failed_count") or 0) == 0
        and int(checks.get("pending_count") or 0) == 0
        and not blocking_review_items
        and review_decision not in MERGE_BLOCKING_REVIEW_DECISIONS
        and not codex_reviewing
        and not pr_af_running
    )


def snapshot_change_key(snapshot):
    pr = snapshot.get("pr") or {}
    checks = snapshot.get("checks") or {}
    review_items = snapshot.get("new_review_items") or []
    blocking_review_items = snapshot.get("blocking_review_items") or []
    codex_gate = snapshot.get("codex_gate") or {}
    return (
        str(pr.get("head_sha") or ""),
        str(pr.get("state") or ""),
        str(pr.get("mergeable") or ""),
        str(pr.get("merge_state_status") or ""),
        str(pr.get("review_decision") or ""),
        int(checks.get("passed_count") or 0),
        int(checks.get("failed_count") or 0),
        int(checks.get("pending_count") or 0),
        tuple(
            (str(item.get("kind") or ""), str(item.get("id") or ""))
            for item in review_items
            if isinstance(item, dict)
        ),
        tuple(
            (str(item.get("kind") or ""), str(item.get("id") or ""))
            for item in blocking_review_items
            if isinstance(item, dict)
        ),
        tuple(snapshot.get("actions") or []),
        bool(codex_gate.get("reviewing")),
        str((snapshot.get("pr_af_gate") or {}).get("status") or ""),
        str((snapshot.get("pr_af_gate") or {}).get("conclusion") or ""),
        # Include whether the checks-terminal grace period is still active.
        # This flips exactly once (True → False) when the grace period expires,
        # ensuring the change-key transitions at that moment and preventing the
        # exponential backoff from starting until after the grace window closes.
        _grace_period_active(snapshot),
    )


def _grace_period_active(snapshot):
    elapsed = snapshot.get("checks_terminal_elapsed_seconds")
    if elapsed is None:
        return False
    return elapsed < CHECKS_TERMINAL_GRACE_PERIOD_SECONDS


# Actions that mean "nothing for the agent to do yet, keep waiting internally".
# Everything else requires agent attention and should cause --once to return.
# Waits for a review bot that is still working on the current head.
BOT_WAIT_ACTIONS = {
    "wait_codex",
    "wait_pr_af",
}
PASSIVE_WAIT_ACTIONS = {"idle"} | BOT_WAIT_ACTIONS
# Actions that ask the agent to update the branch, which starts new bot reviews.
BRANCH_UPDATE_ACTIONS = {
    "diagnose_merge_conflict",
    "diagnose_branch_behind",
}


def waiting_on_review_bot(actions):
    return bool(set(actions or []) & BOT_WAIT_ACTIONS)


def needs_agent_attention(actions):
    """Return True when the actions list contains something the agent should act on.

    Used by --once to decide when to stop polling and return to the caller.
    Returns True for any action that is not a passive wait. A branch update (merge
    conflict or branch behind) waits while a review bot is still running, so that
    its findings land in the same fix cycle as the update. An empty actions list
    also returns True as a safety measure.
    """
    action_set = set(actions or [])
    if not action_set:
        return True
    if waiting_on_review_bot(action_set) and action_set.issubset(PASSIVE_WAIT_ACTIONS | BRANCH_UPDATE_ACTIONS):
        return False
    return not action_set.issubset(PASSIVE_WAIT_ACTIONS)


def should_stop_watching(actions):
    action_set = set(actions or [])
    if "diagnose_merge_blocked" in action_set:
        return True
    if "diagnose_missing_required_checks" in action_set:
        return True
    if "diagnose_no_checks" in action_set:
        return True
    if "stop_pr_closed" in action_set:
        return True
    if "stop_exhausted_retries" in action_set:
        return True
    if "stop_non_retryable_failure" in action_set:
        return True
    if "stop_ready_to_merge" in action_set:
        return True
    if "stop_draft_pr" in action_set:
        return True
    if "diagnose_hung_check" in action_set:
        return True
    if "diagnose_skipping_checks" in action_set:
        return True
    if action_set & BRANCH_UPDATE_ACTIONS and not waiting_on_review_bot(action_set):
        return True
    return False


def with_session_timeout_action(snapshot):
    """Keep the snapshot's own actions and add stop_session_timeout."""
    snapshot = dict(snapshot or {})
    snapshot["actions"] = unique_actions(list(snapshot.get("actions") or []) + ["stop_session_timeout"])
    return snapshot


def run_watch(args):
    poll_seconds = args.poll_seconds
    last_change_key = None
    watch_started_at = time.time()
    max_session_seconds = args.max_session_minutes * 60
    while True:
        elapsed = time.time() - watch_started_at
        if elapsed > max_session_seconds:
            # Report the last known state with the timeout, not the timeout alone.
            try:
                snapshot, state_path = collect_snapshot(args)
            except (GhCommandError, RuntimeError):
                snapshot, state_path = {"actions": []}, None
            snapshot = with_session_timeout_action(snapshot)
            print_event(
                "stop",
                {
                    "actions": snapshot["actions"],
                    "snapshot": snapshot,
                    "state_file": str(state_path) if state_path else None,
                    "elapsed_seconds": int(elapsed),
                    "max_session_seconds": max_session_seconds,
                },
            )
            return 0
        try:
            snapshot, state_path = collect_snapshot(args)
        except (GhCommandError, RuntimeError) as err:
            sys.stderr.write(f"gh_pr_watch.py poll error (retrying): {err}\n")
            time.sleep(poll_seconds)
            continue
        actions = snapshot.get("actions") or []
        if should_stop_watching(actions):
            print_event(
                "snapshot",
                {
                    "snapshot": snapshot,
                    "state_file": str(state_path),
                    "next_poll_seconds": poll_seconds,
                },
            )
            print_event("stop", {"actions": snapshot.get("actions"), "pr": snapshot.get("pr")})
            return 0

        current_change_key = snapshot_change_key(snapshot)
        changed = current_change_key != last_change_key
        green = is_ci_green(snapshot)

        if not green or changed or last_change_key is None:
            next_poll_seconds = args.poll_seconds
        else:
            next_poll_seconds = min(poll_seconds * 2, GREEN_STATE_MAX_POLL_SECONDS)

        print_event(
            "snapshot",
            {
                "snapshot": snapshot,
                "state_file": str(state_path),
                "next_poll_seconds": next_poll_seconds,
            },
        )

        last_change_key = current_change_key
        poll_seconds = next_poll_seconds
        time.sleep(poll_seconds)


def run_once(args):
    """Poll internally until something needs agent attention, then return one snapshot.

    Unlike an instant snapshot, this blocks until checks go terminal, a review
    comment arrives, a stop condition fires, or the session timeout elapses.
    The caller (the agent) only gets called back when there is something to do,
    eliminating blind sleep loops.
    """
    poll_seconds = args.poll_seconds
    started_at = time.time()
    max_session_seconds = args.max_session_minutes * 60
    while True:
        elapsed = time.time() - started_at
        if elapsed > max_session_seconds:
            # Return a timeout snapshot so the agent knows what happened.
            try:
                snapshot, state_path = collect_snapshot(args)
            except (GhCommandError, RuntimeError):
                snapshot, state_path = {"actions": []}, None
            snapshot = with_session_timeout_action(snapshot)
            snapshot["state_file"] = str(state_path) if state_path else None
            return snapshot

        try:
            snapshot, state_path = collect_snapshot(args)
        except (GhCommandError, RuntimeError) as err:
            sys.stderr.write(f"gh_pr_watch.py poll error (retrying): {err}\n")
            time.sleep(poll_seconds)
            continue

        actions = snapshot.get("actions") or []
        snapshot["state_file"] = str(state_path)

        if needs_agent_attention(actions):
            return snapshot

        # Nothing actionable yet — keep polling.
        time.sleep(poll_seconds)


def activate_config(argv):
    """Load the config named by --config (or the default file) into CONFIG."""
    global CONFIG
    pre_parser = argparse.ArgumentParser(add_help=False)
    pre_parser.add_argument("--config")
    known, _unknown = pre_parser.parse_known_args(argv)
    if known.config:
        path, explicit = Path(known.config), True
    else:
        path, explicit = default_config_path(), False
    config, warnings = load_config(path, explicit=explicit)
    for warning in warnings:
        sys.stderr.write(f"gh_pr_watch.py config warning: {warning}\n")
    CONFIG = config
    return path if path.exists() else None


def main():
    try:
        config_file = activate_config(sys.argv[1:])
    except ConfigError as err:
        sys.stderr.write(f"gh_pr_watch.py config error: {err}\n")
        return 1
    args = parse_args()
    if args.print_config:
        print_json({"config": CONFIG, "config_file": str(config_file) if config_file else None})
        return 0
    try:
        if args.retry_failed_now:
            print_json(retry_failed_now(args))
            return 0
        if args.watch:
            return run_watch(args)
        if args.snapshot:
            # Instant snapshot — no waiting.
            snapshot, state_path = collect_snapshot(args)
            snapshot["state_file"] = str(state_path)
            print_json(snapshot)
            return 0
        # --once (default): poll internally until something needs agent attention.
        snapshot = run_once(args)
        print_json(snapshot)
        return 0
    except (GhCommandError, RuntimeError, ValueError) as err:
        sys.stderr.write(f"gh_pr_watch.py error: {err}\n")
        return 1
    except KeyboardInterrupt:
        sys.stderr.write("gh_pr_watch.py interrupted\n")
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
