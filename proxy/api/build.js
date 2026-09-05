// POST /api/build {repo, ref?, name?, env?} -> 200 cached | 202 dispatched | 429 too many in flight
//   env: m5cardputer (Cardputer ADV, default) | m5cardputer-virtual (bare ESP32-S3, phone-only); part of the
//   build's identity (cache hit / in-flight join) and echoed in the body.
// GET  /api/build                          -> the newest page of build runs, described (each with its env)

import { createBuild, listBuilds } from "../lib/builds.js";
import { error, githubOf, json, vercel } from "../lib/http.js";
import { validateBuildRequest } from "../lib/validate.js";

/**
 * @param {import("../lib/http.js").ProxyRequest} request
 * @param {import("../lib/http.js").Ctx} ctx
 */
export async function handle(request, ctx) {
  if (request.method === "POST") {
    const input = validateBuildRequest(request.body);
    const gh = githubOf(ctx);
    const r = await createBuild(gh, input, { now: ctx.now });
    return json(r.status, r.body, r.status === 429 ? { "Retry-After": "60" } : {});
  }
  if (request.method === "GET") {
    const r = await listBuilds(githubOf(ctx));
    return json(r.status, r.body);
  }
  return error(405, "method not allowed");
}

export default vercel(handle);
