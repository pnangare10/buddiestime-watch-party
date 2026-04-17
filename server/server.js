const http = require('http');
const fs   = require('fs');
const path = require('path');
const { WebSocketServer } = require('ws');

const PORT = process.env.PORT || 8080;
const rooms = new Map(); // roomId → Map<ws, { role: 'host'|'guest', id: string }>

// ── HTTP server: serves static HTML files ───────────────────────────────────
const STATIC_ROUTES = {
  '/':             path.join(__dirname, 'index.html'),
  '/install.html':    path.join(__dirname, '..', 'bookmarklet', 'install.html'),
  '/test-page.html':  path.join(__dirname, 'test-page.html'),
  '/guest-test.html': path.join(__dirname, 'guest-test.html'),
};

const httpServer = http.createServer((req, res) => {
  const url = new URL(req.url, `http://localhost`);
  const filePath = STATIC_ROUTES[url.pathname];
  if (filePath) {
    fs.readFile(filePath, (err, data) => {
      if (err) { res.writeHead(404); res.end('Not found'); return; }
      res.writeHead(200, { 'Content-Type': 'text/html' });
      res.end(data);
    });
    return;
  }
  res.writeHead(200, { 'Content-Type': 'text/plain' });
  res.end('Hotstar Watch Party server running');
});

const wss = new WebSocketServer({ server: httpServer });
httpServer.listen(PORT, () => console.log(`Watch Party server running on port ${PORT}`));

wss.on('connection', ws => {
  let roomId = null;
  let clientId = null;

  ws.on('message', raw => {
    let msg;
    try { msg = JSON.parse(raw); } catch { return; }

    if (msg.type === 'join') {
      roomId = msg.roomId;
      clientId = msg.clientId || Math.random().toString(36).slice(2);

      if (!rooms.has(roomId)) rooms.set(roomId, new Map());
      const room = rooms.get(roomId);

      // First client in room becomes host
      const role = room.size === 0 ? 'host' : 'guest';
      room.set(ws, { role, id: clientId });

      ws.send(JSON.stringify({ type: 'joined', role, clientId }));

      // If guest joined, ask host for current state
      if (role === 'guest') {
        broadcast(roomId, ws, { type: 'sync-request', from: clientId });
      }

      console.log(`[${roomId}] ${role} joined (${room.size} total)`);
      return;
    }

    // All other messages: broadcast to room except sender
    if (roomId) broadcast(roomId, ws, msg);
  });

  ws.on('close', () => {
    if (!roomId) return;
    const room = rooms.get(roomId);
    if (!room) return;
    room.delete(ws);
    console.log(`[${roomId}] client left (${room.size} remaining)`);
    if (room.size === 0) rooms.delete(roomId);
  });

  ws.on('error', err => console.error('ws error:', err.message));
});

function broadcast(roomId, sender, msg) {
  const room = rooms.get(roomId);
  if (!room) return;
  const data = JSON.stringify(msg);
  for (const [client] of room) {
    if (client !== sender && client.readyState === 1) {
      client.send(data);
    }
  }
}
