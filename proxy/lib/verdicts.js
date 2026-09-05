// Community verdicts: the phone POSTs a Verdict record and the proxy files the GitHub issue that
// .github/workflows/verdicts.yml folds into apps/verdicts.json (label `verdict`, title "[verdict] ...", body
// = a ```json block). The proxy's PAT is the issue author; the phone never leaves the app (Felipe, 2026-09-04:
// "the button - works - should be JUST CLICK, system handles").

import { GitHubError } from "./github.js";

export const VERDICT_LABEL = "verdict";
export const VERDICT_RATE_LIMIT = 20;
export const VERDICT_RATE_WINDOW_MS = 60 * 60 * 1000;
export const VERDICT_BODY_LIMIT = 4 * 1024;
const RATE_SWEEP_AT = 10_000;

/** @param {import("./validate.js").VerdictRecord} rec */
export function issueTitle(rec) {
  return `[verdict] ${rec.name}/${rec.env} ${rec.result} on ${rec.board} (${rec.firmware_sha256.slice(0, 12)})`;
}

/** @param {import("./validate.js").VerdictRecord} rec keys already in verdicts.json order (validateVerdict) */
export function issueBody(rec) {
  return `Reported from the Droidputter app via the build proxy.\n\n\`\`\`json\n${JSON.stringify(rec, null, 2)}\n\`\`\`\n`;
}

/**
 * Who is posting: the first X-Forwarded-For hop (Vercel's edge writes the client there), else the socket.
 * @param {import("./http.js").ProxyRequest} request
 */
export function clientIp(request) {
  const first = (request.headers["x-forwarded-for"] || "").split(",")[0].trim();
  return first || request.remoteAddress || "unknown";
}

/** @type {Map<string, number[]>} timestamps of the POSTs each address was allowed, oldest first */
const slots = new Map();

export function _resetRateLimit() {
  slots.clear();
}

/**
 * Sliding window of VERDICT_RATE_LIMIT POSTs per address per hour, counted before validation so garbage
 * costs the sender the same as a verdict. A refused call is not counted. Memory is per warm instance.
 * @param {string} ip
 * @param {number} t now, ms
 * @returns {{ok: true} | {ok: false, retryAfterS: number}}
 */
export function takeRateSlot(ip, t) {
  if (slots.size >= RATE_SWEEP_AT) {
    for (const [k, v] of slots) if (t - v[v.length - 1] >= VERDICT_RATE_WINDOW_MS) slots.delete(k);
  }
  const recent = (slots.get(ip) || []).filter((at) => t - at < VERDICT_RATE_WINDOW_MS);
  if (recent.length >= VERDICT_RATE_LIMIT) {
    slots.set(ip, recent);
    return { ok: false, retryAfterS: Math.max(1, Math.ceil((recent[0] + VERDICT_RATE_WINDOW_MS - t) / 1000)) };
  }
  recent.push(t);
  slots.set(ip, recent);
  return { ok: true };
}

/**
 * 403/404 is what GitHub answers a fine-grained PAT without Issues: write (404 hides the repo from it);
 * a 422 that survives the label-less retry is treated the same, since the title and body are ours.
 * @param {import("./github.js").GitHub} gh
 * @param {unknown} e
 * @returns {import("./builds.js").Result}
 */
function upstreamFailure(gh, e) {
  if (!(e instanceof GitHubError)) throw e;
  const lacksWrite = e.upstream === 403 || e.upstream === 404 || e.upstream === 422;
  return { status: 502, body: { error: lacksWrite ? `proxy token lacks Issues: write on ${gh.repo}` : e.message } };
}

/**
 * POST /api/verdict: file the issue with the `verdict` label; on 422 (label refused) once more without
 * labels, since verdicts.yml also matches the "[verdict]" title prefix.
 * @param {import("./github.js").GitHub} gh
 * @param {import("./validate.js").VerdictRecord} rec validated
 * @returns {Promise<import("./builds.js").Result>}
 */
export async function submitVerdict(gh, rec) {
  const title = issueTitle(rec);
  const body = issueBody(rec);
  let issue;
  try {
    issue = await gh.createIssue({ title, body, labels: [VERDICT_LABEL] });
  } catch (e) {
    if (!(e instanceof GitHubError) || e.upstream !== 422) return upstreamFailure(gh, e);
    try {
      issue = await gh.createIssue({ title, body });
    } catch (e2) {
      return upstreamFailure(gh, e2);
    }
  }
  return { status: 201, body: { issue_number: issue.number, issue_url: issue.html_url, request: rec } };
}
