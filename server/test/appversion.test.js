const { test } = require("node:test");
const assert = require("node:assert");
const {
  parseReleaseBody,
  buildManifest,
  createAppVersionService,
} = require("../appversion");

function release(overrides = {}) {
  return {
    tag_name: "v1.2",
    body: "version-code: 7\nmin-supported: 5\n\nFixed the thing.",
    assets: [
      {
        name: "fluffles.apk",
        browser_download_url: "https://example.test/fluffles.apk",
        url: "https://api.github.test/assets/1",
      },
    ],
    ...overrides,
  };
}

// ── body parsing ────────────────────────────────────────────────────────────

test("parseReleaseBody reads version-code and min-supported", () => {
  const p = parseReleaseBody("version-code: 7\nmin-supported: 5\n\nnotes here");
  assert.strictEqual(p.versionCode, 7);
  assert.strictEqual(p.minSupported, 5);
});

test("parseReleaseBody strips the policy lines from the notes", () => {
  const p = parseReleaseBody(
    "version-code: 7\nmin-supported: 5\n\nFixed the thing.",
  );
  assert.strictEqual(p.notes, "Fixed the thing.");
});

test("parseReleaseBody returns nulls when the lines are absent", () => {
  const p = parseReleaseBody("just a changelog, no policy");
  assert.strictEqual(p.versionCode, null);
  assert.strictEqual(p.minSupported, null);
});

test("parseReleaseBody ignores a policy line that is not at line start", () => {
  // A changelog quoting the syntax must not be able to set policy.
  const p = parseReleaseBody('we now emit "version-code: 99" in the body');
  assert.strictEqual(p.versionCode, null);
});

test("parseReleaseBody ignores a markdown list item", () => {
  const p = parseReleaseBody("- min-supported: 99\nversion-code: 7");
  assert.strictEqual(p.versionCode, 7);
  assert.strictEqual(
    p.minSupported,
    null,
    "a bulleted line must not force an update",
  );
});

test("parseReleaseBody tolerates an empty or missing body", () => {
  assert.strictEqual(parseReleaseBody("").versionCode, null);
  assert.strictEqual(parseReleaseBody(null).versionCode, null);
  assert.strictEqual(parseReleaseBody(undefined).notes, "");
});

// ── manifest construction ───────────────────────────────────────────────────

test("buildManifest maps a well-formed release", () => {
  const m = buildManifest(release());
  assert.strictEqual(m.ok, true);
  assert.strictEqual(m.versionCode, 7);
  assert.strictEqual(m.minSupported, 5);
  assert.strictEqual(m.versionName, "1.2", "the leading v is stripped");
  assert.strictEqual(m.url, "https://example.test/fluffles.apk");
  assert.strictEqual(m.notes, "Fixed the thing.");
});

test("buildManifest uses browser_download_url, never the API url", () => {
  const m = buildManifest(release());
  assert.ok(
    !m.url.includes("api.github"),
    "the API asset url returns JSON, not the APK",
  );
});

test("buildManifest rejects a release with no version-code", () => {
  assert.strictEqual(buildManifest(release({ body: "no policy here" })), null);
});

test("buildManifest rejects a release with no .apk asset", () => {
  const m = buildManifest(
    release({
      assets: [
        { name: "notes.txt", browser_download_url: "https://x.test/notes.txt" },
      ],
    }),
  );
  assert.strictEqual(
    m,
    null,
    "a forced update pointing at no APK is a two-device outage",
  );
});

test("buildManifest rejects a release whose apk asset has no download url", () => {
  assert.strictEqual(
    buildManifest(release({ assets: [{ name: "a.apk" }] })),
    null,
  );
});

test("buildManifest refuses to force an update newer than the release itself", () => {
  // min-supported above version-code would demand a version that does not exist.
  const m = buildManifest(
    release({ body: "version-code: 7\nmin-supported: 9" }),
  );
  assert.strictEqual(m.ok, true, "the release is still offered");
  assert.strictEqual(m.minSupported, 0, "but the forcing flag is dropped");
});

