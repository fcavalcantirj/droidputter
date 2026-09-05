import assert from "node:assert/strict";
import { beforeEach, describe, test } from "node:test";
import { handle } from "../api/build.js";
import { vercel } from "../lib/http.js";
import { IN_FLIGHT_LIMIT, _resetShimCache, envOf, nameOf, runTitle, titlePrefix } from "../lib/builds.js";
import { SHIM, TOKEN, UPSTREAM, ctxWith, fakeGitHub, invoke, makeRun, parse, req } from "./helpers.js";

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const VIRTUAL = "m5cardputer-virtual";
const post = (body) => req({ method: "POST", path: "/api/build", body });
const title = (o) => runTitle({ repo: UPSTREAM, shim: SHIM, ...o });

beforeEach(() => _resetShimCache());

describe("run name", () => {
  test("format: build <repo>@<ref|HEAD> env=<env> shim=<shim|?> req=<id|->, env defaulting to m5cardputer", () => {
    assert.equal(runTitle({ repo: UPSTREAM, shim: SHIM, requestId: "r1" }), `build ${UPSTREAM}@HEAD env=m5cardputer shim=${SHIM} req=r1`);
    assert.equal(runTitle({ repo: UPSTREAM, ref: "v2", env: VIRTUAL, shim: SHIM, requestId: "r2" }), `build ${UPSTREAM}@v2 env=${VIRTUAL} shim=${SHIM} req=r2`);
    assert.equal(runTitle({ repo: UPSTREAM, env: VIRTUAL }), `build ${UPSTREAM}@HEAD env=${VIRTUAL} shim=? req=-`);
    assert.equal(titlePrefix({ repo: UPSTREAM, env: VIRTUAL, shim: SHIM }), `build ${UPSTREAM}@HEAD env=${VIRTUAL} shim=${SHIM} `);
    assert.equal(titlePrefix({ repo: UPSTREAM, shim: SHIM }), `build ${UPSTREAM}@HEAD env=m5cardputer shim=${SHIM} `);
  });

  test("envOf parses env=, m5cardputer when a (pre-env) run name carries none; nameOf only an explicit name=", () => {
    assert.equal(envOf(`build ${UPSTREAM}@HEAD env=${VIRTUAL} shim=${SHIM} req=r`), VIRTUAL);
    assert.equal(envOf(`build ${UPSTREAM}@HEAD env=m5cardputer shim=${SHIM} req=r`), "m5cardputer");
    assert.equal(envOf(`build ${UPSTREAM}@HEAD shim=${SHIM} req=r`), "m5cardputer");
    assert.equal(envOf(`build ${UPSTREAM}@HEAD env= shim=${SHIM} req=r`), "m5cardputer");
    assert.equal(envOf(undefined), "m5cardputer");
    assert.equal(nameOf(`build ${UPSTREAM}@HEAD env=${VIRTUAL} shim=${SHIM} req=r`), null);
    assert.equal(nameOf(`build ${UPSTREAM}@HEAD env=${VIRTUAL} name=pense-bem shim=${SHIM} req=r`), "pense-bem");
    assert.equal(nameOf("build x/y@name=foo env=m5cardputer shim=? req=-"), null, "name= inside another token is not a name");
  });
});

