"""Durable, bounded task queue for VEER's restricted Windows companion.

This module is deliberately transport-agnostic. The HTTP API integration is a
separate step; callers must authenticate and obtain explicit per-task approval
before invoking approve_task for any side-effecting operation.
"""
from __future__ import annotations

import hashlib
import json
import secrets
import sqlite3
import time
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path
from typing import Any

ALLOWED_TASKS = frozenset({"health", "list_workspace", "create_text_file", "open_url"})
SIDE_EFFECT_TASKS = frozenset({"create_text_file", "open_url"})
TERMINAL_STATES = frozenset({"completed", "failed", "cancelled", "expired"})
ACTIVE_STATES = frozenset({"pending_approval", "queued", "running"})


class TaskQueueError(Exception):
    """Base queue error."""


class TaskNotFound(TaskQueueError):
    pass


class TaskConflict(TaskQueueError):
    pass


class TaskRejected(TaskQueueError):
    pass


@dataclass(frozen=True)
class Task:
    task_id: str
    idempotency_key: str
    operation: str
    payload: dict[str, Any]
    payload_sha256: str
    state: str
    result: dict[str, Any] | None
    error: str | None
    created_at: float
    updated_at: float
    expires_at: float


def canonical_payload(operation: str, payload: dict[str, Any]) -> tuple[str, dict[str, Any]]:
    if operation not in ALLOWED_TASKS:
        raise TaskRejected("Unsupported task operation")
    if not isinstance(payload, dict):
        raise TaskRejected("Task payload must be an object")
    try:
        encoded = json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    except (TypeError, ValueError) as exc:
        raise TaskRejected("Task payload must contain JSON values only") from exc
    if len(encoded.encode("utf-8")) > 16 * 1024:
        raise TaskRejected("Task payload is too large")
    return encoded, json.loads(encoded)


