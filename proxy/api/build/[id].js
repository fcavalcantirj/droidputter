// GET /api/build/{request_id} -> {status: queued|building|failed|ready, ...} (see lib/builds.js buildStatus)

import { buildStatus } from "../../lib/builds.js";
import { baseUrlOf, error, githubOf, json, param, vercel } from "../../lib/http.js";
import { validateRequestId } from "../../lib/validate.js";

/**
 * @param {import("../../lib/http.js").ProxyRequest} request
 * @param {import("../../lib/http.js").Ctx} ctx
 */
export async function handle(request, ctx) {
  if (request.method !== "GET") return error(405, "method not allowed");
  const requestId = validateRequestId(param(request, "id", 1));
  const r = await buildStatus(githubOf(ctx), requestId, { baseUrl: baseUrlOf(ctx, request) });
  return json(r.status, r.body);
}

export default vercel(handle);