describe("POST /api/build", () => {
  test("cache hit: fresh successful run for repo@HEAD with this shim and env -> 200 cached:true, no dispatch", async () => {
    const fake = fakeGitHub({ runs: [makeRun({ id: 501, title: title({ requestId: "req-501" }), ageMs: 3 * 3600e3 })] });
    const res = await handle(post({ repo: UPSTREAM }), ctxWith(fake));
    assert.equal(res.status, 200);
    assert.deepEqual(parse(res), { request_id: "req-501", repo: UPSTREAM, ref: "", name: "stellar-map", env: "m5cardputer", shim_commit: SHIM, cached: true, run_id: "501" });
    assert.equal(fake.dispatches.length, 0);
  });

  test("stale (25 h) success does not count -> dispatch with the workflow's six inputs", async () => {
    const fake = fakeGitHub({ runs: [makeRun({ id: 502, title: title({ requestId: "req-502" }), ageMs: 25 * 3600e3 })] });
    const res = await handle(post({ repo: UPSTREAM }), ctxWith(fake));
    assert.equal(res.status, 202);
    const body = parse(res);
    assert.match(body.request_id, UUID_RE);
    assert.deepEqual(body, { request_id: body.request_id, repo: UPSTREAM, ref: "", name: "stellar-map", env: "m5cardputer", shim_commit: SHIM, cached: false });
    assert.equal(fake.dispatches.length, 1);
    assert.deepEqual(fake.dispatches[0], { ref: "main", inputs: { repo: UPSTREAM, name: "stellar-map", ref: "", env: "m5cardputer", request_id: body.request_id, shim: SHIM } });
  });

  test("env=m5cardputer-virtual: dispatched as the env input, echoed in the 202 body", async () => {
    const fake = fakeGitHub();
    const res = await handle(post({ repo: UPSTREAM, env: VIRTUAL }), ctxWith(fake));
    assert.equal(res.status, 202);
    const body = parse(res);
    assert.deepEqual(body, { request_id: body.request_id, repo: UPSTREAM, ref: "", name: "stellar-map", env: VIRTUAL, shim_commit: SHIM, cached: false });
    assert.deepEqual(fake.dispatches[0].inputs, { repo: UPSTREAM, name: "stellar-map", ref: "", env: VIRTUAL, request_id: body.request_id, shim: SHIM });
  });

  test("bad env -> 400 before any GitHub call", async () => {
    const fake = fakeGitHub();
    for (const env of ["virtual", "M5Cardputer", "m5cardputer-virtual-elf", 7]) {
      await assert.rejects(handle(post({ repo: UPSTREAM, env }), ctxWith(fake)), (e) => e.status === 400 && /env must be one of/.test(e.message));
    }
    assert.equal(fake.calls.length, 0);
    assert.equal(fake.dispatches.length, 0);
  });

  test("env is part of the cache identity: the ADV build is neither a cache hit nor a join for the virtual one, and vice versa", async () => {
    const adv = makeRun({ id: 601, title: title({ requestId: "adv-fresh" }), ageMs: 3600e3 });
    const virt = makeRun({ id: 602, title: title({ env: VIRTUAL, requestId: "virt-fresh" }), ageMs: 3600e3 });
    let fake = fakeGitHub({ runs: [adv] });
    let body = parse(await handle(post({ repo: UPSTREAM, env: VIRTUAL }), ctxWith(fake)));
    assert.equal(body.cached, false);
    assert.equal(fake.dispatches[0].inputs.env, VIRTUAL);

    fake = fakeGitHub({ runs: [virt] });
    body = parse(await handle(post({ repo: UPSTREAM }), ctxWith(fake)));
    assert.equal(body.cached, false);
    assert.equal(fake.dispatches[0].inputs.env, "m5cardputer");

    fake = fakeGitHub({ runs: [adv, virt] });
    assert.deepEqual(parse(await handle(post({ repo: UPSTREAM, env: VIRTUAL }), ctxWith(fake))), { request_id: "virt-fresh", repo: UPSTREAM, ref: "", name: "stellar-map", env: VIRTUAL, shim_commit: SHIM, cached: true, run_id: "602" });
    assert.deepEqual(parse(await handle(post({ repo: UPSTREAM }), ctxWith(fake))), { request_id: "adv-fresh", repo: UPSTREAM, ref: "", name: "stellar-map", env: "m5cardputer", shim_commit: SHIM, cached: true, run_id: "601" });
    assert.equal(fake.dispatches.length, 0);

    fake = fakeGitHub({ runs: [makeRun({ id: 603, title: title({ requestId: "adv-running" }), status: "in_progress" })] });
    const res = await handle(post({ repo: UPSTREAM, env: VIRTUAL }), ctxWith(fake));
    assert.equal(res.status, 202);
    assert.equal(parse(res).run_id, undefined, "an in-flight ADV run is not joined for the virtual build");
    assert.equal(fake.dispatches.length, 1);
  });

  test("runs from before the env dimension (no env= in the name) never match again, not even for the default env", async () => {
    const old = makeRun({ id: 604, title: `build ${UPSTREAM}@HEAD shim=${SHIM} req=old-604`, ageMs: 3600e3 });
    const fake = fakeGitHub({ runs: [old, makeRun({ id: 605, title: `build ${UPSTREAM}@HEAD shim=${SHIM} req=old-605`, status: "in_progress" })] });
    const res = await handle(post({ repo: UPSTREAM }), ctxWith(fake));
    assert.equal(res.status, 202);
    assert.equal(parse(res).cached, false);
    assert.equal(fake.dispatches.length, 1);
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
    assert.deepEqual(parse(res), { request_id: "req-77", repo: UPSTREAM, ref: "", name: "stellar-map", env: "m5cardputer", shim_commit: SHIM, cached: false, run_id: "77" });
    assert.equal(fake.dispatches.length, 0);
  });

  test("identical virtual build in flight -> joined by env", async () => {
    const fake = fakeGitHub({ runs: [makeRun({ id: 78, title: title({ env: VIRTUAL, requestId: "req-78" }), status: "queued" })] });
    const res = await handle(post({ repo: UPSTREAM, env: VIRTUAL }), ctxWith(fake));
    assert.equal(res.status, 202);
    assert.deepEqual(parse(res), { request_id: "req-78", repo: UPSTREAM, ref: "", name: "stellar-map", env: VIRTUAL, shim_commit: SHIM, cached: false, run_id: "78" });
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

  test("GET /api/build lists the newest runs described, each with its env", async () => {
    const fake = fakeGitHub({ runs: [makeRun({ id: 1, title: title({ requestId: "a" }), status: "queued" }), makeRun({ id: 2, title: title({ env: VIRTUAL, requestId: "b" }) })] });
    const body = parse(await handle(req({ path: "/api/build" }), ctxWith(fake)));
    assert.equal(body.builds_in_flight, 1);
    assert.equal(body.builds.length, 2);
    assert.equal(body.builds[0].status, "queued");
    assert.equal(body.builds[0].env, "m5cardputer");
    assert.equal(body.builds[1].status, "ready");
    assert.equal(body.builds[1].request_id, "b");
    assert.equal(body.builds[1].env, VIRTUAL);
  });

  test("other methods -> 405", async () => {
    assert.equal((await handle(req({ method: "DELETE", path: "/api/build" }), ctxWith(fakeGitHub()))).status, 405);
  });
});

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

      const badEnv = await invoke(vercel(handle), { method: "POST", url: "/api/build", body: JSON.stringify({ repo: UPSTREAM, env: "virtual" }) });
      assert.equal(badEnv.status, 400);
      assert.deepEqual(JSON.parse(badEnv.body), { error: "env must be one of m5cardputer, m5cardputer-virtual" });
      assert.equal(fake.dispatches.length, 1);

      const preflight = await invoke(vercel(handle), { method: "OPTIONS", url: "/api/build" });
      assert.equal(preflight.status, 204);
      assert.equal(preflight.headers["Access-Control-Allow-Methods"], "GET, POST, OPTIONS");
    } finally {
      globalThis.fetch = realFetch;
      restore();
    }
  });
});
