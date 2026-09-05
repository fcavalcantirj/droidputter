// Shared fixtures: a fetch-level fake of the GitHub REST API (no network), run/zip builders, ctx/request
// factories. Everything returns real Response objects so lib/github.js runs unmodified.

import { createHash } from "node:crypto";
import { Readable } from "node:stream";
import { strToU8, zipSync } from "fflate";

export const REPO = "fcavalcantirj/droidputter";
export const API = "https://gh.test";
export const BLOB = "https://blob.test";
export const TOKEN = "ghp_test_token_never_leaks";
export const SHIM_SHA = "abc1234def5678901234567890abcdef12345678";
export const SHIM = SHIM_SHA.slice(0, 7);
export const UPSTREAM = "wisnc/stellar-map";

/** @param {Uint8Array} bytes */
export function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

/** Deterministic pseudo-random bytes so sizes/hashes are stable across the suite. */
export function bytesOf(n, seed) {
  const out = new Uint8Array(n);
  let x = seed >>> 0 || 1;
  for (let i = 0; i < n; i++) {
    x = (x * 1103515245 + 12345) >>> 0;
    out[i] = x >>> 24;
  }
  return out;
}

export const DEFAULT_PARTS = {
  "bootloader.bin": bytesOf(15000, 1),
  "partitions.bin": bytesOf(3072, 2),
  "boot_app0.bin": bytesOf(8192, 3),
  "firmware.bin": bytesOf(150001, 4),
};

export const DEFAULT_BUILD = {
  name: "stellar-map",
  repo: UPSTREAM,
  upstream_commit: "0123456789abcdef0123456789abcdef01234567",
  ok: true,
  ram: "38.5% (126124 B)",
  flash: "33.3% (2267241 B)",
};

/**
 * A zip shaped like actions/upload-artifact@v4 makes of dist/.
 * @param {{build?: object, parts?: Record<string, Uint8Array>, tamperSums?: boolean, omit?: string[], prefix?: string}} [o]
 */
export function makeZip({ build = DEFAULT_BUILD, parts = DEFAULT_PARTS, tamperSums = false, omit = [], prefix = "" } = {}) {
  /** @type {Record<string, Uint8Array>} */
  const entries = {};
  const sumLines = [];
  for (const [name, data] of Object.entries(parts)) {
    if (!omit.includes(name)) entries[prefix + name] = data;
    let h = sha256(data);
    if (tamperSums && name === "firmware.bin") h = h.replace(/^./, h[0] === "0" ? "1" : "0");
    sumLines.push(`${h}  ${name}`);
  }
  entries[prefix + "build.json"] = strToU8(JSON.stringify(build) + "\n");
  entries[prefix + "SHA256SUMS"] = strToU8(sumLines.join("\n") + "\n");
  return zipSync(entries, { level: 1 });
}

/**
 * @param {{id: number, title: string, status?: string, conclusion?: string | null, ageMs?: number, now?: number}} o
 */
export function makeRun({ id, title, status = "completed", conclusion = "success", ageMs = 60_000, now = Date.now() }) {
  const created = new Date(now - ageMs).toISOString();
  return {
    id,
    name: "build-app",
    display_title: title,
    event: "workflow_dispatch",
    status,
    conclusion: status === "completed" ? conclusion : null,
    created_at: created,
    run_started_at: created,
    updated_at: new Date(now - ageMs + 124_000).toISOString(),
    html_url: `https://github.com/${REPO}/actions/runs/${id}`,
  };
}

/** @param {number} id */
export function makeArtifact(id, { name = "stellar-map-m5cardputer", expired = false, size = 180000 } = {}) {
  return { id, name, expired, size_in_bytes: size, expires_at: "2026-09-11T00:00:00Z", archive_download_url: `${API}/repos/${REPO}/actions/artifacts/${id}/zip` };
}

/**
 * @param {{shimSha?: string, runs?: any[], runsPage2?: any[], artifacts?: Record<string, any[]>, zips?: Record<string, Uint8Array>, failCommits?: boolean, issueStatuses?: number[]}} [o]
 *   issueStatuses: what successive POST /issues answer (201 once the list is used up)
 */
