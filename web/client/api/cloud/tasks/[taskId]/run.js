import { authenticate, getDatabase, sendJson, setCors, ensureCloudSchema, CloudTaskError } from "../../../../lib/cloudTaskStore.js";
import { runCloudTask } from "../../../../lib/cloudTaskExecutor.js";

export default async function handler(req, res) {
  setCors(req, res);
  res.setHeader("Access-Control-Allow-Methods", "POST, OPTIONS");
  if (req.method === "OPTIONS") { res.statusCode = 204; return res.end(); }
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST, OPTIONS");
    return sendJson(res, 405, { ok: false, error: "Method not allowed" });
  }
  try {
    const owner = authenticate(req);
    const sql = getDatabase();
    await ensureCloudSchema(sql);
    const row = await runCloudTask(sql, owner, req.query?.taskId);
    return sendJson(res, 200, { ok: true, task: {
      task_id: row.task_id, owner: row.owner, operation: row.operation,
      payload_sha256: row.payload_sha256, state: row.state, result: row.result,
      error: row.error, updated_at: new Date(row.updated_at).getTime() / 1000,
    } });
  } catch (error) {
    const status = Number.isInteger(error?.status) ? error.status : 500;
    return sendJson(res, status, { ok: false, error: status === 500 ? "Cloud task execution is temporarily unavailable" : error.message });
  }
}
