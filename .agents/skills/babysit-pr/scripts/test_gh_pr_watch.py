import json
import os
import sys
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch

SCRIPT_DIR = os.path.dirname(__file__)
if SCRIPT_DIR not in sys.path:
    sys.path.insert(0, SCRIPT_DIR)

import gh_pr_watch as watch


class RetryEligibilityTests(unittest.TestCase):
    def _base_pr(self):
        return {
            "closed": False,
            "merged": False,
            "mergeable": "MERGEABLE",
            "merge_state_status": "CLEAN",
            "review_decision": "APPROVED",
        }

    def _failed_checks_summary(self):
        return {
            "all_terminal": True,
            "failed_count": 1,
            "pending_count": 0,
            "passed_count": 0,
        }

    def test_recommend_actions_does_not_retry_non_flaky_ci_failures(self):
        actions = watch.recommend_actions(
            pr=self._base_pr(),
            checks_summary=self._failed_checks_summary(),
            failed_runs=[
                {
                    "run_id": 123,
                    "workflow_name": "CI",
                    "status": "completed",
                    "conclusion": "failure",
                    "html_url": "https://example.invalid/ci",
                }
            ],
            new_review_items=[],
            hung_checks=[],
            retries_used=0,
            max_retries=3,
            checks_terminal_elapsed=120,
        )

        self.assertIn("diagnose_ci_failure", actions)
        self.assertIn("stop_non_retryable_failure", actions)
        self.assertNotIn("retry_failed_checks", actions)

    def test_recommend_actions_retries_e2e_failures(self):
        actions = watch.recommend_actions(
            pr=self._base_pr(),
            checks_summary=self._failed_checks_summary(),
            failed_runs=[
                {
                    "run_id": 456,
                    "workflow_name": "E2E",
                    "status": "completed",
                    "conclusion": "failure",
                    "html_url": "https://example.invalid/e2e",
                }
            ],
            new_review_items=[],
            hung_checks=[],
            retries_used=0,
            max_retries=3,
            checks_terminal_elapsed=120,
        )

        self.assertIn("retry_failed_checks", actions)

    def test_mixed_failures_prioritize_non_retryable_stop(self):
        actions = watch.recommend_actions(
            pr=self._base_pr(),
            checks_summary=self._failed_checks_summary(),
            failed_runs=[
                {
                    "run_id": 111,
                    "workflow_name": "CI",
                    "status": "completed",
                    "conclusion": "failure",
                    "retry_eligible": False,
                },
                {
                    "run_id": 222,
                    "workflow_name": "E2E",
                    "status": "completed",
                    "conclusion": "failure",
                    "retry_eligible": True,
                },
            ],
            new_review_items=[],
            hung_checks=[],
            retries_used=0,
            max_retries=3,
            checks_terminal_elapsed=120,
        )

        self.assertIn("stop_non_retryable_failure", actions)
        self.assertNotIn("retry_failed_checks", actions)

    def test_is_pr_ready_to_merge_blocks_on_blocking_review_items(self):
        ready = watch.is_pr_ready_to_merge(
            pr=self._base_pr(),
            checks_summary={
                "all_terminal": True,
                "failed_count": 0,
                "pending_count": 0,
                "passed_count": 2,
            },
            new_review_items=[],
            checks_terminal_elapsed=120,
            blocking_review_items=[{"id": "1", "kind": "review_comment"}],
        )

        self.assertFalse(ready)

    def test_is_blocking_review_item_ignores_stale_comments(self):
        created_at = "2026-01-01T00:00:00Z"
        created_at_seconds = watch.datetime.fromisoformat("2026-01-01T00:00:00+00:00").timestamp()
        stale_now = created_at_seconds + watch.BLOCKING_REVIEW_ITEM_FRESH_SECONDS + 1
        item = {
            "kind": "review_comment",
            "commit_id": "abc123",
            "created_at": created_at,
        }

        self.assertFalse(
            watch.is_blocking_review_item(item, head_sha="abc123", now_seconds=stale_now)
        )

    def test_recommend_actions_surfaces_merge_conflict(self):
        pr = self._base_pr()
        pr["mergeable"] = "CONFLICTING"
        pr["merge_state_status"] = "DIRTY"

        actions = watch.recommend_actions(
            pr=pr,
            checks_summary={
                "all_terminal": False,
                "failed_count": 0,
                "pending_count": 0,
                "passed_count": 0,
            },
            failed_runs=[],
            new_review_items=[],
            hung_checks=[],
            retries_used=0,
            max_retries=3,
            checks_terminal_elapsed=None,
            blocking_review_items=[],
            bugbot_gate={
                "required": True,
                "status": "completed",
                "conclusion": "success",
                "is_success": True,
            },
        )

        self.assertIn("diagnose_merge_conflict", actions)

    def test_recommend_actions_hard_blocks_bugbot_non_success(self):
        actions = watch.recommend_actions(
            pr=self._base_pr(),
            checks_summary={
                "all_terminal": True,
                "failed_count": 0,
                "pending_count": 0,
                "passed_count": 2,
            },
            failed_runs=[],
            new_review_items=[],
            hung_checks=[],
            retries_used=0,
            max_retries=3,
            checks_terminal_elapsed=120,
            blocking_review_items=[],
            bugbot_gate={
                "required": True,
                "status": "completed",
                "conclusion": "skipped",
                "is_success": False,
            },
        )

        self.assertIn("stop_bugbot_not_green", actions)
        self.assertNotIn("stop_ready_to_merge", actions)

    def test_recommend_actions_waits_during_bugbot_non_success_grace_window(self):
        actions = watch.recommend_actions(
            pr=self._base_pr(),
            checks_summary={
                "all_terminal": True,
                "failed_count": 0,
                "pending_count": 0,
                "passed_count": 2,
            },
            failed_runs=[],
            new_review_items=[],
            hung_checks=[],
            retries_used=0,
            max_retries=3,
            checks_terminal_elapsed=10,
            blocking_review_items=[],
            bugbot_gate={
                "required": True,
                "status": "completed",
                "conclusion": "neutral",
                "is_success": False,
            },
        )

        self.assertIn("wait_bugbot", actions)
        self.assertNotIn("stop_bugbot_not_green", actions)

    def test_summarize_bugbot_gate_prefers_pr_checks_over_actions_runs(self):
        checks = [
            {
                "name": "Cursor Bugbot",
                "workflow": "",
                "state": "SUCCESS",
                "bucket": "pass",
                "link": "https://example.invalid/bugbot-check",
                "startedAt": "2026-01-01T00:00:00Z",
                "completedAt": "2026-01-01T00:05:00Z",
            }
        ]
        runs = [
            {
                "head_sha": "abc123",
                "name": "CI",
                "status": "completed",
                "conclusion": "success",
                "html_url": "https://example.invalid/ci",
                "id": 1,
            }
        ]

        gate = watch.summarize_bugbot_gate(checks, runs, "abc123")

        self.assertTrue(gate["present"])
        self.assertEqual(gate["status"], "completed")
        self.assertEqual(gate["conclusion"], "success")
        self.assertTrue(gate["is_success"])
        self.assertEqual(gate["source"], "checks")

    def test_summarize_bugbot_gate_uses_bucket_when_state_completed(self):
        checks = [
            {
                "name": "Cursor Bugbot",
                "state": "COMPLETED",
                "bucket": "fail",
                "link": "https://example.invalid/bugbot-check",
            }
        ]

        gate = watch.summarize_bugbot_gate(checks, [], "abc123")

        self.assertEqual(gate["status"], "completed")
        self.assertEqual(gate["conclusion"], "failure")
        self.assertFalse(gate["is_success"])

    def test_summarize_bugbot_gate_prefers_pending_rerun_over_old_success(self):
        checks = [
            {
                "name": "Cursor Bugbot",
                "state": "SUCCESS",
                "bucket": "pass",
                "startedAt": "2026-01-01T00:00:00Z",
                "completedAt": "2026-01-01T00:05:00Z",
                "link": "https://example.invalid/old-success",
            },
            {
                "name": "Cursor Bugbot",
                "state": "IN_PROGRESS",
                "bucket": "pending",
                "startedAt": "2026-01-01T00:06:00Z",
                "completedAt": "",
                "link": "https://example.invalid/new-rerun",
            },
        ]

        gate = watch.summarize_bugbot_gate(checks, [], "abc123")

        self.assertEqual(gate["status"], "in_progress")
        self.assertEqual(gate["conclusion"], "")
        self.assertFalse(gate["is_success"])

    def test_fetch_new_review_items_excludes_resolved_blocking_comments(self):
        pr = {
            "repo": "ADUX-sandbox/Compose-Pi",
            "number": 716,
            "head_sha": "abc123",
        }
        state = {
            "seen_issue_comment_ids": [],
            "seen_review_comment_ids": [],
            "seen_review_ids": [],
            "last_review_poll_at": None,
        }

        review_comment_payload = [
            {
                "id": 42,
                "user": {"login": "cursor[bot]"},
                "author_association": "NONE",
                "created_at": "2026-01-01T00:00:00Z",
                "body": "Please fix this.",
                "path": "foo.kt",
                "line": 1,
                "commit_id": "abc123",
                "html_url": "https://example.invalid/comment",
            }
        ]

        with patch.object(
            watch,
            "gh_api_list_paginated",
            side_effect=[[], review_comment_payload, []],
        ), patch.object(
            watch,
            "get_unresolved_review_comment_ids",
            return_value={"ids": set(), "truncated": False},
        ):
            new_items, blocking_items = watch.fetch_new_review_items(
                pr,
                state,
                fresh_state=True,
                authenticated_login="octocat",
            )

        self.assertEqual(len(new_items), 1)
        self.assertEqual(blocking_items, [])

    def test_fetch_new_review_items_blocks_unresolved_comment_even_if_stale(self):
        pr = {
            "repo": "ADUX-sandbox/Compose-Pi",
            "number": 716,
            "head_sha": "abc123",
        }
        state = {
            "seen_issue_comment_ids": [],
            "seen_review_comment_ids": [],
            "seen_review_ids": [],
            "last_review_poll_at": None,
        }

        review_comment_payload = [
            {
                "id": 42,
                "user": {"login": "cursor[bot]"},
                "author_association": "NONE",
                "created_at": "2025-01-01T00:00:00Z",
                "body": "Please fix this.",
                "path": "foo.kt",
                "line": 1,
                "commit_id": "abc123",
                "html_url": "https://example.invalid/comment",
            }
        ]

        with patch.object(
            watch,
            "gh_api_list_paginated",
            side_effect=[[], review_comment_payload, []],
        ), patch.object(
            watch,
            "get_unresolved_review_comment_ids",
            return_value={"ids": {"42"}, "truncated": False},
        ):
            _, blocking_items = watch.fetch_new_review_items(
                pr,
                state,
                fresh_state=True,
                authenticated_login="octocat",
            )

        self.assertEqual(len(blocking_items), 1)
        self.assertEqual(blocking_items[0]["id"], "42")

    def test_fetch_new_review_items_blocks_unresolved_comment_on_old_commit(self):
        """Unresolved threads block regardless of which commit they were posted on."""
        pr = {
            "repo": "ADUX-sandbox/Compose-Pi",
            "number": 716,
            "head_sha": "abc123",
        }
        state = {
            "seen_issue_comment_ids": [],
            "seen_review_comment_ids": [],
            "seen_review_ids": [],
            "last_review_poll_at": None,
        }

        review_comment_payload = [
            {
                "id": 99,
                "user": {"login": "cursor[bot]"},
                "author_association": "NONE",
                "created_at": "2026-01-01T00:00:00Z",
                "body": "FYI",
                "path": "foo.kt",
                "line": 1,
                "commit_id": "different-sha",
                "html_url": "https://example.invalid/comment",
            }
        ]

        with patch.object(
            watch,
            "gh_api_list_paginated",
            side_effect=[[], review_comment_payload, []],
        ), patch.object(
            watch,
            "get_unresolved_review_comment_ids",
            return_value={"ids": {"99"}, "truncated": False},
        ) as unresolved_lookup:
            _, blocking_items = watch.fetch_new_review_items(
                pr,
                state,
                fresh_state=True,
                authenticated_login="octocat",
            )

        unresolved_lookup.assert_called_once()
        self.assertEqual(len(blocking_items), 1)
        self.assertEqual(blocking_items[0]["id"], "99")

    def test_fetch_new_review_items_resurfaces_edited_issue_comment(self):
        pr = {
            "repo": "ADUX-sandbox/Compose-Pi",
            "number": 716,
            "head_sha": "abc123",
        }
        state = {
            "seen_issue_comment_ids": ["1"],
            "seen_review_comment_ids": [],
            "seen_review_ids": [],
            "seen_issue_comment_updated_at": {"1": "2026-01-01T00:00:00Z"},
            "seen_review_comment_updated_at": {},
            "seen_review_updated_at": {},
            "last_review_poll_at": None,
        }

        issue_payload = [
            {
                "id": 1,
                "user": {"login": "maintainer"},
                "author_association": "MEMBER",
                "created_at": "2026-01-01T00:00:00Z",
                "updated_at": "2026-01-01T01:00:00Z",
                "body": "Updated guidance",
                "html_url": "https://example.invalid/issue-comment",
            }
        ]

        with patch.object(
            watch,
            "gh_api_list_paginated",
            side_effect=[issue_payload, [], []],
        ):
            new_items, _ = watch.fetch_new_review_items(
                pr,
                state,
                fresh_state=False,
                authenticated_login="octocat",
            )

        self.assertEqual(len(new_items), 1)
        self.assertEqual(new_items[0]["id"], "1")

    def test_fetch_new_review_items_ignores_self_authored_comments(self):
        pr = {
            "repo": "ADUX-sandbox/Compose-Pi",
            "number": 716,
            "head_sha": "abc123",
        }
        state = {
            "seen_issue_comment_ids": [],
            "seen_review_comment_ids": [],
            "seen_review_ids": [],
            "seen_issue_comment_updated_at": {},
            "seen_review_comment_updated_at": {},
            "seen_review_updated_at": {},
            "last_review_poll_at": None,
        }

        issue_payload = [
            {
                "id": 2,
                "user": {"login": "octocat"},
                "author_association": "MEMBER",
                "created_at": "2026-01-01T00:00:00Z",
                "updated_at": "2026-01-01T00:00:00Z",
                "body": "my own note",
                "html_url": "https://example.invalid/issue-comment",
            }
        ]

        with patch.object(
            watch,
            "gh_api_list_paginated",
            side_effect=[issue_payload, [], []],
        ):
            new_items, _ = watch.fetch_new_review_items(
                pr,
                state,
                fresh_state=True,
                authenticated_login="octocat",
            )

        self.assertEqual(new_items, [])

    def test_fetch_new_review_items_does_not_block_on_seen_issue_comment_without_edits(self):
        pr = {
            "repo": "ADUX-sandbox/Compose-Pi",
            "number": 716,
            "head_sha": "abc123",
        }
        state = {
            "seen_issue_comment_ids": ["5"],
            "seen_review_comment_ids": [],
            "seen_review_ids": [],
            "seen_issue_comment_updated_at": {"5": "2026-01-01T00:00:00Z"},
            "seen_review_comment_updated_at": {},
            "seen_review_updated_at": {},
            "last_review_poll_at": None,
        }

        issue_payload = [
            {
                "id": 5,
                "user": {"login": "maintainer"},
                "author_association": "MEMBER",
                "created_at": "2026-01-01T00:00:00Z",
                "updated_at": "2026-01-01T00:00:00Z",
                "body": "Please rename this",
                "html_url": "https://example.invalid/issue-comment",
            }
        ]

        with patch.object(
            watch,
            "gh_api_list_paginated",
            side_effect=[issue_payload, [], []],
        ):
            new_items, blocking_items = watch.fetch_new_review_items(
                pr,
                state,
                fresh_state=False,
                authenticated_login="octocat",
            )

        self.assertEqual(new_items, [])
        self.assertEqual(blocking_items, [])

    def test_fetch_new_review_items_resurfaces_edited_old_issue_comment(self):
        pr = {
            "repo": "ADUX-sandbox/Compose-Pi",
            "number": 716,
            "head_sha": "abc123",
        }
        state = {
            "seen_issue_comment_ids": ["5"],
            "seen_review_comment_ids": [],
            "seen_review_ids": [],
            "seen_issue_comment_updated_at": {"5": "2026-01-01T00:00:00Z"},
            "seen_review_comment_updated_at": {},
            "seen_review_updated_at": {},
            "last_review_poll_at": None,
        }

        issue_payload = [
            {
                "id": 5,
                "user": {"login": "maintainer"},
                "author_association": "MEMBER",
                "created_at": "2026-01-01T00:00:00Z",
                "updated_at": "2026-01-02T00:00:00Z",
                "body": "Updated after long delay",
                "html_url": "https://example.invalid/issue-comment",
            }
        ]

        with patch.object(
            watch,
            "gh_api_list_paginated",
            side_effect=[issue_payload, [], []],
        ):
            new_items, blocking_items = watch.fetch_new_review_items(
                pr,
                state,
                fresh_state=False,
                authenticated_login="octocat",
            )

        self.assertEqual(len(new_items), 1)
        self.assertEqual(blocking_items, [])

    def test_fetch_new_review_items_ignores_approved_reviews(self):
        pr = {
            "repo": "ADUX-sandbox/Compose-Pi",
            "number": 716,
            "head_sha": "abc123",
        }
        state = {
            "seen_issue_comment_ids": [],
            "seen_review_comment_ids": [],
            "seen_review_ids": [],
            "last_review_poll_at": None,
        }

        review_payload = [
            {
                "id": 77,
                "user": {"login": "maintainer"},
                "author_association": "MEMBER",
                "state": "APPROVED",
                "submitted_at": "2026-01-01T00:00:00Z",
                "body": "Looks good",
                "html_url": "https://example.invalid/review",
            }
        ]

        with patch.object(
            watch,
            "gh_api_list_paginated",
            side_effect=[[], [], review_payload],
        ):
            new_items, _ = watch.fetch_new_review_items(
                pr,
                state,
                fresh_state=True,
                authenticated_login="octocat",
            )

        self.assertEqual(new_items, [])

    def test_normalize_reviews_prefers_updated_at_over_submitted_at(self):
        items = [
            {
                "id": 1,
                "user": {"login": "maintainer"},
                "author_association": "MEMBER",
                "state": "COMMENTED",
                "submitted_at": "2026-01-01T00:00:00Z",
                "updated_at": "2026-01-01T01:00:00Z",
                "created_at": "2026-01-01T00:00:00Z",
                "body": "note",
                "html_url": "https://example.invalid/review",
            }
        ]

        normalized = watch.normalize_reviews(items)

        self.assertEqual(normalized[0]["updated_at"], "2026-01-01T01:00:00Z")

    def test_fetch_new_review_items_fallback_heuristic_when_unresolved_lookup_errors(self):
        pr = {
            "repo": "ADUX-sandbox/Compose-Pi",
            "number": 716,
            "head_sha": "abc123",
        }
        state = {
            "seen_issue_comment_ids": [],
            "seen_review_comment_ids": [],
            "seen_review_ids": [],
            "last_review_poll_at": None,
        }

        review_comment_payload = [
            {
                "id": 42,
                "user": {"login": "cursor[bot]"},
                "author_association": "NONE",
                "created_at": "2025-01-01T00:00:00Z",
                "body": "Please fix this.",
                "path": "foo.kt",
                "line": 1,
                "commit_id": "abc123",
                "html_url": "https://example.invalid/comment",
            }
        ]

        with patch.object(
            watch,
            "gh_api_list_paginated",
            side_effect=[[], review_comment_payload, []],
        ), patch.object(
            watch,
            "get_unresolved_review_comment_ids",
            side_effect=watch.GhCommandError("boom"),
        ):
            _, blocking_items = watch.fetch_new_review_items(
                pr,
                state,
                fresh_state=True,
                authenticated_login="octocat",
            )

        self.assertEqual(blocking_items, [])

    def test_hung_checks_from_checks_flags_never_started_pending_checks(self):
        checks = [
            {
                "name": "CI",
                "bucket": "pending",
                "state": "PENDING",
                "startedAt": "",
                "workflow": "CI",
                "link": "https://example.invalid/check",
            }
        ]
        pending_first_seen = {"ci|CI|https://example.invalid/check": 100}

        with patch.object(watch.time, "time", return_value=watch.HUNG_CHECK_THRESHOLDS_SECONDS["default"] + 101):
            hung = watch.hung_checks_from_checks(checks, pending_first_seen)

        self.assertEqual(len(hung), 1)
        self.assertEqual(hung[0]["name"], "CI")

    def test_recommend_actions_waits_for_missing_bugbot_while_checks_pending(self):
        actions = watch.recommend_actions(
            pr=self._base_pr(),
            checks_summary={
                "all_terminal": False,
                "failed_count": 0,
                "pending_count": 1,
                "passed_count": 0,
            },
            failed_runs=[],
            new_review_items=[],
            hung_checks=[],
            retries_used=0,
            max_retries=3,
            checks_terminal_elapsed=None,
            blocking_review_items=[],
            bugbot_gate={
                "required": True,
                "status": "missing",
                "conclusion": "",
                "is_success": False,
            },
        )

        self.assertIn("wait_bugbot", actions)
        self.assertNotIn("stop_bugbot_not_green", actions)

    def test_recommend_actions_waits_for_missing_bugbot_during_grace(self):
        actions = watch.recommend_actions(
            pr=self._base_pr(),
            checks_summary={
                "all_terminal": True,
                "failed_count": 0,
                "pending_count": 0,
                "passed_count": 2,
            },
            failed_runs=[],
            new_review_items=[],
            hung_checks=[],
            retries_used=0,
            max_retries=3,
            checks_terminal_elapsed=10,
            blocking_review_items=[],
            bugbot_gate={
                "required": True,
                "status": "missing",
                "conclusion": "",
                "is_success": False,
            },
        )

        self.assertIn("wait_bugbot", actions)
        self.assertNotIn("stop_bugbot_not_green", actions)

    def test_reset_state_for_new_head_sha_clears_pending_map(self):
        state = {
            "last_seen_head_sha": "oldsha",
            "pending_checks_first_seen_at": {"ci|CI|url": 100},
        }

        watch.reset_state_for_new_head_sha(state, "newsha")

        self.assertEqual(state["pending_checks_first_seen_at"], {})

    def test_load_state_resets_seen_tracking_when_stale(self):
        stale_state = {
            "seen_issue_comment_ids": ["1"],
            "seen_review_comment_ids": ["2"],
            "seen_review_ids": ["3"],
            "seen_issue_comment_updated_at": {"1": "2026-01-01T00:00:00Z"},
            "seen_review_comment_updated_at": {"2": "2026-01-01T00:00:00Z"},
            "seen_review_updated_at": {"3": "2026-01-01T00:00:00Z"},
            "last_review_poll_at": "2026-01-01T00:00:00Z",
            "pending_checks_first_seen_at": {"ci|CI|url": 1},
            "checks_went_terminal_at": 100,
            "checks_terminal_sha": "abc",
            "last_snapshot_at": 0,
        }

        with tempfile.TemporaryDirectory() as tmp_dir:
            path = watch.Path(tmp_dir) / "state.json"
            path.write_text(json.dumps(stale_state))
            loaded, fresh = watch.load_state(path)

        self.assertTrue(fresh)
        self.assertEqual(loaded["seen_issue_comment_ids"], [])
        self.assertEqual(loaded["seen_review_comment_ids"], [])
        self.assertEqual(loaded["seen_review_ids"], [])
        self.assertEqual(loaded["seen_issue_comment_updated_at"], {})
        self.assertEqual(loaded["seen_review_comment_updated_at"], {})
        self.assertEqual(loaded["seen_review_updated_at"], {})
        self.assertEqual(loaded["pending_checks_first_seen_at"], {})
        self.assertIsNone(loaded["checks_went_terminal_at"])
        self.assertIsNone(loaded["checks_terminal_sha"])

    def test_should_stop_watching_waits_when_conflict_and_bugbot_running(self):
        self.assertFalse(watch.should_stop_watching(["diagnose_merge_conflict", "wait_bugbot"]))
        self.assertTrue(watch.should_stop_watching(["diagnose_merge_conflict"]))

    def test_is_ci_green_false_when_blocking_review_items_present(self):
        snapshot = {
            "pr": {"review_decision": "APPROVED"},
            "checks": {
                "all_terminal": True,
                "failed_count": 0,
                "pending_count": 0,
            },
            "bugbot_gate": {"required": True, "is_success": True},
            "blocking_review_items": [{"id": "1"}],
            "checks_terminal_elapsed_seconds": 120,
        }

        self.assertFalse(watch.is_ci_green(snapshot))

    def test_is_ci_green_allows_non_required_bugbot(self):
        snapshot = {
            "pr": {"review_decision": "APPROVED"},
            "checks": {
                "all_terminal": True,
                "failed_count": 0,
                "pending_count": 0,
            },
            "bugbot_gate": {"required": False, "is_success": False},
            "blocking_review_items": [],
            "checks_terminal_elapsed_seconds": 120,
        }

        self.assertTrue(watch.is_ci_green(snapshot))

    def test_run_watch_backs_off_on_unchanged_green_state(self):
        sleeps = []
        events = []
        snapshot = {
            "pr": {
                "closed": False,
                "merged": False,
                "head_sha": "abc123",
                "state": "OPEN",
                "mergeable": "MERGEABLE",
                "merge_state_status": "CLEAN",
                "review_decision": "APPROVED",
            },
            "checks": {
                "all_terminal": True,
                "failed_count": 0,
                "pending_count": 0,
                "passed_count": 3,
            },
            "bugbot_gate": {
                "required": True,
                "is_success": True,
                "status": "completed",
                "conclusion": "success",
            },
            "new_review_items": [],
            "blocking_review_items": [],
            "actions": ["idle"],
            "checks_terminal_elapsed_seconds": 120,
        }

        with patch.object(watch, "collect_snapshot", return_value=(snapshot, watch.Path("/tmp/state.json"))), \
             patch.object(watch, "print_event", side_effect=lambda event, payload: events.append((event, payload))):

            class StopLoop(Exception):
                pass

            def fake_sleep(seconds):
                sleeps.append(seconds)
                if len(sleeps) >= 2:
                    raise StopLoop()

            with patch.object(watch.time, "sleep", side_effect=fake_sleep):
                with self.assertRaises(StopLoop):
                    watch.run_watch(SimpleNamespace(poll_seconds=30, max_session_minutes=10))

        self.assertEqual(sleeps, [30, 60])
        snapshot_events = [payload for event, payload in events if event == "snapshot"]
        self.assertEqual([item["next_poll_seconds"] for item in snapshot_events[:2]], [30, 60])

    def test_retry_failed_now_skips_non_retryable_failures(self):
        snapshot = {
            "pr": {
                "closed": False,
                "merged": False,
                "repo": "ADUX-sandbox/Compose-Pi",
                "head_sha": "abc123",
            },
            "checks": {
                "failed_count": 1,
                "all_terminal": True,
                "pending_count": 0,
                "passed_count": 0,
            },
            "failed_runs": [
                {
                    "run_id": 999,
                    "workflow_name": "CI",
                    "conclusion": "failure",
                    "status": "completed",
                    "retry_eligible": False,
                }
            ],
            "retry_state": {
                "current_sha_retries_used": 0,
                "max_flaky_retries": 3,
            },
        }

        with tempfile.TemporaryDirectory() as tmp_dir:
            state_file = os.path.join(tmp_dir, "state.json")
            with patch.object(watch, "collect_snapshot", return_value=(snapshot, watch.Path(state_file))):
                result = watch.retry_failed_now(SimpleNamespace())

        self.assertFalse(result["rerun_attempted"])
        self.assertEqual(result["reason"], "contains_non_retryable_failed_runs")


