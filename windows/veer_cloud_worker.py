"""Deployment-independent contract for a future VEER cloud task worker.

This module deliberately contains no HTTP server, cloud SDK, credentials, or
local-PC access. A hosting adapter must authenticate requests and provide a
durable store/executor implementing the protocols below.
"""
from __future__ import annotations

from dataclasses import dataclass
import hashlib
import hmac
import json
import re
from typing import Any, Mapping, Protocol

MAX_PAYLOAD_BYTES = 16 * 1024
ALLOWED_OPERATIONS = frozenset({"health", "list_workspace", "create_text_file", "open_url"})
SIDE_EFFECT_OPERATIONS = frozenset({"create_text_file", "open_url"})
_TASK_ID = re.compile(r"^[A-Za-z0-9_-]{16,64}$")
_IDEMPOTENCY_KEY = re.compile(r"^[A-Za-z0-9._:-]{16,128}$")
_SHA256 = re.compile(r"^[a-fA-F0-9]{64}$")


class CloudTaskError(ValueError):
    """A request violates the cloud worker contract."""


@dataclass(frozen=True)
class CloudPrincipal:
    subject: str
    scopes: frozenset[str]
    expires_at: float


@dataclass(frozen=True)
class CloudTaskRequest:
    task_id: str
    operation: str
    payload: Mapping[str, Any]
    idempotency_key: str
    payload_sha256: str
    approval_sha256: str | None = None

    @classmethod
    def parse(cls, raw: Mapping[str, Any]) -> "CloudTaskRequest":
        try:
            task_id = raw["task_id"]
            operation = raw["operation"]
            payload = raw["payload"]
            idempotency_key = raw["idempotency_key"]
            payload_hash = raw["payload_sha256"]
        except (KeyError, TypeError) as exc:
            raise CloudTaskError("Missing cloud task fields") from exc
        if not isinstance(task_id, str) or not _TASK_ID.fullmatch(task_id):
            raise CloudTaskError("Invalid cloud task ID")
        if not isinstance(operation, str) or operation not in ALLOWED_OPERATIONS:
            raise CloudTaskError("Unsupported cloud operation")
        if not isinstance(payload, dict):
            raise CloudTaskError("Task payload must be an object")
        try:
            encoded = json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
        except (TypeError, ValueError) as exc:
            raise CloudTaskError("Task payload is not JSON serializable") from exc
        if len(encoded) > MAX_PAYLOAD_BYTES:
            raise CloudTaskError("Cloud task payload is too large")
        if not isinstance(idempotency_key, str) or not _IDEMPOTENCY_KEY.fullmatch(idempotency_key):
            raise CloudTaskError("Invalid idempotency key")
        if not isinstance(payload_hash, str) or not _SHA256.fullmatch(payload_hash):
            raise CloudTaskError("Invalid payload hash")
        actual_hash = hashlib.sha256(encoded).hexdigest()
        if not hmac.compare_digest(actual_hash, payload_hash.lower()):
            raise CloudTaskError("Payload hash does not match exact task payload")
        approval = raw.get("approval_sha256")
        if approval is not None and (not isinstance(approval, str) or not _SHA256.fullmatch(approval)):
            raise CloudTaskError("Invalid approval hash")
        return cls(task_id, operation, payload, idempotency_key, actual_hash, approval.lower() if approval else None)


class CloudTaskStore(Protocol):
    """Durable adapter contract; implementations must enforce unique idempotency keys."""

    def get_by_idempotency_key(self, key: str) -> Mapping[str, Any] | None: ...
    def save_pending(self, task: CloudTaskRequest, owner: str) -> Mapping[str, Any]: ...


class CloudTaskExecutor(Protocol):
    """Executor is restricted to an isolated cloud workspace, never the user's PC."""

    def execute(self, operation: str, payload: Mapping[str, Any], *, workspace_id: str) -> Mapping[str, Any]: ...


def accept_cloud_task(
    raw: Mapping[str, Any],
    principal: CloudPrincipal,
    store: CloudTaskStore,
    *,
    now: float,
) -> Mapping[str, Any]:
    """Validate identity/scope and durably accept a task; does not execute it."""
    if not principal.subject or principal.expires_at <= now:
        raise CloudTaskError("Cloud credential is expired or invalid")
    if "tasks:write" not in principal.scopes:
        raise CloudTaskError("Credential lacks tasks:write scope")
    task = CloudTaskRequest.parse(raw)
    existing = store.get_by_idempotency_key(task.idempotency_key)
    if existing is not None:
        return existing
    # Side effects are always created pending approval. An approval must be
    # checked by the executor against this exact hash immediately before run.
    state = "pending_approval" if task.operation in SIDE_EFFECT_OPERATIONS else "queued"
    return store.save_pending(task, principal.subject) | {"state": state}


def verify_one_shot_approval(task: CloudTaskRequest, supplied_hash: str | None) -> None:
    """Fail closed unless approval matches this task's exact payload hash."""
    if task.operation not in SIDE_EFFECT_OPERATIONS:
        return
    if not supplied_hash or not _SHA256.fullmatch(supplied_hash):
        raise CloudTaskError("Exact task approval is required")
    if not hashlib.compare_digest(task.payload_sha256, supplied_hash.lower()):
        raise CloudTaskError("Approval does not match this exact task")
