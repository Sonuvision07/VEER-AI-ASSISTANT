import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from veer_cloud_worker import CloudTaskError, CloudTaskRequest
from veer_cloud_store import SQLiteCloudTaskStore


def make_task(task_id="task_1234567890abcd", operation="create_text_file", key="idem-key-1234567890", content="hello"):
    payload = {"path": "notes/a.txt", "content": content} if operation == "create_text_file" else {}
    encoded = json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()
    return CloudTaskRequest(task_id, operation, payload, key, hashlib.sha256(encoded).hexdigest())


class CloudStoreTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / "cloud.sqlite3"
        self.store = SQLiteCloudTaskStore(self.path, ttl_seconds=60, clock=lambda: 1000)

    def tearDown(self):
        self.temp.cleanup()

    def test_persists_across_store_restart_and_idempotent_retry(self):
        task = make_task()
        first = self.store.save_pending(task, "tenant-a")
        second = SQLiteCloudTaskStore(self.path, clock=lambda: 1001).get_by_idempotency_key(task.idempotency_key)
        self.assertEqual(first["task_id"], second["task_id"])
        self.assertEqual("pending_approval", second["state"])
        self.assertEqual(1, len(list(self.path.parent.glob("cloud.sqlite3"))))

    def test_idempotency_key_cannot_cross_owner_or_payload(self):
        task = make_task()
        self.store.save_pending(task, "tenant-a")
        with self.assertRaises(CloudTaskError):
            self.store.save_pending(task, "tenant-b")
        altered = make_task(task_id="task_1234567890abcx", key=task.idempotency_key, content="tampered")
        with self.assertRaises(CloudTaskError):
            self.store.save_pending(altered, "tenant-a")

    def test_exact_approval_is_one_shot_and_claimable(self):
        task = make_task()
        self.store.save_pending(task, "tenant-a")
        with self.assertRaises(CloudTaskError):
            self.store.approve(task.task_id, "tenant-a", "0" * 64)
        approved = self.store.approve(task.task_id, "tenant-a", task.payload_sha256)
        self.assertEqual("queued", approved["state"])
        self.assertTrue(approved["approval_consumed"])
        with self.assertRaises(CloudTaskError):
            self.store.approve(task.task_id, "tenant-a", task.payload_sha256)
        claimed = self.store.claim_next()
        self.assertEqual("running", claimed["state"])
        done = self.store.finish(task.task_id, result={"created": "notes/a.txt"})
        self.assertEqual("completed", done["state"])

    def test_read_only_task_is_queued_without_approval(self):
        task = make_task(operation="health")
        saved = self.store.save_pending(task, "tenant-a")
        self.assertEqual("queued", saved["state"])
        self.assertEqual("running", self.store.claim_next()["state"])

    def test_owner_scoped_reads_and_cancel(self):
        task = make_task()
        self.store.save_pending(task, "tenant-a")
        with self.assertRaises(CloudTaskError):
            self.store.get(task.task_id, "tenant-b")
        cancelled = self.store.cancel(task.task_id, "tenant-a")
        self.assertEqual("cancelled", cancelled["state"])
        with self.assertRaises(CloudTaskError):
            self.store.cancel(task.task_id, "tenant-a")

    def test_expired_task_is_not_claimed(self):
        task = make_task()
        self.store.save_pending(task, "tenant-a")
        expired_store = SQLiteCloudTaskStore(self.path, clock=lambda: 1061)
        self.assertEqual("expired", expired_store.get(task.task_id, "tenant-a")["state"])
        self.assertIsNone(expired_store.claim_next())


if __name__ == "__main__":
    unittest.main()
