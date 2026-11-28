"""SQLite durable-store adapter for the deployment-independent VEER cloud contract.

This is a local/test adapter, not a production cloud database. A deployed
service must provide encrypted storage, backups, tenant isolation and auth.
"""
from __future__ import annotations

import hmac
import json
import sqlite3
import time
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Mapping

from veer_cloud_worker import CloudTaskError, CloudTaskRequest, SIDE_EFFECT_OPERATIONS


class SQLiteCloudTaskStore:
    """Transactional task persistence with owner scoping and one-shot approval."""

    def __init__(self, database: str | Path, *, ttl_seconds: int = 900, clock=time.time):
        if ttl_seconds < 1:
            raise ValueError("Task TTL must be positive")
        self.database = str(database)
        self.ttl_seconds = ttl_seconds
        self.clock = clock
        Path(self.database).parent.mkdir(parents=True, exist_ok=True)
        with self._connect() as db:
            db.execute("""
                CREATE TABLE IF NOT EXISTS cloud_tasks (
                    task_id TEXT PRIMARY KEY,
                    idempotency_key TEXT NOT NULL UNIQUE,
                    owner TEXT NOT NULL,
                    operation TEXT NOT NULL,
                    payload_json TEXT NOT NULL,
                    payload_sha256 TEXT NOT NULL,
                    state TEXT NOT NULL,
                    approval_consumed INTEGER NOT NULL DEFAULT 0,
                    result_json TEXT,
                    error TEXT,
                    created_at REAL NOT NULL,
                    updated_at REAL NOT NULL,
                    expires_at REAL NOT NULL
                )
            """)
            db.execute("CREATE INDEX IF NOT EXISTS cloud_tasks_state_created ON cloud_tasks(state, created_at)")

    @contextmanager
    def _connect(self):
        db = sqlite3.connect(self.database, timeout=10, isolation_level="IMMEDIATE")
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA busy_timeout=10000")
        try:
            yield db
            db.commit()
        except Exception:
            db.rollback()
            raise
        finally:
            db.close()

    @staticmethod
    def _dict(row: sqlite3.Row | None) -> dict[str, Any] | None:
        if row is None:
            return None
        item = dict(row)
        item["payload"] = json.loads(item.pop("payload_json"))
        item["result"] = json.loads(item.pop("result_json")) if item.get("result_json") else None
        item.pop("result_json", None)
        item["approval_consumed"] = bool(item["approval_consumed"])
        return item

    def _expire(self, db: sqlite3.Connection, now: float) -> None:
        db.execute(
            "UPDATE cloud_tasks SET state='expired',updated_at=?,error='Task expired' "
            "WHERE state IN ('pending_approval','queued') AND expires_at<=?",
            (now, now),
        )

    def get_by_idempotency_key(self, key: str) -> Mapping[str, Any] | None:
        with self._connect() as db:
            self._expire(db, self.clock())
            row = db.execute("SELECT * FROM cloud_tasks WHERE idempotency_key=?", (key,)).fetchone()
            return self._dict(row)

    def save_pending(self, task: CloudTaskRequest, owner: str) -> Mapping[str, Any]:
        if not isinstance(owner, str) or not owner.strip() or len(owner) > 256:
            raise CloudTaskError("Invalid task owner")
        now = self.clock()
        state = "pending_approval" if task.operation in SIDE_EFFECT_OPERATIONS else "queued"
        payload_json = json.dumps(task.payload, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
        with self._connect() as db:
            self._expire(db, now)
            existing = db.execute("SELECT * FROM cloud_tasks WHERE idempotency_key=?", (task.idempotency_key,)).fetchone()
            if existing:
                current = self._dict(existing)
                if current["owner"] != owner or current["payload_sha256"] != task.payload_sha256:
                    raise CloudTaskError("Idempotency key is already bound to another task")
                return current
            db.execute(
                """INSERT INTO cloud_tasks
                (task_id,idempotency_key,owner,operation,payload_json,payload_sha256,state,
                 created_at,updated_at,expires_at)
                VALUES (?,?,?,?,?,?,?,?,?,?)""",
                (task.task_id, task.idempotency_key, owner, task.operation, payload_json,
                 task.payload_sha256, state, now, now, now + self.ttl_seconds),
            )
            return self._dict(db.execute("SELECT * FROM cloud_tasks WHERE task_id=?", (task.task_id,)).fetchone())

    def approve(self, task_id: str, owner: str, payload_sha256: str) -> Mapping[str, Any]:
        now = self.clock()
        with self._connect() as db:
            self._expire(db, now)
            row = db.execute("SELECT * FROM cloud_tasks WHERE task_id=?", (task_id,)).fetchone()
            if row is None or row["owner"] != owner:
                raise CloudTaskError("Task not found")
            if row["state"] != "pending_approval" or row["approval_consumed"]:
                raise CloudTaskError("Task is not awaiting approval")
            if not isinstance(payload_sha256, str) or not hmac.compare_digest(row["payload_sha256"], payload_sha256.lower()):
                raise CloudTaskError("Approval does not match exact task payload")
            db.execute(
                "UPDATE cloud_tasks SET state='queued',approval_consumed=1,updated_at=? WHERE task_id=?",
                (now, task_id),
            )
            return self._dict(db.execute("SELECT * FROM cloud_tasks WHERE task_id=?", (task_id,)).fetchone())

    def claim_next(self) -> Mapping[str, Any] | None:
        now = self.clock()
        with self._connect() as db:
            self._expire(db, now)
            row = db.execute(
                "SELECT * FROM cloud_tasks WHERE state='queued' ORDER BY created_at,task_id LIMIT 1"
            ).fetchone()
            if row is None:
                return None
            db.execute("UPDATE cloud_tasks SET state='running',updated_at=? WHERE task_id=?", (now, row["task_id"]))
            return self._dict(db.execute("SELECT * FROM cloud_tasks WHERE task_id=?", (row["task_id"],)).fetchone())

    def finish(self, task_id: str, *, result: Mapping[str, Any] | None = None, error: str | None = None) -> Mapping[str, Any]:
        if (result is None) == (error is None):
            raise CloudTaskError("Provide exactly one of result or error")
        result_json = json.dumps(result, sort_keys=True, separators=(",", ":"), ensure_ascii=False) if result is not None else None
        if result_json and len(result_json.encode("utf-8")) > 16 * 1024:
            raise CloudTaskError("Task result is too large")
        now = self.clock()
        with self._connect() as db:
            row = db.execute("SELECT state FROM cloud_tasks WHERE task_id=?", (task_id,)).fetchone()
            if row is None:
                raise CloudTaskError("Task not found")
            if row["state"] != "running":
                raise CloudTaskError("Only a running task can finish")
            db.execute(
                "UPDATE cloud_tasks SET state=?,result_json=?,error=?,updated_at=? WHERE task_id=?",
                ("failed" if error is not None else "completed", result_json, str(error)[:500] if error is not None else None, now, task_id),
            )
            return self._dict(db.execute("SELECT * FROM cloud_tasks WHERE task_id=?", (task_id,)).fetchone())

    def cancel(self, task_id: str, owner: str) -> Mapping[str, Any]:
        now = self.clock()
        with self._connect() as db:
            row = db.execute("SELECT * FROM cloud_tasks WHERE task_id=?", (task_id,)).fetchone()
            if row is None or row["owner"] != owner:
                raise CloudTaskError("Task not found")
            if row["state"] not in ("pending_approval", "queued"):
                raise CloudTaskError("Task cannot be cancelled in its current state")
            db.execute(
                "UPDATE cloud_tasks SET state='cancelled',error='Cancelled by owner',updated_at=? WHERE task_id=?",
                (now, task_id),
            )
            return self._dict(db.execute("SELECT * FROM cloud_tasks WHERE task_id=?", (task_id,)).fetchone())

    def get(self, task_id: str, owner: str) -> Mapping[str, Any]:
        with self._connect() as db:
            self._expire(db, self.clock())
            row = db.execute("SELECT * FROM cloud_tasks WHERE task_id=?", (task_id,)).fetchone()
            if row is None or row["owner"] != owner:
                raise CloudTaskError("Task not found")
            return self._dict(row)
