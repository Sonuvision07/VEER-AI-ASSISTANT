"""Security and API tests for the restricted VEER Windows companion."""
from __future__ import annotations

import json
import tempfile
import threading
import unittest
import urllib.error
import urllib.parse
import urllib.request
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

    def test_write_creates_inside_workspace_and_never_overwrites(self):
        status, body = self.request("/write-text", "POST", {"path": "notes/first.txt", "content": "Namaste"})
        self.assertEqual(status, 201)
        self.assertEqual(body["created"], "notes/first.txt")
        target = self.server.veer_workspace / "notes" / "first.txt"
        self.assertEqual(target.read_text(encoding="utf-8"), "Namaste")

        status, body = self.request("/write-text", "POST", {"path": "notes/first.txt", "content": "Changed"})
        self.assertEqual(status, 409)
        self.assertEqual(target.read_text(encoding="utf-8"), "Namaste")

    def test_path_traversal_is_rejected_for_listing_and_writing(self):
        status, _ = self.request("/files?path=" + urllib.parse.quote("../outside"))
        self.assertEqual(status, 400)

        status, _ = self.request("/write-text", "POST", {"path": "../outside.txt", "content": "blocked"})
        self.assertEqual(status, 400)
        self.assertFalse((self.app_dir / "outside.txt").exists())

    def test_absolute_paths_are_rejected(self):
        status, _ = self.request("/write-text", "POST", {"path": str(self.app_dir / "outside.txt"), "content": "blocked"})
        self.assertEqual(status, 400)

    def test_open_url_rejects_non_http_schemes_and_credentials(self):
        status, _ = self.request("/open-url", "POST", {"url": "file:///C:/Windows/win.ini"})
        self.assertEqual(status, 400)
        status, _ = self.request("/open-url", "POST", {"url": "https://user:pass@example.com"})
        self.assertEqual(status, 400)

    def test_open_url_reports_browser_acceptance(self):
        with mock.patch("veer_pc_agent.webbrowser.open", return_value=True) as browser:
            status, body = self.request("/open-url", "POST", {"url": "https://example.com"})
        self.assertEqual(status, 200)
        self.assertTrue(body["ok"])
        browser.assert_called_once_with("https://example.com", new=2)

        with mock.patch("veer_pc_agent.webbrowser.open", return_value=False):
            status, body = self.request("/open-url", "POST", {"url": "https://example.com"})
        self.assertEqual(status, 503)
        self.assertFalse(body["ok"])

    def test_large_body_is_rejected(self):
        status, body = self.request("/write-text", "POST", {"path": "large.txt", "content": "x" * (veer_pc_agent.MAX_BODY + 100)})
        self.assertEqual(status, 400)
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
        self.assertGreaterEqual(len(first), veer_pc_agent.TOKEN_MIN_LENGTH)
        self.assertEqual(first, second)
        self.assertTrue((token_dir / "agent.token").is_file())


if __name__ == "__main__":
    unittest.main()
