require("dotenv").config();

const http = require("http");
const fs = require("fs");
const path = require("path");
const { WebSocketServer } = require("ws");
const { mintToken, LK_READY } = require("./livekit");
const { sweepClients } = require("./keepalive");
const pairingStore =
  process.env.HWP_STORE === "fake"
    ? require("./test/store.fake").makeFakeStore()
    : require("./store");
const pairing = require("./pairing");
const push = require("./push");
const {
  parseStateUpdate,
  parseJoinContent,
  parseDeviceId,
  fmt,
} = require("./validate");
const wsauth = require("./wsauth");

const PORT = process.env.PORT || 8080;
const MAX_NAME_LEN = 32;
const MAX_CHAT_LEN = 500;
const VOICE_CAP = 4;
const PING_INTERVAL_MS = Number(process.env.PING_INTERVAL_MS) || 30000;
const ROOM_GRACE_MS = Number(process.env.ROOM_GRACE_MS) || 300000;
// A playing room's stored time goes stale the moment we store it. Cap how far we
// extrapolate it: past this, the host is gone rather than genuinely that far ahead.
const MAX_PROJECTION_SEC = 30;

// WebSocket join authorisation (P0-3), rolled out in stages via env only:
//   observe (default) — check, log the verdict, always allow. Safe to deploy ahead
//                       of the client that sends deviceId.
//   enforce           — refuse non-members.
//   off               — skip the check (dev escape hatch, e.g. to use room.html
//                       against a paired room).
// Flipping to enforce is an env change on Render, not a redeploy, so rollback is
// one variable. See docs/superpowers/plans/2026-08-02-p0-3-websocket-auth.md §5.
const WS_AUTH_MODE = process.env.WS_AUTH_MODE || "observe";
// What to do when Upstash itself is unreachable. `open` keeps a store outage from
// bricking the app — it degrades to the pre-P0-3 security level, never below it.
const WS_AUTH_FAIL = process.env.WS_AUTH_FAIL || "open";

const rooms = new Map(); // roomId → Map<ws, { role, id, name, voice: boolean }>
const clientsById = new Map(); // roomId → Map<clientId, ws>
const roomState = new Map(); // roomId → { platform, videoUrl, time, paused, updatedAt }
const roomGraceTimers = new Map(); // roomId → timeout

const STATIC_ROUTES = {
  "/": path.join(__dirname, "index.html"),
  "/install.html": path.join(__dirname, "..", "bookmarklet", "install.html"),
};

// ---------------------------------------------------------------- App Links
// Android fetches /.well-known/assetlinks.json to decide whether this app may
// handle https://<host>/pair/* itself. Without a *validating* file, Android 12+
// does not merely deprioritise the app — it drops it from link handling entirely
// (`pm get-app-links` reports the domain as "Disabled"), so every invite opens in
// a browser instead. The manifest's android:autoVerify="true" is inert on its own;
// this route is the other half of that pair.
const APP_LINK_PACKAGE = "com.fluffles.watchparty"; // applicationId, not the namespace
// Debug keystore (~/.android/debug.keystore) — every locally installed build is
// signed with it, so verification works on emulators without a release key.
const DEBUG_CERT_SHA256 =
  "BA:B7:BA:89:D9:9D:E4:1D:E4:C5:D6:1E:12:20:92:EE:CF:67:F0:FA:9A:8A:B7:97:2E:0E:9F:6A:CD:6A:C3:40";

/**
 * Release builds are signed with a key this repo does not carry, and Play App
 * Signing re-signs again with a third. Both must appear here or verification
 * fails for store installs, so extra fingerprints come from the environment
 * (comma-separated) rather than requiring a code change per key.
 */
function appLinkFingerprints() {
  const extra = (process.env.ANDROID_CERT_SHA256 || "")
    .split(",")
    .map((s) => s.trim().toUpperCase())
    .filter(Boolean);
  return [...new Set([DEBUG_CERT_SHA256, ...extra])];
}

function assetLinksBody() {
  return JSON.stringify(
    [
      {
        relation: ["delegate_permission/common.handle_all_urls"],
        target: {
          namespace: "android_app",
          package_name: APP_LINK_PACKAGE,
          sha256_cert_fingerprints: appLinkFingerprints(),
        },
      },
    ],
    null,
    2,
  );
}

/**
 * `intent://…#Intent;…;package=…;end` — an *explicit* package-targeted intent.
 * A plain https link on the fallback page cannot work: the browser owns that
 * navigation and only hands it over when domain verification already succeeded,
 * which is exactly the case the fallback page exists to rescue. Naming the
 * package bypasses link-handling policy entirely.
 */
function appIntentUrl(host, roomId, token) {
  return (
    `intent://${host}/pair/${encodeURIComponent(roomId)}/${encodeURIComponent(token)}` +
    `#Intent;scheme=https;package=${APP_LINK_PACKAGE};end`
  );
}

