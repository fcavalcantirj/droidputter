import assert from "node:assert/strict";
import { Readable } from "node:stream";
import { beforeEach, describe, test } from "node:test";
import { handle } from "../api/build.js";
import { vercel } from "../lib/http.js";
import { IN_FLIGHT_LIMIT, _resetShimCache, runTitle } from "../lib/builds.js";
import { SHIM, TOKEN, UPSTREAM, ctxWith, fakeGitHub, makeRun, parse, req } from "./helpers.js";

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const post = (body) => req({ method: "POST", path: "/api/build", body });
const title = (o) => runTitle({ repo: UPSTREAM, shim: SHIM, ...o });

beforeEach(() => _resetShimCache());

describe("POST /api/build", () => {
  test("cache hit: fresh successful run for repo@HEAD with this shim -> 200 cached:true, no dispatch", async () => {
    const fake = fakeGitHub({ runs: [makeRun({ id: 501, title: title({ requestId: "req-501" }), ageMs: 3 * 3600e3 })] });
    const res = await handle(post({ repo: UPSTREAM }), ctxWith(fake));
    assert.equal(res.status, 200);
    assert.deepEqual(parse(res), { request_id: "req-501", repo: UPSTREAM, ref: "", name: "stellar-map", shim_commit: SHIM, cached: true, run_id: "501" });
    assert.equal(fake.dispatches.length, 0);
  });

  test("stale (25 h) success does not count -> dispatch with the workflow's five inputs", async () => {
    const fake = fakeGitHub({ runs: [makeRun({ id: 502, title: title({ requestId: "req-502" }), ageMs: 25 * 3600e3 })] });
    const res = await handle(post({ repo: UPSTREAM }), ctxWith(fake));
    assert.equal(res.status, 202);
    const body = parse(res);
    assert.match(body.request_id, UUID_RE);
    assert.deepEqual(body, { request_id: body.request_id, repo: UPSTREAM, ref: "", name: "stellar-map", shim_commit: SHIM, cached: false });
    assert.equal(fake.dispatches.length, 1);
    assert.deepEqual(fake.dispatches[0], { ref: "main", inputs: { repo: UPSTREAM, name: "stellar-map", ref: "", request_id: body.request_id, shim: SHIM } });
  });

  test("a different ref, a different shim, or a manual run (req=-) are not cache hits", async () => {
    const runs = [
      makeRun({ id: 1, title: title({ requestId: "r1" }) }), // HEAD, current shim -- but we ask for v2
      makeRun({ id: 2, title: runTitle({ repo: UPSTREAM, ref: "v2", shim: "0ld5h1m", requestId: "r2" }) }),
      makeRun({ id: 3, title: runTitle({ repo: UPSTREAM, ref: "v2", shim: SHIM }) }), // req=- (gh workflow run by hand)
      makeRun({ id: 4, title: runTitle({ repo: UPSTREAM, ref: "v2", shim: SHIM + "9", requestId: "r4" }) }), // shim prefix collision
    ];
    const fake = fakeGitHub({ runs });
    const res = await handle(post({ repo: UPSTREAM, ref: "v2" }), ctxWith(fake));
    assert.equal(res.status, 202);
    assert.equal(fake.dispatches[0].inputs.ref, "v2");
  });

  test("identical build already in flight -> 202 joins it (its request_id + run_id), no second dispatch", async () => {
    const fake = fakeGitHub({ runs: [makeRun({ id: 77, title: title({ requestId: "req-77" }), status: "in_progress" })] });
    const res = await handle(post({ repo: UPSTREAM }), ctxWith(fake));
    assert.equal(res.status, 202);
    assert.deepEqual(parse(res), { request_id: "req-77", repo: UPSTREAM, ref: "", name: "stellar-map", shim_commit: SHIM, cached: false, run_id: "77" });
    assert.equal(fake.dispatches.length, 0);
  });

  test(`${IN_FLIGHT_LIMIT} builds queued+running -> 429 + Retry-After, no dispatch`, async () => {
    const runs = [];
    for (let i = 0; i < IN_FLIGHT_LIMIT; i++) {
      runs.push(makeRun({ id: 900 + i, title: runTitle({ repo: `other/app${i}`, shim: SHIM, requestId: `q${i}` }), status: i % 2 ? "queued" : "in_progress" }));
    }
    const fake = fakeGitHub({ runs });
    const res = await handle(post({ repo: UPSTREAM }), ctxWith(fake));
    assert.equal(res.status, 429);
    assert.equal(res.headers["Retry-After"], "60");
    assert.deepEqual(parse(res), { error: "too many builds in flight", retry_after_s: 60 });
    assert.equal(fake.dispatches.length, 0);
  });

  test(`${IN_FLIGHT_LIMIT - 1} in flight still dispatches`, async () => {
    const runs = [];
    for (let i = 0; i < IN_FLIGHT_LIMIT - 1; i++) runs.push(makeRun({ id: 800 + i, title: runTitle({ repo: `other/app${i}`, shim: SHIM, requestId: `q${i}` }), status: "queued" }));
    const fake = fakeGitHub({ runs });
    assert.equal((await handle(post({ repo: UPSTREAM }), ctxWith(fake))).status, 202);
  });

  test("shim_commit comes from the newest commit touching shim/ on main, cached 60 s", async () => {
    let t = 1_000_000;
    const fake = fakeGitHub();
    const ctx = ctxWith(fake, { now: () => t });
    await handle(post({ repo: UPSTREAM }), ctx);
    t += 59_000;
    await handle(post({ repo: UPSTREAM }), ctx);
    const commitCalls = () => fake.calls.filter((c) => c.url.includes("/commits?path=shim&sha=main&per_page=1")).length;
    assert.equal(commitCalls(), 1);
    t += 2_000;
    await handle(post({ repo: UPSTREAM }), ctx);
    assert.equal(commitCalls(), 2);
  });

  test("GitHub headers: bearer token, api version, user agent, accept", async () => {
    const fake = fakeGitHub();
    await handle(post({ repo: UPSTREAM }), ctxWith(fake));
    for (const c of fake.calls) {
      assert.equal(c.headers.authorization, `Bearer ${TOKEN}`);
      assert.equal(c.headers.accept, "application/vnd.github+json");
      assert.equal(c.headers["x-github-api-version"], "2022-11-28");
      assert.equal(c.headers["user-agent"], "droidputter-proxy");
    }
  });

  test("GitHub failure surfaces as 502 without the token", async () => {
    const fake = fakeGitHub({ failCommits: true });
    await assert.rejects(handle(post({ repo: UPSTREAM }), ctxWith(fake)), (e) => {
      assert.equal(e.status, 502);
      assert.ok(!e.message.includes(TOKEN));
      assert.match(e.message, /^github 500 on \/repos\/fcavalcantirj\/droidputter\/commits\?path=shim&sha=main&per_page=1: boom$/);
      return true;
    });
  });

  test("GET /api/build lists the newest runs described", async () => {
    const fake = fakeGitHub({ runs: [makeRun({ id: 1, title: title({ requestId: "a" }), status: "queued" }), makeRun({ id: 2, title: title({ requestId: "b" }) })] });
    const body = parse(await handle(req({ path: "/api/build" }), ctxWith(fake)));
    assert.equal(body.builds_in_flight, 1);
    assert.equal(body.builds.length, 2);
    assert.equal(body.builds[0].status, "queued");
    assert.equal(body.builds[1].status, "ready");
    assert.equal(body.builds[1].request_id, "b");
  });

  test("other methods -> 405", async () => {
    assert.equal((await handle(req({ method: "DELETE", path: "/api/build" }), ctxWith(fakeGitHub()))).status, 405);
  });
});

