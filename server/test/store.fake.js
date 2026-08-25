// In-memory fake implementing store.js's interface — no network, for fast pairing-logic tests.
//
// HWP_FAKE_STORE_DELAY_MS adds artificial latency to getRoom. The real store is a
// network call, so the WebSocket join handler now awaits mid-flight; with an
// instant fake that await resolves in a single microtask and the window is
// impossible to aim at from a test. The delay makes "the socket died during the
// membership check" and "a frame arrived during the await" deterministic instead of
// a race the suite would lose 99 times out of 100.
const GET_ROOM_DELAY_MS = Number(process.env.HWP_FAKE_STORE_DELAY_MS) || 0;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function makeFakeStore() {
  const devices = new Map();
  const rooms = new Map();
  const roomNames = new Map(); // roomName -> roomId
  const invites = new Map(); // token -> roomId
  return {
    async getDevice(id) {
      return devices.get(id) ?? null;
    },
    async putDevice(id, rec) {
      devices.set(id, rec);
    },
    async getRoom(id) {
      if (GET_ROOM_DELAY_MS) await sleep(GET_ROOM_DELAY_MS);
      return rooms.get(id) ?? null;
    },
    async putRoom(id, rec) {
      rooms.set(id, rec);
    },
    async findRoomIdByName(name) {
      return roomNames.get(name) ?? null;
    },
    async reserveRoomName(name, roomId) {
      if (roomNames.has(name)) return false;
      roomNames.set(name, roomId);
      return true;
    },
    async getInvite(token) {
      const roomId = invites.get(token);
      return roomId ? { roomId } : null;
    },
    async putInvite(token, roomId) {
      invites.set(token, roomId);
    },
    async deleteInvite(token) {
      invites.delete(token);
    },
  };
}
module.exports = { makeFakeStore };