function escapeHtml(s) {
  return String(s)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

function readJsonBody(req) {
  return new Promise((resolve, reject) => {
    let data = "";
    req.on("data", (c) => (data += c));
    req.on("end", () => {
      try {
        resolve(data ? JSON.parse(data) : {});
      } catch (e) {
        reject(e);
      }
    });
    req.on("error", reject);
  });
}

// { ok:true, ... } | { ok:false, reason } from pairing.js → an HTTP status + JSON body.
function sendPairingResult(res, result) {
  const status = result.ok
    ? 200
    : {
        forbidden: 403,
        "unknown-room": 404,
        "unknown-device": 404,
        "not-a-room-member": 403,
        "room-name-taken": 409,
        "already-used": 409,
        "invalid-token": 409,
        "pin-required": 409,
        "pin-mismatch": 409,
        "device-already-in-room": 409,
      }[result.reason] || 400;
  res.writeHead(status, { "Content-Type": "application/json" });
  res.end(JSON.stringify(result));
}

async function handleHttp(req, res) {
  const url = new URL(req.url, `http://localhost`);
  console.log(`[HTTP] ${req.method} ${url.pathname}`);

  if (req.method === "POST" && url.pathname === "/api/devices") {
    const { deviceId } = await pairing.createDevice(pairingStore);
    console.log(`[HTTP]   → /api/devices → deviceId=${deviceId}`);
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ deviceId }));
    return;
  }

  if (req.method === "POST" && url.pathname === "/api/rooms") {
    const body = await readJsonBody(req);
    console.log(`[HTTP]   → POST /api/rooms body=${JSON.stringify(body)}`);
    const result = await pairing.createRoom(pairingStore, {
      roomName: body.roomName,
      ownerDeviceId: body.deviceId,
      ownerProfile: body.ownerProfile,
      partnerProfileDraft: body.partnerProfileDraft,
      anniversaryDate: body.anniversaryDate,
    });
    sendPairingResult(res, result);
    return;
  }

  {
    const m = url.pathname.match(/^\/api\/rooms\/([^/]+)\/invite$/);
    if (req.method === "POST" && m) {
      const roomId = decodeURIComponent(m[1]);
      const body = await readJsonBody(req);
      console.log(`[HTTP]   → POST /api/rooms/${roomId}/invite`);
      const result = await pairing.mintInvite(pairingStore, {
        roomId,
        requestingDeviceId: body.deviceId,
        pin: body.pin,
      });
      sendPairingResult(res, result);
      return;
    }
  }

  {
    const m = url.pathname.match(/^\/api\/rooms\/([^/]+)\/join$/);
    if (req.method === "POST" && m) {
      const roomId = decodeURIComponent(m[1]);
      const body = await readJsonBody(req);
      console.log(
        `[HTTP]   → POST /api/rooms/${roomId}/join deviceId=${body.deviceId}`,
      );
      const result = await pairing.redeemInvite(pairingStore, {
        roomId,
        token: body.token,
        deviceId: body.deviceId,
        pin: body.pin,
      });
      // The only place room membership ever changes. Without this the new partner
      // is a 'non-member' to the WebSocket layer until the cache TTL lapses — i.e.
      // rejected for up to a minute immediately after successfully pairing.
      if (result.ok) wsauth.invalidate(roomId);
      sendPairingResult(res, result);
      return;
    }
  }

  {
    const m = url.pathname.match(/^\/api\/rooms\/([^/]+)\/nudge$/);
    if (req.method === "POST" && m) {
      const roomId = decodeURIComponent(m[1]);
      const body = await readJsonBody(req);
      console.log(
        `[HTTP]   → POST /api/rooms/${roomId}/nudge from=${body.deviceId}`,
      );
      const result = await pairing.triggerNudge(pairingStore, push.sendNudge, {
        roomId,
        triggeringDeviceId: body.deviceId,
        customText: body.text,
      });
      res.writeHead(result.ok ? 200 : 409, {
        "Content-Type": "application/json",
      });
      res.end(JSON.stringify(result));
      return;
    }
  }

  {
    const m = url.pathname.match(/^\/api\/rooms\/([^/]+)\/theme$/);
    if (req.method === "PATCH" && m) {
      const roomId = decodeURIComponent(m[1]);
      const body = await readJsonBody(req);
      console.log(
        `[HTTP]   → PATCH /api/rooms/${roomId}/theme mode=${body.mode}`,
      );
      const result = await pairing.setTheme(pairingStore, {
        roomId,
        deviceId: body.deviceId,
        mode: body.mode,
        value: body.value,
      });
      sendPairingResult(res, result);
      return;
    }
  }

  {
    const m = url.pathname.match(
      /^\/api\/rooms\/([^/]+)\/messages\/(nudge|welcome)$/,
    );
    if (req.method === "POST" && m) {
      const roomId = decodeURIComponent(m[1]);
      const pool = m[2];
      const body = await readJsonBody(req);
      console.log(`[HTTP]   → POST /api/rooms/${roomId}/messages/${pool}`);
      const result = await pairing.addMessage(pairingStore, {
        roomId,
        deviceId: body.deviceId,
        pool,
        text: body.text,
      });
      sendPairingResult(res, result);
      return;
    }
  }

  {
    const m = url.pathname.match(
      /^\/api\/rooms\/([^/]+)\/messages\/(nudge|welcome)\/([^/]+)$/,
    );
    if (req.method === "DELETE" && m) {
      const roomId = decodeURIComponent(m[1]);
      const pool = m[2];
      const id = decodeURIComponent(m[3]);
      const body = await readJsonBody(req);
      console.log(
        `[HTTP]   → DELETE /api/rooms/${roomId}/messages/${pool}/${id}`,
      );
      const result = await pairing.removeMessage(pairingStore, {
        roomId,
        deviceId: body.deviceId,
        pool,
        id,
      });
      sendPairingResult(res, result);
      return;
    }
  }

  {
    // "status" is the existing legacy /api/rooms/status endpoint (handled further
    // below) — excluded here so this pairing route doesn't shadow it.
    const m = url.pathname.match(/^\/api\/rooms\/(?!status$)([^/]+)$/);
    if (req.method === "GET" && m) {
      const roomId = decodeURIComponent(m[1]);
      const deviceId = req.headers["x-device-id"];
      console.log(`[HTTP]   → GET /api/rooms/${roomId} deviceId=${deviceId}`);
      const result = await pairing.getRoomView(pairingStore, {
        roomId,
        deviceId,
      });
      sendPairingResult(res, result);
      return;
    }
  }

  {
    const m = url.pathname.match(/^\/api\/devices\/([^/]+)$/);
    if (req.method === "PATCH" && m) {
      const deviceId = decodeURIComponent(m[1]);
      const body = await readJsonBody(req);
      console.log(`[HTTP]   → PATCH /api/devices/${deviceId}`);
      const result = await pairing.updateDeviceProfile(pairingStore, {
        deviceId,
        patch: body,
      });
      sendPairingResult(res, result);
      return;
    }
  }

  // Must be served over https from the invite's own host, as application/json,
  // with no redirect — Android's verifier rejects anything else.
  if (url.pathname === "/.well-known/assetlinks.json") {
    const body = assetLinksBody();
    console.log(
      `[HTTP]   → /.well-known/assetlinks.json → 200 (${appLinkFingerprints().length} fingerprint(s))`,
    );
    res.writeHead(200, {
      "Content-Type": "application/json",
      "Content-Length": Buffer.byteLength(body),
    });
    res.end(body);
    return;
  }

  {
    const m = url.pathname.match(/^\/pair\/([^/]+)\/([^/]+)$/);
    if (req.method === "GET" && m) {
      const roomId = decodeURIComponent(m[1]);
      const token = decodeURIComponent(m[2]);
      console.log(`[HTTP]   → GET /pair/${roomId}/*** → fallback page`);
      const room = await pairingStore.getRoom(roomId).catch(() => null);
      // Take the host from the request so the button works on whichever host the
      // invite was actually minted for (Render, tailnet, localhost).
      const host = req.headers.host || url.host;
      fs.readFile(
        path.join(__dirname, "pair-fallback.html"),
        "utf8",
        (err, html) => {
          if (err) {
            console.warn(
              `[HTTP]   → pair-fallback.html read error: ${err.message}`,
            );
            res.writeHead(404);
            res.end("Not found");
            return;
          }
          const roomName = room?.roomName
            ? `"${room.roomName}"`
            : "a watch party";
          res.writeHead(200, { "Content-Type": "text/html" });
          res.end(
            html
              .replace("{{ROOM_NAME}}", escapeHtml(roomName))
              .replace(
                "{{INTENT_URL}}",
                escapeHtml(appIntentUrl(host, roomId, token)),
              ),
          );
        },
      );
      return;
    }
  }

  // Which authorisation mode is this instance actually running? The rollout gate
  // between observe and enforce needs an answer that does not depend on catching
  // the right log line on Render.
  if (url.pathname === "/api/authmode") {
    const body = JSON.stringify({ mode: WS_AUTH_MODE, fail: WS_AUTH_FAIL });
    console.log(`[HTTP]   → /api/authmode → ${body}`);
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(body);
    return;
  }

  if (url.pathname === "/health") {
    console.log(`[HTTP]   → /health → 200`);
    res.writeHead(200, { "Content-Type": "text/plain" });
    res.end("OK");
    return;
  }

  if (url.pathname === "/api/rooms/status") {
    const ids = (url.searchParams.get("ids") || "")
      .split(",")
      .map((s) => s.trim())
      .filter(Boolean);
    console.log(`[HTTP]   → /api/rooms/status ids=[${ids.join(",")}]`);
    const payload = { rooms: ids.map(roomStatus) };
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify(payload));
    return;
  }

  if (url.pathname.startsWith("/api/room/")) {
    const id = decodeURIComponent(url.pathname.slice("/api/room/".length));
    console.log(`[HTTP]   → /api/room/${id}`);
    if (!roomState.has(id)) {
      res.writeHead(404, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ error: "not-found" }));
      return;
    }
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify(roomStatus(id)));
    return;
  }

  if (url.pathname.startsWith("/room/")) {
    console.log(`[HTTP]   → /room/* → room.html`);
    fs.readFile(path.join(__dirname, "room.html"), (err, data) => {
      if (err) {
        console.warn(`[HTTP]   → room.html read error: ${err.message}`);
        res.writeHead(404);
        res.end("Not found");
        return;
      }
      res.writeHead(200, { "Content-Type": "text/html" });
      res.end(data);
    });
    return;
  }

  const filePath = STATIC_ROUTES[url.pathname];
  if (filePath) {
    console.log(`[HTTP]   → static ${url.pathname} → ${filePath}`);
    fs.readFile(filePath, (err, data) => {
      if (err) {
        console.warn(`[HTTP]   → static read error: ${err.message}`);
        res.writeHead(404);
        res.end("Not found");
        return;
      }
      res.writeHead(200, { "Content-Type": "text/html" });
      res.end(data);
    });
    return;
  }

  console.log(`[HTTP]   → no route match → 404`);
  res.writeHead(404, { "Content-Type": "text/plain" });
  res.end("Not found");
}

