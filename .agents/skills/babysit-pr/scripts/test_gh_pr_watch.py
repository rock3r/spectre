import json
import os
import subprocess
import sys
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch

SCRIPT_DIR = os.path.dirname(__file__)
if SCRIPT_DIR not in sys.path:
    sys.path.insert(0, SCRIPT_DIR)

import gh_pr_watch as watch


class PrChecksExitCodeTests(unittest.TestCase):
    def test_get_pr_checks_parses_semantic_nonzero_exit_codes(self):
        for exit_code, bucket in ((1, "fail"), (8, "pending")):
            with self.subTest(exit_code=exit_code):
                payload = json.dumps([{"bucket": bucket}])
                error = subprocess.CalledProcessError(
                    exit_code,
                    ["gh", "pr", "checks"],
                    output=payload,
                    stderr="",
                )
                with patch.object(watch.subprocess, "run", side_effect=error):
                    self.assertEqual(
                        watch.get_pr_checks("62", repo="rock3r/punaro"),
                        [{"bucket": bucket}],
                    )


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

    def test_is_pr_ready_to_merge_blocks_when_head_is_behind_base(self):
        pr = self._base_pr()
        pr["merge_state_status"] = "BEHIND"
        self.assertFalse(watch.is_pr_ready_to_merge(
            pr=pr,
            checks_summary={"all_terminal": True, "failed_count": 0, "pending_count": 0, "passed_count": 2},
            new_review_items=[], checks_terminal_elapsed=120, blocking_review_items=[],
        ))

    def test_is_blocking_review_item_blocks_when_thread_resolution_is_unknown(self):
        created_at = "2026-01-01T00:00:00Z"
        created_at_seconds = watch.datetime.fromisoformat("2026-01-01T00:00:00+00:00").timestamp()
        stale_now = created_at_seconds + watch.BLOCKING_REVIEW_ITEM_FRESH_SECONDS + 1
        item = {
            "kind": "review_comment",
            "commit_id": "abc123",
            "created_at": created_at,
        }

        self.assertTrue(
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
        )

        self.assertIn("diagnose_merge_conflict", actions)

    def test_recommend_actions_surfaces_behind_branch_without_conflict(self):
        pr = self._base_pr()
        pr["merge_state_status"] = "BEHIND"
        actions = watch.recommend_actions(pr=pr, checks_summary={"all_terminal": False, "failed_count": 0, "pending_count": 0, "passed_count": 0}, failed_runs=[], new_review_items=[], hung_checks=[], retries_used=0, max_retries=3)
        self.assertIn("diagnose_branch_behind", actions)
        self.assertNotIn("diagnose_merge_conflict", actions)

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

    def test_fetch_new_review_items_blocks_when_unresolved_lookup_errors(self):
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

        self.assertEqual(len(blocking_items), 1)
        self.assertEqual(blocking_items[0]["id"], "42")

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

    def test_reset_state_for_new_head_sha_clears_pending_map(self):
        state = {
            "last_seen_head_sha": "oldsha",
            "pending_checks_first_seen_at": {"ci|CI|url": 100},
        }

        watch.reset_state_for_new_head_sha(state, "newsha")

        self.assertEqual(state["pending_checks_first_seen_at"], {})

    def test_load_state_reads_non_ascii_state_files_as_utf8(self):
        """save_state pins UTF-8, so load_state must not decode with the locale default."""
        # Emoji reach the state dict through pending_check_key(), which embeds
        # the GitHub check and workflow names verbatim.
        key = "build \U0001f680|CI \u2014 main|https://example.test/1"

        state = {
            # A fresh snapshot timestamp keeps load_state from treating this as
            # stale and resetting the seen-tracking fields.
            "last_snapshot_at": watch.time.time(),
            "pending_checks_first_seen_at": {key: 1},
        }

        with tempfile.TemporaryDirectory() as tmp_dir:
            path = watch.Path(tmp_dir) / "state.json"
            # Written as raw UTF-8 rather than via save_state: json.dumps escapes
            # non-ASCII by default, so save_state cannot currently produce the
            # bytes this guards against.
            path.write_bytes(json.dumps(state, ensure_ascii=False).encode("utf-8"))
            loaded, _ = watch.load_state(path)

        self.assertEqual(loaded["pending_checks_first_seen_at"], {key: 1})

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

    def test_is_ci_green_false_when_blocking_review_items_present(self):
        snapshot = {
            "pr": {"review_decision": "APPROVED"},
            "checks": {
                "all_terminal": True,
                "failed_count": 0,
                "pending_count": 0,
            },
            "blocking_review_items": [{"id": "1"}],
            "checks_terminal_elapsed_seconds": 120,
        }

        self.assertFalse(watch.is_ci_green(snapshot))

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

    def test_wait_codex_does_not_need_attention(self):
        self.assertFalse(watch.needs_agent_attention(["wait_codex"]))

    def test_combined_passive_waits_do_not_need_attention(self):
        self.assertFalse(watch.needs_agent_attention(["idle", "wait_codex"]))

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
        self.assertTrue(watch.needs_agent_attention(["wait_codex", "diagnose_ci_failure"]))

    def test_empty_actions_needs_attention(self):
        self.assertTrue(watch.needs_agent_attention([]))

    def test_none_actions_needs_attention(self):
        self.assertTrue(watch.needs_agent_attention(None))


