import assert from "node:assert/strict";
import { beforeEach, describe, test } from "node:test";
import { handle } from "../api/build/[id].js";
import { _resetArtifactCache } from "../lib/artifact.js";
import { runTitle } from "../lib/builds.js";
import { DEFAULT_PARTS, SHIM, UPSTREAM, ctxWith, fakeGitHub, makeArtifact, makeRun, makeZip, parse, req, sha256 } from "./helpers.js";

const RID = "6f1c2a2e-1b2c-4d3e-8f90-0123456789ab";
const title = (o) => runTitle({ repo: UPSTREAM, shim: SHIM, requestId: RID, ...o });
const status = (fake, o = {}) => handle(req({ path: `/api/build/${RID}`, params: { id: RID }, ...o }), ctxWith(fake, o.ctx || {}));

beforeEach(() => _resetArtifactCache());

describe("GET /api/build/{request_id}", () => {
  test("no run yet (dispatch is asynchronous) -> queued with just the id", async () => {
    const fake = fakeGitHub({ runs: [makeRun({ id: 1, title: runTitle({ repo: UPSTREAM, shim: SHIM, requestId: "someone-else" }) })] });
    const res = await status(fake);
    assert.equal(res.status, 200);
    assert.deepEqual(parse(res), { request_id: RID, status: "queued" });
  });

  test("queued run", async () => {
    const run = makeRun({ id: 11, title: title(), status: "queued" });
    const body = parse(await status(fakeGitHub({ runs: [run] })));
    assert.equal(body.status, "queued");
    assert.equal(body.run_id, "11");
    assert.equal(body.run_url, run.html_url);
    assert.equal(body.started_at, run.run_started_at);
    assert.equal(body.request_id, RID);
  });

  test("in_progress run -> building", async () => {
    const body = parse(await status(fakeGitHub({ runs: [makeRun({ id: 12, title: title(), status: "in_progress" })] })));
    assert.equal(body.status, "building");
    assert.equal(body.run_id, "12");
    assert.ok(body.started_at);
  });

  test("completed failure / cancelled -> failed with the conclusion", async () => {
    for (const conclusion of ["failure", "cancelled", "timed_out"]) {
      const body = parse(await status(fakeGitHub({ runs: [makeRun({ id: 13, title: title(), conclusion })] })));
      assert.equal(body.status, "failed");
      assert.equal(body.conclusion, conclusion);
      assert.equal(body.run_id, "13");
      assert.match(body.run_url, /actions\/runs\/13$/);
    }
  });

  test("completed success -> ready with build + the four parts (offsets, sizes, sha256 from SHA256SUMS, urls)", async () => {
    const run = makeRun({ id: 33919498873, title: title() });
    const fake = fakeGitHub({ runs: [run], artifacts: { 33919498873: [makeArtifact(4242)] }, zips: { 4242: makeZip() } });
    const res = await status(fake);
    assert.equal(res.status, 200);
    const body = parse(res);
    assert.equal(body.status, "ready");
    assert.equal(body.run_id, "33919498873");
    assert.equal(body.completed_at, run.updated_at);
    assert.deepEqual(body.build, { upstream_commit: "0123456789abcdef0123456789abcdef01234567", ram: "38.5% (126124 B)", flash: "33.3% (2267241 B)" });
    assert.deepEqual(
      body.parts.map((p) => [p.file, p.offset]),
      [["bootloader.bin", 0x0], ["partitions.bin", 0x8000], ["boot_app0.bin", 0xe000], ["firmware.bin", 0x10000]],
    );
    for (const p of body.parts) {
      assert.equal(p.size, DEFAULT_PARTS[p.file].byteLength);
      assert.equal(p.sha256, sha256(DEFAULT_PARTS[p.file]));
      assert.equal(p.url, `https://proxy.test/api/artifact/33919498873/${p.file}`);
    }
    assert.equal(fake.blobHits.length, 1);
    assert.equal(fake.blobHits[0].hasAuth, false, "the blob hop must not carry the GitHub token");
  });

  test("ready twice -> the artifact is downloaded once (module cache)", async () => {
    const fake = fakeGitHub({ runs: [makeRun({ id: 21, title: title() })], artifacts: { 21: [makeArtifact(1)] }, zips: { 1: makeZip() } });
    await status(fake);
    await status(fake);
    assert.equal(fake.blobHits.length, 1);
    assert.equal(fake.calls.filter((c) => c.url.endsWith("/actions/runs/21/artifacts?per_page=100")).length, 1);
  });

  test("ready but the artifact expired -> 404", async () => {
    const fake = fakeGitHub({ runs: [makeRun({ id: 22, title: title() })], artifacts: { 22: [makeArtifact(2, { expired: true })] } });
    await assert.rejects(status(fake), (e) => e.status === 404 && /expired/.test(e.message));
  });

  test("part urls use BASE_URL when set, else the forwarded host", async () => {
    const fake = fakeGitHub({ runs: [makeRun({ id: 23, title: title() })], artifacts: { 23: [makeArtifact(3)] }, zips: { 3: makeZip() } });
    const body = parse(await status(fake, { ctx: { baseUrl: "" }, headers: { host: "127.0.0.1:3000", "x-forwarded-host": "droidputter-proxy.vercel.app", "x-forwarded-proto": "https" } }));
    assert.equal(body.parts[0].url, "https://droidputter-proxy.vercel.app/api/artifact/23/bootloader.bin");
  });

  test("the run is searched on two pages of 50", async () => {
    const filler = Array.from({ length: 50 }, (_, i) => makeRun({ id: 1000 + i, title: runTitle({ repo: "other/x", shim: SHIM, requestId: `f${i}` }) }));
    const fake = fakeGitHub({ runs: filler, runsPage2: [makeRun({ id: 5, title: title(), status: "queued" })] });
    const body = parse(await status(fake));
    assert.equal(body.status, "queued");
    assert.equal(body.run_id, "5");
    assert.equal(fake.calls.filter((c) => c.url.includes("/runs?event=workflow_dispatch")).length, 2);
  });

  test("the id can come from the path alone (no rewrite query)", async () => {
    const fake = fakeGitHub({ runs: [makeRun({ id: 6, title: title(), status: "queued" })] });
    const body = parse(await handle(req({ path: `/api/build/${RID}` }), ctxWith(fake)));
    assert.equal(body.run_id, "6");
  });

  test("bad request_id -> 400; POST -> 405", async () => {
    const fake = fakeGitHub();
    await assert.rejects(handle(req({ path: "/api/build/x", params: { id: "a b" } }), ctxWith(fake)), (e) => e.status === 400);
    assert.equal((await handle(req({ method: "POST", path: `/api/build/${RID}` }), ctxWith(fake))).status, 405);
  });
});