// Error boundary. handleHttp is async, so without this any rejection inside a route becomes an
// unhandled rejection and Node terminates the process — one bad request took the whole server
// down and every subsequent call 502'd until the platform restarted it.
const httpServer = http.createServer((req, res) => {
  handleHttp(req, res).catch((err) => {
    const unavailable = err && err.code === "STORE_UNAVAILABLE";
    if (unavailable) {
      console.warn(
        `[HTTP]   → ${req.method} ${req.url} → 503 store-unavailable`,
      );
    } else {
      console.error(`[HTTP]   → ${req.method} ${req.url} → 500`, err);
    }
    if (res.headersSent) {
      res.destroy();
      return;
    }
    res.writeHead(unavailable ? 503 : 500, {
      "Content-Type": "application/json",
    });
    res.end(
      JSON.stringify({
        ok: false,
        reason: unavailable ? "store-unavailable" : "internal-error",
      }),
    );
  });
});

// Last resort: log instead of dying silently. A rejection that escapes the boundary above is a
// bug worth seeing in the platform logs rather than a bare stack trace and an exit.
process.on("unhandledRejection", (err) => {
  console.error("[SERVER] unhandled rejection —", err);
});

// The WS message handler has its own try/catch, so client input can no longer reach
// here. Anything that still does is genuinely unexpected, and continuing to serve from
// unknown state is worse than a clean restart — log loudly and let the platform respawn.
process.on("uncaughtException", (err) => {
  console.error(
    "[SERVER] FATAL uncaught exception — exiting for a clean restart:",
    err,
  );
  process.exit(1);
});