class GhTextTests(unittest.TestCase):
    def _run_emitting(self, payload):
        """Run gh_text against a real child process that writes `payload` as UTF-8."""
        emitter = "import sys; sys.stdout.buffer.write({}.encode('utf-8'))".format(
            ascii(payload)
        )
        real_run = subprocess.run

        def fake_run(cmd, **kwargs):
            return real_run([sys.executable, "-c", emitter], **kwargs)

        return patch.object(watch.subprocess, "run", side_effect=fake_run)

    def test_gh_text_requests_utf8_decoding(self):
        """gh output must be decoded as UTF-8, never with the locale default."""
        captured = {}

        def fake_run(cmd, **kwargs):
            captured.update(kwargs)
            return SimpleNamespace(stdout="{}", stderr="")

        with patch.object(watch.subprocess, "run", side_effect=fake_run):
            watch.gh_text(["pr", "view"])

        self.assertEqual(captured.get("encoding"), "utf-8")
        self.assertEqual(captured.get("errors"), "replace")

    def test_gh_text_decodes_non_ascii_output(self):
        """An em-dash or emoji in a review body must not blow up on a cp1252 locale."""
        body = "Codex review \u2014 nit \U0001f41b"

        with self._run_emitting(body):
            out = watch.gh_text(["pr", "view"])

        self.assertEqual(out, body)

    def test_gh_json_parses_non_ascii_output(self):
        """The full gh_json path must survive non-ASCII review comment bodies."""
        body = "Bugbot \u2014 found an issue \U0001f41b"
        payload = json.dumps({"body": body}, ensure_ascii=False)

        with self._run_emitting(payload):
            data = watch.gh_json(["pr", "view", "--json", "body"])

        self.assertEqual(data["body"], body)

    def test_gh_text_raises_when_stdout_is_missing(self):
        """A dead stdout reader thread must not leak out as an AttributeError."""
        with patch.object(
            watch.subprocess, "run", return_value=SimpleNamespace(stdout=None, stderr="")
        ):
            with self.assertRaises(watch.GhCommandError) as context:
                watch.gh_text(["pr", "view"])

        self.assertIn("No output captured", str(context.exception))

    def test_gh_json_reports_a_clear_error_when_stdout_is_missing(self):
        with patch.object(
            watch.subprocess, "run", return_value=SimpleNamespace(stdout=None, stderr="")
        ):
            with self.assertRaises(watch.GhCommandError) as context:
                watch.gh_json(["pr", "view", "--json", "number"])

        self.assertIn("No output captured", str(context.exception))


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

    def test_waits_through_codex(self):
        """wait_codex should not cause early return."""
        waiting = {
            "pr": {"closed": False, "merged": False},
            "checks": {"all_terminal": True, "failed_count": 0, "pending_count": 0, "passed_count": 2},
            "actions": ["wait_codex"],
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
            codex_gate={"reviewing": False, "status": "idle"},
        )
        self.assertTrue(ready)

    def test_codex_unknown_blocks_merge_readiness(self):
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
            codex_gate={"reviewing": True, "status": "unknown"},
        )
        self.assertFalse(ready)

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
        )
        self.assertIn("diagnose_skipping_checks", actions)

    def test_summarize_checks_counts_skipping_and_cancelled_as_failures(self):
        checks = [
            {"bucket": "pass", "state": "SUCCESS"},
            {"bucket": "cancel", "state": "CANCELLED"},
            {"bucket": "skipping", "state": "SKIPPING"},
            {"bucket": "neutral", "state": "NEUTRAL"},
        ]
        summary = watch.summarize_checks(checks)
        self.assertEqual(summary["passed_count"], 1)
        self.assertEqual(summary["failed_count"], 1)
        self.assertEqual(summary["skipping_count"], 2)
        self.assertTrue(summary["all_terminal"])

    def test_summarize_checks_ignores_a_skipped_deploy_job(self):
        checks = [
            {"name": "deploy", "bucket": "skipping", "state": "SKIPPING"},
            {"name": "other", "bucket": "skipping", "state": "SKIPPING"},
            {"name": "deploy", "bucket": "neutral", "state": "NEUTRAL"},
        ]

        summary = watch.summarize_checks(checks)

        self.assertEqual(summary["skipping_count"], 2)

    def test_should_stop_watching_on_skipping_checks(self):
        self.assertTrue(watch.should_stop_watching(["diagnose_skipping_checks"]))


