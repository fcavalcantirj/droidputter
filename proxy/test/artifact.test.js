import assert from "node:assert/strict";
import { beforeEach, describe, test } from "node:test";
import { strFromU8 } from "fflate";
import { handle } from "../api/artifact/[run]/[file].js";
import { ArtifactError, BUILD_ENVS, PART_FILES, _resetArtifactCache, artifactLabel, parseArtifactZip, parseSha256Sums, partsOf, selectArtifact } from "../lib/artifact.js";
import { DEFAULT_PARTS, TOKEN, ctxWith, fakeGitHub, makeArtifact, makeZip, parse, req, sha256 } from "./helpers.js";

const get = (fake, run, file, extra = {}) => handle(req({ path: `/api/artifact/${run}/${file}`, params: { run, file }, ...extra }), ctxWith(fake));
const VIRTUAL = "m5cardputer-virtual";
const named = (...names) => names.map((name, i) => makeArtifact(i + 1, { name }));

beforeEach(() => _resetArtifactCache());

describe("artifact selection (<name>-<env>, never -elf)", () => {
  test("with name and env: the exact <name>-<env> only", () => {
    const list = named("logs", "stellar-map-m5cardputer-elf", "stellar-map-m5cardputer-virtual", "stellar-map-m5cardputer", "my-stellar-map-m5cardputer");
    assert.equal(selectArtifact(list, { name: "stellar-map", env: "m5cardputer" }).name, "stellar-map-m5cardputer");
    assert.equal(selectArtifact(list, { name: "stellar-map", env: VIRTUAL }).name, "stellar-map-m5cardputer-virtual");
    assert.equal(selectArtifact(list, { name: "pense-bem", env: "m5cardputer" }), undefined);
    assert.equal(selectArtifact(named("pense-bem-m5cardputer-elf"), { name: "pense-bem", env: "m5cardputer" }), undefined);
  });

  test("with env only: the artifact ending in -<env> that does not end in -elf; the two envs never cross", () => {
    assert.equal(selectArtifact(named("stellar-map-m5cardputer-elf", "stellar-map-m5cardputer"), { env: "m5cardputer" }).name, "stellar-map-m5cardputer");
    assert.equal(selectArtifact(named("stellar-map-m5cardputer-virtual-elf", "stellar-map-m5cardputer-virtual"), { env: VIRTUAL }).name, "stellar-map-m5cardputer-virtual");
    assert.equal(selectArtifact(named("stellar-map-m5cardputer-virtual", "stellar-map-m5cardputer-virtual-elf"), { env: "m5cardputer" }), undefined);
    assert.equal(selectArtifact(named("stellar-map-m5cardputer", "stellar-map-m5cardputer-elf"), { env: VIRTUAL }), undefined);
    assert.equal(selectArtifact(named("stellar-map-m5cardputer-elf"), { env: "m5cardputer" }), undefined);
  });

  test("with nothing known (the artifact route): any env of BUILD_ENVS, still never -elf, name-less entries ignored", () => {
    assert.deepEqual(BUILD_ENVS, ["m5cardputer", VIRTUAL]);
    assert.equal(selectArtifact(named("stellar-map-m5cardputer-virtual-elf", "stellar-map-m5cardputer-virtual")).name, "stellar-map-m5cardputer-virtual");
    assert.equal(selectArtifact(named("stellar-map-m5cardputer-elf", "stellar-map-m5cardputer"), {}).name, "stellar-map-m5cardputer");
    assert.equal(selectArtifact(named("stellar-map-m5cardputer-elf", "stellar-map-m5cardputer-virtual-elf", "logs")), undefined);
    assert.equal(selectArtifact([{ id: 1 }, { id: 2, name: 7 }]), undefined);
    assert.equal(selectArtifact([]), undefined);
  });

  test("artifactLabel names what was looked for", () => {
    assert.equal(artifactLabel({ name: "pense-bem", env: VIRTUAL }), "pense-bem-m5cardputer-virtual");
    assert.equal(artifactLabel({ env: "m5cardputer" }), "*-m5cardputer");
    assert.equal(artifactLabel({ name: null, env: null }), "*-{m5cardputer|m5cardputer-virtual}");
    assert.equal(artifactLabel(), "*-{m5cardputer|m5cardputer-virtual}");
  });
});

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

  test("finds the <name>-m5cardputer artifact among others, skipping its -elf sibling; follows the 302 without the token", async () => {
    const fake = fakeGitHub({ artifacts: { 101: [makeArtifact(1, { name: "logs" }), makeArtifact(3, { name: "pense-bem-m5cardputer-elf" }), makeArtifact(2, { name: "pense-bem-m5cardputer" })] }, zips: { 2: makeZip() } });
    const res = await get(fake, "101", "bootloader.bin");
    assert.equal(res.status, 200);
    assert.equal(fake.blobHits.length, 1);
    assert.equal(fake.blobHits[0].url, "https://blob.test/zip/2");
    assert.equal(fake.blobHits[0].hasAuth, false);
    const zipCall = fake.calls.find((c) => c.url.endsWith("/actions/artifacts/2/zip"));
    assert.equal(zipCall.headers.authorization, `Bearer ${TOKEN}`);
  });

  test("serves a virtual run's parts out of <name>-m5cardputer-virtual (the URL carries only the run), never the -elf one", async () => {
    const fake = fakeGitHub({ artifacts: { 107: [makeArtifact(11, { name: "stellar-map-m5cardputer-virtual-elf" }), makeArtifact(12, { name: "stellar-map-m5cardputer-virtual" })] }, zips: { 12: makeZip() } });
    const res = await get(fake, "107", "firmware.bin");
    assert.equal(res.status, 200);
    assert.equal(res.headers["Content-Length"], String(DEFAULT_PARTS["firmware.bin"].byteLength));
    assert.deepEqual(fake.blobHits.map((h) => h.url), ["https://blob.test/zip/12"]);
    const elfOnly = fakeGitHub({ artifacts: { 108: [makeArtifact(13, { name: "stellar-map-m5cardputer-elf" }), makeArtifact(14, { name: "stellar-map-m5cardputer-virtual-elf" })] } });
    await assert.rejects(get(elfOnly, "108", "firmware.bin"), (e) => e.status === 404 && /run 108 has no \*-\{m5cardputer\|m5cardputer-virtual\} artifact/.test(e.message));
    assert.equal(elfOnly.blobHits.length, 0);
  });

  test("four parts of one run -> one zip download", async () => {
    const fake = fakeGitHub({ artifacts: { 102: [makeArtifact(3)] }, zips: { 3: makeZip() } });
    for (const f of PART_FILES) assert.equal((await get(fake, "102", f)).status, 200);
    assert.equal(fake.blobHits.length, 1);
  });

  test("expired artifact -> 404; run without artifacts -> 404", async () => {
    const fake = fakeGitHub({ artifacts: { 103: [makeArtifact(4, { expired: true })], 104: [] } });
    await assert.rejects(get(fake, "103", "firmware.bin"), (e) => e.status === 404 && /expired/.test(e.message));
    await assert.rejects(get(fake, "104", "firmware.bin"), (e) => e.status === 404 && /run 104 has no \*-\{m5cardputer\|m5cardputer-virtual\} artifact \(expired or never uploaded\)/.test(e.message));
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
