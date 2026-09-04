// Build orchestration against build-app.yml: resolve the shim commit, reuse a fresh successful run or
// dispatch a new one, and map a run to the v1 status object the phone polls.
//
// Correlation is by the run's display_title, which GitHub sets to the workflow's run-name:
//   build <repo>@<ref|HEAD> shim=<shim|?> req=<request_id|->

import { randomUUID } from "node:crypto";
import { buildSummary, loadArtifact, partsOf } from "./artifact.js";

export const IN_FLIGHT_LIMIT = 6;
export const CACHE_MAX_AGE_MS = 24 * 60 * 60 * 1000;
export const SHIM_TTL_MS = 60 * 1000;
export const IN_FLIGHT_STATUSES = new Set(["queued", "in_progress", "waiting", "pending", "requested"]);

/** @param {{repo: string, ref?: string, shim?: string, requestId?: string}} p */
export function runTitle({ repo, ref, shim, requestId }) {
  return `build ${repo}@${ref || "HEAD"} shim=${shim || "?"} req=${requestId || "-"}`;
}

/** Prefix every run of this (repo, ref, shim) shares, trailing space included so shim=abc1234 != abc12345. */
export function titlePrefix({ repo, ref, shim }) {
  return `build ${repo}@${ref || "HEAD"} shim=${shim} `;
}

/** @param {unknown} title @returns {string | null} the req= value, null when absent or the manual '-' */
export function requestIdOf(title) {
  const m = typeof title === "string" ? /(?:^|\s)req=(\S+)\s*$/.exec(title) : null;
  return m && m[1] !== "-" ? m[1] : null;
}

/** @param {any} run */
export function titleOf(run) {
  return typeof run.display_title === "string" ? run.display_title : "";
}

/** @param {any[]} runs */
export function countInFlight(runs) {
  return runs.filter((r) => IN_FLIGHT_STATUSES.has(r.status)).length;
}

/** @type {Map<string, {sha: string, at: number}>} keyed by repo */
const shimCache = new Map();

export function _resetShimCache() {
  shimCache.clear();
}

/**
 * Short sha of the newest commit touching shim/ on main, cached 60 s per repo.
 * @param {import("./github.js").GitHub} gh
 * @param {() => number} [now]
 */
export async function resolveShimCommit(gh, now = Date.now) {
  const hit = shimCache.get(gh.repo);
  const t = now();
  if (hit && t - hit.at < SHIM_TTL_MS) return hit.sha;
  const sha = await gh.latestShimCommit();
  shimCache.set(gh.repo, { sha, at: t });
  return sha;
}

/** @typedef {{status: number, body: Record<string, unknown>}} Result */

/**
 * POST /api/build.
 * @param {import("./github.js").GitHub} gh
 * @param {{repo: string, ref: string, name: string}} input validated
 * @param {{now?: () => number, uuid?: () => string}} [opts]
 * @returns {Promise<Result>}
 */
export async function createBuild(gh, input, { now = Date.now, uuid = randomUUID } = {}) {
  const shim = await resolveShimCommit(gh, now);
  const runs = await gh.listRuns();
  const prefix = titlePrefix({ repo: input.repo, ref: input.ref, shim });
  const same = runs.filter((r) => titleOf(r).startsWith(prefix) && requestIdOf(titleOf(r)));
  const echo = { repo: input.repo, ref: input.ref, name: input.name, shim_commit: shim };

  const fresh = same.find(
    (r) => r.status === "completed" && r.conclusion === "success" && now() - Date.parse(r.created_at) < CACHE_MAX_AGE_MS,
  );
  if (fresh) {
    return { status: 200, body: { request_id: requestIdOf(titleOf(fresh)), ...echo, cached: true, run_id: String(fresh.id) } };
  }

  // The same build is already queued or running (a retried POST, a second phone): join it instead of
  // burning another runner. Same 202 shape as a fresh dispatch, plus the run it joined.
  const running = same.find((r) => IN_FLIGHT_STATUSES.has(r.status));
  if (running) {
    return { status: 202, body: { request_id: requestIdOf(titleOf(running)), ...echo, cached: false, run_id: String(running.id) } };
  }

  if (countInFlight(runs) >= IN_FLIGHT_LIMIT) {
    return { status: 429, body: { error: "too many builds in flight", retry_after_s: 60 } };
  }

  const requestId = uuid();
  await gh.dispatch({ repo: input.repo, name: input.name, ref: input.ref, request_id: requestId, shim });
  return { status: 202, body: { request_id: requestId, ...echo, cached: false } };
}

/**
 * The run whose title carries req=<requestId>, searching the two newest pages (100 runs).
 * @param {import("./github.js").GitHub} gh
 * @param {string} requestId
 */
export async function findRunByRequestId(gh, requestId) {
  for (const page of [1, 2]) {
    const runs = await gh.listRuns({ page });
    const hit = runs.find((r) => requestIdOf(titleOf(r)) === requestId);
    if (hit) return hit;
    if (runs.length < 50) break;
  }
  return null;
}

/** @param {any} run @returns {"queued" | "building" | "failed" | "ready"} */
export function stateOf(run) {
  if (run.status === "completed") return run.conclusion === "success" ? "ready" : "failed";
  if (run.status === "in_progress") return "building";
  return "queued";
}

/**
 * The shape the phone polls, minus the artifact part (see buildStatus).
 * @param {any} run
 */
export function describeRun(run) {
  const state = stateOf(run);
  const base = {
    request_id: requestIdOf(titleOf(run)),
    status: state,
    run_id: String(run.id),
    run_url: run.html_url,
    title: titleOf(run),
    created_at: run.created_at,
  };
  if (state === "queued" || state === "building") return { ...base, started_at: run.run_started_at || run.created_at };
  if (state === "failed") return { ...base, conclusion: run.conclusion };
  return { ...base, completed_at: run.updated_at };
}

/**
 * GET /api/build/{request_id}.
 * @param {import("./github.js").GitHub} gh
 * @param {string} requestId
 * @param {{baseUrl: string}} where
 * @returns {Promise<Result>}
 */
export async function buildStatus(gh, requestId, { baseUrl }) {
  const run = await findRunByRequestId(gh, requestId);
  if (!run) return { status: 200, body: { request_id: requestId, status: "queued" } };
  const desc = describeRun(run);
  if (desc.status !== "ready") return { status: 200, body: { ...desc, request_id: requestId } };
  const parsed = await loadArtifact(gh, desc.run_id);
  return {
    status: 200,
    body: {
      ...desc,
      request_id: requestId,
      build: buildSummary(parsed),
      parts: partsOf(parsed, { runId: desc.run_id, baseUrl }),
    },
  };
}

/**
 * GET /api/build: the newest page of runs, described.
 * @param {import("./github.js").GitHub} gh
 * @returns {Promise<Result>}
 */
export async function listBuilds(gh) {
  const runs = await gh.listRuns();
  return { status: 200, body: { builds_in_flight: countInFlight(runs), builds: runs.map(describeRun) } };
}
