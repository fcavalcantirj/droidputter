// All GitHub REST calls the proxy makes, fetch-based. The token lives only in the request headers built
// here; GitHubError carries status + path so it can be surfaced to the phone without leaking anything.

const API_VERSION = "2022-11-28";
const USER_AGENT = "droidputter-proxy";

/** Paths on main whose newest commit is the build's "shim" identity (run-name shim=, cache key). */
export const SHIM_PATHS = Object.freeze(["shim", "tools/overlay.py"]);

export class GitHubError extends Error {
  /**
   * @param {string} message
   * @param {number} status HTTP status to surface (502 for upstream failures, 404 when GitHub says so)
   * @param {string} path GitHub path that failed (no token, no query secrets)
   * @param {number} [upstream] the status GitHub itself answered (0 when it could not be reached)
   */
  constructor(message, status, path, upstream = 0) {
    super(message);
    this.name = "GitHubError";
    this.status = status;
    this.path = path;
    this.upstream = upstream;
  }
}

/**
 * @param {object} opts
 * @param {string} opts.token
 * @param {string} opts.repo owner/name hosting the workflow
 * @param {string} opts.workflow workflow file name (build-app.yml)
 * @param {string} [opts.apiBase]
 * @param {typeof fetch} [opts.fetch]
 */
export function createGitHub({ token, repo, workflow, apiBase = "https://api.github.com", fetch = globalThis.fetch }) {
  const headers = {
    Accept: "application/vnd.github+json",
    Authorization: `Bearer ${token}`,
    "X-GitHub-Api-Version": API_VERSION,
    "User-Agent": USER_AGENT,
  };

  /**
   * @param {string} path e.g. /repos/o/n/actions/runs
   * @param {RequestInit} [init]
   * @returns {Promise<Response>}
   */
  async function raw(path, init = {}) {
    let res;
    try {
      res = await fetch(apiBase + path, { ...init, headers: { ...headers, ...(init.headers || {}) } });
    } catch (e) {
      throw new GitHubError(`github unreachable: ${e instanceof Error ? e.message : String(e)}`, 502, path);
    }
    const redirect = init.redirect === "manual" && res.status >= 300 && res.status < 400;
    if (!res.ok && !redirect) {
      const status = res.status === 404 ? 404 : 502;
      let detail = "";
      try {
        const j = await res.json();
        if (j && typeof j.message === "string") detail = `: ${j.message}`;
      } catch {
        /* non-JSON error body */
      }
      throw new GitHubError(`github ${res.status} on ${path}${detail}`, status, path, res.status);
    }
    return res;
  }

  /** @param {string} path @param {RequestInit} [init] */
  async function api(path, init) {
    const res = await raw(path, init);
    if (res.status === 204) return null;
    return res.json();
  }

  const base = `/repos/${repo}`;
  const wf = `${base}/actions/workflows/${workflow}`;

  return {
    repo,
    workflow,

    /**
     * Short sha of the newest commit on main that shaped the build: the shim (shim/) or the overlay generator
     * (tools/overlay.py, whose env template sets memory type, flags and libs -- 2026-09-05: the virtual env's
     * PSRAM type changed there and a shim-only identity would have served the stale build as cached).
     */
    async latestShimCommit() {
      let best = null;
      for (const path of SHIM_PATHS) {
        const commits = await api(`${base}/commits?path=${encodeURIComponent(path)}&sha=main&per_page=1`);
        const c = Array.isArray(commits) && commits[0];
        const sha = c && c.sha;
        if (typeof sha !== "string" || sha.length < 7) continue;
        const date = Date.parse((c.commit && c.commit.committer && c.commit.committer.date) || "") || 0;
        if (!best || date > best.date) best = { sha, date };
      }
      if (!best) throw new GitHubError(`no commit touching ${SHIM_PATHS.join(" or ")} on main`, 502, `${base}/commits`);
      return best.sha.slice(0, 7);
    },

    /**
     * One page of workflow_dispatch runs of the build workflow, newest first.
     * @param {{page?: number, perPage?: number}} [q]
     * @returns {Promise<any[]>}
     */
    async listRuns({ page = 1, perPage = 50 } = {}) {
      const j = await api(`${wf}/runs?event=workflow_dispatch&per_page=${perPage}&page=${page}`);
      return Array.isArray(j && j.workflow_runs) ? j.workflow_runs : [];
    },

    /**
     * workflow_dispatch on main with the given inputs. GitHub answers 204 and creates the run asynchronously.
     * @param {Record<string, string>} inputs
     */
    async dispatch(inputs) {
      await api(`${wf}/dispatches`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ ref: "main", inputs }),
      });
    },

    /**
     * @param {string} runId
     * @returns {Promise<any[]>}
     */
    async listArtifacts(runId) {
      const j = await api(`${base}/actions/runs/${runId}/artifacts?per_page=100`);
      return Array.isArray(j && j.artifacts) ? j.artifacts : [];
    },

    /**
     * The artifact zip. GitHub answers 302 to a short-lived blob URL; that hop is made WITHOUT the
     * Authorization header (the blob store rejects a second credential), so redirects are followed by hand.
     * @param {string | number} artifactId
     * @returns {Promise<Uint8Array>}
     */
    async downloadArtifactZip(artifactId) {
      const path = `${base}/actions/artifacts/${artifactId}/zip`;
      const first = await raw(path, { redirect: "manual" });
      let res = first;
      if (first.status >= 300 && first.status < 400) {
        const location = first.headers.get("location");
        if (!location) throw new GitHubError("artifact redirect without Location", 502, path);
        try {
          res = await fetch(location, { headers: { "User-Agent": USER_AGENT }, redirect: "follow" });
        } catch (e) {
          throw new GitHubError(`artifact blob unreachable: ${e instanceof Error ? e.message : String(e)}`, 502, path);
        }
        if (!res.ok) throw new GitHubError(`artifact blob ${res.status}`, 502, path);
      }
      return new Uint8Array(await res.arrayBuffer());
    },

    /**
     * Open an issue on the repo (201 with the issue). On a fine-grained PAT without Issues: write GitHub
     * answers 403 or 404 -- the caller reads .upstream to tell that apart from a real failure.
     * @param {{title: string, body: string, labels?: string[]}} issue labels omitted = none requested
     * @returns {Promise<{number: number, html_url: string}>}
     */
    async createIssue({ title, body, labels }) {
      const path = `${base}/issues`;
      const j = await api(path, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(labels ? { title, body, labels } : { title, body }),
      });
      if (!j || typeof j.number !== "number" || typeof j.html_url !== "string") {
        throw new GitHubError("issue created without a number", 502, path, 201);
      }
      return { number: j.number, html_url: j.html_url };
    },
  };
}

/** @typedef {ReturnType<typeof createGitHub>} GitHub */
