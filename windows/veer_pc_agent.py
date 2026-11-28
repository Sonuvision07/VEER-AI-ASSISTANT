#!/usr/bin/env python3
"""VEER Windows companion: restricted, authenticated local agent."""
from __future__ import annotations

import argparse
from dataclasses import asdict
import heapq
import json
import os
import secrets
import webbrowser
import unicodedata
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

from veer_task_queue import DurableTaskQueue, TaskConflict, TaskNotFound, TaskRejected

MAX_BODY = 64 * 1024
TOKEN_MIN_LENGTH = 40


def valid_token(token: str) -> bool:
    return (
        isinstance(token, str)
        and len(token) >= TOKEN_MIN_LENGTH
        and not any(char.isspace() or unicodedata.category(char) == "Cc" for char in token)
    )


def default_app_dir() -> Path:
    return Path(os.environ.get("VEER_HOME", str(Path.home() / "VEER"))).expanduser().resolve()


def load_token(app_dir: Path | None = None) -> str:
    """Load or securely create the local bearer token without replacing a valid one."""
    root = (app_dir or default_app_dir()).expanduser().resolve()
    root.mkdir(parents=True, exist_ok=True)
    token_file = root / "agent.token"

    if token_file.exists():
        token = token_file.read_text(encoding="utf-8").strip()
        if valid_token(token):
            return token
        raise RuntimeError("Existing VEER token is invalid. Back it up and remove it before restarting.")

    token = secrets.token_urlsafe(48)
    try:
        descriptor = os.open(token_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        existing = token_file.read_text(encoding="utf-8").strip()
        if valid_token(existing):
            return existing
        raise RuntimeError("VEER token creation raced with an invalid token file.")

    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            handle.write(token + "\n")
        try:
            os.chmod(token_file, 0o600)
        except OSError:
            # Windows permissions are governed by the user's filesystem ACL.
            pass
    except Exception:
        try:
            token_file.unlink(missing_ok=True)
        except OSError:
            pass
        raise
    return token


def safe_path(workspace: Path, raw: str) -> Path:
    """Resolve a relative path and reject anything outside the VEER workspace."""
    if not isinstance(raw, str) or not raw.strip():
        raise ValueError("A relative workspace path is required")
    candidate = Path(raw)
    if candidate.is_absolute() or candidate.drive or candidate.root:
        raise ValueError("Absolute paths are not allowed")
    root = workspace.resolve()
    target = (root / candidate).resolve()
    if target != root and root not in target.parents:
        raise ValueError("Path outside VEER workspace")
    return target


def _handler_for(workspace: Path, token: str, task_queue: DurableTaskQueue):
    class Handler(BaseHTTPRequestHandler):
        server_version = "VEER-PC-Agent/0.2"
        sys_version = ""

        def setup(self) -> None:
            super().setup()
            self.connection.settimeout(15)

        def log_message(self, fmt: str, *args) -> None:
            # Never log Authorization headers or request bodies.
            print("[%s] %s" % (datetime.now().isoformat(timespec="seconds"), fmt % args))

        def send_json(self, status: int, data: dict) -> None:
            raw = json.dumps(data, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(raw)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.end_headers()
            self.wfile.write(raw)

        def authorized(self) -> bool:
            supplied = self.headers.get("Authorization", "")
            return secrets.compare_digest(supplied, "Bearer " + token)

        def body(self) -> dict:
            if self.headers.get("Transfer-Encoding"):
                raise ValueError("Chunked request bodies are not supported")
            try:
                size = int(self.headers.get("Content-Length", "0"))
            except ValueError as error:
                raise ValueError("Invalid Content-Length") from error
            if size < 0 or size > MAX_BODY:
                raise ValueError("Request body too large")
            raw = self.rfile.read(size)
            if len(raw) != size:
                raise ValueError("Incomplete request body")
            value = json.loads(raw.decode("utf-8") or "{}")
            if not isinstance(value, dict):
                raise ValueError("JSON object required")
            return value

        def do_GET(self) -> None:
            if not self.authorized():
                return self.send_json(401, {"ok": False, "error": "Unauthorized"})

            route = urlparse(self.path)
            if route.path == "/health":
                return self.send_json(200, {
                    "ok": True,
                    "agent": "VEER-PC-Agent",
                    "version": "0.2",
                    "time": datetime.now(timezone.utc).isoformat(),
                })
            if route.path.startswith("/tasks/"):
                task_id = route.path[len("/tasks/"):].strip("/")
                if not task_id or "/" in task_id:
                    return self.send_json(404, {"ok": False, "error": "Not found"})
                try:
                    task = task_queue.get(task_id)
                    return self.send_json(200, {"ok": True, "task": asdict(task)})
                except TaskNotFound:
                    return self.send_json(404, {"ok": False, "error": "Task not found"})
            if route.path != "/files":
                return self.send_json(404, {"ok": False, "error": "Not found"})

            try:
                relative = parse_qs(route.query).get("path", ["."])[0] or "."
                folder = safe_path(workspace, relative)
                if not folder.is_dir():
                    raise ValueError("Workspace folder not found")
                entries = heapq.nsmallest(200, folder.iterdir(), key=lambda item: item.name.casefold())
                items = [
                    {
                        "name": item.name,
                        "type": "link" if item.is_symlink() else "folder" if item.is_dir() else "file",
                        "size": item.stat().st_size if item.is_file() and not item.is_symlink() else None,
                    }
                    for item in entries
                ]
                return self.send_json(200, {
                    "ok": True,
                    "path": folder.relative_to(workspace).as_posix(),
                    "items": items,
                })
            except (ValueError, TaskRejected) as error:
                return self.send_json(400, {"ok": False, "error": str(error)})
            except TaskNotFound:
                return self.send_json(404, {"ok": False, "error": "Task not found"})
            except TaskConflict as error:
                return self.send_json(409, {"ok": False, "error": str(error)})
            except OSError:
                return self.send_json(500, {"ok": False, "error": "Unable to read workspace folder"})

        def do_POST(self) -> None:
            if not self.authorized():
                return self.send_json(401, {"ok": False, "error": "Unauthorized"})

            route = urlparse(self.path).path
            try:
                data = self.body()
                if route == "/tasks":
                    operation = data.get("operation")
                    payload = data.get("payload", {})
                    idempotency_key = data.get("idempotency_key") or self.headers.get("Idempotency-Key", "")
                    task = task_queue.enqueue(operation, payload, idempotency_key)
                    return self.send_json(202, {"ok": True, "task": asdict(task)})
                if route.startswith("/tasks/") and route.endswith("/approve"):
                    task_id = route[len("/tasks/"):-len("/approve")].strip("/")
                    if not task_id or "/" in task_id:
                        return self.send_json(404, {"ok": False, "error": "Not found"})
                    task = task_queue.approve_task(task_id, data.get("payload_sha256", ""))
                    return self.send_json(200, {"ok": True, "task": asdict(task)})
                if route.startswith("/tasks/") and route.endswith("/cancel"):
                    task_id = route[len("/tasks/"):-len("/cancel")].strip("/")
                    if not task_id or "/" in task_id:
                        return self.send_json(404, {"ok": False, "error": "Not found"})
                    task = task_queue.cancel(task_id)
                    return self.send_json(200, {"ok": True, "task": asdict(task)})
                if route not in ("/open-url", "/write-text"):
                    return self.send_json(404, {"ok": False, "error": "Not found"})
                if route == "/open-url":
                    url = str(data.get("url", "")).strip()
                    if not url or any(ord(char) < 32 for char in url):
                        raise ValueError("A valid HTTP(S) URL is required")
                    parsed = urlparse(url)
                    if parsed.scheme.lower() not in ("https", "http") or not parsed.hostname:
                        raise ValueError("Only valid HTTP(S) URLs are allowed")
                    if parsed.username is not None or parsed.password is not None:
                        raise ValueError("URLs containing credentials are not allowed")
                    try:
                        _ = parsed.port
                    except ValueError as error:
                        raise ValueError("Invalid URL port") from error
                    opened = webbrowser.open(url, new=2)
                    if not opened:
                        return self.send_json(503, {"ok": False, "error": "The default browser did not accept the open request"})
                    return self.send_json(200, {"ok": True, "message": "Browser open request accepted", "url": url})

                relative = str(data.get("path", "")).strip()
                content = data.get("content", "")
                if not relative or not isinstance(content, str):
                    raise ValueError("path and text content are required")
                if len(content.encode("utf-8")) > MAX_BODY:
                    raise ValueError("Text content too large")

                target = safe_path(workspace, relative)
                if target == workspace.resolve():
                    raise ValueError("A file name inside the workspace is required")
                target.parent.mkdir(parents=True, exist_ok=True)
                # Re-resolve after directory creation to catch symlink changes.
                target = safe_path(workspace, relative)
                try:
                    # Exclusive creation prevents a check-then-write overwrite race.
                    with target.open("x", encoding="utf-8", newline="") as handle:
                        handle.write(content)
                except FileExistsError:
                    return self.send_json(409, {"ok": False, "error": "Refusing to overwrite existing file"})
                return self.send_json(201, {"ok": True, "created": target.relative_to(workspace).as_posix()})
            except ValueError as error:
                return self.send_json(400, {"ok": False, "error": str(error)})
            except OSError:
                return self.send_json(500, {"ok": False, "error": "The requested local operation failed"})
            except Exception:
                return self.send_json(502, {"ok": False, "error": "The browser or local operation failed"})

    return Handler


class VeerHTTPServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def create_server(
    host: str = "127.0.0.1",
    port: int = 8765,
    app_dir: Path | None = None,
    token: str | None = None,
) -> VeerHTTPServer:
    """Create a testable server; production binding is deliberately loopback-only."""
    if host not in ("127.0.0.1", "localhost"):
        raise ValueError("VEER PC Agent must bind to loopback only")
    root = (app_dir or default_app_dir()).expanduser().resolve()
    workspace_candidate = root / "workspace"
    workspace_candidate.mkdir(parents=True, exist_ok=True)
    workspace = workspace_candidate.resolve()
    if workspace != workspace_candidate:
        raise ValueError("VEER workspace cannot be a symlink or junction")
    active_token = token if token is not None else load_token(root)
    if not valid_token(active_token):
        raise ValueError("VEER bearer token must be at least 40 characters and contain no whitespace or control characters")
    task_queue = DurableTaskQueue(root / "tasks.sqlite3")
    server = VeerHTTPServer((host, port), _handler_for(workspace, active_token, task_queue))
    server.veer_task_queue = task_queue
    server.veer_workspace = workspace
    server.veer_token_file = root / "agent.token"
    return server


def main() -> None:
    parser = argparse.ArgumentParser(description="Run the restricted VEER Windows companion")
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    server = create_server(port=args.port)
    print("VEER PC Agent on http://127.0.0.1:%d" % server.server_address[1])
    print("Workspace:", server.veer_workspace)
    print("Private token:", server.veer_token_file)
    print("Keep the token private. Stop with Ctrl+C.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("Stopping VEER PC Agent")
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
