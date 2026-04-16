/**
 * host-sim.js — Simulates a host client for end-to-end testing.
 * Run this alongside server.js to act as host in room "party1".
 * When an Android guest joins, it will receive sync-response and seek to the reported time.
 */
const { WebSocket } = require('ws');

const SERVER = process.argv[2] || 'ws://localhost:8080';
const ROOM   = process.argv[3] || 'party1';

let currentTime = 300; // simulated playhead starts at 5 minutes
let paused = false;
let ws;

function connect() {
  ws = new WebSocket(SERVER);

  ws.on('open', () => {
    ws.send(JSON.stringify({ type: 'join', roomId: ROOM, clientId: 'host-sim' }));
    console.log(`[host-sim] Connected to ${SERVER}, joining room "${ROOM}"`);
  });

  ws.on('message', raw => {
    const msg = JSON.parse(raw);
    console.log('[host-sim] recv:', msg.type, msg);

    if (msg.type === 'joined') {
      console.log(`[host-sim] Role: ${msg.role}`);
      // Advance playhead every second to simulate real playback
      setInterval(() => { if (!paused) currentTime += 1; }, 1000);
      return;
    }

    if (msg.type === 'sync-request') {
      // Guest joined — send current video state
      const resp = { type: 'sync-response', time: currentTime, paused };
      ws.send(JSON.stringify(resp));
      console.log(`[host-sim] Sent sync-response: time=${currentTime.toFixed(1)}s paused=${paused}`);
    }
  });

  ws.on('close', () => console.log('[host-sim] Disconnected'));
  ws.on('error', e => console.error('[host-sim] Error:', e.message));
}

connect();

// Every 5s, send a "still playing" keepalive seek so guest drift is correctable
setInterval(() => {
  if (ws && ws.readyState === WebSocket.OPEN && !paused) {
    ws.send(JSON.stringify({ type: 'seek', time: currentTime }));
    console.log(`[host-sim] keepalive seek t=${currentTime.toFixed(1)}s`);
  }
}, 5000);

console.log('[host-sim] Press Ctrl+C to quit');
