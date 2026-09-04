// Input validation for the v1 API contract. Every failure is a ValidationError (HTTP 400).

import { PART_FILES } from "./artifact.js";

export const REPO_RE = /^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/;
export const REF_RE = /^[A-Za-z0-9_./-]{0,100}$/;
export const NAME_RE = /^[a-z0-9_.-]{1,64}$/;
export const REQUEST_ID_RE = /^[A-Za-z0-9_.-]{1,64}$/;
export const RUN_ID_RE = /^[0-9]{1,20}$/;

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
 * @param {unknown} body parsed JSON body of POST /api/build
 * @returns {{repo: string, ref: string, name: string}}
 */
export function validateBuildRequest(body) {
  if (!body || typeof body !== "object" || Array.isArray(body)) {
    throw new ValidationError("body must be a JSON object {repo, ref?, name?}");
  }
  const { repo, ref = "", name } = /** @type {Record<string, unknown>} */ (body);
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
  return { repo, ref, name: finalName };
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
