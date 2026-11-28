"""Framework-neutral authenticated cloud task API and injected worker runtime.

A hosting adapter should translate HTTP requests to CloudTaskService.handle().
Authentication is delegated to an injected verifier; this module stores no
secrets and never executes shell commands or accesses a paired local PC.
"""
from __future__ import annotations

import json
import re
import time
from typing import Any, Mapping, Protocol
from urllib.parse import unquote

from veer_cloud_worker import (
    ALLOWED_OPERATIONS,
    SIDE_EFFECT_OPERATIONS,
    CloudPrincipal,
    CloudTaskError,
    CloudTaskExecutor,
    CloudTaskStore,
    accept_cloud_task,
)

_TASK_PATH = re.compile(r"^/tasks/([A-Za-z0-9_-]{16,64})(?:/(approve|cancel))?$")
_MAX_BODY_BYTES = 20 * 1024


class TokenVerifier(Protocol):
    def verify(self, bearer_token: str) -> CloudPrincipal | None: ...


class CloudTaskService:
    """Small HTTP-shaped controller; caller supplies a real auth verifier."""

    def __init__(self, store: Any, verifier: TokenVerifier, *, clock=time.time):
        self.store = store
        self.verifier = verifier
        self.clock = clock

    @staticmethod
    def _response(status: int, payload: Mapping[str, Any]) -> tuple[int, dict[str, Any]]:
        return status, dict(payload)

    @staticmethod
    def _decode_body(body: bytes | str | Mapping[str, Any] | None) -> Mapping[str, Any]:
        if body is None:
            return {}
        if isinstance(body, Mapping):
            value = dict(body)
            encoded = json.dumps(value, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
        else:
            encoded = body.encode("utf-8") if isinstance(body, str) else body
            if not isinstance(encoded, bytes) or len(encoded) > _MAX_BODY_BYTES:
                raise CloudTaskError("Request body is too large or invalid")
            try:
                value = json.loads(encoded.decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError) as exc:
                raise CloudTaskError("Request body must be valid JSON") from exc
        if len(encoded) > _MAX_BODY_BYTES:
            raise CloudTaskError("Request body is too large")
        if not isinstance(value, dict):
            raise CloudTaskError("Request body must be a JSON object")
        return value

    def _principal(self, headers: Mapping[str, str]) -> CloudPrincipal:
        auth = next((v for k, v in headers.items() if k.lower() == "authorization"), "")
        if not isinstance(auth, str) or not auth.startswith("Bearer "):
            raise PermissionError("Bearer authentication required")
        token = auth[7:].strip()
        if not token or len(token) > 4096 or any(ord(ch) < 33 or ord(ch) > 126 for ch in token):
            raise PermissionError("Invalid bearer credential")
        principal = self.verifier.verify(token)
        if not isinstance(principal, CloudPrincipal) or not principal.subject or principal.expires_at <= self.clock():
            raise PermissionError("Invalid or expired bearer credential")
        return principal

    def handle(
        self,
        method: str,
        path: str,
        headers: Mapping[str, str],
        body: bytes | str | Mapping[str, Any] | None = None,
    ) -> tuple[int, dict[str, Any]]:
        """Return (HTTP status, JSON-safe body); no network listener is started."""
        try:
            principal = self._principal(headers)
        except PermissionError as exc:
            return self._response(401, {"error": str(exc)})

        try:
            data = self._decode_body(body)
            if method == "POST" and path == "/tasks":
                if "tasks:write" not in principal.scopes:
                    return self._response(403, {"error": "Credential lacks tasks:write scope"})
                result = accept_cloud_task(data, principal, self.store, now=self.clock())
                return self._response(202, {"task": dict(result)})

            match = _TASK_PATH.fullmatch(path)
            if not match:
                return self._response(404, {"error": "Route not found"})
            task_id, action = match.groups()

            if method == "GET" and action is None:
                if "tasks:read" not in principal.scopes:
                    return self._response(403, {"error": "Credential lacks tasks:read scope"})
                return self._response(200, {"task": dict(self.store.get(task_id, principal.subject))})

            if method == "POST" and action == "approve":
                if "tasks:approve" not in principal.scopes:
                    return self._response(403, {"error": "Credential lacks tasks:approve scope"})
                payload_hash = data.get("payload_sha256")
                if not isinstance(payload_hash, str):
                    raise CloudTaskError("Exact payload_sha256 approval is required")
                return self._response(200, {"task": dict(self.store.approve(task_id, principal.subject, payload_hash))})

            if method == "POST" and action == "cancel":
                if "tasks:cancel" not in principal.scopes:
                    return self._response(403, {"error": "Credential lacks tasks:cancel scope"})
                return self._response(200, {"task": dict(self.store.cancel(task_id, principal.subject))})

            return self._response(405, {"error": "Method not allowed"})
        except PermissionError as exc:
            return self._response(403, {"error": str(exc)})
        except CloudTaskError as exc:
            message = str(exc)
            status = 404 if message == "Task not found" else 400
            return self._response(status, {"error": message})
        except (TypeError, ValueError) as exc:
            return self._response(400, {"error": str(exc)[:300]})


class CloudTaskRunner:
    """Single-claim runner using an injected, sandboxed operation executor."""

    def __init__(self, store: Any, executor: CloudTaskExecutor):
        self.store = store
        self.executor = executor

    def run_once(self) -> Mapping[str, Any] | None:
        task = self.store.claim_next()
        if task is None:
            return None
        operation = task.get("operation")
        if operation not in ALLOWED_OPERATIONS:
            return self.store.finish(task["task_id"], error="Unsupported operation")
        if operation in SIDE_EFFECT_OPERATIONS and not task.get("approval_consumed"):
            return self.store.finish(task["task_id"], error="Exact approval was not consumed")
        try:
            result = self.executor.execute(operation, task.get("payload", {}), workspace_id=task["owner"])
            if not isinstance(result, Mapping):
                raise CloudTaskError("Executor must return an object result")
            encoded = json.dumps(result, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
            if len(encoded) > 16 * 1024:
                raise CloudTaskError("Executor result is too large")
            return self.store.finish(task["task_id"], result=dict(result))
        except Exception as exc:
            return self.store.finish(task["task_id"], error=f"{type(exc).__name__}: {str(exc)[:400]}")