class HungCheckZeroTimeTests(unittest.TestCase):
    """A queued check whose `startedAt` is a zero-time sentinel must not be
    flagged as hung. GitHub reports Go's zero time (``0001-01-01T00:00:00Z``)
    or the Unix epoch for checks that are queued but not yet started; parsing
    those yields a non-positive timestamp and
    a multi-billion-second elapsed, which previously tripped a false-positive
    ``diagnose_hung_check``."""

    def _pending_queued_check(self, started_at):
        return {
            "name": "Queued check",
            "bucket": "pending",
            "state": "QUEUED",
            "startedAt": started_at,
            "workflow": "",
            "link": "",
        }

    def test_ignores_go_zero_time_started_at(self):
        check = self._pending_queued_check("0001-01-01T00:00:00Z")
        now = 1_000_000.0
        # First seen only 10 seconds ago -> nowhere near the hung threshold.
        pending_first_seen = {watch.pending_check_key(check): now - 10}

        with patch.object(watch.time, "time", return_value=now):
            hung = watch.hung_checks_from_checks([check], pending_first_seen)

        self.assertEqual(hung, [])

    def test_ignores_unix_epoch_started_at(self):
        check = self._pending_queued_check("1970-01-01T00:00:00Z")
        now = 1_000_000.0
        pending_first_seen = {watch.pending_check_key(check): now - 10}

        with patch.object(watch.time, "time", return_value=now):
            hung = watch.hung_checks_from_checks([check], pending_first_seen)

        self.assertEqual(hung, [])

    def test_zero_time_falls_back_to_first_seen_when_genuinely_hung(self):
        """Rejecting the zero-time sentinel must fall back to first-seen
        tracking, not skip the check entirely: a genuinely old queued check is
        still flagged hung."""
        check = self._pending_queued_check("0001-01-01T00:00:00Z")
        threshold = watch.hung_threshold_for_check("Queued check")
        now = 1_000_000.0
        pending_first_seen = {watch.pending_check_key(check): now - threshold - 100}

        with patch.object(watch.time, "time", return_value=now):
            hung = watch.hung_checks_from_checks([check], pending_first_seen)

        self.assertEqual(len(hung), 1)
        self.assertEqual(hung[0]["name"], "Queued check")


class ReviewThreadGraphQLPayloadTests(unittest.TestCase):
    def test_rejects_graphql_errors_instead_of_returning_no_blockers(self):
        with patch.object(watch, "gh_json", return_value={"data": None, "errors": [{"message": "boom"}]}):
            with self.assertRaises(watch.GhCommandError):
                watch.get_unresolved_review_comment_ids("owner/repo", 1)


class GetPrIssueReactionsTests(unittest.TestCase):
    def test_paginates_reactions_via_list_helper(self):
        """Reactions must be fetched across all pages: a bot's 👀 can land on a
        later page on a busy PR, and missing it would let the watcher declare
        merge-readiness while a review is still in progress."""
        pages = [
            {"content": "+1", "user": {"login": "someone"}},
            {"content": "eyes", "user": {"login": "chatgpt-codex-connector[bot]"}},
        ]
        with patch.object(watch, "gh_api_list_paginated", return_value=pages) as paginated:
            result = watch.get_pr_issue_reactions("owner/repo", 1178)

        paginated.assert_called_once()
        endpoint = paginated.call_args[0][0]
        self.assertIn("issues/1178/reactions", endpoint)
        self.assertEqual(result, pages)

    def test_returns_none_on_error(self):
        with patch.object(
            watch, "gh_api_list_paginated", side_effect=watch.GhCommandError("boom")
        ):
            self.assertIsNone(watch.get_pr_issue_reactions("owner/repo", 1178))


