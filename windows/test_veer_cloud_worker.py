import hashlib
import json
import unittest

from veer_cloud_worker import (
    CloudPrincipal,
    CloudTaskError,
    CloudTaskRequest,
    accept_cloud_task,
    verify_one_shot_approval,
)


def request(operation="create_text_file", payload=None):
    payload = payload or {"path": "notes/hello.txt", "content": "hello"}
    encoded = json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode()
    return {
        "task_id": "task_1234567890abcd",
        "operation": operation,
        "payload": payload,
        "idempotency_key": "idem-key-1234567890",
        "payload_sha256": hashlib.sha256(encoded).hexdigest(),
    }


class Store:
    def __init__(self):
        self.items = {}

    def get_by_idempotency_key(self, key):
        return self.items.get(key)

    def save_pending(self, task, owner):
        item = {"task_id": task.task_id, "owner": owner, "payload_sha256": task.payload_sha256}
        self.items[task.idempotency_key] = item
        return item


class CloudWorkerContractTests(unittest.TestCase):
    def setUp(self):
        self.principal = CloudPrincipal("user-1", frozenset({"tasks:write"}), 5000)
        self.store = Store()

    def test_side_effect_is_accepted_pending_approval_only(self):
        result = accept_cloud_task(request(), self.principal, self.store, now=1000)
        self.assertEqual("pending_approval", result["state"])

    def test_read_only_task_is_queued(self):
        result = accept_cloud_task(request("health", {}), self.principal, self.store, now=1000)
        self.assertEqual("queued", result["state"])

    def test_expired_or_under_scoped_credentials_fail_closed(self):
        with self.assertRaises(CloudTaskError):
            accept_cloud_task(request(), CloudPrincipal("u", frozenset({"tasks:write"}), 999), self.store, now=1000)
        with self.assertRaises(CloudTaskError):
            accept_cloud_task(request(), CloudPrincipal("u", frozenset(), 5000), self.store, now=1000)

    def test_idempotency_returns_existing_task(self):
        first = accept_cloud_task(request(), self.principal, self.store, now=1000)
        second = accept_cloud_task(request(), self.principal, self.store, now=1000)
        self.assertEqual(first, second)
        self.assertEqual(1, len(self.store.items))

    def test_modified_payload_and_unsupported_operation_are_rejected(self):
        raw = request()
        raw["payload"]["content"] = "tampered"
        with self.assertRaises(CloudTaskError):
            CloudTaskRequest.parse(raw)
        with self.assertRaises(CloudTaskError):
            CloudTaskRequest.parse(request("run_shell", {}))

    def test_approval_is_exact_and_one_shot_per_payload(self):
        task = CloudTaskRequest.parse(request())
        verify_one_shot_approval(task, task.payload_sha256)
        with self.assertRaises(CloudTaskError):
            verify_one_shot_approval(task, "0" * 64)
        with self.assertRaises(CloudTaskError):
            verify_one_shot_approval(task, None)

    def test_oversized_payload_is_rejected(self):
        with self.assertRaises(CloudTaskError):
            CloudTaskRequest.parse(request(payload={"content": "x" * 20000}))


if __name__ == "__main__":
    unittest.main()
