// Persistent Device/Room storage over Upstash Redis's REST API (no client SDK —
// plain fetch, two env vars, same "external service via env creds" shape as livekit.js).
const BASE = process.env.UPSTASH_REDIS_REST_URL;
const TOKEN = process.env.UPSTASH_REDIS_REST_TOKEN;
const STORE_READY = !!(BASE && TOKEN);

if (!STORE_READY) {
  console.warn('[STORE] Upstash credentials missing — pairing endpoints will fail closed');
  console.warn('[STORE]   UPSTASH_REDIS_REST_URL set?', !!BASE);
  console.warn('[STORE]   UPSTASH_REDIS_REST_TOKEN set?', !!TOKEN);
} else {
  console.log('[STORE] Upstash ready');
}

async function cmd(...parts) {
  const url = BASE + '/' + parts.map(encodeURIComponent).join('/');
  const res = await fetch(url, { headers: { Authorization: `Bearer ${TOKEN}` } });
  if (!res.ok) throw new Error(`[STORE] Upstash ${parts[0]} failed: ${res.status}`);
  const body = await res.json();
  return body.result;
}

async function getJSON(key) {
  const raw = await cmd('GET', key);
  return raw ? JSON.parse(raw) : null;
}
async function putJSON(key, value) {
  await cmd('SET', key, JSON.stringify(value));
}

const deviceKey = (id) => `device:${id}`;
const roomKey = (id) => `room:${id}`;
const roomNameKey = (name) => `roomname:${name.toLowerCase()}`;
const inviteKey = (token) => `invite:${token}`;

async function getDevice(id) { return getJSON(deviceKey(id)); }
async function putDevice(id, rec) { return putJSON(deviceKey(id), rec); }
async function getRoom(id) { return getJSON(roomKey(id)); }
async function putRoom(id, rec) { return putJSON(roomKey(id), rec); }
async function findRoomIdByName(name) { return cmd('GET', roomNameKey(name)); }

// SETNX — atomic "set if not exists"; Upstash returns 1 on success, 0 if the key existed.
async function reserveRoomName(name, roomId) {
  const result = await cmd('SETNX', roomNameKey(name), roomId);
  return result === 1;
}

async function getInvite(token) {
  const roomId = await cmd('GET', inviteKey(token));
  return roomId ? { roomId } : null;
}
async function putInvite(token, roomId) { await cmd('SET', inviteKey(token), roomId); }
async function deleteInvite(token) { await cmd('DEL', inviteKey(token)); }

module.exports = {
  STORE_READY, getDevice, putDevice, getRoom, putRoom,
  findRoomIdByName, reserveRoomName, getInvite, putInvite, deleteInvite,
};
