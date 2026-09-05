import assert from "node:assert/strict";
import { Readable } from "node:stream";
import { beforeEach, describe, test } from "node:test";
import handler, { handle } from "../api/verdict.js";
import { VERDICT_KEYS, ValidationError, validateVerdict } from "../lib/validate.js";
import { VERDICT_BODY_LIMIT, VERDICT_RATE_LIMIT, _resetRateLimit, issueBody, issueTitle } from "../lib/verdicts.js";
import { REPO, TOKEN, ctxWith, fakeGitHub, invoke, parse, req } from "./helpers.js";

const SHA = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
const TODAY = "2026-09-04";
const NOW = Date.parse(`${TODAY}T12:00:00Z`);
const VERDICT = Object.freeze({ name: "stellar-map", env: "m5cardputer", firmware_sha256: SHA, shim_commit: "abc1234", board: "M5Cardputer", result: "works", note: "boots; GPS fix in 40 s", date: TODAY, reporter: "device-1a2b3c4d" });
const TITLE = `[verdict] stellar-map/m5cardputer works on M5Cardputer (${SHA.slice(0, 12)})`;
const ISSUES_PATH = `/repos/${REPO}/issues`;
const BLOCK_RE = /```json\s*\r?\n(.*?)\r?\n\s*```/is; // the regex tools/fold_verdict.py extracts the record with
const with_ = (patch) => ({ ...VERDICT, ...patch });
const post = (body, o = {}) => req({ method: "POST", path: "/api/verdict", body, ...o });
const bad = (body, re) => assert.throws(() => validateVerdict(body, { today: TODAY }), (e) => e instanceof ValidationError && e.status === 400 && re.test(e.message));

beforeEach(() => _resetRateLimit());

describe("validateVerdict", () => {
  test("full record passes unchanged, keys in verdicts.json order", () => {
    const rec = validateVerdict({ ...VERDICT }, { today: "1999-01-01" });
    assert.deepEqual(rec, VERDICT);
    assert.deepEqual(Object.keys(rec), VERDICT_KEYS);
  });

  test("what the phone sends: null shim_commit/reporter dropped, empty note/date defaulted, sha and result lowercased, fields trimmed, unknown keys ignored", () => {
    const rec = validateVerdict(
      { name: " stellar-map ", env: "m5cardputer", firmware_sha256: SHA.toUpperCase(), shim_commit: null, board: "M5Cardputer", result: "Works", note: "", date: "", reporter: null, extra: "ignored" },
      { today: TODAY },
    );
    assert.deepEqual(rec, { name: "stellar-map", env: "m5cardputer", firmware_sha256: SHA, board: "M5Cardputer", result: "works", note: "", date: TODAY });
    assert.deepEqual(Object.keys(rec), ["name", "env", "firmware_sha256", "board", "result", "note", "date"]);
    assert.equal(validateVerdict(with_({ note: "line 1\nline 2" })).note, "line 1\nline 2");
  });

  test("rejects: not an object, missing/empty/long/typed fields, bad sha, bad result, bad date, control characters", () => {
    for (const body of [undefined, null, "stellar-map", 7, [VERDICT]]) bad(body, /^body must be a JSON object/);
    bad((({ name, ...rest }) => rest)(VERDICT), /^missing name$/);
    bad(with_({ name: "" }), /^empty name$/);
    bad(with_({ name: "   " }), /^empty name$/);
    bad(with_({ name: 42 }), /^name is not a string$/);
    bad(with_({ name: "x".repeat(81) }), /^name is longer than 80 characters$/);
    bad(with_({ name: "a\nb" }), /^name contains control characters$/);
    bad(with_({ env: "x".repeat(41) }), /^env is longer than 40 characters$/);
    bad(with_({ env: null }), /^missing env$/);
    bad(with_({ board: "x".repeat(41) }), /^board is longer than 40 characters$/);
    bad(with_({ firmware_sha256: undefined }), /^missing firmware_sha256$/);
    bad(with_({ firmware_sha256: SHA.slice(0, 63) }), /^firmware_sha256 is not 64 hex characters$/);
    bad(with_({ firmware_sha256: `${SHA.slice(0, 63)}g` }), /^firmware_sha256 is not 64 hex characters$/);
    bad(with_({ firmware_sha256: `${SHA}0` }), /^firmware_sha256 is not 64 hex characters$/);
    bad(with_({ result: "maybe" }), /^result must be 'works' or 'broken'$/);
    bad(with_({ result: "" }), /^empty result$/);
    bad(with_({ result: true }), /^result is not a string$/);
    bad(with_({ note: "x".repeat(501) }), /^note is longer than 500 characters$/);
    bad(with_({ note: 7 }), /^note is not a string$/);
    bad(with_({ date: "09/04/2026" }), /^date is not YYYY-MM-DD$/);
    bad(with_({ date: "2026-9-4" }), /^date is not YYYY-MM-DD$/);
    bad(with_({ reporter: "x".repeat(61) }), /^reporter is longer than 60 characters$/);
    bad(with_({ shim_commit: "x".repeat(41) }), /^shim_commit is longer than 40 characters$/);
  });
});