const wss = new WebSocketServer({ server: httpServer });
const keepAlive = setInterval(
  () => sweepClients(wss.clients),
  PING_INTERVAL_MS,
);
wss.on("close", () => clearInterval(keepAlive));
httpServer.listen(PORT, () =>
  console.log(`[SERVER] Watch Party running on port ${PORT}`),
);

// ── helpers ──────────────────────────────────────────────────────────────────

function sanitizeName(raw) {
  if (typeof raw !== "string") {
    console.log(
      `[SERVER] sanitizeName: not a string (type=${typeof raw}) → invalid`,
    );
    return null;
  }
  const trimmed = raw.trim();
  if (trimmed.length === 0) {
    console.log(`[SERVER] sanitizeName: empty after trim → invalid`);
    return null;
  }
  if (trimmed.length > MAX_NAME_LEN) {
    console.log(
      `[SERVER] sanitizeName: too long (${trimmed.length}>${MAX_NAME_LEN}) → invalid`,
    );
    return null;
  }
  const escaped = trimmed
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");
  console.log(`[SERVER] sanitizeName: "${raw}" → "${escaped}"`);
  return escaped;
}

function sanitizeChat(raw) {
  if (typeof raw !== "string") {
    console.log(`[SERVER] sanitizeChat: not a string → reject`);
    return null;
  }
  const trimmed = raw.trim();
  if (trimmed.length === 0) {
    console.log(`[SERVER] sanitizeChat: empty → reject`);
    return null;
  }
  const capped =
    trimmed.length > MAX_CHAT_LEN ? trimmed.slice(0, MAX_CHAT_LEN) : trimmed;
  if (trimmed.length > MAX_CHAT_LEN)
    console.log(
      `[SERVER] sanitizeChat: capped ${trimmed.length}→${MAX_CHAT_LEN}`,
    );
  const escaped = capped
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");
  return escaped;
}

function logState(roomId, label) {
  const state = roomState.get(roomId);
  const room = rooms.get(roomId);
  if (!state) {
    console.log(`[${roomId}] ${label} | (no state)`);
    return;
  }
  const members = room
    ? [...room.values()].map((c) => `${c.role}:${c.id}(${c.name})`).join(", ")
    : "none";
  console.log(
    `[${roomId}] ${label} | time=${fmt(state.time)}s paused=${state.paused} url=${state.videoUrl} members=[${members}]`,
  );
}

function participantsList(roomId) {
  const room = rooms.get(roomId);
  if (!room) return [];
  return [...room.values()].map((c) => ({
    id: c.id,
    role: c.role,
    name: c.name,
    voice: !!c.voice,
  }));
}

// A playing room's stored time keeps advancing in the real world after we store it.
// Anyone reading the state later wants where the video is *now*, not where it was at
// the last push. Paused rooms are frozen, so their timestamp stays authoritative.
function projectedTime(state) {
  if (!state) return 0;
  const time = typeof state.time === "number" ? state.time : 0;
  if (state.paused !== false) return time;
  const ageSec =
    Math.max(0, Date.now() - (state.updatedAt || Date.now())) / 1000;
  return time + Math.min(ageSec, MAX_PROJECTION_SEC);
}

function roomStatus(roomId) {
  const state = roomState.get(roomId);
  if (!state)
    return {
      roomId,
      active: false,
      count: 0,
      platform: null,
      videoUrl: null,
      title: null,
    };
  const room = rooms.get(roomId);
  return {
    roomId,
    active: true,
    count: room ? room.size : 0,
    platform: state.platform || null,
    videoUrl: state.videoUrl || null,
    title: state.title || null,
  };
}

function voiceCount(roomId) {
  const room = rooms.get(roomId);
  if (!room) return 0;
  let n = 0;
  for (const info of room.values()) if (info.voice) n++;
  return n;
}

function broadcastVoiceParticipants(roomId, reason) {
  const room = rooms.get(roomId);
  if (!room) return;
  const list = [...room.values()]
    .filter((c) => c.voice)
    .map((c) => ({ id: c.id, name: c.name }));
  console.log(
    `[${roomId}] broadcastVoiceParticipants (reason=${reason}) → ${list.length} member(s)`,
  );
  broadcastToAll(roomId, { type: "voice-participants", list });
}

function broadcastParticipants(roomId, reason) {
  const list = participantsList(roomId);
  console.log(
    `[${roomId}] broadcastParticipants (reason=${reason}) → ${list.length} members`,
  );
  broadcastToAll(roomId, { type: "participants", list });
}

// Broadcasts a payload we constructed server-side to EVERY client in the room (including sender).
function broadcastToAll(roomId, msg) {
  const room = rooms.get(roomId);
  if (!room) {
    console.log(`[${roomId}] broadcastToAll: room missing`);
    return;
  }
  const data = JSON.stringify(msg);
  let sent = 0;
  for (const [client, info] of room) {
    if (client.readyState === 1) {
      client.send(data);
      sent++;
    } else {
      console.log(
        `[${roomId}]   ✗ skipped ${info.role}:${info.id} (readyState=${client.readyState})`,
      );
    }
  }
  console.log(
    `[${roomId}]   broadcastToAll type=${msg.type} → ${sent} client(s)`,
  );
}

