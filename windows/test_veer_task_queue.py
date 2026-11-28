"""Tests for VEER's durable bounded task queue."""
from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from veer_task_queue import (
    DurableTaskQueue, TaskConflict, TaskRejected, TaskNotFound,
)


class DurableTaskQueueTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.db = Path(self.tmp.name) / "tasks.sqlite3"
        self.now = [1_000.0]
        self.queue = DurableTaskQueue(self.db, max_active=2, task_ttl_seconds=60, clock=lambda: self.now[0])

    def tearDown(self):
        self.tmp.cleanup()

    def key(self, value):
        return f"idempotency-key-{value:04d}"

    def test_read_only_task_can_be_claimed_and_completed(self):
        task = self.queue.enqueue("list_workspace", {"path": "."}, self.key(1))
        self.assertEqual(task.state, "queued")
        claimed = self.queue.claim_next()
        self.assertEqual(claimed.task_id, task.task_id)
        self.assertEqual(claimed.state, "running")
        done = self.queue.finish(task.task_id, result={"items": []})
        self.assertEqual(done.state, "completed")
        self.assertEqual(done.result, {"items": []})

    def test_side_effect_waits_for_exact_payload_approval(self):
        task = self.queue.enqueue("create_text_file", {"path": "a.txt", "text": "hello"}, self.key(2))
        self.assertEqual(task.state, "pending_approval")
        self.assertIsNone(self.queue.claim_next())
        with self.assertRaises(TaskRejected):
            self.queue.approve_task(task.task_id, "0" * 64)
        self.assertEqual(self.queue.get(task.task_id).state, "pending_approval")
        approved = self.queue.approve_task(task.task_id, task.payload_sha256)
        self.assertEqual(approved.state, "queued")
        self.assertEqual(self.queue.claim_next().task_id, task.task_id)

    def test_idempotency_returns_same_task_and_rejects_payload_change(self):
        first = self.queue.enqueue("list_workspace", {"path": "."}, self.key(3))
        second = self.queue.enqueue("list_workspace", {"path": "."}, self.key(3))
        self.assertEqual(first.task_id, second.task_id)
        with self.assertRaises(TaskConflict):
            self.queue.enqueue("list_workspace", {"path": "private"}, self.key(3))

    def test_queue_capacity_is_bounded(self):
        self.queue.enqueue("list_workspace", {}, self.key(4))
        self.queue.enqueue("health", {}, self.key(5))
        with self.assertRaises(TaskRejected):
            self.queue.enqueue("health", {}, self.key(6))

    def test_cancel_pending_task(self):
        task = self.queue.enqueue("list_workspace", {}, self.key(7))
        cancelled = self.queue.cancel(task.task_id)
        self.assertEqual(cancelled.state, "cancelled")
        with self.assertRaises(TaskConflict):
            self.queue.cancel(task.task_id)

    def test_running_task_requires_worker_cooperative_cancellation(self):
        task = self.queue.enqueue("health", {}, self.key(8))
        self.queue.claim_next()
        with self.assertRaises(TaskConflict):
            self.queue.cancel(task.task_id)

    def test_expired_task_cannot_be_claimed_or_approved(self):
        task = self.queue.enqueue("open_url", {"url": "https://example.com"}, self.key(9))
        self.now[0] += 61
        self.assertEqual(self.queue.get(task.task_id).state, "expired")
        with self.assertRaises(TaskConflict):
            self.queue.approve_task(task.task_id, task.payload_sha256)

    def test_queue_survives_reopen(self):
        task = self.queue.enqueue("list_workspace", {"path": "."}, self.key(10))
        reopened = DurableTaskQueue(self.db, max_active=2, task_ttl_seconds=60, clock=lambda: self.now[0])
        self.assertEqual(reopened.get(task.task_id).state, "queued")

    def test_unsupported_operation_and_oversized_payload_rejected(self):
        with self.assertRaises(TaskRejected):
            self.queue.enqueue("run_shell", {"command": "whoami"}, self.key(11))
        with self.assertRaises(TaskRejected):
            self.queue.enqueue("create_text_file", {"text": "x" * 20_000}, self.key(12))

    def test_finish_requires_exactly_one_outcome(self):
        task = self.queue.enqueue("health", {}, self.key(13))
        self.queue.claim_next()
        with self.assertRaises(TaskRejected):
            self.queue.finish(task.task_id)
        with self.assertRaises(TaskRejected):
            self.queue.finish(task.task_id, result={}, error="bad")

    def test_missing_task(self):
        with self.assertRaises(TaskNotFound):
            self.queue.get("unknown-task")


if __name__ == "__main__":
    unittest.main()
