// Framework-free HTTP plumbing: handlers are pure functions (request, ctx) -> {status, headers, body};
// `vercel()` adapts one to the Node (req, res) signature Vercel functions use. CORS on everything, JSON
// everywhere except artifact bytes, errors always {error}.

import { loadConfig } from "./config.js";
import { createGitHub } from "./github.js";

export const CORS = Object.freeze({
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type",
  "Access-Control-Max-Age": "86400",
});

/**
 * @typedef {object} ProxyRequest
 * @property {string} method
 * @property {URL} url
 * @property {Record<string, string>} headers lower-cased names
 * @property {Record<string, string>} params route params (Vercel's req.query merged with the URL's search)
 * @property {unknown} body parsed JSON body (undefined when empty)
 *
 * @typedef {object} ProxyResponse
 * @property {number} status
 * @property {Record<string, string>} headers
 * @property {string | Uint8Array} body
 *
 * @typedef {object} Ctx
 * @property {import("./config.js").ProxyConfig} config
 * @property {typeof fetch} fetch
 * @property {() => number} [now]
 */

/**
 * @param {number} status
 * @param {unknown} body
 * @param {Record<string, string>} [headers]
 * @returns {ProxyResponse}
 */
export function json(status, body, headers = {}) {
  return {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store", ...headers },
    body: JSON.stringify(body),
  };
}

/** @param {number} status @param {string} message */
export function error(status, message) {
  return json(status, { error: message });
}

/**
 * @param {number} status
 * @param {Uint8Array} bytes
 * @param {Record<string, string>} [headers]
 * @returns {ProxyResponse}
 */
export function octets(status, bytes, headers = {}) {
  return {
    status,
    headers: { "Content-Type": "application/octet-stream", "Content-Length": String(bytes.byteLength), ...headers },
    body: Buffer.from(bytes.buffer, bytes.byteOffset, bytes.byteLength),
  };
}

/** Errors thrown by validate/github/artifact carry .status; anything else is a 500 with a generic message. */
export function fromError(e) {
  const status = e && typeof e.status === "number" ? e.status : 500;
  const message = status === 500 ? `proxy: ${e instanceof Error ? e.message : String(e)}` : String(e.message);
  return error(status, message);
}

/**
 * GitHub client for this request, or the 500 the contract demands when the token is missing.
 * @param {Ctx} ctx
 * @returns {import("./github.js").GitHub}
 */
export function githubOf(ctx) {
  if (!ctx.config.token) {
    const e = new Error("GITHUB_TOKEN is not set on the proxy (fine-grained PAT: Actions read+write, Contents read)");
    /** @type {any} */ (e).status = 500;
    throw e;
  }
  return createGitHub({ ...ctx.config, fetch: ctx.fetch });
}

/**
 * Public origin for part URLs: BASE_URL, else the request's forwarded host.
 * @param {Ctx} ctx
 * @param {ProxyRequest} request
 */
export function baseUrlOf(ctx, request) {
  if (ctx.config.baseUrl) return ctx.config.baseUrl;
  const host = request.headers["x-forwarded-host"] || request.headers.host || "localhost";
  const proto = request.headers["x-forwarded-proto"] || (host.startsWith("localhost") ? "http" : "https");
  return `${proto}://${host}`;
}

/**
 * Route param by name: Vercel's req.query first, then the URL search, then the path segment at `index`
 * counted from the end (so `/api/build/<id>` works with or without the vercel.json rewrite).
 * @param {ProxyRequest} request
 * @param {string} name
 * @param {number} fromEnd 1 = last segment, 2 = the one before
 */
export function param(request, name, fromEnd) {
  const v = request.params[name];
  if (typeof v === "string" && v !== "" && v !== `[${name}]`) return v;
  const segs = request.url.pathname.split("/").filter(Boolean);
  const seg = segs[segs.length - fromEnd];
  return seg && seg !== `[${name}]` ? decodeURIComponent(seg) : "";
}

/** @param {import("node:http").IncomingMessage & {query?: any, body?: any}} req */
async function readBody(req) {
  if (req.body !== undefined) {
    if (typeof req.body === "string") return req.body === "" ? undefined : JSON.parse(req.body);
    if (Buffer.isBuffer(req.body)) return req.body.length === 0 ? undefined : JSON.parse(req.body.toString("utf8"));
    return req.body;
  }
  const chunks = [];
  for await (const chunk of req) chunks.push(typeof chunk === "string" ? Buffer.from(chunk) : chunk);
  const text = Buffer.concat(chunks).toString("utf8");
  return text.trim() === "" ? undefined : JSON.parse(text);
}

/**
 * @param {import("node:http").IncomingMessage & {query?: any, body?: any}} req
 * @returns {Promise<ProxyRequest>}
 */
export async function toRequest(req) {
  /** @type {Record<string, string>} */
  const headers = {};
  for (const [k, v] of Object.entries(req.headers || {})) headers[k.toLowerCase()] = Array.isArray(v) ? v[0] : String(v ?? "");
  const url = new URL(req.url || "/", `http://${headers.host || "localhost"}`);
  /** @type {Record<string, string>} */
  const params = {};
  for (const [k, v] of url.searchParams) params[k] = v;
  for (const [k, v] of Object.entries(req.query || {})) if (typeof v === "string") params[k] = v;
  let body;
  if (req.method === "POST" || req.method === "PUT") {
    try {
      body = await readBody(req);
    } catch {
      const e = new Error("body is not valid JSON");
      /** @type {any} */ (e).status = 400;
      throw e;
    }
  }
  return { method: (req.method || "GET").toUpperCase(), url, headers, params, body };
}

/**
 * Wrap a pure handler as a Vercel Node function.
 * @param {(request: ProxyRequest, ctx: Ctx) => Promise<ProxyResponse>} handle
 */
export function vercel(handle) {
  return async function handler(req, res) {
    /** @type {ProxyResponse} */
    let out;
    try {
      const request = await toRequest(req);
      if (request.method === "OPTIONS") out = { status: 204, headers: {}, body: "" };
      else out = await handle(request, { config: loadConfig(), fetch: globalThis.fetch });
    } catch (e) {
      out = fromError(e);
    }
    res.writeHead(out.status, { ...CORS, ...out.headers });
    res.end(out.body);
  };
}