export function fakeGitHub({ shimSha = SHIM_SHA, runs = [], runsPage2 = [], artifacts = {}, zips = {}, failCommits = false, issueStatuses = [] } = {}) {
  /** @type {{method: string, url: string, headers: Record<string, string>, body?: string}[]} */
  const calls = [];
  /** @type {any[]} */
  const dispatches = [];
  /** @type {{url: string, hasAuth: boolean}[]} */
  const blobHits = [];
  /** @type {{title: string, body: string, labels?: string[]}[]} */
  const issues = [];
  const pendingIssueStatuses = [...issueStatuses];
  const ISSUE_MESSAGES = { 403: "Resource not accessible by personal access token", 404: "Not Found", 422: "Validation Failed" };

  const jsonRes = (status, body) => new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

  /** @type {typeof fetch} */
  const fetchImpl = async (input, init = {}) => {
    const url = String(input);
    const method = (init.method || "GET").toUpperCase();
    /** @type {Record<string, string>} */
    const headers = {};
    for (const [k, v] of Object.entries(init.headers || {})) headers[k.toLowerCase()] = String(v);
    calls.push({ method, url, headers, body: typeof init.body === "string" ? init.body : undefined });
    const u = new URL(url);

    if (u.origin === BLOB) {
      blobHits.push({ url, hasAuth: "authorization" in headers });
      const id = u.pathname.split("/").pop() || "";
      const zip = zips[id];
      return zip ? new Response(zip, { status: 200, headers: { "content-type": "application/zip" } }) : new Response("gone", { status: 404 });
    }
    if (u.origin !== API || headers.authorization !== `Bearer ${TOKEN}`) return jsonRes(401, { message: "Bad credentials" });

    const p = u.pathname;
    const base = `/repos/${REPO}`;
    if (p === `${base}/commits`) {
      if (failCommits) return jsonRes(500, { message: "boom" });
      return jsonRes(200, [{ sha: shimSha }]);
    }
    if (p === `${base}/actions/workflows/build-app.yml/runs`) {
      const page = Number(u.searchParams.get("page") || "1");
      const list = page === 2 ? runsPage2 : page === 1 ? runs : [];
      return jsonRes(200, { total_count: runs.length + runsPage2.length, workflow_runs: list });
    }
    if (p === `${base}/actions/workflows/build-app.yml/dispatches` && method === "POST") {
      dispatches.push(JSON.parse(init.body));
      return new Response(null, { status: 204 });
    }
    if (p === `${base}/issues` && method === "POST") {
      const issue = JSON.parse(init.body);
      issues.push(issue);
      const status = pendingIssueStatuses.length ? pendingIssueStatuses.shift() : 201;
      if (status !== 201) return jsonRes(status, { message: ISSUE_MESSAGES[status] || "boom" });
      const number = 100 + issues.length;
      return jsonRes(201, { number, html_url: `https://github.com/${REPO}/issues/${number}`, title: issue.title, labels: (issue.labels || []).map((name) => ({ name })) });
    }
    let m = /^\/repos\/[^/]+\/[^/]+\/actions\/runs\/(\d+)\/artifacts$/.exec(p);
    if (m) return jsonRes(200, { total_count: (artifacts[m[1]] || []).length, artifacts: artifacts[m[1]] || [] });
    m = /^\/repos\/[^/]+\/[^/]+\/actions\/artifacts\/(\d+)\/zip$/.exec(p);
    if (m) return new Response(null, { status: 302, headers: { location: `${BLOB}/zip/${m[1]}` } });
    return jsonRes(404, { message: "Not Found" });
  };

  return { fetch: fetchImpl, calls, dispatches, blobHits, issues };
}

/**
 * @param {ReturnType<typeof fakeGitHub>} fake
 * @param {{token?: string, baseUrl?: string, now?: () => number}} [o]
 */
export function ctxWith(fake, { token = TOKEN, baseUrl = "https://proxy.test", now } = {}) {
  return { config: { token, repo: REPO, workflow: "build-app.yml", baseUrl, apiBase: API }, fetch: fake.fetch, now };
}

/**
 * @param {{method?: string, path?: string, body?: unknown, params?: Record<string, string>, headers?: Record<string, string>, remoteAddress?: string, bodyBytes?: number}} [o]
 *   bodyBytes defaults to the size of the body as JSON
 */
export function req({ method = "GET", path = "/", body, params = {}, headers = {}, remoteAddress = "198.51.100.7", bodyBytes } = {}) {
  const bytes = bodyBytes !== undefined ? bodyBytes : body === undefined ? 0 : Buffer.byteLength(JSON.stringify(body));
  return { method, url: new URL(path, "https://proxy.test"), headers: { host: "proxy.test", ...headers }, params, body, bodyBytes: bytes, remoteAddress };
}

/** @param {{body: string | Uint8Array}} res */
export function parse(res) {
  return JSON.parse(typeof res.body === "string" ? res.body : Buffer.from(res.body).toString("utf8"));
}

/**
 * Drive the real Vercel-style (req, res) adapter with a streamed body.
 * @param {(req: any, res: any) => Promise<void>} handler
 * @param {{method: string, url: string, body?: string, headers?: Record<string, string>, remoteAddress?: string}} o
 */
export async function invoke(handler, { method, url, body, headers = {}, remoteAddress = "198.51.100.7" }) {
  const chunks = body === undefined ? [] : [Buffer.from(body)];
  const request = Object.assign(Readable.from(chunks), { method, url, headers: { host: "proxy.test", ...headers }, socket: { remoteAddress } });
  return new Promise((resolve) => {
    const out = { status: 0, headers: {}, body: /** @type {any} */ (undefined) };
    const response = {
      writeHead(status, headers) {
        out.status = status;
        out.headers = headers;
      },
      end(payload) {
        out.body = payload;
        resolve(out);
      },
    };
    handler(request, response);
  });
}
