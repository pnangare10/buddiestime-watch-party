require("dotenv").config();

const http = require("http");
const fs = require("fs");
const path = require("path");
const { WebSocketServer } = require("ws");
const { mintToken, LK_READY } = require("./livekit");

const PORT = process.env.PORT || 8080;
const MAX_NAME_LEN = 32;
const MAX_CHAT_LEN = 500;
const VOICE_CAP = 4;
const ROOM_GRACE_MS = Number(process.env.ROOM_GRACE_MS) || 300000;

const rooms = new Map(); // roomId → Map<ws, { role, id, name, voice: boolean }>
const clientsById = new Map(); // roomId → Map<clientId, ws>
const roomState = new Map(); // roomId → { platform, videoUrl, time, paused, updatedAt }
const roomGraceTimers = new Map(); // roomId → timeout

const STATIC_ROUTES = {
  "/": path.join(__dirname, "index.html"),
  "/install.html": path.join(__dirname, "..", "bookmarklet", "install.html"),
};

const httpServer = http.createServer((req, res) => {
  const url = new URL(req.url, `http://localhost`);
  console.log(`[HTTP] ${req.method} ${url.pathname}`);

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
});

const wss = new WebSocketServer({ server: httpServer });
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
    `[${roomId}] ${label} | time=${state.time?.toFixed(2)}s paused=${state.paused} url=${state.videoUrl} members=[${members}]`,
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
  const ip = req?.socket?.remoteAddress || "unknown";
  console.log(`[WS] connection opened from ${ip}`);

  let roomId = null;
  let clientId = null;

  ws.on("message", (raw) => {
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
      const incomingRoom = msg.roomId;
      const incomingId = msg.clientId || Math.random().toString(36).slice(2);
      const { platform, videoUrl } = msg;
      const name = sanitizeName(msg.displayName);

      console.log(
        `[${incomingRoom}] JOIN request clientId=${incomingId} platform=${platform} videoUrl=${videoUrl} rawName="${msg.displayName}"`,
      );

      if (!name) {
        console.warn(
          `[${incomingRoom}] JOIN REJECTED for clientId=${incomingId} — invalid displayName`,
        );
        try {
          ws.send(
            JSON.stringify({
              type: "error",
              reason: "name-required",
              detail: `displayName required (1-${MAX_NAME_LEN} chars, after trim)`,
            }),
          );
        } catch (e) {
          console.warn(
            `[${incomingRoom}] failed to send reject reason: ${e.message}`,
          );
        }
        ws.close(1008, "name-required");
        return;
      }

      roomId = incomingRoom;
      clientId = incomingId;

      if (!rooms.has(roomId)) {
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

      if (byId.has(clientId)) {
        console.warn(
          `[${roomId}] clientId=${clientId} already present — closing stale socket first`,
        );
        const stale = byId.get(clientId);
        try {
          stale.close(1000, "replaced");
        } catch {}
        room.delete(stale);
      }

      const role = room.size === 0 ? "host" : "guest";
      console.log(
        `[${roomId}] assigning role=${role} (room size before insert = ${room.size})`,
      );

      room.set(ws, { role, id: clientId, name, voice: false });
      byId.set(clientId, ws);

      const state = roomState.get(roomId) || {};
      const response = {
        type: "joined",
        role,
        clientId,
        name,
        platform: state.platform,
        videoUrl: state.videoUrl,
        time: state.time,
        paused: state.paused,
      };
      console.log(
        `[${roomId}] → sending joined payload:`,
        JSON.stringify(response),
      );
      ws.send(JSON.stringify(response));

      logState(roomId, "room state after join");
      broadcastParticipants(roomId, "member-joined");
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

      Object.assign(state, {
        time: msg.time,
        paused: msg.paused,
        videoUrl: msg.videoUrl,
        updatedAt: Date.now(),
      });

      const guestCount = [...room.values()].filter(
        (c) => c.role === "guest",
      ).length;
      console.log(
        `[${roomId}] HOST state-update: time=${msg.time?.toFixed(2)}s paused=${msg.paused} videoUrl=${msg.videoUrl} → broadcasting to ${guestCount} guest(s)`,
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

    console.log(
      `[${roomId}] unknown message type="${msg.type}" from clientId=${clientId}`,
    );
  });

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