class NeedsAgentAttentionTests(unittest.TestCase):
    def test_idle_does_not_need_attention(self):
        self.assertFalse(watch.needs_agent_attention(["idle"]))

    def test_wait_bugbot_does_not_need_attention(self):
        self.assertFalse(watch.needs_agent_attention(["wait_bugbot"]))

    def test_wait_codex_does_not_need_attention(self):
        self.assertFalse(watch.needs_agent_attention(["wait_codex"]))

    def test_combined_passive_waits_do_not_need_attention(self):
        self.assertFalse(watch.needs_agent_attention(["idle", "wait_bugbot", "wait_codex"]))

    def test_stop_ready_to_merge_needs_attention(self):
        self.assertTrue(watch.needs_agent_attention(["stop_ready_to_merge"]))

    def test_diagnose_ci_failure_needs_attention(self):
        self.assertTrue(watch.needs_agent_attention(["diagnose_ci_failure"]))

    def test_retry_failed_checks_needs_attention(self):
        self.assertTrue(watch.needs_agent_attention(["retry_failed_checks"]))

    def test_process_review_comment_needs_attention(self):
        self.assertTrue(watch.needs_agent_attention(["process_review_comment"]))

    def test_stop_pr_closed_needs_attention(self):
        self.assertTrue(watch.needs_agent_attention(["stop_pr_closed"]))

    def test_mixed_passive_and_active_needs_attention(self):
        self.assertTrue(watch.needs_agent_attention(["wait_bugbot", "diagnose_ci_failure"]))

    def test_empty_actions_needs_attention(self):
        self.assertTrue(watch.needs_agent_attention([]))

    def test_none_actions_needs_attention(self):
        self.assertTrue(watch.needs_agent_attention(None))


