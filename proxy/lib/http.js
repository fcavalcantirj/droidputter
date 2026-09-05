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
 * @property {number} bodyBytes size of the raw body (0 when empty)
 * @property {string} remoteAddress the socket's peer ("" when unknown); X-Forwarded-For is in headers
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
    const e = new Error("GITHUB_TOKEN is not set on the proxy (fine-grained PAT: Actions read+write, Contents read, Issues read+write)");
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

/** @param {number} bytes @param {number} limit throws the 413 the moment a body outgrows its route's cap */
function checkSize(bytes, limit) {
  if (bytes <= limit) return;
  const e = new Error(`body larger than ${limit} bytes`);
  /** @type {any} */ (e).status = 413;
  throw e;
}

/**
 * The parsed JSON body and its raw size. Vercel's helper may already have consumed the stream into req.body
 * (string, Buffer or parsed object -- for the last one Content-Length is the only size left).
 * @param {import("node:http").IncomingMessage & {query?: any, body?: any}} req
 * @param {number} maxBytes
 * @returns {Promise<{body: unknown, bytes: number}>}
 */
async function readBody(req, maxBytes) {
  if (req.body !== undefined) {
    if (typeof req.body === "string" || Buffer.isBuffer(req.body)) {
      const text = typeof req.body === "string" ? req.body : req.body.toString("utf8");
      const bytes = Buffer.byteLength(text);
      checkSize(bytes, maxBytes);
      return { body: text === "" ? undefined : JSON.parse(text), bytes };
    }
    const bytes = Number(req.headers && req.headers["content-length"]) || Buffer.byteLength(JSON.stringify(req.body));
    checkSize(bytes, maxBytes);
    return { body: req.body, bytes };
  }
  const chunks = [];
  let bytes = 0;
  for await (const chunk of req) {
    const buf = typeof chunk === "string" ? Buffer.from(chunk) : chunk;
    bytes += buf.length;
    checkSize(bytes, maxBytes);
    chunks.push(buf);
  }
  const text = Buffer.concat(chunks).toString("utf8");
  return { body: text.trim() === "" ? undefined : JSON.parse(text), bytes };
}

/**
 * @param {import("node:http").IncomingMessage & {query?: any, body?: any}} req
 * @param {{maxBodyBytes?: number}} [o] bodies over the cap are refused with 413 before they are parsed
 * @returns {Promise<ProxyRequest>}
 */
export async function toRequest(req, { maxBodyBytes = Infinity } = {}) {
  /** @type {Record<string, string>} */
  const headers = {};
  for (const [k, v] of Object.entries(req.headers || {})) headers[k.toLowerCase()] = Array.isArray(v) ? v[0] : String(v ?? "");
  const url = new URL(req.url || "/", `http://${headers.host || "localhost"}`);
  /** @type {Record<string, string>} */
  const params = {};
  for (const [k, v] of url.searchParams) params[k] = v;
  for (const [k, v] of Object.entries(req.query || {})) if (typeof v === "string") params[k] = v;
  let body;
  let bodyBytes = 0;
  if (req.method === "POST" || req.method === "PUT") {
    try {
      ({ body, bytes: bodyBytes } = await readBody(req, maxBodyBytes));
    } catch (e) {
      if (e && typeof e.status === "number") throw e;
      const err = new Error("body is not valid JSON");
      /** @type {any} */ (err).status = 400;
      throw err;
    }
  }
  const remoteAddress = (req.socket && req.socket.remoteAddress) || "";
  return { method: (req.method || "GET").toUpperCase(), url, headers, params, body, bodyBytes, remoteAddress };
}

/**
 * Wrap a pure handler as a Vercel Node function.
 * @param {(request: ProxyRequest, ctx: Ctx) => Promise<ProxyResponse>} handle
 * @param {{maxBodyBytes?: number}} [o] passed to toRequest
 */
export function vercel(handle, { maxBodyBytes } = {}) {
  return async function handler(req, res) {
    /** @type {ProxyResponse} */
    let out;
    try {
      const request = await toRequest(req, { maxBodyBytes });
      if (request.method === "OPTIONS") out = { status: 204, headers: {}, body: "" };
      else out = await handle(request, { config: loadConfig(), fetch: globalThis.fetch });
    } catch (e) {
      out = fromError(e);
    }
    res.writeHead(out.status, { ...CORS, ...out.headers });
    res.end(out.body);
  };
}