// Forwards a raw message from a sender to every OTHER client in the room.
function broadcast(roomId, sender, msg) {
  const room = rooms.get(roomId);
  if (!room) {
    console.log(`[${roomId}] broadcast: room missing`);
    return;
  }
  const data = JSON.stringify(msg);
  let sent = 0;
  for (const [client, info] of room) {
    if (client !== sender && client.readyState === 1) {
      client.send(data);
      console.log(
        `[${roomId}]   → forwarded type=${msg.type} to ${info.role}:${info.id}`,
      );
      sent++;
    }
  }
  if (sent === 0)
    console.log(
      `[${roomId}]   → no connected peers to forward type=${msg.type} to`,
    );
}

function cancelRoomExpiry(roomId) {
  const t = roomGraceTimers.get(roomId);
  if (t) {
    clearTimeout(t);
    roomGraceTimers.delete(roomId);
    console.log(`[${roomId}] grace timer cancelled`);
  }
}

function scheduleRoomExpiry(roomId) {
  cancelRoomExpiry(roomId);
  const state = roomState.get(roomId);
  if (state) state.emptySince = Date.now();
  console.log(
    `[${roomId}] room empty — scheduling expiry in ${ROOM_GRACE_MS}ms`,
  );
  const timer = setTimeout(() => {
    // Defensive: if the room got repopulated between cancel and fire, don't destroy.
    const r = rooms.get(roomId);
    if (r && r.size > 0) {
      console.log(
        `[${roomId}] grace fired but room repopulated — skip destroy`,
      );
      roomGraceTimers.delete(roomId);
      return;
    }
    rooms.delete(roomId);
    clientsById.delete(roomId);
    roomState.delete(roomId);
    roomGraceTimers.delete(roomId);
    console.log(`[${roomId}] grace elapsed — Room DESTROYED`);
  }, ROOM_GRACE_MS);
  roomGraceTimers.set(roomId, timer);
}

function promoteNewHost(roomId) {
  const room = rooms.get(roomId);
  if (!room || room.size === 0) return null;
  const alreadyHost = [...room.values()].some((c) => c.role === "host");
  if (alreadyHost) return null;
  const [ws, info] = [...room.entries()][0]; // oldest surviving member (insertion order)
  info.role = "host";
  console.log(`[${roomId}] promoting ${info.id}(${info.name}) → host`);
  if (ws.readyState === 1)
    ws.send(JSON.stringify({ type: "role", role: "host", clientId: info.id }));
  return info;
}

// ── connection ───────────────────────────────────────────────────────────────

