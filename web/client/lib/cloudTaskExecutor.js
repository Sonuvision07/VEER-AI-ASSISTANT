import { CloudTaskError } from "./cloudTaskStore.js";

const TASK_ID = /^[A-Za-z0-9_-]{16,64}$/;
const MAX_FILE_BYTES = 8 * 1024;
const MAX_PATH_LENGTH = 180;

export function validateWorkspacePath(value) {
  if (typeof value !== "string" || value.length < 1 || value.length > MAX_PATH_LENGTH) {
    throw new CloudTaskError("A valid workspace path is required");
  }
  if (value.startsWith("/") || value.includes("\\") || value.includes("\0") ||
      /^[A-Za-z]:/.test(value) || value.split("/").some((part) => !part || part === "." || part === ".." || !/^[A-Za-z0-9._-]+$/.test(part))) {
    throw new CloudTaskError("Workspace path must be a safe relative path");
  }
  return value;
}

export function validateHttpsUrl(value) {
  let parsed;
  try { parsed = new URL(value); } catch { throw new CloudTaskError("A valid HTTPS URL is required"); }
  if (parsed.protocol !== "https:" || parsed.username || parsed.password || parsed.href.length > 2048) {
    throw new CloudTaskError("Only credential-free HTTPS URLs are allowed");
  }
  return parsed.href;
}

export async function executeCloudOperation(task, workspace) {
  if (!task || !["health", "list_workspace", "create_text_file", "open_url"].includes(task.operation)) {
    throw new CloudTaskError("Unsupported cloud operation");
  }
  const payload = task.payload && typeof task.payload === "object" && !Array.isArray(task.payload) ? task.payload : {};
  switch (task.operation) {
    case "health":
      return { ok: true, executor: "vercel-bounded", workspace: "isolated-cloud" };
    case "list_workspace":
      return { files: await workspace.listFiles() };
    case "create_text_file": {
      const path = validateWorkspacePath(payload.path);
      if (typeof payload.content !== "string" || Buffer.byteLength(payload.content, "utf8") > MAX_FILE_BYTES) {
        throw new CloudTaskError("Text file content must be a string up to 8 KiB");
      }
      const saved = await workspace.createFile(path, payload.content);
      return { created: true, path: saved.path, bytes: Buffer.byteLength(payload.content, "utf8") };
    }
    case "open_url":
      return { action: "open_url", url: validateHttpsUrl(payload.url), requiresClientLaunch: true };
    default:
      throw new CloudTaskError("Unsupported cloud operation");
  }
}

export async function runCloudTask(sql, owner, taskId) {
  if (typeof taskId !== "string" || !TASK_ID.test(taskId)) throw new CloudTaskError("Invalid task ID");
  await sql`CREATE TABLE IF NOT EXISTS veer_cloud_workspace_files (
    owner TEXT NOT NULL, path TEXT NOT NULL, content TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), PRIMARY KEY(owner,path)
  )`;
  await sql`
    UPDATE veer_cloud_tasks SET state='expired',updated_at=NOW(),error='Task expired'
    WHERE task_id=${taskId} AND owner=${owner}
      AND state IN ('pending_approval','queued') AND expires_at<=NOW()
  `;
  const claimed = await sql`
    UPDATE veer_cloud_tasks SET state='running',updated_at=NOW()
    WHERE task_id=${taskId} AND owner=${owner} AND state='queued'
      AND expires_at>NOW()
      AND (operation NOT IN ('create_text_file','open_url') OR approval_consumed=TRUE)
    RETURNING *
  `;
  if (!claimed.length) {
    const rows = await sql`SELECT state FROM veer_cloud_tasks WHERE task_id=${taskId} AND owner=${owner} LIMIT 1`;
    if (!rows.length) throw new CloudTaskError("Task not found", 404);
    throw new CloudTaskError("Task is not queued, approved, or has expired", 409);
  }
  const row = claimed[0];
  try {
    const result = await executeCloudOperation(
      { operation: row.operation, payload: row.payload },
      {
        async listFiles() {
          const rows = await sql`SELECT path,octet_length(content) AS bytes,created_at FROM veer_cloud_workspace_files WHERE owner=${owner} ORDER BY path LIMIT 200`;
          return rows.map((file) => ({ path: file.path, bytes: Number(file.bytes), created_at: new Date(file.created_at).toISOString() }));
        },
        async createFile(path, content) {
          const rows = await sql`INSERT INTO veer_cloud_workspace_files(owner,path,content) VALUES (${owner},${path},${content}) ON CONFLICT(owner,path) DO NOTHING RETURNING path`;
          if (!rows.length) throw new CloudTaskError("A file already exists at this workspace path", 409);
          return rows[0];
        },
      },
    );
    const finished = await sql`UPDATE veer_cloud_tasks SET state='completed',result=${JSON.stringify(result)}::jsonb,error=NULL,updated_at=NOW() WHERE task_id=${taskId} AND owner=${owner} AND state='running' RETURNING *`;
    if (!finished.length) throw new CloudTaskError("Task completion could not be committed", 409);
    return finished[0];
  } catch (error) {
    const safeMessage = error instanceof CloudTaskError ? error.message.slice(0, 240) : "Cloud task execution failed";
    await sql`UPDATE veer_cloud_tasks SET state='failed',error=${safeMessage},updated_at=NOW() WHERE task_id=${taskId} AND owner=${owner} AND state='running'`;
    throw error;
  }
}