class CodexGateReactionTests(unittest.TestCase):
    """`summarize_codex_gate` now operates on pre-fetched reactions."""

    def test_detects_eyes_reaction_from_codex(self):
        reactions = [{"content": "eyes", "user": {"login": "chatgpt-codex-connector[bot]"}}]
        gate = watch.summarize_codex_gate(reactions)
        self.assertTrue(gate["reviewing"])
        self.assertEqual(gate["status"], "in_progress")

    def test_idle_when_no_codex_eyes_reaction(self):
        reactions = [{"content": "+1", "user": {"login": "chatgpt-codex-connector[bot]"}}]
        gate = watch.summarize_codex_gate(reactions)
        self.assertFalse(gate["reviewing"])
        self.assertEqual(gate["status"], "idle")

    def test_unknown_when_reactions_unavailable(self):
        gate = watch.summarize_codex_gate(None)
        self.assertTrue(gate["reviewing"])
        self.assertEqual(gate["status"], "unknown")


class BugbotGateRemovalTests(unittest.TestCase):
    """Cursor Bugbot is retired; nothing about it may gate a PR any more."""

    def test_no_bugbot_symbols_remain(self):
        leftovers = sorted(name for name in dir(watch) if "bugbot" in name.lower())
        self.assertEqual([], leftovers)

    def test_bugbot_actions_are_not_recognized(self):
        self.assertNotIn("wait_bugbot", watch.PASSIVE_WAIT_ACTIONS)
        self.assertFalse(watch.should_stop_watching(["stop_bugbot_not_green"]))

    def test_recommend_actions_rejects_a_bugbot_gate_argument(self):
        with self.assertRaises(TypeError):
            watch.recommend_actions(
                {"closed": False, "merged": False, "mergeable": "MERGEABLE"},
                {"pending_count": 0, "failed_count": 0, "passed_count": 1, "all_terminal": True},
                [],
                [],
                [],
                0,
                3,
                bugbot_gate={"required": True, "is_success": False},
            )

    def test_is_ci_green_ignores_a_stale_bugbot_gate(self):
        snapshot = {
            "pr": {"review_decision": "APPROVED"},
            "checks": {"all_terminal": True, "failed_count": 0, "pending_count": 0},
            "bugbot_gate": {"required": True, "is_success": False},
            "blocking_review_items": [],
            "checks_terminal_elapsed_seconds": 120,
        }
        self.assertTrue(watch.is_ci_green(snapshot))


class CodeRabbitRemovalTests(unittest.TestCase):
    """CodeRabbit is off the repo; it must neither gate nor surface comments."""

    def test_no_coderabbit_symbols_remain(self):
        leftovers = sorted(name for name in dir(watch) if "coderabbit" in name.lower())
        self.assertEqual([], leftovers)

    def test_coderabbit_action_is_not_recognized(self):
        self.assertNotIn("wait_coderabbit", watch.PASSIVE_WAIT_ACTIONS)

    def test_coderabbit_comments_are_not_actionable(self):
        self.assertFalse(watch.is_actionable_review_bot_login("coderabbitai[bot]"))

    def test_recommend_actions_rejects_a_coderabbit_gate_argument(self):
        with self.assertRaises(TypeError):
            watch.recommend_actions(
                {"closed": False, "merged": False, "mergeable": "MERGEABLE"},
                {"pending_count": 0, "failed_count": 0, "passed_count": 1, "all_terminal": True},
                [],
                [],
                [],
                0,
                3,
                coderabbit_gate={"reviewing": True},
            )

    def test_is_ci_green_ignores_a_stale_coderabbit_gate(self):
        snapshot = {
            "pr": {"review_decision": "APPROVED"},
            "checks": {"all_terminal": True, "failed_count": 0, "pending_count": 0},
            "blocking_review_items": [],
            "checks_terminal_elapsed_seconds": 120,
        }
        self.assertTrue(watch.is_ci_green(snapshot))


if __name__ == "__main__":
    unittest.main()
