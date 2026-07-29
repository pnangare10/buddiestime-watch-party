// In-memory fake implementing store.js's interface — no network, for fast pairing-logic tests.
function makeFakeStore() {
  const devices = new Map();
  const rooms = new Map();
  const roomNames = new Map(); // roomName -> roomId
  const invites = new Map(); // token -> roomId
  return {
    async getDevice(id) { return devices.get(id) ?? null; },
    async putDevice(id, rec) { devices.set(id, rec); },
    async getRoom(id) { return rooms.get(id) ?? null; },
    async putRoom(id, rec) { rooms.set(id, rec); },
    async findRoomIdByName(name) { return roomNames.get(name) ?? null; },
    async reserveRoomName(name, roomId) {
      if (roomNames.has(name)) return false;
      roomNames.set(name, roomId);
      return true;
    },
    async getInvite(token) { const roomId = invites.get(token); return roomId ? { roomId } : null; },
    async putInvite(token, roomId) { invites.set(token, roomId); },
    async deleteInvite(token) { invites.delete(token); },
  };
}
module.exports = { makeFakeStore };
