"""Security and API tests for the restricted VEER Windows companion."""
from __future__ import annotations

import json
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import unittest
from pathlib import Path
from unittest import mock

import veer_pc_agent

TEST_TOKEN = "veer-unit-test-token-0123456789abcdef0123456789"


class VeerPcAgentTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.app_dir = Path(self.temp_dir.name)
        self.server = veer_pc_agent.create_server(port=0, app_dir=self.app_dir, token=TEST_TOKEN)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        host, port = self.server.server_address[:2]
        self.base_url = f"http://{host}:{port}"

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.temp_dir.cleanup()

    def request(self, path: str, method: str = "GET", data=None, authenticated: bool = True):
        headers = {}
        body = None
        if authenticated:
            headers["Authorization"] = "Bearer " + TEST_TOKEN
        if data is not None:
            body = json.dumps(data).encode("utf-8")
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(self.base_url + path, data=body, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=3) as response:
                return response.status, json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as error:
            return error.code, json.loads(error.read().decode("utf-8"))

    def test_health_requires_bearer_token(self):
        status, body = self.request("/health", authenticated=False)
        self.assertEqual(status, 401)
        self.assertFalse(body["ok"])
        status, body = self.request("/health")
        self.assertEqual(status, 200)
        self.assertEqual(body["agent"], "VEER-PC-Agent")
        self.assertEqual(body["version"], "0.2")

    def test_legacy_direct_action_endpoints_are_disabled(self):
        with mock.patch("veer_pc_agent.webbrowser.open") as browser:
            status, body = self.request("/write-text", "POST", {"path": "notes/blocked.txt", "content": "must not write"})
            self.assertEqual(status, 404)
            self.assertFalse(body["ok"])
            self.assertFalse((self.server.veer_workspace / "notes" / "blocked.txt").exists())
            status, body = self.request("/open-url", "POST", {"url": "https://example.com"})
            self.assertEqual(status, 404)
            self.assertFalse(body["ok"])
            browser.assert_not_called()

    def test_path_traversal_is_rejected_for_listing(self):
        status, _ = self.request("/files?path=" + urllib.parse.quote("../outside"))
        self.assertEqual(status, 400)
        self.assertFalse((self.app_dir / "outside.txt").exists())

    def test_workspace_symlink_escape_is_rejected_for_listing(self):
        outside = self.app_dir / "outside-folder"
        outside.mkdir()
        link = self.server.veer_workspace / "outside-link"
        try:
            link.symlink_to(outside, target_is_directory=True)
        except (OSError, NotImplementedError):
            self.skipTest("Directory symlinks are unavailable on this Windows runner")
        status, _ = self.request("/files?path=outside-link")
        self.assertEqual(status, 400)
        self.assertFalse((outside / "escape.txt").exists())

    def test_body_must_be_json_object(self):
        status, body = self.request("/tasks", "POST", ["not", "an", "object"])
        self.assertEqual(status, 400)
        self.assertFalse(body["ok"])

    def test_legacy_direct_file_and_browser_routes_are_not_exposed(self):
        status, _ = self.request("/write-text", "POST", {"path": str(self.app_dir / "outside.txt"), "content": "blocked"})
        self.assertEqual(status, 404)
        with mock.patch("veer_pc_agent.webbrowser.open") as browser:
            status, _ = self.request("/open-url", "POST", {"url": "file:///C:/Windows/win.ini"})
            self.assertEqual(status, 404)
            browser.assert_not_called()
        self.assertFalse((self.app_dir / "outside.txt").exists())

    def test_large_body_to_legacy_action_route_is_not_processed(self):
        status, body = self.request("/write-text", "POST", {"path": "large.txt", "content": "x" * (veer_pc_agent.MAX_BODY + 100)})
        self.assertEqual(status, 404)
        self.assertFalse(body["ok"])
        self.assertFalse((self.server.veer_workspace / "large.txt").exists())

    def test_unknown_routes_do_not_execute(self):
        status, body = self.request("/run-command", "POST", {"command": "whoami"})
        self.assertEqual(status, 404)
        self.assertFalse(body["ok"])

    def test_server_refuses_non_loopback_binding(self):
        with self.assertRaises(ValueError):
            veer_pc_agent.create_server(host="0.0.0.0", port=0, app_dir=self.app_dir, token=TEST_TOKEN)

    def test_token_is_created_once_and_reused(self):
        token_dir = self.app_dir / "token-test"
        first = veer_pc_agent.load_token(token_dir)
        second = veer_pc_agent.load_token(token_dir)
        self.assertTrue(veer_pc_agent.valid_token(first))
        self.assertEqual(first, second)
        self.assertTrue((token_dir / "agent.token").is_file())

    def test_existing_token_with_whitespace_or_control_characters_is_rejected(self):
        for invalid in ("x" * 20 + " " + "y" * 20, "x" * 20 + "\n" + "y" * 20, "x" * 20 + "\x1b" + "y" * 20):
            with self.subTest(invalid=repr(invalid)):
                token_dir = self.app_dir / ("invalid-" + str(len(list(self.app_dir.iterdir()))))
                token_dir.mkdir()
                (token_dir / "agent.token").write_text(invalid, encoding="utf-8")
                with self.assertRaises(RuntimeError):
                    veer_pc_agent.load_token(token_dir)

    def test_server_rejects_explicit_token_with_whitespace(self):
        with self.assertRaises(ValueError):
            veer_pc_agent.create_server(port=0, app_dir=self.app_dir, token="x" * 20 + " " + "y" * 20)

    def wait_for_task(self, task_id, expected="completed", timeout=3):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            task = self.server.veer_task_queue.get(task_id)
            if task.state in ("completed", "failed", "cancelled", "expired"):
                return task
            time.sleep(0.02)
        self.fail("Task worker did not finish within the test timeout")

    def test_worker_executes_read_only_task_and_reports_result(self):
        queue = self.server.veer_task_queue
        task = queue.enqueue("list_workspace", {"path": "."}, "worker-list-task-key-0001")
        worker = veer_pc_agent.TaskWorker(queue, self.server.veer_workspace, poll_seconds=0.01)
        worker.start()
        try:
            completed = self.wait_for_task(task.task_id)
        finally:
            worker.stop()
        self.assertEqual(completed.state, "completed")
        self.assertEqual(completed.result["items"], [])

    def test_worker_never_executes_side_effect_before_exact_approval(self):
        queue = self.server.veer_task_queue
        task = queue.enqueue("create_text_file", {"path": "worker.txt", "content": "approved"}, "worker-write-task-key-0001")
        self.assertEqual(task.state, "pending_approval")
        worker = veer_pc_agent.TaskWorker(queue, self.server.veer_workspace, poll_seconds=0.01)
        worker.start()
        try:
            time.sleep(0.08)
            self.assertFalse((self.server.veer_workspace / "worker.txt").exists())
            queue.approve_task(task.task_id, task.payload_sha256)
            completed = self.wait_for_task(task.task_id)
        finally:
            worker.stop()
        self.assertEqual(completed.state, "completed")
        self.assertEqual((self.server.veer_workspace / "worker.txt").read_text(encoding="utf-8"), "approved")

    def test_worker_rejects_unsafe_paths_and_reports_failure(self):
        queue = self.server.veer_task_queue
        task = queue.enqueue("create_text_file", {"path": "../outside.txt", "content": "blocked"}, "worker-unsafe-task-key-001")
        queue.approve_task(task.task_id, task.payload_sha256)
        worker = veer_pc_agent.TaskWorker(queue, self.server.veer_workspace, poll_seconds=0.01)
        worker.start()
        try:
            failed = self.wait_for_task(task.task_id, expected="failed")
        finally:
            worker.stop()
        self.assertEqual(failed.state, "failed")
        self.assertFalse((self.app_dir / "outside.txt").exists())

    def test_worker_open_url_uses_browser_only_for_approved_task(self):
        queue = self.server.veer_task_queue
        task = queue.enqueue("open_url", {"url": "https://example.com"}, "worker-open-url-task-0001")
        worker = veer_pc_agent.TaskWorker(queue, self.server.veer_workspace, poll_seconds=0.01)
        with mock.patch("veer_pc_agent.webbrowser.open", return_value=True) as browser:
            worker.start()
            try:
                time.sleep(0.08)
                browser.assert_not_called()
                queue.approve_task(task.task_id, task.payload_sha256)
                completed = self.wait_for_task(task.task_id)
            finally:
                worker.stop()
        self.assertEqual(completed.state, "completed")
        browser.assert_called_once_with("https://example.com", new=2)

    def test_task_api_requires_auth_and_supports_status(self):
        payload = {"operation": "health", "payload": {}, "idempotency_key": "health-task-key-0001"}
        status, body = self.request("/tasks", "POST", payload, authenticated=False)
        self.assertEqual(status, 401)
        status, body = self.request("/tasks", "POST", payload)
        self.assertEqual(status, 202)
        task_id = body["task"]["task_id"]
        self.assertEqual(body["task"]["state"], "queued")
        status, body = self.request("/tasks/" + task_id)
        self.assertEqual(status, 200)
        self.assertEqual(body["task"]["operation"], "health")

    def test_side_effect_task_requires_exact_payload_approval(self):
        payload = {"operation": "create_text_file", "payload": {"path": "approved.txt", "content": "hello"}, "idempotency_key": "write-task-key-0001"}
        status, body = self.request("/tasks", "POST", payload)
        self.assertEqual(status, 202)
        task = body["task"]
        self.assertEqual(task["state"], "pending_approval")
        status, body = self.request("/tasks/" + task["task_id"] + "/approve", "POST", {"payload_sha256": "0" * 64})
        self.assertEqual(status, 400)
        self.assertEqual(body["error"], "Approval does not match the exact task payload")
        status, body = self.request("/tasks/" + task["task_id"] + "/approve", "POST", {"payload_sha256": task["payload_sha256"]})
        self.assertEqual(status, 200)
        self.assertEqual(body["task"]["state"], "queued")

    def test_task_api_cancel_idempotency_and_unsupported_operation(self):
        payload = {"operation": "health", "payload": {}, "idempotency_key": "cancel-task-key-0001"}
        status, first = self.request("/tasks", "POST", payload)
        self.assertEqual(status, 202)
        status, duplicate = self.request("/tasks", "POST", payload)
        self.assertEqual(status, 202)
        self.assertEqual(first["task"]["task_id"], duplicate["task"]["task_id"])
        task_id = first["task"]["task_id"]
        status, cancelled = self.request("/tasks/" + task_id + "/cancel", "POST", {})
        self.assertEqual(status, 200)
        self.assertEqual(cancelled["task"]["state"], "cancelled")
        status, rejected = self.request("/tasks", "POST", {"operation": "run_shell", "payload": {}, "idempotency_key": "unsupported-task-key-001"})
        self.assertEqual(status, 400)
        self.assertFalse(rejected["ok"])


if __name__ == "__main__":
    unittest.main()