/** Drive the real Vercel-style (req, res) adapter with a streamed body. */
async function invoke(handler, { method, url, body, headers = {} }) {
  const chunks = body === undefined ? [] : [Buffer.from(body)];
  const request = Object.assign(Readable.from(chunks), { method, url, headers: { host: "proxy.test", ...headers } });
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

describe("vercel adapter", () => {
  const saved = { ...process.env };
  const restore = () => {
    for (const k of ["GITHUB_TOKEN", "GITHUB_REPO", "GITHUB_API", "BASE_URL"]) {
      if (saved[k] === undefined) delete process.env[k];
      else process.env[k] = saved[k];
    }
  };

  test("missing GITHUB_TOKEN -> 500 {error} with CORS", async () => {
    process.env.GITHUB_TOKEN = "";
    try {
      const out = await invoke(vercel(handle), { method: "POST", url: "/api/build", body: JSON.stringify({ repo: UPSTREAM }), headers: { "content-type": "application/json" } });
      assert.equal(out.status, 500);
      assert.equal(out.headers["Access-Control-Allow-Origin"], "*");
      assert.match(JSON.parse(out.body).error, /GITHUB_TOKEN/);
    } finally {
      restore();
    }
  });

  test("full path: streamed JSON body -> 202 through the adapter; bad JSON -> 400; bad repo -> 400; OPTIONS -> 204", async () => {
    const fake = fakeGitHub();
    const realFetch = globalThis.fetch;
    process.env.GITHUB_TOKEN = TOKEN;
    process.env.GITHUB_REPO = "fcavalcantirj/droidputter";
    process.env.GITHUB_API = "https://gh.test";
    globalThis.fetch = fake.fetch;
    try {
      const ok = await invoke(vercel(handle), { method: "POST", url: "/api/build", body: JSON.stringify({ repo: UPSTREAM, ref: "main" }) });
      assert.equal(ok.status, 202);
      assert.equal(ok.headers["Content-Type"], "application/json; charset=utf-8");
      assert.equal(JSON.parse(ok.body).ref, "main");
      assert.equal(fake.dispatches.length, 1);

      const badJson = await invoke(vercel(handle), { method: "POST", url: "/api/build", body: "{nope" });
      assert.equal(badJson.status, 400);
      assert.deepEqual(JSON.parse(badJson.body), { error: "body is not valid JSON" });

      const badRepo = await invoke(vercel(handle), { method: "POST", url: "/api/build", body: JSON.stringify({ repo: "nope" }) });
      assert.equal(badRepo.status, 400);
      assert.match(JSON.parse(badRepo.body).error, /owner\/name/);

      const preflight = await invoke(vercel(handle), { method: "OPTIONS", url: "/api/build" });
      assert.equal(preflight.status, 204);
      assert.equal(preflight.headers["Access-Control-Allow-Methods"], "GET, POST, OPTIONS");
    } finally {
      globalThis.fetch = realFetch;
      restore();
    }
  });
});
