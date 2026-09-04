// GET /api/shim -> {shim_commit, repo, workflow, builds_in_flight}
//
// ---- OPERATOR NOTES (droidputter build proxy) --------------------------------------------------------
//
// What this is: a Vercel project (proxy/) of four Node 22 functions, zero framework, one dependency
// (fflate). The phone POSTs /api/build {repo, ref?, name?}; the proxy dispatches
// .github/workflows/build-app.yml on fcavalcantirj/droidputter@main (workflow_dispatch inputs repo, name,
// ref, request_id, shim), polls the run by its run-name ("build <repo>@<ref|HEAD> shim=<shim> req=<id>")
// and streams the parts (bootloader.bin 0x0, partitions.bin 0x8000, boot_app0.bin 0xe000,
// firmware.bin 0x10000) straight out of the run's <name>-m5cardputer artifact zip. Nothing is pre-built
// or hosted; a run's artifact (7-day retention) is the only cache, plus module memory while warm.
//
// Environment variables (Vercel project settings -> Environment Variables, Production + Preview):
//   GITHUB_TOKEN  required. Fine-grained personal access token scoped to the ONE repository
//                 fcavalcantirj/droidputter with permissions
//                   Actions:  Read and write   (dispatch the workflow, list runs, download artifacts)
//                   Contents: Read             (latest commit touching shim/ = shim_commit)
//                 (Metadata: Read is implied.) Rotate by replacing the variable and redeploying.
//   GITHUB_REPO   default "fcavalcantirj/droidputter" -- the repo hosting the workflow.
//   WORKFLOW      default "build-app.yml".
//   BASE_URL      optional, e.g. https://droidputter-proxy.vercel.app -- the origin written into
//                 parts[].url; when unset the request's x-forwarded-host is used.
//   GITHUB_API    tests only (points the client at a fake); leave unset in production.
//
// Deploy (from THIS directory, with the Vercel CLI logged into the founder's PERSONAL account):
//   cd proxy && npm install && npm test
//   vercel link          # once: personal scope, project droidputter-proxy
//   vercel env add GITHUB_TOKEN production
//   vercel --prod
// Node version comes from package.json "engines": {"node": "22.x"}; vercel.json sets maxDuration 60 s
// on api/** (a cold artifact fetch is a ~2-4 MB zip). The rewrites in vercel.json mirror Vercel's own
// dynamic-route mapping ([id].js, [run]/[file].js); handlers also fall back to parsing the path.
//
// Clients MUST send POST bodies with Content-Type: application/json -- Vercel's Node helper consumes the
// stream and only exposes req.body for known content types; a JSON body without that header is empty.
//
// Smoke test after deploy:
//   curl -s $BASE/api/shim
//   curl -s -X POST $BASE/api/build -H 'content-type: application/json' -d '{"repo":"wisnc/stellar-map"}'
//   curl -s $BASE/api/build/<request_id>          # until status == ready (~2 min warm, ~4 min cold)
//   curl -sI $BASE/api/artifact/<run_id>/firmware.bin
//
// Limits: at most 6 queued+running builds (429 + Retry-After: 60 otherwise); a successful run younger
// than 24 h for the same repo@ref and shim_commit is reused (200 cached: true); an identical build
// already in flight is joined (202 with its request_id and run_id). GitHub REST budget per POST:
// 1 commits (cached 60 s) + 1 runs list + 1 dispatch; per status poll: 1-2 runs lists (+ artifacts list
// and one zip download the first time a run is ready on this instance).
// ------------------------------------------------------------------------------------------------------

import { countInFlight, resolveShimCommit } from "../lib/builds.js";
import { error, githubOf, json, vercel } from "../lib/http.js";

/**
 * @param {import("../lib/http.js").ProxyRequest} request
 * @param {import("../lib/http.js").Ctx} ctx
 */
export async function handle(request, ctx) {
  if (request.method !== "GET") return error(405, "method not allowed");
  const gh = githubOf(ctx);
  const [shim, runs] = await Promise.all([resolveShimCommit(gh, ctx.now), gh.listRuns()]);
  return json(200, { shim_commit: shim, repo: gh.repo, workflow: gh.workflow, builds_in_flight: countInFlight(runs) });
}

export default vercel(handle);
