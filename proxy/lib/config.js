// Proxy configuration from the environment. Only the token is secret; it never appears in a response,
// an error message or a log line (GitHubError carries status + path, never headers).

/**
 * @typedef {object} ProxyConfig
 * @property {string} token     GITHUB_TOKEN -- fine-grained PAT (Actions read+write, Contents read on the one repo)
 * @property {string} repo      GITHUB_REPO  -- owner/name that hosts build-app.yml (default fcavalcantirj/droidputter)
 * @property {string} workflow  WORKFLOW     -- workflow file name (default build-app.yml)
 * @property {string} baseUrl   BASE_URL     -- public origin of this proxy for part URLs ("" = derive from the request Host)
 * @property {string} apiBase   GITHUB_API   -- GitHub REST origin (tests point it at a fake)
 */

/**
 * @param {Record<string, string | undefined>} [env]
 * @returns {ProxyConfig}
 */
export function loadConfig(env = process.env) {
  return {
    token: env.GITHUB_TOKEN || "",
    repo: env.GITHUB_REPO || "fcavalcantirj/droidputter",
    workflow: env.WORKFLOW || "build-app.yml",
    baseUrl: (env.BASE_URL || "").replace(/\/+$/, ""),
    apiBase: (env.GITHUB_API || "https://api.github.com").replace(/\/+$/, ""),
  };
}