describe("POST /api/verdict", () => {
  test("files the issue: POST /repos/<repo>/issues, exact title, label verdict, body = json block with ordered keys -> 201", async () => {
    const fake = fakeGitHub();
    const res = await handle(post(VERDICT), ctxWith(fake, { now: () => NOW }));
    assert.equal(res.status, 201);
    assert.deepEqual(parse(res), { issue_number: 101, issue_url: `https://github.com/${REPO}/issues/101`, request: VERDICT });

    assert.equal(fake.calls.length, 1);
    assert.equal(fake.calls[0].method, "POST");
    assert.equal(fake.calls[0].url, `https://gh.test${ISSUES_PATH}`);
    assert.equal(fake.calls[0].headers["content-type"], "application/json");
    assert.equal(fake.calls[0].headers.authorization, `Bearer ${TOKEN}`);

    const issue = fake.issues[0];
    assert.deepEqual(Object.keys(issue), ["title", "body", "labels"]);
    assert.equal(issue.title, TITLE);
    assert.deepEqual(issue.labels, ["verdict"]);
    assert.ok(issue.body.startsWith("Reported from the Droidputter app via the build proxy.\n\n```json\n"));
    assert.ok(issue.body.endsWith("\n```\n"));
    const block = BLOCK_RE.exec(issue.body);
    assert.ok(block, "fold_verdict.py's ```json block regex must match");
    assert.equal(block[1], JSON.stringify(VERDICT, null, 2));
    assert.deepEqual(JSON.parse(block[1]), VERDICT);
    assert.deepEqual(Object.keys(JSON.parse(block[1])), VERDICT_KEYS);
    assert.equal(issue.title, issueTitle(VERDICT));
    assert.equal(issue.body, issueBody(VERDICT));
  });

  test("the phone's payload: uppercase sha lowercased, null shim/reporter dropped, empty date = today UTC from the clock", async () => {
    const fake = fakeGitHub();
    const body = { name: "stellar-map", env: "m5cardputer", firmware_sha256: SHA.toUpperCase(), shim_commit: null, board: "M5Cardputer", result: "broken", note: "", date: "", reporter: "device-1a2b3c4d" };
    const res = await handle(post(body), ctxWith(fake, { now: () => Date.parse("2026-12-31T23:59:59Z") }));
    assert.equal(res.status, 201);
    const expected = { name: "stellar-map", env: "m5cardputer", firmware_sha256: SHA, board: "M5Cardputer", result: "broken", note: "", date: "2026-12-31", reporter: "device-1a2b3c4d" };
    assert.deepEqual(parse(res).request, expected);
    assert.equal(fake.issues[0].title, `[verdict] stellar-map/m5cardputer broken on M5Cardputer (${SHA.slice(0, 12)})`);
    assert.deepEqual(JSON.parse(BLOCK_RE.exec(fake.issues[0].body)[1]), expected);
  });

  test("422 on the labelled create -> once more without labels -> 201", async () => {
    const fake = fakeGitHub({ issueStatuses: [422] });
    const res = await handle(post(VERDICT), ctxWith(fake));
    assert.equal(res.status, 201);
    assert.equal(parse(res).issue_number, 102);
    assert.equal(fake.issues.length, 2);
    assert.deepEqual(fake.issues[0].labels, ["verdict"]);
    assert.deepEqual(Object.keys(fake.issues[1]), ["title", "body"]);
    assert.equal(fake.issues[1].title, fake.issues[0].title);
    assert.equal(fake.issues[1].body, fake.issues[0].body);
  });

  test("403 / 404 from GitHub (PAT without Issues: write) -> 502 with the operator message; a 422 that survives the retry too", async () => {
    for (const statuses of [[403], [404], [422, 422], [422, 403], [422, 404]]) {
      const fake = fakeGitHub({ issueStatuses: statuses });
      const res = await handle(post(VERDICT), ctxWith(fake));
      assert.equal(res.status, 502, String(statuses));
      assert.deepEqual(parse(res), { error: `proxy token lacks Issues: write on ${REPO}` });
      assert.equal(fake.issues.length, statuses.length);
      assert.ok(!res.body.includes(TOKEN));
    }
  });

  test("another GitHub failure -> 502 {error} naming the path, never the token", async () => {
    const fake = fakeGitHub({ issueStatuses: [500] });
    const res = await handle(post(VERDICT), ctxWith(fake));
    assert.equal(res.status, 502);
    assert.deepEqual(parse(res), { error: `github 500 on ${ISSUES_PATH}: boom` });
    assert.equal(fake.issues.length, 1);
    assert.ok(!res.body.includes(TOKEN));
  });

  test("bad record -> 400 before any GitHub call", async () => {
    const fake = fakeGitHub();
    const cases = [
      [with_({ firmware_sha256: "abc" }), /^firmware_sha256 is not 64 hex characters$/],
      [with_({ result: "meh" }), /^result must be 'works' or 'broken'$/],
      [with_({ note: "n".repeat(501) }), /^note is longer than 500 characters$/],
      [(({ name, ...rest }) => rest)(VERDICT), /^missing name$/],
      [[], /^body must be a JSON object/],
    ];
    for (const [body, re] of cases) {
      await assert.rejects(handle(post(body), ctxWith(fake)), (e) => e.status === 400 && re.test(e.message));
    }
    assert.equal(fake.calls.length, 0);
  });

  test(`${VERDICT_RATE_LIMIT} verdicts an hour per address: the next -> 429 + Retry-After and is not counted; the window slides`, async () => {
    let t = NOW;
    const fake = fakeGitHub();
    const ctx = ctxWith(fake, { now: () => t });
    for (let i = 0; i < VERDICT_RATE_LIMIT; i++) {
      assert.equal((await handle(post(VERDICT), ctx)).status, 201, `call ${i + 1}`);
      t += 60_000; // one a minute
    }
    const refused = await handle(post(VERDICT), ctx); // 20 min after the first
    assert.equal(refused.status, 429);
    assert.equal(refused.headers["Retry-After"], "2400");
    assert.deepEqual(parse(refused), { error: "too many verdicts from this address", retry_after_s: 2400 });
    assert.equal(fake.issues.length, VERDICT_RATE_LIMIT);

    t = NOW + 3_599_999;
    const almost = await handle(post(VERDICT), ctx);
    assert.equal(almost.status, 429);
    assert.equal(parse(almost).retry_after_s, 1);

    t = NOW + 3_600_000; // the first slot has expired
    assert.equal((await handle(post(VERDICT), ctx)).status, 201);
    const again = await handle(post(VERDICT), ctx);
    assert.equal(again.status, 429);
    assert.equal(parse(again).retry_after_s, 60); // the second slot expires a minute later
    assert.equal(fake.issues.length, VERDICT_RATE_LIMIT + 1);
  });

  test("address = first X-Forwarded-For hop, else the socket; other addresses unaffected; a rejected record still costs a slot", async () => {
    const fake = fakeGitHub();
    const ctx = ctxWith(fake, { now: () => NOW });
    const viaEdge = { headers: { "x-forwarded-for": "203.0.113.5, 10.1.2.3" } };
    for (let i = 0; i < VERDICT_RATE_LIMIT - 1; i++) assert.equal((await handle(post(VERDICT, viaEdge), ctx)).status, 201);
    await assert.rejects(handle(post(with_({ result: "meh" }), viaEdge), ctx), (e) => e.status === 400); // the 20th slot
    assert.equal((await handle(post(VERDICT, { headers: { "x-forwarded-for": "203.0.113.5" } }), ctx)).status, 429); // same first hop
    assert.equal((await handle(post(VERDICT, { headers: { "x-forwarded-for": "10.1.2.3, 203.0.113.5" } }), ctx)).status, 201); // another first hop
    assert.equal((await handle(post(VERDICT, { remoteAddress: "203.0.113.5" }), ctx)).status, 429); // no XFF: the socket is the client
    assert.equal((await handle(post(VERDICT, { remoteAddress: "192.0.2.9" }), ctx)).status, 201);
    assert.equal(fake.issues.length, VERDICT_RATE_LIMIT + 1);
  });

  test(`body over ${VERDICT_BODY_LIMIT} bytes -> 413 before the rate limit or GitHub`, async () => {
    const fake = fakeGitHub();
    const res = await handle(post(VERDICT, { bodyBytes: VERDICT_BODY_LIMIT + 1 }), ctxWith(fake));
    assert.equal(res.status, 413);
    assert.deepEqual(parse(res), { error: `body larger than ${VERDICT_BODY_LIMIT} bytes` });
    assert.equal(fake.calls.length, 0);
    assert.equal((await handle(post(VERDICT, { bodyBytes: VERDICT_BODY_LIMIT }), ctxWith(fake))).status, 201);
  });

  test("GET -> 405; missing token -> 500 after validation, before any GitHub call", async () => {
    const fake = fakeGitHub();
    assert.equal((await handle(req({ path: "/api/verdict" }), ctxWith(fake))).status, 405);
    await assert.rejects(handle(post(VERDICT), ctxWith(fake, { token: "" })), (e) => e.status === 500 && /GITHUB_TOKEN/.test(e.message) && /Issues/.test(e.message));
    assert.equal(fake.calls.length, 0);
  });
});

