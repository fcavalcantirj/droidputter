import assert from "node:assert/strict";
import { beforeEach, describe, test } from "node:test";
import { handle } from "../api/shim.js";
import { _resetShimCache, runTitle } from "../lib/builds.js";
import { SHIM, ctxWith, fakeGitHub, makeRun, parse, req } from "./helpers.js";

beforeEach(() => _resetShimCache());

describe("GET /api/shim", () => {
  test("shim_commit, repo, workflow, builds_in_flight", async () => {
    const runs = [
      makeRun({ id: 1, title: runTitle({ repo: "a/b", shim: SHIM, requestId: "x" }), status: "queued" }),
      makeRun({ id: 2, title: runTitle({ repo: "a/c", shim: SHIM, requestId: "y" }), status: "in_progress" }),
      makeRun({ id: 3, title: runTitle({ repo: "a/d", shim: SHIM, requestId: "z" }) }),
      makeRun({ id: 4, title: runTitle({ repo: "a/e", shim: SHIM, requestId: "w" }), conclusion: "failure" }),
    ];
    const res = await handle(req({ path: "/api/shim" }), ctxWith(fakeGitHub({ runs })));
    assert.equal(res.status, 200);
    assert.deepEqual(parse(res), { shim_commit: SHIM, repo: "fcavalcantirj/droidputter", workflow: "build-app.yml", builds_in_flight: 2 });
  });

  test("shim commit cached 60 s, runs always fresh", async () => {
    let t = 5_000_000;
    const fake = fakeGitHub();
    const ctx = ctxWith(fake, { now: () => t });
    await handle(req({ path: "/api/shim" }), ctx);
    t += 30_000;
    await handle(req({ path: "/api/shim" }), ctx);
    assert.equal(fake.calls.filter((c) => c.url.includes("/commits?")).length, 1);
    assert.equal(fake.calls.filter((c) => c.url.includes("/runs?")).length, 2);
    t += 31_000;
    await handle(req({ path: "/api/shim" }), ctx);
    assert.equal(fake.calls.filter((c) => c.url.includes("/commits?")).length, 2);
  });

  test("POST -> 405; missing token -> 500 before any GitHub call", async () => {
    const fake = fakeGitHub();
    assert.equal((await handle(req({ method: "POST", path: "/api/shim" }), ctxWith(fake))).status, 405);
    await assert.rejects(handle(req({ path: "/api/shim" }), ctxWith(fake, { token: "" })), (e) => e.status === 500 && /GITHUB_TOKEN/.test(e.message));
    assert.equal(fake.calls.length, 0);
  });
});
