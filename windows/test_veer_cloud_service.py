import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from veer_cloud_worker import CloudPrincipal, CloudTaskError
from veer_cloud_store import SQLiteCloudTaskStore
from veer_cloud_service import CloudTaskRunner, CloudTaskService


def make_request(operation="create_text_file", payload=None, task_id="task_1234567890abcd", key="idem-key-1234567890"):
    payload = payload if payload is not None else {"path": "notes/a.txt", "content": "hello"}
    encoded = json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode()
    return {
        "task_id": task_id,
        "operation": operation,
        "payload": payload,
        "idempotency_key": key,
        "payload_sha256": hashlib.sha256(encoded).hexdigest(),
    }


class Verifier:
    def __init__(self, principal):
        self.principal = principal

    def verify(self, token):
        return self.principal if token == "test-token" else None


class Executor:
    def __init__(self):
        self.calls = []

    def execute(self, operation, payload, *, workspace_id):
        self.calls.append((operation, dict(payload), workspace_id))
        return {"ok": True}


class CloudServiceRuntimeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.store = SQLiteCloudTaskStore(Path(self.temp.name) / "tasks.sqlite3", clock=lambda: 1000)
        self.principal = CloudPrincipal(
            "tenant-a",
            frozenset({"tasks:write", "tasks:read", "tasks:approve", "tasks:cancel"}),
            5000,
        )
        self.service = CloudTaskService(self.store, Verifier(self.principal), clock=lambda: 1000)
        self.headers = {"Authorization": "Bearer test-token"}

    def tearDown(self):
        self.temp.cleanup()

    def call(self, method, path, body=None, headers=None):
        return self.service.handle(method, path, self.headers if headers is None else headers, body)

    def test_authentication_and_scope_are_enforced(self):
        status, _ = self.call("GET", "/tasks/task_1234567890abcd", headers={})
        self.assertEqual(401, status)
        limited = CloudTaskService(
            self.store,
            Verifier(CloudPrincipal("tenant-a", frozenset(), 5000)),
            clock=lambda: 1000,
        )
        status, _ = limited.handle("POST", "/tasks", self.headers, make_request())
        self.assertEqual(403, status)

    def test_submit_read_approve_cancel_are_owner_scoped(self):
        status, response = self.call("POST", "/tasks", make_request())
        self.assertEqual(202, status)
        task = response["task"]
        self.assertEqual("pending_approval", task["state"])
        status, _ = self.call("GET", "/tasks/" + task["task_id"])
        self.assertEqual(200, status)
        status, _ = self.call("POST", "/tasks/" + task["task_id"] + "/approve", {"payload_sha256": "0" * 64})
        self.assertEqual(400, status)
        status, approved = self.call(
            "POST", "/tasks/" + task["task_id"] + "/approve",
            {"payload_sha256": task["payload_sha256"]},
        )
        self.assertEqual(200, status)
        self.assertEqual("queued", approved["task"]["state"])
        status, cancelled = self.call("POST", "/tasks/" + task["task_id"] + "/cancel")
        self.assertEqual(200, status)
        self.assertEqual("cancelled", cancelled["task"]["state"])
        self.assertEqual(202, self.call("POST", "/tasks", make_request("health", {}, "task_2234567890abcd", "idem-key-2234567890"))[0])

    def test_body_limits_and_route_validation(self):
        status, _ = self.call("POST", "/tasks", b"x" * 21000)
        self.assertEqual(400, status)
        status, _ = self.call("GET", "/tasks/not-a-valid-id")
        self.assertEqual(404, status)

    def test_runner_only_executes_approved_side_effect_and_persists_result(self):
        _, response = self.call("POST", "/tasks", make_request())
        task = response["task"]
        runner_executor = Executor()
        runner = CloudTaskRunner(self.store, runner_executor)
        self.assertIsNone(runner.run_once())
        self.store.approve(task["task_id"], "tenant-a", task["payload_sha256"])
        result = runner.run_once()
        self.assertEqual("completed", result["state"])
        self.assertEqual([("create_text_file", {"path": "notes/a.txt", "content": "hello"}, "tenant-a")], runner_executor.calls)

    def test_runner_records_executor_failure_without_crashing(self):
        _, response = self.call("POST", "/tasks", make_request("health", {}))
        class BrokenExecutor:
            def execute(self, operation, payload, *, workspace_id):
                raise RuntimeError("isolated runtime unavailable")
        result = CloudTaskRunner(self.store, BrokenExecutor()).run_once()
        self.assertEqual("failed", result["state"])
        self.assertIn("isolated runtime unavailable", result["error"])


if __name__ == "__main__":
    unittest.main()