describe("vercel adapter: /api/verdict", () => {
  const saved = { ...process.env };
  const restore = () => {
    for (const k of ["GITHUB_TOKEN", "GITHUB_REPO", "GITHUB_API", "BASE_URL"]) {
      if (saved[k] === undefined) delete process.env[k];
      else process.env[k] = saved[k];
    }
  };

  test("streamed JSON -> 201 with CORS; 5 KB body -> 413 before parsing; pre-parsed body sized by Content-Length; bad JSON -> 400; OPTIONS -> 204; GET -> 405", async () => {
    const fake = fakeGitHub();
    const realFetch = globalThis.fetch;
    process.env.GITHUB_TOKEN = TOKEN;
    process.env.GITHUB_REPO = REPO;
    process.env.GITHUB_API = "https://gh.test";
    globalThis.fetch = fake.fetch;
    try {
      const ok = await invoke(handler, { method: "POST", url: "/api/verdict", body: JSON.stringify(VERDICT), headers: { "content-type": "application/json", "x-forwarded-for": "203.0.113.77" } });
      assert.equal(ok.status, 201);
      assert.equal(ok.headers["Access-Control-Allow-Origin"], "*");
      assert.equal(ok.headers["Content-Type"], "application/json; charset=utf-8");
      assert.equal(JSON.parse(ok.body).issue_number, 101);
      assert.equal(fake.issues[0].title, TITLE);

      const big = await invoke(handler, { method: "POST", url: "/api/verdict", body: `{${"x".repeat(5000)}` }); // not even JSON: refused by size first
      assert.equal(big.status, 413);
      assert.equal(big.headers["Access-Control-Allow-Origin"], "*");
      assert.deepEqual(JSON.parse(big.body), { error: `body larger than ${VERDICT_BODY_LIMIT} bytes` });

      // Vercel's helper has already parsed req.body: Content-Length is the size that counts.
      const preParsed = (contentLength) =>
        Object.assign(Readable.from([]), { method: "POST", url: "/api/verdict", headers: { host: "proxy.test", "content-type": "application/json", "content-length": contentLength }, body: { ...VERDICT }, socket: { remoteAddress: "192.0.2.1" } });
      const sized = await new Promise((resolve) => {
        const out = { status: 0, body: undefined };
        handler(preParsed("5000"), { writeHead: (status) => (out.status = status), end: (body) => resolve({ ...out, body }) });
      });
      assert.equal(sized.status, 413);
      const fine = await new Promise((resolve) => {
        const out = { status: 0, body: undefined };
        handler(preParsed("300"), { writeHead: (status) => (out.status = status), end: (body) => resolve({ ...out, body }) });
      });
      assert.equal(fine.status, 201);
      assert.equal(fake.issues.length, 2);

      const badJson = await invoke(handler, { method: "POST", url: "/api/verdict", body: "{nope" });
      assert.equal(badJson.status, 400);
      assert.deepEqual(JSON.parse(badJson.body), { error: "body is not valid JSON" });

      assert.equal((await invoke(handler, { method: "OPTIONS", url: "/api/verdict" })).status, 204);
      assert.equal((await invoke(handler, { method: "GET", url: "/api/verdict" })).status, 405);
    } finally {
      globalThis.fetch = realFetch;
      restore();
    }
  });
});
