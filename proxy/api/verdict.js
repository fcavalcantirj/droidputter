// POST /api/verdict {name, env, firmware_sha256, shim_commit?, board, result, note?, date?, reporter?}
//   -> 201 {issue_number, issue_url, request} the GitHub issue verdicts.yml folds into apps/verdicts.json
//    | 400 bad record | 413 body over 4 KB | 429 more than 20 from one address in an hour (Retry-After)
//    | 502 {error: "proxy token lacks Issues: write on <repo>"} or another GitHub failure

import { error, githubOf, json, vercel } from "../lib/http.js";
import { validateVerdict } from "../lib/validate.js";
import { VERDICT_BODY_LIMIT, clientIp, submitVerdict, takeRateSlot } from "../lib/verdicts.js";

/**
 * @param {import("../lib/http.js").ProxyRequest} request
 * @param {import("../lib/http.js").Ctx} ctx
 */
export async function handle(request, ctx) {
  if (request.method !== "POST") return error(405, "method not allowed");
  if (request.bodyBytes > VERDICT_BODY_LIMIT) return error(413, `body larger than ${VERDICT_BODY_LIMIT} bytes`);
  const now = ctx.now || Date.now;
  const slot = takeRateSlot(clientIp(request), now());
  if (!slot.ok) {
    return json(429, { error: "too many verdicts from this address", retry_after_s: slot.retryAfterS }, { "Retry-After": String(slot.retryAfterS) });
  }
  const rec = validateVerdict(request.body, { today: new Date(now()).toISOString().slice(0, 10) });
  const r = await submitVerdict(githubOf(ctx), rec);
  return json(r.status, r.body);
}

export default vercel(handle, { maxBodyBytes: VERDICT_BODY_LIMIT });