wss.on("connection", (ws, req) => {
  ws.isAlive = true;
  ws.on("pong", () => {
    ws.isAlive = true;
  });

  const ip = req?.socket?.remoteAddress || "unknown";
  console.log(`[WS] connection opened from ${ip}`);

  let roomId = null;
  let clientId = null;
  // True only while handleJoin is awaiting the membership check. Together with the
  // `roomId !== null` test it enforces one socket → one room for the socket's whole
  // lifetime. Two joins on one socket previously left it in BOTH rooms' maps, and
  // the close handler only ever cleans up the room named by `roomId` above — so the
  // first room kept a dead socket, never reached size 0, never expired, and reported
  // a phantom live party through /api/room/:id. That predates this change (it
  // reproduces on the fully synchronous handler); the guard is also what keeps the
  // new await below from making it worse.
  let joinInFlight = false;

  /** ws.send that cannot throw — a peer may vanish between the check and the write. */
  function sendSafe(payload) {
    try {
      ws.send(JSON.stringify(payload));
    } catch (e) {
      console.warn(`[${roomId || "?"}] send failed: ${e.message}`);
    }
  }

  // A throw anywhere in message handling used to reach uncaughtException and take
  // the whole process down — one state-update with a string `time` disconnected
  // every room on the instance. Validation now prevents the known cases; this catch
  // makes an unknown one cost a single dropped message instead of the server.
  ws.on("message", (raw) => {
    try {
      handleWsMessage(raw);
    } catch (err) {
      console.error(
        `[${roomId || "?"}] WS handler threw for ${clientId || ip}:`,
        err,
      );
    }
  });

  function handleWsMessage(raw) {
    let msg;
    try {
      msg = JSON.parse(raw);
    } catch {
      console.error(
        `[WS] bad JSON from ${clientId || ip}:`,
        raw.toString().slice(0, 200),
      );
      return;
    }

    console.log(`[WS] recv from ${clientId || "(pre-join)"} type=${msg.type}`);

    // ── join ──────────────────────────────────────────────────────────────
    if (msg.type === "join") {
      // The only asynchronous path — it consults the pairing store. Split into its
      // own function so every other message type keeps its exact synchronous
      // semantics inside the try/catch above, unchanged by P0-3.
      handleJoin(msg).catch((err) =>
        console.error(`[${msg.roomId || "?"}] join handler threw:`, err),
      );
      return;
    }

    // All post-join messages require roomId + clientId to be set
    if (!roomId || !clientId) {
      console.warn(
        `[WS] ignoring type=${msg.type} — socket not yet joined a room`,
      );
      return;
    }

    const room = rooms.get(roomId);
    const senderInfo = room?.get(ws);
    if (!senderInfo) {
      console.warn(
        `[${roomId}] type=${msg.type} from ${clientId} but sender not in room map — ignoring`,
      );
      return;
    }

    // ── state-update (host → guests) ─────────────────────────────────────
    if (msg.type === "state-update") {
      const state = roomState.get(roomId);
      if (!state) {
        console.warn(
          `[${roomId}] state-update from ${clientId} but room has no state — ignoring`,
        );
        return;
      }
      if (senderInfo.role !== "host") {
        console.warn(
          `[${roomId}] state-update from ${clientId} role=${senderInfo.role} — only host can push, ignoring`,
        );
        return;
      }

      // Validate before touching room state. A malformed value written here reaches
      // the log line below, projectedTime()'s arithmetic, and every guest's player.
      const update = parseStateUpdate(msg);
      if (!update.ok) {
        console.warn(
          `[${roomId}] state-update from ${clientId} REJECTED (${update.reason}) — ignoring`,
        );
        return;
      }

      const wasPaused = state.paused;
      Object.assign(state, {
        time: update.time,
        paused: update.paused,
        // undefined means the client sent nothing usable — keep what the room had
        // rather than blanking a good URL because one heartbeat was malformed.
        videoUrl: update.videoUrl ?? state.videoUrl,
        updatedAt: Date.now(),
      });

      // senderInfo.deviceId, not clientId: clientId is a client-chosen string
      // (`android-1234`) that never matches a real device, so this call always
      // looked like a non-member. Harmless before triggerNudge had a membership
      // guard — it just picked the wrong recipient — but a guaranteed rejection now.
      if (wasPaused && update.paused === false) {
        pairing
          .triggerNudge(pairingStore, push.sendNudge, {
            roomId,
            triggeringDeviceId: senderInfo.deviceId,
          })
          .then((r) =>
            console.log(
              `[${roomId}] auto-nudge on video-start → ${JSON.stringify(r)}`,
            ),
          )
          .catch((e) =>
            console.warn(`[${roomId}] auto-nudge error: ${e.message}`),
          );
      }

      const guestCount = [...room.values()].filter(
        (c) => c.role === "guest",
      ).length;
      console.log(
        `[${roomId}] HOST state-update: time=${fmt(state.time)}s paused=${state.paused} videoUrl=${state.videoUrl} → broadcasting to ${guestCount} guest(s)`,
      );
      logState(roomId, "room state now");

      broadcast(roomId, ws, {
        type: "state-update",
        time: state.time,
        paused: state.paused,
        videoUrl: state.videoUrl,
        platform: state.platform,
      });
      return;
    }

    // ── voice-token-request (client wants to join LiveKit voice room) ───
    if (msg.type === "voice-token-request") {
      console.log(
        `[${roomId}] VOICE-TOKEN-REQUEST from ${senderInfo.id}(${senderInfo.name}) — voice_ready=${LK_READY}`,
      );
      if (!LK_READY) {
        ws.send(
          JSON.stringify({
            type: "error",
            reason: "voice-not-configured",
            detail: "server missing LiveKit credentials",
          }),
        );
        return;
      }
      const current = voiceCount(roomId);
      if (!senderInfo.voice && current >= VOICE_CAP) {
        console.warn(
          `[${roomId}]   → REJECT: voice cap ${VOICE_CAP} reached (current=${current})`,
        );
        ws.send(
          JSON.stringify({
            type: "error",
            reason: "voice-room-full",
            detail: `Voice cap is ${VOICE_CAP}`,
          }),
        );
        return;
      }
      mintToken(roomId, senderInfo.id, senderInfo.name)
        .then((result) => {
          if (!result.ok) {
            console.warn(`[${roomId}]   → mintToken failed: ${result.reason}`);
            ws.send(
              JSON.stringify({
                type: "error",
                reason: result.reason || "voice-token-failed",
              }),
            );
            return;
          }
          senderInfo.voice = true;
          console.log(
            `[${roomId}]   → marked ${senderInfo.id} voice=true (count now ${voiceCount(roomId)})`,
          );
          ws.send(
            JSON.stringify({
              type: "voice-token",
              token: result.token,
              lkUrl: result.lkUrl,
              lkRoom: result.lkRoom,
            }),
          );
          broadcastVoiceParticipants(roomId, "voice-join");
          broadcastParticipants(roomId, "voice-join");
        })
        .catch((err) => {
          console.error(`[${roomId}]   → mintToken threw: ${err.message}`);
          ws.send(
            JSON.stringify({
              type: "error",
              reason: "voice-token-failed",
              detail: err.message,
            }),
          );
        });
      return;
    }

    // ── voice-leave (client is disconnecting from LiveKit room) ─────────
    if (msg.type === "voice-leave") {
      console.log(
        `[${roomId}] VOICE-LEAVE from ${senderInfo.id} (was voice=${senderInfo.voice})`,
      );
      if (senderInfo.voice) {
        senderInfo.voice = false;
        broadcastVoiceParticipants(roomId, "voice-leave");
        broadcastParticipants(roomId, "voice-leave");
      }
      return;
    }

    // ── chat (any client → all clients incl. sender) ────────────────────
    if (msg.type === "chat") {
      const clean = sanitizeChat(msg.text);
      if (!clean) {
        console.warn(
          `[${roomId}] chat from ${clientId} dropped — empty/invalid text`,
        );
        return;
      }
      const payload = {
        type: "chat",
        from: senderInfo.id, // server-verified, never trust client-supplied from
        name: senderInfo.name, // server-stamped
        text: clean,
        ts: Date.now(),
      };
      console.log(
        `[${roomId}] CHAT from ${senderInfo.role}:${senderInfo.id}(${senderInfo.name}) → "${clean.slice(0, 80)}${clean.length > 80 ? "…" : ""}"`,
      );
      broadcastToAll(roomId, payload);
      return;
    }

    // ── reaction (any client → everyone else; sender renders locally) ────
    if (msg.type === "reaction") {
      const emoji =
        typeof msg.emoji === "string" && msg.emoji.trim()
          ? msg.emoji.trim().slice(0, 8)
          : "💗";
      console.log(
        `[${roomId}] REACTION ${emoji} from ${senderInfo.role}:${senderInfo.id}(${senderInfo.name}) → relaying`,
      );
      broadcast(roomId, ws, { type: "reaction", emoji, from: senderInfo.id });
      return;
    }

    // ── close-app (one device asks its peers to shut down) ───────────────
    if (msg.type === "close-app") {
      console.log(
        `[${roomId}] CLOSE-APP from ${senderInfo.role}:${senderInfo.id}(${senderInfo.name}) → relaying to peers`,
      );
      // from/name are stamped here, never taken from the client, so a peer can
      // always show who asked. The sender is excluded — it keeps its own app.
      broadcast(roomId, ws, {
        type: "close-app",
        from: senderInfo.id,
        name: senderInfo.name,
      });
      return;
    }

    // ── leave-party (one device ends the movie night for the whole room) ─
    if (msg.type === "leave-party") {
      const state = roomState.get(roomId);
      if (state) {
        Object.assign(state, {
          videoUrl: null,
          time: 0,
          paused: true,
          updatedAt: Date.now(),
        });
      }
      console.log(
        `[${roomId}] LEAVE-PARTY from ${senderInfo.role}:${senderInfo.id}(${senderInfo.name}) → room state cleared, relaying to peers`,
      );
      // from/name are stamped here, never taken from the client, so a peer can
      // always show who left. The sender is excluded — it's already navigating
      // away on its own.
      broadcast(roomId, ws, {
        type: "party-left",
        from: senderInfo.id,
        name: senderInfo.name,
      });
      return;
    }

    console.log(
      `[${roomId}] unknown message type="${msg.type}" from clientId=${clientId}`,
    );
  }

  /**
   * Join, in a strict order that keeps the one `await` out of the critical section.
   *
   * The room bookkeeping below (evict stale → restore role → insert → promote) is
   * the same code that fixed the hostless-room bug, and its correctness argument
   * depends on running to completion in a single uninterrupted turn. So:
   *
   *   1–3  synchronous: parse, refuse a second join, mark the check in flight
   *   4    the ONLY await — consult the pairing store
   *   5–7  synchronous: clear the flag, drop a socket that died meanwhile, refuse a
   *        non-member WITHOUT touching any shared state
   *   8    the critical section, byte-for-byte as before, no await inside
   *
   * Steps 1–7 mutate nothing shared, so nothing they observed can have gone stale
   * by step 8. Note that `roomId`/`clientId` stay null throughout 1–7: a non-join
   * frame arriving during the await is dropped by the guard in handleWsMessage.
   * That is a real behaviour change from the old synchronous join, but harmless —
   * every client sends its first frame only after receiving `joined`.
   */
  async function handleJoin(msg) {
    const incomingRoom = msg.roomId;
    const incomingId = msg.clientId || Math.random().toString(36).slice(2);
    // Never read platform/videoUrl off msg past this point. These seed a new
    // room's state, which is then served to every later joiner in their `joined`
    // payload and through GET /api/room/:id — so an unvalidated URL planted here
    // reaches an Android guest's WebView.loadUrl without the sender ever being
    // host or sending a single state-update.
    const { platform, videoUrl } = parseJoinContent(msg);
    const deviceId = parseDeviceId(msg);
    const name = sanitizeName(msg.displayName);

    console.log(
      `[${incomingRoom}] JOIN request clientId=${incomingId} deviceId=${deviceId || "(absent)"} platform=${platform} videoUrl=${videoUrl} rawName="${msg.displayName}"`,
    );

    // One socket, one room, for its lifetime. See the joinInFlight comment above.
    if (joinInFlight || roomId !== null) {
      console.warn(
        `[${incomingRoom}] REFUSED second join — socket is already in ${roomId || "a join in flight"}`,
      );
      sendSafe({
        type: "error",
        reason: "already-joined",
        detail: "this connection has already joined a room",
      });
      return;
    }

    if (!name) {
      console.warn(
        `[${incomingRoom}] JOIN REJECTED for clientId=${incomingId} — invalid displayName`,
      );
      sendSafe({
        type: "error",
        reason: "name-required",
        detail: `displayName required (1-${MAX_NAME_LEN} chars, after trim)`,
      });
      ws.close(1008, "name-required");
      return;
    }

    joinInFlight = true;
    let check;
    try {
      check = await wsauth.checkMembership(
        pairingStore,
        incomingRoom,
        deviceId,
      );
    } finally {
      joinInFlight = false;
    }
    const gate = wsauth.decide(check.verdict, WS_AUTH_MODE, WS_AUTH_FAIL);

    // The rollout signal. Every field matters: `verdict` distinguishes a real
    // non-member from an ad-hoc room or a store outage, and `allowed` is what has
    // to read false-for-nobody before WS_AUTH_MODE is flipped to enforce.
    console.log(
      `[WS-AUTH] room=${incomingRoom} device=${deviceId || "absent"} ` +
        `verdict=${check.verdict}${check.stale ? "(stale)" : ""} cached=${!!check.cached} ` +
        `mode=${WS_AUTH_MODE} fail=${WS_AUTH_FAIL} allowed=${gate.allow}`,
    );

    // The socket may have gone away while we were talking to Upstash. Inserting a
    // dead socket into the room map would leave a member that never leaves.
    if (ws.readyState !== 1) {
      console.warn(
        `[${incomingRoom}] socket closed during the membership check — abandoning join`,
      );
      return;
    }

    if (!gate.allow) {
      console.warn(
        `[${incomingRoom}] JOIN REJECTED for clientId=${incomingId} — ${gate.reason}`,
      );
      sendSafe({
        type: "error",
        reason: gate.reason,
        detail:
          gate.reason === "not-a-room-member"
            ? "this device is not paired to that room"
            : "room membership could not be verified right now",
      });
      ws.close(1008, gate.reason);
      return;
    }

    // ══ critical section — synchronous from here to the end, no await ══════════
    roomId = incomingRoom;
    clientId = incomingId;

    const roomExisted = rooms.has(roomId);
    if (!roomExisted) {
      console.log(`[${roomId}] room does not exist yet → CREATE`);
      rooms.set(roomId, new Map());
      clientsById.set(roomId, new Map());
      roomState.set(roomId, {
        platform,
        videoUrl,
        time: 0,
        paused: true,
        updatedAt: Date.now(),
      });
      console.log(
        `[${roomId}] room CREATED — initial state: platform=${platform} videoUrl=${videoUrl}`,
      );
    } else {
      console.log(`[${roomId}] room exists → joining`);
    }

    const room = rooms.get(roomId);
    const byId = clientsById.get(roomId);

    cancelRoomExpiry(roomId);

    let priorRole = null;
    if (byId.has(clientId)) {
      console.warn(
        `[${roomId}] clientId=${clientId} already present — closing stale socket first`,
      );
      const stale = byId.get(clientId);
      // Capture the role BEFORE the eviction below deletes it. Deriving role from
      // room.size afterwards demoted a reconnecting host to guest (the eviction had
      // already shrunk the room), while the stale socket's close handler skipped
      // promotion because its `leaving` lookup was empty — leaving the room with
      // zero hosts and playback sync permanently dead.
      priorRole = room.get(stale)?.role ?? null;
      // Evict BEFORE closing. The stale socket's close handler announces a
      // departure, and it must not do so for a client that is merely
      // reconnecting — dropping it from the room first is what makes its
      // `leaving` lookup come back empty and stay quiet.
      room.delete(stale);
      try {
        stale.close(1000, "replaced");
      } catch {}
    }

    // Why restoring priorRole cannot produce two hosts: a socket leaves a room by
    // exactly one of two paths, and each destroys what the other needs.
    //   A. reconnect eviction (here) — leaves byId pointing at the NEW socket, so
    //      the stale close handler finds `leaving` undefined and never promotes.
    //   B. genuine disconnect (close handler) — deletes the byId entry AND promotes,
    //      so a later rejoin finds no stale entry and priorRole is null.
    // They cannot interleave: stale.close() only queues the close event, and Node's
    // event loop is single-threaded, so this block always runs to completion first.
    // That still holds with the await above, because the await is behind us.
    const role = priorRole ?? (room.size === 0 ? "host" : "guest");
    console.log(
      `[${roomId}] assigning role=${role}${priorRole ? " (restored on reconnect)" : ""} (room size before insert = ${room.size})`,
    );

    // deviceId rides along on the member record so later handlers have a verified
    // identity to work with — the auto-nudge below used to pass `clientId`, which is
    // client-chosen and never matches a real device.
    room.set(ws, { role, id: clientId, name, voice: false, deviceId });
    byId.set(clientId, ws);

    // Insurance against any path not covered by the two above. A room with no host
    // accepts no state-update at all, so it is worth an O(members) check per join.
    if (![...room.values()].some((c) => c.role === "host")) {
      console.warn(`[${roomId}] no host after join — promoting`);
      promoteNewHost(roomId);
    }

    const state = roomState.get(roomId) || {};
    const response = {
      type: "joined",
      role,
      clientId,
      name,
      platform: state.platform,
      videoUrl: state.videoUrl,
      // Aged forward: state.time is a snapshot from the host's last push, which
      // may be up to a heartbeat old. Handing it over raw starts every joiner
      // behind by exactly that much, and a hard seek can never claw it back.
      time: projectedTime(state),
      paused: state.paused,
      existed: roomExisted,
    };
    console.log(
      `[${roomId}] → sending joined payload:`,
      JSON.stringify(response),
    );
    ws.send(JSON.stringify(response));

    logState(roomId, "room state after join");
    broadcastParticipants(roomId, "member-joined");
  }

  ws.on("close", (code, reason) => {
    console.log(
      `[WS] close from ${clientId || ip} code=${code} reason="${reason?.toString() || ""}"`,
    );
    if (!roomId) {
      console.log(`[WS]   (socket never joined a room)`);
      return;
    }

    const room = rooms.get(roomId);
    const byId = clientsById.get(roomId);
    if (!room) {
      console.log(`[${roomId}] close: room already gone`);
      return;
    }

    const leaving = room.get(ws);
    room.delete(ws);
    if (byId && leaving) byId.delete(leaving.id);

    console.log(
      `[${roomId}] ${leaving?.role || "?"}:${clientId} disconnected (${room.size} remaining)`,
    );

    if (room.size === 0) {
      scheduleRoomExpiry(roomId);
      console.log(`[${roomId}] now empty — kept alive for grace window`);
    } else {
      if (leaving?.role === "host") {
        console.log(`[${roomId}] host left — promoting a replacement`);
        promoteNewHost(roomId);
      }
      // Only a socket that was still a room member genuinely left. A socket
      // displaced by a reconnect was already evicted in the join handler, so
      // `leaving` is empty and no departure is announced.
      if (leaving) {
        console.log(
          `[${roomId}] announcing departure of ${leaving.id}(${leaving.name})`,
        );
        broadcastToAll(roomId, {
          type: "system",
          event: "left",
          name: leaving.name,
          ts: Date.now(),
        });
      }
      broadcastParticipants(roomId, "member-left");
      if (leaving?.voice) {
        console.log(
          `[${roomId}]   leaver was in voice — broadcasting voice-participants`,
        );
        broadcastVoiceParticipants(roomId, "member-left");
      }
    }
  });

  ws.on("error", (err) =>
    console.error(
      `[${roomId || "(?)"}] ws error from ${clientId || ip}: ${err.message}`,
    ),
  );
});
