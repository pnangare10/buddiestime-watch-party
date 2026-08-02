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

function getText(url) {
  return new Promise((resolve, reject) => {
    http
      .get(new URL(url), (res) => {
        let out = "";
        res.on("data", (d) => (out += d));
        res.on("end", () =>
          resolve({ status: res.statusCode, headers: res.headers, body: out }),
        );
      })
      .on("error", reject);
  });
}

test("full pairing round trip over HTTP", async () => {
  const srv = await startServer({
    port: 8095,
    env: {
      UPSTASH_REDIS_REST_URL: "fake",
      UPSTASH_REDIS_REST_TOKEN: "fake",
      HWP_STORE: "fake",
    },
  });
  try {
    const hisDevice = await req("POST", srv.baseUrl + "/api/devices");
    assert.strictEqual(hisDevice.status, 200);
    const herDevice = await req("POST", srv.baseUrl + "/api/devices");

    const room = await req("POST", srv.baseUrl + "/api/rooms", {
      deviceId: hisDevice.json.deviceId,
      roomName: "HttpTestRoom",
      ownerProfile: { displayName: "Sonu" },
      partnerProfileDraft: { displayName: "Komal" },
    });
    assert.strictEqual(room.status, 200);

    const invite = await req(
      "POST",
      `${srv.baseUrl}/api/rooms/${room.json.roomId}/invite`,
      { deviceId: hisDevice.json.deviceId },
    );
    assert.strictEqual(invite.status, 200);

    const join = await req(
      "POST",
      `${srv.baseUrl}/api/rooms/${room.json.roomId}/join`,
      {
        deviceId: herDevice.json.deviceId,
        token: invite.json.token,
      },
    );
    assert.strictEqual(join.status, 200);
    assert.strictEqual(join.json.herProfile.displayName, "Komal");

    const second = await req(
      "POST",
      `${srv.baseUrl}/api/rooms/${room.json.roomId}/join`,
      {
        deviceId: "someone-else",
        token: invite.json.token,
      },
    );
    assert.strictEqual(second.status, 409, "reused token must be rejected");

    // Android fetches this to verify the app may handle /pair/* links. If it stops
    // validating, nothing errors — invites just quietly open in a browser again,
    // which is the bug this suite exists to keep fixed.
    const links = await getText(srv.baseUrl + "/.well-known/assetlinks.json");
    assert.strictEqual(links.status, 200);
    assert.match(links.headers["content-type"], /application\/json/);
    const statements = JSON.parse(links.body);
    assert.strictEqual(statements[0].target.namespace, "android_app");
    assert.strictEqual(
      statements[0].target.package_name,
      "com.fluffles.watchparty",
      "must be the applicationId, not the Kotlin namespace",
    );
    assert.ok(
      statements[0].target.sha256_cert_fingerprints.length > 0,
      "at least the debug fingerprint must be published",
    );
    assert.ok(
      statements[0].relation.includes(
        "delegate_permission/common.handle_all_urls",
      ),
    );

    // The fallback page's button must be a package-targeted intent: URL. A plain
    // https href there cannot work — the browser only hands an https link to an
    // app once verification succeeded, the very case this page rescues.
    const page = await getText(
      `${srv.baseUrl}/pair/${room.json.roomId}/${invite.json.token}`,
    );
    assert.strictEqual(page.status, 200);
    assert.ok(
      page.body.includes(
        `intent://${new URL(srv.baseUrl).host}/pair/${room.json.roomId}/${invite.json.token}#Intent;scheme=https;package=com.fluffles.watchparty;end`,
      ),
      "fallback page must expose a package-targeted intent: URL",
    );
    assert.ok(
      !page.body.includes("{{"),
      "no unsubstituted template placeholders may reach the browser",
    );
  } finally {
    await srv.stop();
  }
});
