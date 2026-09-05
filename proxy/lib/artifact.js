// The build artifact: ONE zip named <name>-<env> (env = m5cardputer for the Cardputer ADV, m5cardputer-virtual
// for a bare ESP32-S3 with the phone as its only screen) holding bootloader.bin, partitions.bin, boot_app0.bin,
// firmware.bin, build.json (one JSON line from tools/overlay.py) and SHA256SUMS (sha256sum format). The run's
// other artifact, <name>-<env>-elf, is the linked ELF and is never selected here. Downloaded once per run,
// unzipped in memory with fflate, kept in module memory.

import { createHash } from "node:crypto";
import { strFromU8, unzipSync } from "fflate";

/** Flash offsets of the four parts on the ESP32-S3 (arduino-esp32 2.x default layout, 8 MB flash). */
export const PART_OFFSETS = Object.freeze({
  "bootloader.bin": 0x0,
  "partitions.bin": 0x8000,
  "boot_app0.bin": 0xe000,
  "firmware.bin": 0x10000,
});
export const PART_FILES = Object.freeze(Object.keys(PART_OFFSETS));
/** The two PlatformIO envs every overlay carries (tools/overlay.py ENV_TEMPLATE); build-app.yml refuses any other. */
export const BUILD_ENVS = Object.freeze(["m5cardputer", "m5cardputer-virtual"]);
export const DEFAULT_ENV = BUILD_ENVS[0];
const ELF_SUFFIX = "-elf";
const CACHE_MAX = 8;

export class ArtifactError extends Error {
  /** @param {string} message @param {number} status */
  constructor(message, status) {
    super(message);
    this.name = "ArtifactError";
    this.status = status;
  }
}

/**
 * @typedef {object} ParsedArtifact
 * @property {Map<string, Uint8Array>} files basename -> bytes (directories dropped)
 * @property {Record<string, unknown> | null} build the build.json record (upstream_commit, ram, flash, ...)
 * @property {Map<string, string>} sums basename -> sha256 hex from SHA256SUMS
 * @property {{id: number, name: string, size_in_bytes: number, expires_at: string} | null} artifact
 */

/**
 * sha256sum output: "<64 hex>  <name>" per line (a leading '*' marks binary mode).
 * @param {string} text
 */
export function parseSha256Sums(text) {
  const sums = new Map();
  for (const line of text.split(/\r?\n/)) {
    const m = /^([0-9a-fA-F]{64})\s+\*?(.+?)\s*$/.exec(line);
    if (m) sums.set(basename(m[2]), m[1].toLowerCase());
  }
  return sums;
}

/** @param {string} p */
function basename(p) {
  const parts = p.split("/");
  return parts[parts.length - 1];
}

/**
 * @param {Uint8Array} zipBytes
 * @returns {ParsedArtifact}
 */
export function parseArtifactZip(zipBytes) {
  let entries;
  try {
    entries = unzipSync(zipBytes);
  } catch (e) {
    throw new ArtifactError(`artifact is not a zip: ${e instanceof Error ? e.message : String(e)}`, 502);
  }
  const files = new Map();
  for (const [path, data] of Object.entries(entries)) {
    if (path.endsWith("/")) continue;
    files.set(basename(path), data);
  }
  let build = null;
  const buildJson = files.get("build.json");
  if (buildJson) {
    const text = strFromU8(buildJson).trim();
    try {
      const parsed = JSON.parse(text.split("\n").pop() || "null");
      build = parsed && typeof parsed === "object" ? parsed : null;
    } catch {
      build = null;
    }
  }
  const sumsFile = files.get("SHA256SUMS");
  const sums = sumsFile ? parseSha256Sums(strFromU8(sumsFile)) : new Map();
  return { files, build, sums, artifact: null };
}

