import assert from "node:assert/strict";
import { describe, test } from "node:test";
import {
  ValidationError,
  defaultName,
  validateBuildRequest,
  validatePartFile,
  validateRequestId,
  validateRunId,
} from "../lib/validate.js";

const bad = (fn) => assert.throws(fn, (e) => e instanceof ValidationError && e.status === 400);

describe("validateBuildRequest", () => {
  test("defaults: ref '' and name = repo name lowercased", () => {
    assert.deepEqual(validateBuildRequest({ repo: "wisnc/Stellar-Map" }), { repo: "wisnc/Stellar-Map", ref: "", name: "stellar-map" });
  });
  test("explicit ref and name pass through", () => {
    assert.deepEqual(validateBuildRequest({ repo: "a/b", ref: "v1.2.3", name: "custom_name.v2" }), { repo: "a/b", ref: "v1.2.3", name: "custom_name.v2" });
    assert.equal(validateBuildRequest({ repo: "a/b", ref: "refs/heads/main" }).ref, "refs/heads/main");
    assert.equal(validateBuildRequest({ repo: "a/b", ref: "0123456789abcdef0123456789abcdef01234567" }).ref.length, 40);
  });
  test("repo must be owner/name", () => {
    for (const repo of ["wisnc", "a/b/c", "a b/c", "", "a/b?x", "/b", "a/", "a/b\n"]) bad(() => validateBuildRequest({ repo }));
    bad(() => validateBuildRequest({ repo: 42 }));
    bad(() => validateBuildRequest({}));
  });
  test("ref: no spaces, no ~^:, max 100 chars", () => {
    bad(() => validateBuildRequest({ repo: "a/b", ref: "feat branch" }));
    bad(() => validateBuildRequest({ repo: "a/b", ref: "a~b" }));
    bad(() => validateBuildRequest({ repo: "a/b", ref: "x".repeat(101) }));
    assert.equal(validateBuildRequest({ repo: "a/b", ref: "x".repeat(100) }).ref.length, 100);
  });
  test("name: lowercase [a-z0-9_.-], 1..64", () => {
    bad(() => validateBuildRequest({ repo: "a/b", name: "Stellar" }));
    bad(() => validateBuildRequest({ repo: "a/b", name: "with space" }));
    bad(() => validateBuildRequest({ repo: "a/b", name: "a".repeat(65) }));
    assert.equal(validateBuildRequest({ repo: "a/b", name: "" }).name, "b");
  });
  test("body must be an object", () => {
    bad(() => validateBuildRequest(undefined));
    bad(() => validateBuildRequest("x"));
    bad(() => validateBuildRequest([]));
  });
  test("defaultName lowercases and squashes stray characters to '-'", () => {
    assert.equal(defaultName("o/My.Repo_X"), "my.repo_x");
    assert.equal(defaultName("o/Weird$Name"), "weird-name");
    assert.equal(defaultName("o/" + "A".repeat(80)).length, 64);
  });
});

describe("path params", () => {
  test("part file must be one of the four .bin names", () => {
    for (const f of ["bootloader.bin", "partitions.bin", "boot_app0.bin", "firmware.bin"]) assert.equal(validatePartFile(f), f);
    for (const f of ["evil.bin", "../firmware.bin", "build.json", "SHA256SUMS", "", undefined]) bad(() => validatePartFile(f));
  });
  test("run id is decimal digits", () => {
    assert.equal(validateRunId("33919498873"), "33919498873");
    for (const r of ["12a", "", "-1", "1".repeat(21)]) bad(() => validateRunId(r));
  });
  test("request id is a uuid-ish token", () => {
    assert.equal(validateRequestId("6f1c2a2e-1b2c-4d3e-8f90-0123456789ab"), "6f1c2a2e-1b2c-4d3e-8f90-0123456789ab");
    for (const r of ["a/b", "", "x".repeat(65), "a b"]) bad(() => validateRequestId(r));
  });
});
