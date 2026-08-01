// Regression test for the crash where a pairing request against an unconfigured store
// killed the whole process. store.js printed "pairing endpoints will fail closed" but never
// checked STORE_READY, so BASE was undefined, fetch() got the literal string
// "undefined/SET/..." and threw ERR_INVALID_URL. That rejection escaped the async
// createServer handler, Node terminated, and every later request 502'd until a restart.
const { test } = require("node:test");
const assert = require("node:assert");
const http = require("node:http");
const { startServer } = require("./harness");

function req(method, url, body) {
  return new Promise((resolve, reject) => {
    const data = body ? JSON.stringify(body) : null;
    const u = new URL(url);
    const r = http.request(
      u,
      {
        method,
        headers: data
          ? {
              "Content-Type": "application/json",
              "Content-Length": Buffer.byteLength(data),
            }
          : {},
      },
      (res) => {
        let out = "";
        res.on("data", (d) => (out += d));
        res.on("end", () =>
          resolve({
            status: res.statusCode,
            json: out ? JSON.parse(out) : null,
          }),
        );
      },
    );
    r.on("error", reject);
    if (data) r.write(data);
    r.end();
  });
}

function health(url) {
  return new Promise((resolve, reject) => {
    const r = http.get(url + "/health", (res) => {
      res.resume();
      resolve(res.statusCode);
    });
    r.on("error", reject);
  });
}

test("pairing request without store credentials fails closed and leaves the server up", async () => {
  // No UPSTASH_* and no HWP_STORE=fake — exactly the deployed misconfiguration.
  const srv = await startServer({
    port: 8096,
    env: { UPSTASH_REDIS_REST_URL: "", UPSTASH_REDIS_REST_TOKEN: "" },
  });
  try {
    assert.strictEqual(await health(srv.baseUrl), 200, "healthy before");

    const res = await req("POST", srv.baseUrl + "/api/devices");
    assert.strictEqual(
      res.status,
      503,
      "store-unavailable is a 503, not a crash",
    );
    assert.deepStrictEqual(res.json, {
      ok: false,
      reason: "store-unavailable",
    });

    // The actual regression: the process must survive the request above.
    assert.strictEqual(await health(srv.baseUrl), 200, "still healthy after");

    // And it must keep surviving — not die on the second one either.
    await req("POST", srv.baseUrl + "/api/devices");
    assert.strictEqual(
      await health(srv.baseUrl),
      200,
      "still healthy after two",
    );
  } finally {
    await srv.stop();
  }
});