class RunOnceTests(unittest.TestCase):
    def _idle_snapshot(self):
        return {
            "pr": {"closed": False, "merged": False},
            "checks": {"all_terminal": False, "failed_count": 0, "pending_count": 1, "passed_count": 0},
            "actions": ["idle"],
        }

    def _actionable_snapshot(self):
        return {
            "pr": {"closed": False, "merged": False},
            "checks": {"all_terminal": True, "failed_count": 1, "pending_count": 0, "passed_count": 1},
            "actions": ["diagnose_ci_failure", "stop_non_retryable_failure"],
        }

    def test_returns_immediately_when_actionable(self):
        """If the first snapshot already needs attention, return without sleeping."""
        actionable = self._actionable_snapshot()
        sleeps = []

        with patch.object(watch, "collect_snapshot", return_value=(actionable, watch.Path("/tmp/s.json"))), \
             patch.object(watch.time, "sleep", side_effect=sleeps.append):
            result = watch.run_once(SimpleNamespace(
                pr="auto", repo=None, state_file=None, poll_seconds=30, max_flaky_retries=3, max_session_minutes=90,
            ))

        self.assertEqual(result["actions"], ["diagnose_ci_failure", "stop_non_retryable_failure"])
        self.assertEqual(sleeps, [])

    def test_polls_until_actionable(self):
        """Should keep polling through idle snapshots and return on the first actionable one."""
        idle = self._idle_snapshot()
        actionable = self._actionable_snapshot()
        call_count = [0]
        sleeps = []

        def fake_collect(args):
            call_count[0] += 1
            if call_count[0] <= 3:
                return idle, watch.Path("/tmp/s.json")
            return actionable, watch.Path("/tmp/s.json")

        with patch.object(watch, "collect_snapshot", side_effect=fake_collect), \
             patch.object(watch.time, "sleep", side_effect=sleeps.append):
            result = watch.run_once(SimpleNamespace(
                pr="auto", repo=None, state_file=None, poll_seconds=30, max_flaky_retries=3, max_session_minutes=90,
            ))

        self.assertEqual(call_count[0], 4)
        self.assertEqual(len(sleeps), 3)
        self.assertIn("diagnose_ci_failure", result["actions"])

    def test_waits_through_bugbot_and_codex(self):
        """wait_bugbot and wait_codex should not cause early return."""
        waiting = {
            "pr": {"closed": False, "merged": False},
            "checks": {"all_terminal": True, "failed_count": 0, "pending_count": 0, "passed_count": 2},
            "actions": ["wait_bugbot", "wait_codex"],
        }
        ready = {
            "pr": {"closed": False, "merged": False},
            "checks": {"all_terminal": True, "failed_count": 0, "pending_count": 0, "passed_count": 2},
            "actions": ["stop_ready_to_merge"],
        }
        call_count = [0]

        def fake_collect(args):
            call_count[0] += 1
            if call_count[0] <= 2:
                return waiting, watch.Path("/tmp/s.json")
            return ready, watch.Path("/tmp/s.json")

        with patch.object(watch, "collect_snapshot", side_effect=fake_collect), \
             patch.object(watch.time, "sleep", lambda s: None):
            result = watch.run_once(SimpleNamespace(
                pr="auto", repo=None, state_file=None, poll_seconds=30, max_flaky_retries=3, max_session_minutes=90,
            ))

        self.assertEqual(call_count[0], 3)
        self.assertIn("stop_ready_to_merge", result["actions"])

    def test_session_timeout(self):
        """Should return stop_session_timeout when max session time elapses."""
        idle = self._idle_snapshot()
        fake_time = [0.0]

        def advancing_sleep(seconds):
            fake_time[0] += seconds

        def fake_time_fn():
            return fake_time[0]

        with patch.object(watch, "collect_snapshot", return_value=(idle, watch.Path("/tmp/s.json"))), \
             patch.object(watch.time, "sleep", side_effect=advancing_sleep), \
             patch.object(watch.time, "time", side_effect=fake_time_fn):
            result = watch.run_once(SimpleNamespace(
                pr="auto", repo=None, state_file=None, poll_seconds=30, max_flaky_retries=3, max_session_minutes=1,
            ))

        self.assertIn("stop_session_timeout", result["actions"])

    def test_retries_on_gh_error(self):
        """GhCommandError during polling should be retried, not crash."""
        actionable = self._actionable_snapshot()
        call_count = [0]
        sleeps = []

        def flaky_collect(args):
            call_count[0] += 1
            if call_count[0] == 1:
                raise watch.GhCommandError("rate limited")
            return actionable, watch.Path("/tmp/s.json")

        with patch.object(watch, "collect_snapshot", side_effect=flaky_collect), \
             patch.object(watch.time, "sleep", side_effect=sleeps.append):
            result = watch.run_once(SimpleNamespace(
                pr="auto", repo=None, state_file=None, poll_seconds=30, max_flaky_retries=3, max_session_minutes=90,
            ))

        self.assertEqual(call_count[0], 2)
        self.assertEqual(len(sleeps), 1)
        self.assertIn("diagnose_ci_failure", result["actions"])