test("buildManifest defaults minSupported to 0 when unset", () => {
  const m = buildManifest(release({ body: "version-code: 7" }));
  assert.strictEqual(m.minSupported, 0);
});

// ── service: caching and failure behavior ───────────────────────────────────

function okFetch(payload, calls) {
  return async (url) => {
    if (calls) calls.push(url);
    return { ok: true, status: 200, json: async () => payload };
  };
}

test("the service fetches and returns a manifest", async () => {
  const svc = createAppVersionService({ fetchImpl: okFetch(release()) });
  const m = await svc.get();
  assert.strictEqual(m.ok, true);
  assert.strictEqual(m.versionCode, 7);
});

test("the service caches within the TTL and does not refetch", async () => {
  const calls = [];
  const svc = createAppVersionService({ fetchImpl: okFetch(release(), calls) });
  await svc.get();
  await svc.get();
  assert.strictEqual(calls.length, 1, "second call must be served from cache");
});

test("the service refetches once the TTL lapses", async () => {
  const calls = [];
  let clock = 1000;
  const svc = createAppVersionService({
    fetchImpl: okFetch(release(), calls),
    ttlMs: 100,
    now: () => clock,
  });
  await svc.get();
  clock += 101;
  await svc.get();
  assert.strictEqual(calls.length, 2);
});

test("a GitHub error serves the last good answer", async () => {
  let fail = false;
  const svc = createAppVersionService({
    ttlMs: 0,
    fetchImpl: async () => {
      if (fail) throw new Error("network down");
      return { ok: true, status: 200, json: async () => release() };
    },
  });
  const first = await svc.get();
  assert.strictEqual(first.versionCode, 7);
  fail = true;
  const second = await svc.get();
  assert.strictEqual(second.versionCode, 7, "stale but usable beats nothing");
  assert.strictEqual(second.stale, true, "staleness is reported, not hidden");
});

test("a GitHub error with a cold cache reports unavailable rather than throwing", async () => {
  const svc = createAppVersionService({
    fetchImpl: async () => {
      throw new Error("network down");
    },
  });
  const m = await svc.get();
  assert.strictEqual(m.ok, false);
  assert.strictEqual(m.reason, "unavailable");
});

test("a rate-limited GitHub response with a cold cache is unavailable, not a crash", async () => {
  const svc = createAppVersionService({
    fetchImpl: async () => ({ ok: false, status: 403, json: async () => ({}) }),
  });
  const m = await svc.get();
  assert.strictEqual(m.ok, false);
  assert.strictEqual(m.reason, "unavailable");
});

test("a malformed release reports no-valid-release rather than being served", async () => {
  const svc = createAppVersionService({
    fetchImpl: okFetch(release({ body: "no policy" })),
  });
  const m = await svc.get();
  assert.strictEqual(m.ok, false);
  assert.strictEqual(m.reason, "no-valid-release");
});

test("the kill switch disables OTA entirely without touching GitHub", async () => {
  const calls = [];
  const svc = createAppVersionService({
    disabled: true,
    fetchImpl: okFetch(release(), calls),
  });
  const m = await svc.get();
  assert.strictEqual(m.ok, false);
  assert.strictEqual(m.reason, "ota-disabled");
  assert.strictEqual(
    calls.length,
    0,
    "the kill switch must not depend on GitHub being up",
  );
});

test("the service requests the configured repo's latest release", async () => {
  const calls = [];
  const svc = createAppVersionService({
    repo: "someone/somerepo",
    fetchImpl: okFetch(release(), calls),
  });
  await svc.get();
  assert.match(calls[0], /someone\/somerepo\/releases\/latest$/);
});

test("get never rejects, whatever fetch does", async () => {
  const svc = createAppVersionService({
    fetchImpl: async () => {
      throw new TypeError("totally unexpected");
    },
  });
  await assert.doesNotReject(() => svc.get());
});
