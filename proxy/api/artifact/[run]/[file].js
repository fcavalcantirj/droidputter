// GET /api/artifact/{run_id}/{file} -> the bytes of one part out of the run's <name>-<env> artifact (the same
// selection as the status route -- lib/artifact.js selectArtifact -- with the env taken from the artifact's
// own -m5cardputer / -m5cardputer-virtual suffix since the URL carries only the run; the -elf artifact is never
// served). 400 unless file is one of the four .bin names, 404 when the run has no (unexpired) artifact.

import { loadArtifact, sha256Hex } from "../../../lib/artifact.js";
import { error, githubOf, octets, param, vercel } from "../../../lib/http.js";
import { validatePartFile, validateRunId } from "../../../lib/validate.js";

/**
 * @param {import("../../../lib/http.js").ProxyRequest} request
 * @param {import("../../../lib/http.js").Ctx} ctx
 */
export async function handle(request, ctx) {
  if (request.method !== "GET") return error(405, "method not allowed");
  const file = validatePartFile(param(request, "file", 1));
  const runId = validateRunId(param(request, "run", 2));
  const parsed = await loadArtifact(githubOf(ctx), runId);
  const bytes = parsed.files.get(file);
  if (!bytes) return error(404, `artifact of run ${runId} has no ${file}`);
  return octets(200, bytes, {
    "Cache-Control": "public, max-age=31536000, immutable",
    ETag: `"${parsed.sums.get(file) || sha256Hex(bytes)}"`,
    "Content-Disposition": `attachment; filename="${file}"`,
  });
}

export default vercel(handle);