class DurableTaskQueue:
    """SQLite-backed queue with bounded capacity, TTL and idempotency."""

    def __init__(
        self,
        database: str | Path,
        *,
        max_active: int = 50,
        task_ttl_seconds: int = 15 * 60,
        clock=time.time,
    ) -> None:
        if max_active < 1 or task_ttl_seconds < 1:
            raise ValueError("Queue limits must be positive")
        self.database = str(database)
        self.max_active = max_active
        self.task_ttl_seconds = task_ttl_seconds
        self.clock = clock
        Path(self.database).parent.mkdir(parents=True, exist_ok=True)
        with self._connect() as db:
            db.execute("""
                CREATE TABLE IF NOT EXISTS tasks (
                    task_id TEXT PRIMARY KEY,
                    idempotency_key TEXT NOT NULL UNIQUE,
                    operation TEXT NOT NULL,
                    payload_json TEXT NOT NULL,
                    payload_sha256 TEXT NOT NULL,
                    state TEXT NOT NULL,
                    result_json TEXT,
                    error TEXT,
                    created_at REAL NOT NULL,
                    updated_at REAL NOT NULL,
                    expires_at REAL NOT NULL
                )
            """)
            db.execute("CREATE INDEX IF NOT EXISTS idx_tasks_state_created ON tasks(state, created_at)")

    @contextmanager
    def _connect(self):
        db = sqlite3.connect(self.database, timeout=10, isolation_level="IMMEDIATE")
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA busy_timeout=10000")
        try:
            yield db
        finally:
            db.close()

    @staticmethod
    def _row(row: sqlite3.Row | None) -> Task | None:
        if row is None:
            return None
        return Task(
            task_id=row["task_id"],
            idempotency_key=row["idempotency_key"],
            operation=row["operation"],
            payload=json.loads(row["payload_json"]),
            payload_sha256=row["payload_sha256"],
            state=row["state"],
            result=json.loads(row["result_json"]) if row["result_json"] else None,
            error=row["error"],
            created_at=row["created_at"],
            updated_at=row["updated_at"],
            expires_at=row["expires_at"],
        )

    def _expire(self, db: sqlite3.Connection, now: float) -> None:
        db.execute(
            "UPDATE tasks SET state='expired', updated_at=?, error='Task expired' "
            "WHERE state IN ('pending_approval','queued','running') AND expires_at<=?",
            (now, now),
        )

    def enqueue(self, operation: str, payload: dict[str, Any], idempotency_key: str) -> Task:
        if not isinstance(idempotency_key, str) or not (16 <= len(idempotency_key) <= 128):
            raise TaskRejected("Idempotency key must contain 16 to 128 characters")
        if any(ord(ch) < 33 or ord(ch) > 126 for ch in idempotency_key):
            raise TaskRejected("Invalid idempotency key")
        payload_json, normalized = canonical_payload(operation, payload)
        digest = hashlib.sha256((operation + "\n" + payload_json).encode("utf-8")).hexdigest()
        now = self.clock()
        task_id = secrets.token_urlsafe(18)
        initial_state = "pending_approval" if operation in SIDE_EFFECT_TASKS else "queued"
        with self._connect() as db:
            self._expire(db, now)
            existing = db.execute("SELECT * FROM tasks WHERE idempotency_key=?", (idempotency_key,)).fetchone()
            if existing:
                task = self._row(existing)
                if task.operation != operation or task.payload_sha256 != digest:
                    raise TaskConflict("Idempotency key was already used for a different task")
                return task
            active = db.execute(
                "SELECT COUNT(*) FROM tasks WHERE state IN ('pending_approval','queued','running')"
            ).fetchone()[0]
            if active >= self.max_active:
                raise TaskRejected("Task queue is full")
            db.execute(
                """INSERT INTO tasks
                (task_id,idempotency_key,operation,payload_json,payload_sha256,state,
                 created_at,updated_at,expires_at)
                VALUES (?,?,?,?,?,?,?,?,?)""",
                (task_id, idempotency_key, operation, payload_json, digest, initial_state,
                 now, now, now + self.task_ttl_seconds),
            )
            row = db.execute("SELECT * FROM tasks WHERE task_id=?", (task_id,)).fetchone()
            return self._row(row)

    def approve_task(self, task_id: str, expected_payload_sha256: str) -> Task:
        now = self.clock()
        with self._connect() as db:
            self._expire(db, now)
            row = db.execute("SELECT * FROM tasks WHERE task_id=?", (task_id,)).fetchone()
            if row is None:
                raise TaskNotFound("Task not found")
            task = self._row(row)
            if task.state != "pending_approval":
                raise TaskConflict("Task is not awaiting approval")
            if not secrets.compare_digest(task.payload_sha256, str(expected_payload_sha256)):
                raise TaskRejected("Approval does not match the exact task payload")
            db.execute("UPDATE tasks SET state='queued',updated_at=? WHERE task_id=?", (now, task_id))
            return self._row(db.execute("SELECT * FROM tasks WHERE task_id=?", (task_id,)).fetchone())

    def claim_next(self) -> Task | None:
        now = self.clock()
        with self._connect() as db:
            self._expire(db, now)
            row = db.execute(
                "SELECT * FROM tasks WHERE state='queued' ORDER BY created_at,task_id LIMIT 1"
            ).fetchone()
            if row is None:
                return None
            db.execute("UPDATE tasks SET state='running',updated_at=? WHERE task_id=?", (now, row["task_id"]))
            return self._row(db.execute("SELECT * FROM tasks WHERE task_id=?", (row["task_id"],)).fetchone())

    def finish(self, task_id: str, *, result: dict[str, Any] | None = None, error: str | None = None) -> Task:
        if (result is None) == (error is None):
            raise TaskRejected("Provide exactly one of result or error")
        now = self.clock()
        state = "failed" if error is not None else "completed"
        result_json = json.dumps(result, sort_keys=True, separators=(",", ":"), ensure_ascii=False) if result is not None else None
        if result_json is not None and len(result_json.encode("utf-8")) > 16 * 1024:
            raise TaskRejected("Task result is too large")
        if error is not None:
            error = str(error)[:500]
        with self._connect() as db:
            row = db.execute("SELECT state FROM tasks WHERE task_id=?", (task_id,)).fetchone()
            if row is None:
                raise TaskNotFound("Task not found")
            if row["state"] != "running":
                raise TaskConflict("Only a running task can be finished")
            db.execute(
                "UPDATE tasks SET state=?,result_json=?,error=?,updated_at=? WHERE task_id=?",
                (state, result_json, error, now, task_id),
            )
            return self._row(db.execute("SELECT * FROM tasks WHERE task_id=?", (task_id,)).fetchone())

    def cancel(self, task_id: str) -> Task:
        now = self.clock()
        with self._connect() as db:
            row = db.execute("SELECT state FROM tasks WHERE task_id=?", (task_id,)).fetchone()
            if row is None:
                raise TaskNotFound("Task not found")
            if row["state"] in TERMINAL_STATES:
                raise TaskConflict("Task is already terminal")
            if row["state"] == "running":
                raise TaskConflict("Running task cancellation requires worker cooperation")
            db.execute(
                "UPDATE tasks SET state='cancelled',updated_at=?,error='Cancelled by user' WHERE task_id=?",
                (now, task_id),
            )
            return self._row(db.execute("SELECT * FROM tasks WHERE task_id=?", (task_id,)).fetchone())

    def get(self, task_id: str) -> Task:
        now = self.clock()
        with self._connect() as db:
            self._expire(db, now)
            row = db.execute("SELECT * FROM tasks WHERE task_id=?", (task_id,)).fetchone()
            if row is None:
                raise TaskNotFound("Task not found")
            return self._row(row)