class CodexGateTests(unittest.TestCase):
    def test_codex_reviewing_blocks_merge_readiness(self):
        pr = {
            "closed": False,
            "merged": False,
            "mergeable": "MERGEABLE",
            "merge_state_status": "CLEAN",
            "review_decision": "APPROVED",
        }
        checks = {
            "all_terminal": True,
            "failed_count": 0,
            "pending_count": 0,
            "passed_count": 2,
            "skipping_count": 0,
        }
        ready = watch.is_pr_ready_to_merge(
            pr, checks, new_review_items=[], checks_terminal_elapsed=120,
            blocking_review_items=[],
            bugbot_gate={"required": True, "is_success": True},
            codex_gate={"reviewing": True, "status": "in_progress"},
        )
        self.assertFalse(ready)

    def test_codex_idle_allows_merge_readiness(self):
        pr = {
            "closed": False,
            "merged": False,
            "mergeable": "MERGEABLE",
            "merge_state_status": "CLEAN",
            "review_decision": "APPROVED",
        }
        checks = {
            "all_terminal": True,
            "failed_count": 0,
            "pending_count": 0,
            "passed_count": 2,
            "skipping_count": 0,
        }
        ready = watch.is_pr_ready_to_merge(
            pr, checks, new_review_items=[], checks_terminal_elapsed=120,
            blocking_review_items=[],
            bugbot_gate={"required": True, "is_success": True},
            codex_gate={"reviewing": False, "status": "idle"},
        )
        self.assertTrue(ready)

    def test_recommend_actions_emits_wait_codex(self):
        pr = {
            "closed": False,
            "merged": False,
            "mergeable": "MERGEABLE",
            "merge_state_status": "CLEAN",
            "review_decision": "APPROVED",
        }
        actions = watch.recommend_actions(
            pr=pr,
            checks_summary={"all_terminal": True, "failed_count": 0, "pending_count": 0, "passed_count": 2, "skipping_count": 0},
            failed_runs=[],
            new_review_items=[],
            hung_checks=[],
            retries_used=0,
            max_retries=3,
            checks_terminal_elapsed=120,
            blocking_review_items=[],
            bugbot_gate={"required": True, "status": "completed", "conclusion": "success", "is_success": True},
            codex_gate={"reviewing": True, "status": "in_progress"},
        )
        self.assertIn("wait_codex", actions)
        self.assertNotIn("stop_ready_to_merge", actions)


