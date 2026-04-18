const http = require('http');
const fs   = require('fs');
const path = require('path');
const { WebSocketServer } = require('ws');

const PORT = process.env.PORT || 8080;
const rooms     = new Map(); // roomId → Map<ws, { role: 'host'|'guest', id: string }>
const roomState = new Map(); // roomId → { platform, videoUrl, time, paused, updatedAt }

const STATIC_ROUTES = {
  '/':             path.join(__dirname, 'index.html'),
  '/install.html': path.join(__dirname, '..', 'bookmarklet', 'install.html'),
};

const httpServer = http.createServer((req, res) => {
  const url = new URL(req.url, `http://localhost`);

  if (url.pathname === '/health') {
    res.writeHead(200, { 'Content-Type': 'text/plain' });
    res.end('OK');
    return;
  }

  if (url.pathname.startsWith('/room/')) {
    fs.readFile(path.join(__dirname, 'room.html'), (err, data) => {
      if (err) { res.writeHead(404); res.end('Not found'); return; }
      res.writeHead(200, { 'Content-Type': 'text/html' });
      res.end(data);
    });
    return;
  }

  const filePath = STATIC_ROUTES[url.pathname];
  if (filePath) {
    fs.readFile(filePath, (err, data) => {
      if (err) { res.writeHead(404); res.end('Not found'); return; }
      res.writeHead(200, { 'Content-Type': 'text/html' });
      res.end(data);
    });
    return;
  }

  res.writeHead(404, { 'Content-Type': 'text/plain' });
  res.end('Not found');
});

const wss = new WebSocketServer({ server: httpServer });
httpServer.listen(PORT, () => console.log(`[SERVER] Watch Party running on port ${PORT}`));

function logState(roomId, label) {
  const state = roomState.get(roomId);
  const room  = rooms.get(roomId);
  if (!state) return;
  const members = room ? [...room.values()].map(c => c.role + ':' + c.id).join(', ') : 'none';
  console.log(`[${roomId}] ${label} | time=${state.time?.toFixed(2)}s paused=${state.paused} url=${state.videoUrl} members=[${members}]`);
}

wss.on('connection', ws => {
  let roomId   = null;
  let clientId = null;

  ws.on('message', raw => {
    let msg;
    try { msg = JSON.parse(raw); } catch { console.error('[SERVER] bad JSON:', raw); return; }

    // ── join ────────────────────────────────────────────────────────────────
    if (msg.type === 'join') {
      roomId   = msg.roomId;
      clientId = msg.clientId || Math.random().toString(36).slice(2);
      const { platform, videoUrl } = msg;

      console.log(`[${roomId}] JOIN request from clientId=${clientId} platform=${platform} videoUrl=${videoUrl}`);

      if (!rooms.has(roomId)) {
        rooms.set(roomId, new Map());
        roomState.set(roomId, { platform, videoUrl, time: 0, paused: true, updatedAt: Date.now() });
        console.log(`[${roomId}] Room CREATED — initial state: platform=${platform} videoUrl=${videoUrl}`);
      }

      const room = rooms.get(roomId);
      const role = room.size === 0 ? 'host' : 'guest';
      room.set(ws, { role, id: clientId });

      const state = roomState.get(roomId) || {};
      const response = { type: 'joined', role, clientId, platform: state.platform, videoUrl: state.videoUrl, time: state.time, paused: state.paused };
      ws.send(JSON.stringify(response));

      console.log(`[${roomId}] JOINED as ${role} (${room.size} total) — sent state: time=${state.time?.toFixed(2)}s paused=${state.paused} videoUrl=${state.videoUrl}`);
      logState(roomId, 'room state after join');
      return;
    }

    // ── state-update ────────────────────────────────────────────────────────
    if (msg.type === 'state-update') {
      const state = roomState.get(roomId);
      const room  = rooms.get(roomId);
      const senderRole = room?.get(ws)?.role;

      if (!state) {
        console.warn(`[${roomId}] state-update from ${clientId} but room has no state — ignoring`);
        return;
      }
      if (senderRole !== 'host') {
        console.warn(`[${roomId}] state-update from ${clientId} (role=${senderRole}) — only host can push state, ignoring`);
        return;
      }

      Object.assign(state, { time: msg.time, paused: msg.paused, videoUrl: msg.videoUrl, updatedAt: Date.now() });

      const guestCount = [...room.values()].filter(c => c.role === 'guest').length;
      console.log(`[${roomId}] HOST state-update received: time=${msg.time?.toFixed(2)}s paused=${msg.paused} videoUrl=${msg.videoUrl} — broadcasting to ${guestCount} guest(s)`);
      logState(roomId, 'room state now');

      broadcast(roomId, ws, { type: 'state-update', time: state.time, paused: state.paused, videoUrl: state.videoUrl });
      return;
    }

    console.log(`[${roomId}] unknown message type="${msg.type}" from clientId=${clientId}`);
  });

  ws.on('close', () => {
    if (!roomId) return;
    const room = rooms.get(roomId);
    if (!room) return;
    const leavingRole = room.get(ws)?.role;
    room.delete(ws);
    console.log(`[${roomId}] ${leavingRole}:${clientId} disconnected (${room.size} remaining)`);
    if (room.size === 0) {
      rooms.delete(roomId);
      roomState.delete(roomId);
      console.log(`[${roomId}] Room DESTROYED (empty)`);
    }
  });

  ws.on('error', err => console.error(`[${roomId}] ws error:`, err.message));
});

function broadcast(roomId, sender, msg) {
  const room = rooms.get(roomId);
  if (!room) return;
  const data = JSON.stringify(msg);
  let sent = 0;
  for (const [client, info] of room) {
    if (client !== sender && client.readyState === 1) {
      client.send(data);
      console.log(`[${roomId}]   → pushed to ${info.role}:${info.id}`);
      sent++;
    }
  }
  if (sent === 0) console.log(`[${roomId}]   → no connected guests to broadcast to`);
}
