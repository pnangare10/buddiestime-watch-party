// OTA update manifest, derived from the repo's latest GitHub Release.
//
// Shape mirrors livekit.js / push.js / store.js: configured from env, exposes a READY
// flag, and fails soft when it can't do its job. The hard rule here is that nothing in
// this module may break the app — a failed update check must read to the client as
// "no update available", never as an error, so `get()` never rejects.
//
// No token is used or accepted. The repo is public, so unauthenticated reads suffice,
// and having no credential present means there is none to leak through this endpoint.

const DEFAULT_REPO = "pncodes10/buddiestime-watch-party";
const DEFAULT_TTL_MS = 15 * 60 * 1000;

const REPO = process.env.GITHUB_RELEASES_REPO || DEFAULT_REPO;
// Kill switch for when GitHub itself is the problem, or a bad release needs to stop
// reaching phones before a corrected one is published.
const OTA_DISABLED = /^(1|true|yes)$/i.test(process.env.OTA_DISABLED || "");
const READY = !OTA_DISABLED;

if (OTA_DISABLED) {
  console.warn(
    "[OTA] OTA_DISABLED set — /api/app-version will report no updates",
  );
} else {
  console.log(`[OTA] serving update manifest from ${REPO}`);
}

// Anchored to line start and end so a changelog that merely *quotes* the syntax, or
// bullets it, cannot set update policy. `- min-supported: 99` must not force an update.
const VERSION_CODE_RE = /^[ \t]*version-code:[ \t]*(\d+)[ \t]*$/im;
const MIN_SUPPORTED_RE = /^[ \t]*min-supported:[ \t]*(\d+)[ \t]*$/im;

function parseReleaseBody(body) {
  const text = typeof body === "string" ? body : "";
  const vc = text.match(VERSION_CODE_RE);
  const ms = text.match(MIN_SUPPORTED_RE);
  const notes = text
    .split(/\r?\n/)
    .filter(
      (line) => !VERSION_CODE_RE.test(line) && !MIN_SUPPORTED_RE.test(line),
    )
    .join("\n")
    .trim();
  return {
    versionCode: vc ? Number(vc[1]) : null,
    minSupported: ms ? Number(ms[1]) : null,
    notes,
  };
}

/**
 * Translates a GitHub release into the client-facing manifest, or null if the release
 * is unusable. Returning null (rather than a partial manifest) keeps a malformed
 * release invisible to clients instead of half-applied.
 */
function buildManifest(release) {
  if (!release || typeof release !== "object") return null;
  const { versionCode, minSupported, notes } = parseReleaseBody(release.body);
  if (!versionCode) return null;

  const apk = (release.assets || []).find(
    (a) =>
      a && typeof a.name === "string" && a.name.toLowerCase().endsWith(".apk"),
  );
  // browser_download_url, never `url`: the latter is the API endpoint and returns JSON
  // metadata unless an octet-stream Accept header is sent, which DownloadManager won't.
  if (!apk || !apk.browser_download_url) return null;

  // A min-supported above the release's own version demands a build that does not
  // exist — it would wall every phone with no reachable escape. Offer the release,
  // drop the forcing flag.
  const forcing =
    minSupported && minSupported <= versionCode ? minSupported : 0;

  return {
    ok: true,
    versionCode,
    minSupported: forcing,
    versionName: String(release.tag_name || "").replace(/^v/, ""),
    url: apk.browser_download_url,
    notes,
  };
}

function createAppVersionService({
  fetchImpl = (...args) => fetch(...args),
  repo = REPO,
  ttlMs = DEFAULT_TTL_MS,
  now = Date.now,
  disabled = OTA_DISABLED,
} = {}) {
  let cached = null;
  let cachedAt = 0;

  async function get() {
    if (disabled) return { ok: false, reason: "ota-disabled" };
    if (cached && now() - cachedAt < ttlMs) return cached;

    try {
      const res = await fetchImpl(
        `https://api.github.com/repos/${repo}/releases/latest`,
        {
          headers: {
            Accept: "application/vnd.github+json",
            "User-Agent": "fluffles-ota",
          },
        },
      );
      if (!res || !res.ok)
        throw new Error(`GitHub responded ${res && res.status}`);
      const manifest = buildManifest(await res.json());
      if (!manifest) {
        // Loud on purpose: a release nobody receives is worse than a failed one.
        console.warn(
          `[OTA] latest release in ${repo} is unusable — ignoring it`,
        );
        return { ok: false, reason: "no-valid-release" };
      }
      cached = manifest;
      cachedAt = now();
      return manifest;
    } catch (e) {
      if (cached) {
        console.warn(`[OTA] ${e.message} — serving cached manifest`);
        return { ...cached, stale: true };
      }
      console.warn(`[OTA] ${e.message} — no cached manifest to fall back on`);
      return { ok: false, reason: "unavailable" };
    }
  }

  return { get };
}

module.exports = {
  READY,
  REPO,
  parseReleaseBody,
  buildManifest,
  createAppVersionService,
  appVersion: createAppVersionService(),
};
