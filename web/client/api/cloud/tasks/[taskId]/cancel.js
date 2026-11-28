import { handleCloud } from "../../../../lib/cloudTaskStore.js";
export default function handler(req, res) { return handleCloud(req, res, "cancel"); }
