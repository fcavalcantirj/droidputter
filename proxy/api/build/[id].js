// GET /api/build/{request_id} -> {status: queued|building|failed|ready, env, ...} (see lib/builds.js buildStatus)
// env is parsed from the run name (m5cardputer when absent); once ready the parts come from the run's
// <name>-<env> artifact, never from the <name>-<env>-elf one.

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