class SkippingChecksTests(unittest.TestCase):
    def test_skipping_count_blocks_merge_readiness(self):
        pr = {
            "closed": False,
            "merged": False,
            "mergeable": "MERGEABLE",
            "merge_state_status": "CLEAN",
            "review_decision": "APPROVED",
        }
        checks = {
            "all_terminal": True,
            "failed_count": 0,
            "pending_count": 0,
            "passed_count": 2,
            "skipping_count": 1,
        }
        ready = watch.is_pr_ready_to_merge(
            pr, checks, new_review_items=[], checks_terminal_elapsed=120,
        )
        self.assertFalse(ready)

    def test_diagnose_skipping_checks_emitted(self):
        pr = {
            "closed": False,
            "merged": False,
            "mergeable": "MERGEABLE",
            "merge_state_status": "CLEAN",
            "review_decision": "APPROVED",
        }
        actions = watch.recommend_actions(
            pr=pr,
            checks_summary={"all_terminal": True, "failed_count": 0, "pending_count": 0, "passed_count": 2, "skipping_count": 1},
            failed_runs=[],
            new_review_items=[],
            hung_checks=[],
            retries_used=0,
            max_retries=3,
            checks_terminal_elapsed=120,
            blocking_review_items=[],
            bugbot_gate={"required": True, "status": "completed", "conclusion": "success", "is_success": True},
        )
        self.assertIn("diagnose_skipping_checks", actions)

    def test_summarize_checks_counts_skipping(self):
        checks = [
            {"bucket": "pass", "state": "SUCCESS"},
            {"bucket": "skipping", "state": "SKIPPING"},
            {"bucket": "neutral", "state": "NEUTRAL"},
        ]
        summary = watch.summarize_checks(checks)
        self.assertEqual(summary["passed_count"], 1)
        self.assertEqual(summary["skipping_count"], 2)
        self.assertTrue(summary["all_terminal"])

    def test_should_stop_watching_on_skipping_checks(self):
        self.assertTrue(watch.should_stop_watching(["diagnose_skipping_checks"]))


if __name__ == "__main__":
    unittest.main()
