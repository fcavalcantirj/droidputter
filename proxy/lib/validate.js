// Input validation for the v1 API contract. Every failure is a ValidationError (HTTP 400).

import { BUILD_ENVS, DEFAULT_ENV, PART_FILES } from "./artifact.js";

export { BUILD_ENVS, DEFAULT_ENV };
export const REPO_RE = /^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/;
export const REF_RE = /^[A-Za-z0-9_./-]{0,100}$/;
export const NAME_RE = /^[a-z0-9_.-]{1,64}$/;
export const REQUEST_ID_RE = /^[A-Za-z0-9_.-]{1,64}$/;
export const RUN_ID_RE = /^[0-9]{1,20}$/;
export const SHA256_RE = /^[0-9a-f]{64}$/;
export const DATE_RE = /^\d{4}-\d{2}-\d{2}$/;
export const CONTROL_RE = /[\x00-\x1f\x7f]/;
export const VERDICT_RESULTS = Object.freeze(["works", "broken"]);
export const VERDICT_KEYS = Object.freeze(["name", "env", "firmware_sha256", "shim_commit", "board", "result", "note", "date", "reporter"]);

export class ValidationError extends Error {
  /** @param {string} message */
  constructor(message) {
    super(message);
    this.name = "ValidationError";
    this.status = 400;
  }
}

/**
 * Default overlay name: the repo's name, lowercased, anything outside [a-z0-9_.-] becomes '-'.
 * @param {string} repo owner/name
 */
export function defaultName(repo) {
  const tail = repo.split("/")[1] || repo;
  return tail.toLowerCase().replace(/[^a-z0-9_.-]/g, "-").slice(0, 64);
}

/**
 * `env` picks the PlatformIO env of the overlay: m5cardputer (Cardputer ADV, the default) or m5cardputer-virtual
 * (bare ESP32-S3, the phone is the only screen). Like `name`, a missing, null or empty env takes the default;
 * anything else must be exactly one of the two names.
 * @param {unknown} body parsed JSON body of POST /api/build
 * @returns {{repo: string, ref: string, name: string, env: string}}
 */
export function validateBuildRequest(body) {
  if (!body || typeof body !== "object" || Array.isArray(body)) {
    throw new ValidationError("body must be a JSON object {repo, ref?, name?, env?}");
  }
  const { repo, ref = "", name, env } = /** @type {Record<string, unknown>} */ (body);
  if (typeof repo !== "string" || !REPO_RE.test(repo)) {
    throw new ValidationError("repo must match owner/name ([A-Za-z0-9_.-])");
  }
  if (typeof ref !== "string" || !REF_RE.test(ref)) {
    throw new ValidationError("ref must match ^[A-Za-z0-9_./-]{0,100}$ (branch, tag or sha)");
  }
  const finalName = name === undefined || name === null || name === "" ? defaultName(repo) : name;
  if (typeof finalName !== "string" || !NAME_RE.test(finalName)) {
    throw new ValidationError("name must match ^[a-z0-9_.-]{1,64}$");
  }
  const finalEnv = env === undefined || env === null || env === "" ? DEFAULT_ENV : env;
  if (typeof finalEnv !== "string" || !BUILD_ENVS.includes(finalEnv)) {
    throw new ValidationError(`env must be one of ${BUILD_ENVS.join(", ")}`);
  }
  return { repo, ref, name: finalName, env: finalEnv };
}

/** @param {unknown} id */
export function validateRequestId(id) {
  if (typeof id !== "string" || !REQUEST_ID_RE.test(id)) throw new ValidationError("bad request_id");
  return id;
}

/** @param {unknown} run */
export function validateRunId(run) {
  if (typeof run !== "string" || !RUN_ID_RE.test(run)) throw new ValidationError("bad run_id");
  return run;
}

/** @param {unknown} file */
export function validatePartFile(file) {
  if (typeof file !== "string" || !PART_FILES.includes(file)) {
    throw new ValidationError(`file must be one of ${PART_FILES.join(", ")}`);
  }
  return file;
}

/**
 * @typedef {object} VerdictRecord one entry of apps/verdicts.json (android/core .../catalog/Verdict.kt)
 * @property {string} name
 * @property {string} env
 * @property {string} firmware_sha256 64 lowercase hex
 * @property {string} [shim_commit]
 * @property {string} board
 * @property {"works" | "broken"} result
 * @property {string} note
 * @property {string} date YYYY-MM-DD
 * @property {string} [reporter] anonymous device id from the phone
 */

/**
 * The Verdict record the phone posts, checked the way tools/fold_verdict.py does (same limits, same
 * messages) except that over-long fields are refused instead of truncated. Missing, null or empty optional
 * fields are dropped (shim_commit, reporter) or defaulted (note "", date = today UTC). Keys come out in
 * VERDICT_KEYS order so the pretty-printed issue body reads like verdicts.json.
 * @param {unknown} body parsed JSON body of POST /api/verdict
 * @param {{today?: string}} [o] the date used when the record carries none
 * @returns {VerdictRecord}
 */
export function validateVerdict(body, { today = new Date().toISOString().slice(0, 10) } = {}) {
  if (!body || typeof body !== "object" || Array.isArray(body)) {
    throw new ValidationError("body must be a JSON object {name, env, firmware_sha256, board, result, ...}");
  }
  const raw = /** @type {Record<string, unknown>} */ (body);
  /** @param {string} key @param {boolean} required @param {number} [limit] */
  const text = (key, required, limit) => {
    const v = raw[key];
    if (v === undefined || v === null) {
      if (required) throw new ValidationError(`missing ${key}`);
      return "";
    }
    if (typeof v !== "string") throw new ValidationError(`${key} is not a string`);
    const s = v.trim();
    if (required && !s) throw new ValidationError(`empty ${key}`);
    if (limit !== undefined && s.length > limit) throw new ValidationError(`${key} is longer than ${limit} characters`);
    if (key !== "note" && CONTROL_RE.test(s)) throw new ValidationError(`${key} contains control characters`);
    return s;
  };
  const name = text("name", true, 80);
  const env = text("env", true, 40);
  const firmware_sha256 = text("firmware_sha256", true).toLowerCase();
  if (!SHA256_RE.test(firmware_sha256)) throw new ValidationError("firmware_sha256 is not 64 hex characters");
  const shim_commit = text("shim_commit", false, 40);
  const board = text("board", true, 40);
  const result = text("result", true).toLowerCase();
  if (!VERDICT_RESULTS.includes(result)) throw new ValidationError("result must be 'works' or 'broken'");
  const note = text("note", false, 500);
  const date = text("date", false, 10) || today;
  if (!DATE_RE.test(date)) throw new ValidationError("date is not YYYY-MM-DD");
  const reporter = text("reporter", false, 60);
  return {
    name,
    env,
    firmware_sha256,
    ...(shim_commit ? { shim_commit } : {}),
    board,
    result: /** @type {"works" | "broken"} */ (result),
    note,
    date,
    ...(reporter ? { reporter } : {}),
  };
}