/** @param {Uint8Array} bytes */
export function sha256Hex(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

/**
 * The phone's flash plan: the four parts with fixed offsets, sizes from the zip, sha256 from SHA256SUMS
 * (verified against the bytes) and the proxy URL that serves each one.
 * @param {ParsedArtifact} parsed
 * @param {{runId: string, baseUrl: string}} where
 */
export function partsOf(parsed, { runId, baseUrl }) {
  return PART_FILES.map((file) => {
    const bytes = parsed.files.get(file);
    if (!bytes) throw new ArtifactError(`artifact of run ${runId} has no ${file}`, 502);
    const computed = sha256Hex(bytes);
    const listed = parsed.sums.get(file);
    if (listed && listed !== computed) throw new ArtifactError(`${file} does not match SHA256SUMS in run ${runId}`, 502);
    return {
      file,
      // Contract v1: offsets are "0x…" strings (the phone parses them as hex, like apps/catalog.json).
      offset: "0x" + PART_OFFSETS[/** @type {keyof typeof PART_OFFSETS} */ (file)].toString(16),
      size: bytes.byteLength,
      sha256: listed || computed,
      url: `${baseUrl}/api/artifact/${runId}/${file}`,
    };
  });
}

/** @param {ParsedArtifact} parsed */
export function buildSummary(parsed) {
  const b = parsed.build || {};
  return {
    upstream_commit: typeof b.upstream_commit === "string" ? b.upstream_commit : null,
    ram: typeof b.ram === "string" ? b.ram : null,
    flash: typeof b.flash === "string" ? b.flash : null,
  };
}

/**
 * @typedef {object} ArtifactSelector
 * @property {string | null} [name] overlay name (build-app.yml input `name`) when known
 * @property {string | null} [env] PlatformIO env (one of BUILD_ENVS) when known
 */

/**
 * The flashable artifact of a run. With the name: the EXACT `<name>-<env>`. Without it (the run name carries
 * repo, ref, env, shim and request id, not the overlay name): the artifact whose name ends with `-<env>` and
 * not with `-elf`; without the env either (GET /api/artifact/{run}/{file} knows only the run): `-<env>` for any
 * env of BUILD_ENVS. One run builds one env, so the fallback is unambiguous. Never the `-elf` artifact.
 * @param {any[]} artifacts the run's artifacts as GitHub lists them
 * @param {ArtifactSelector} [sel]
 * @returns {any | undefined}
 */
export function selectArtifact(artifacts, { name, env } = {}) {
  const envs = env ? [env] : BUILD_ENVS;
  const named = (a) => typeof a.name === "string" && !a.name.endsWith(ELF_SUFFIX);
  if (name) return artifacts.find((a) => named(a) && envs.some((e) => a.name === `${name}-${e}`));
  return artifacts.find((a) => named(a) && envs.some((e) => a.name.endsWith(`-${e}`)));
}

/** What selectArtifact looked for, for error messages: `<name>-<env>`, `*-<env>` or `*-{m5cardputer|m5cardputer-virtual}`. */
export function artifactLabel({ name, env } = {}) {
  const e = env || `{${BUILD_ENVS.join("|")}}`;
  return `${name || "*"}-${e}`;
}

/** @type {Map<string, ParsedArtifact>} keyed by `${repo}#${runId}` (one run = one env = one flashable artifact); best effort, survives while the instance is warm */
const cache = new Map();

export function _resetArtifactCache() {
  cache.clear();
}

/**
 * Find + download + unzip the run's `<name>-<env>` artifact (cached per run; see selectArtifact).
 * @param {import("./github.js").GitHub} gh
 * @param {string} runId
 * @param {ArtifactSelector} [sel] name/env when the caller knows them (the status route parses env from the run name)
 * @returns {Promise<ParsedArtifact>}
 */
export async function loadArtifact(gh, runId, sel = {}) {
  const key = `${gh.repo}#${runId}`;
  const hit = cache.get(key);
  if (hit) return hit;

  const artifacts = await gh.listArtifacts(runId);
  const match = selectArtifact(artifacts, sel);
  if (!match) throw new ArtifactError(`run ${runId} has no ${artifactLabel(sel)} artifact (expired or never uploaded)`, 404);
  if (match.expired) throw new ArtifactError(`artifact of run ${runId} expired (${match.expires_at || "retention 7 days"})`, 404);

  const parsed = parseArtifactZip(await gh.downloadArtifactZip(match.id));
  parsed.artifact = { id: match.id, name: match.name, size_in_bytes: match.size_in_bytes, expires_at: match.expires_at };
  if (cache.size >= CACHE_MAX) cache.delete(cache.keys().next().value);
  cache.set(key, parsed);
  return parsed;
}
