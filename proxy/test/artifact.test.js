import assert from "node:assert/strict";
import { beforeEach, describe, test } from "node:test";
import { strFromU8 } from "fflate";
import { handle } from "../api/artifact/[run]/[file].js";
import { ArtifactError, PART_FILES, _resetArtifactCache, parseArtifactZip, parseSha256Sums, partsOf } from "../lib/artifact.js";
import { DEFAULT_PARTS, TOKEN, ctxWith, fakeGitHub, makeArtifact, makeZip, parse, req, sha256 } from "./helpers.js";

const get = (fake, run, file, extra = {}) => handle(req({ path: `/api/artifact/${run}/${file}`, params: { run, file }, ...extra }), ctxWith(fake));

beforeEach(() => _resetArtifactCache());

describe("artifact parsing", () => {
  test("SHA256SUMS: text and binary-mode lines, junk ignored", () => {
    const h = "a".repeat(64);
    const sums = parseSha256Sums(`${h}  bootloader.bin\n${"B".repeat(64)} *firmware.bin\nnot a line\n`);
    assert.equal(sums.get("bootloader.bin"), h);
    assert.equal(sums.get("firmware.bin"), "b".repeat(64));
    assert.equal(sums.size, 2);
  });

  test("zip -> files by basename, build.json record, sums", () => {
    const parsed = parseArtifactZip(makeZip({ prefix: "dist/" }));
    assert.deepEqual([...parsed.files.keys()].sort(), [...PART_FILES, "SHA256SUMS", "build.json"].sort());
    assert.equal(parsed.build.upstream_commit, "0123456789abcdef0123456789abcdef01234567");
    assert.equal(parsed.sums.size, 4);
    assert.equal(strFromU8(parsed.files.get("build.json")).trim().startsWith("{"), true);
  });

  test("not a zip -> 502", () => {
    assert.throws(() => parseArtifactZip(new Uint8Array([1, 2, 3, 4])), (e) => e instanceof ArtifactError && e.status === 502);
  });

  test("partsOf: fixed offsets, sizes from the zip, sha from SHA256SUMS verified against the bytes", () => {
    const parts = partsOf(parseArtifactZip(makeZip()), { runId: "7", baseUrl: "https://p" });
    assert.deepEqual(parts.map((p) => p.offset), ["0x0", "0x8000", "0xe000", "0x10000"]);
    assert.deepEqual(parts.map((p) => p.size), PART_FILES.map((f) => DEFAULT_PARTS[f].byteLength));
    assert.deepEqual(parts.map((p) => p.sha256), PART_FILES.map((f) => sha256(DEFAULT_PARTS[f])));
    assert.deepEqual(parts.map((p) => p.url), PART_FILES.map((f) => `https://p/api/artifact/7/${f}`));
  });

  test("partsOf: SHA256SUMS mismatch -> 502; missing part -> 502", () => {
    assert.throws(() => partsOf(parseArtifactZip(makeZip({ tamperSums: true })), { runId: "7", baseUrl: "" }), (e) => e.status === 502 && /firmware\.bin does not match/.test(e.message));
    assert.throws(() => partsOf(parseArtifactZip(makeZip({ omit: ["boot_app0.bin"] })), { runId: "7", baseUrl: "" }), (e) => e.status === 502 && /no boot_app0\.bin/.test(e.message));
  });
});

describe("GET /api/artifact/{run}/{file}", () => {
  test("streams the bytes with octet-stream, length, immutable caching", async () => {
    const fake = fakeGitHub({ artifacts: { 100: [makeArtifact(9)] }, zips: { 9: makeZip() } });
    const res = await get(fake, "100", "firmware.bin");
    assert.equal(res.status, 200);
    assert.equal(res.headers["Content-Type"], "application/octet-stream");
    assert.equal(res.headers["Content-Length"], String(DEFAULT_PARTS["firmware.bin"].byteLength));
    assert.equal(res.headers["Cache-Control"], "public, max-age=31536000, immutable");
    assert.equal(res.headers.ETag, `"${sha256(DEFAULT_PARTS["firmware.bin"])}"`);
    assert.equal(Buffer.compare(Buffer.from(res.body), Buffer.from(DEFAULT_PARTS["firmware.bin"])), 0);
  });

  test("finds the *-m5cardputer artifact among others; follows the 302 without the token", async () => {
    const fake = fakeGitHub({ artifacts: { 101: [makeArtifact(1, { name: "logs" }), makeArtifact(2, { name: "pense-bem-m5cardputer" })] }, zips: { 2: makeZip() } });
    const res = await get(fake, "101", "bootloader.bin");
    assert.equal(res.status, 200);
    assert.equal(fake.blobHits.length, 1);
    assert.equal(fake.blobHits[0].url, "https://blob.test/zip/2");
    assert.equal(fake.blobHits[0].hasAuth, false);
    const zipCall = fake.calls.find((c) => c.url.endsWith("/actions/artifacts/2/zip"));
    assert.equal(zipCall.headers.authorization, `Bearer ${TOKEN}`);
  });

  test("four parts of one run -> one zip download", async () => {
    const fake = fakeGitHub({ artifacts: { 102: [makeArtifact(3)] }, zips: { 3: makeZip() } });
    for (const f of PART_FILES) assert.equal((await get(fake, "102", f)).status, 200);
    assert.equal(fake.blobHits.length, 1);
  });

  test("expired artifact -> 404; run without artifacts -> 404", async () => {
    const fake = fakeGitHub({ artifacts: { 103: [makeArtifact(4, { expired: true })], 104: [] } });
    await assert.rejects(get(fake, "103", "firmware.bin"), (e) => e.status === 404 && /expired/.test(e.message));
    await assert.rejects(get(fake, "104", "firmware.bin"), (e) => e.status === 404 && /no -m5cardputer artifact/.test(e.message));
    await assert.rejects(get(fake, "105", "firmware.bin"), (e) => e.status === 404);
  });

  test("file outside the four names -> 400 before any GitHub call; bad run id -> 400", async () => {
    const fake = fakeGitHub();
    await assert.rejects(get(fake, "100", "build.json"), (e) => e.status === 400);
    await assert.rejects(get(fake, "100", "SHA256SUMS"), (e) => e.status === 400);
    await assert.rejects(get(fake, "1x", "firmware.bin"), (e) => e.status === 400);
    assert.equal(fake.calls.length, 0);
  });

  test("params fall back to the path when the rewrite query is absent", async () => {
    const fake = fakeGitHub({ artifacts: { 106: [makeArtifact(5)] }, zips: { 5: makeZip() } });
    const res = await handle(req({ path: "/api/artifact/106/partitions.bin" }), ctxWith(fake));
    assert.equal(res.status, 200);
    assert.equal(res.headers["Content-Length"], "3072");
  });
});
