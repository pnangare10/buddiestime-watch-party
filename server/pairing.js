// Pure pairing business logic over an injected store (see store.js / store.fake.js
// for the interface both must satisfy). No HTTP here — server.js's routes call these.
const crypto = require('crypto');

function newId() { return crypto.randomBytes(16).toString('hex'); }
function newToken() { return crypto.randomBytes(24).toString('base64url'); }

async function createDevice(store) {
  const deviceId = newId();
  await store.putDevice(deviceId, {
    deviceId, roomId: null, role: null,
    profile: {}, fcmToken: null, quietHours: null, createdAt: Date.now(),
  });
  console.log(`[PAIRING] createDevice → ${deviceId}`);
  return { deviceId };
}

async function createRoom(store, { roomName, ownerDeviceId, ownerProfile, partnerProfileDraft, anniversaryDate }) {
  const device = await store.getDevice(ownerDeviceId);
  if (!device) return { ok: false, reason: 'unknown-device' };
  if (device.roomId) return { ok: false, reason: 'device-already-in-room' };

  const roomId = newId();
  const reserved = await store.reserveRoomName(roomName, roomId);
  if (!reserved) {
    console.warn(`[PAIRING] createRoom: name "${roomName}" taken`);
    return { ok: false, reason: 'room-name-taken' };
  }

  await store.putRoom(roomId, {
    roomId, roomName, ownerDeviceId, partnerDeviceId: null,
    anniversaryDate: anniversaryDate ?? null,
    theme: { mode: 'auto', value: null, setByDeviceId: null, setAt: null },
    nudgeMessages: [], welcomeMessages: [],
    pendingInvite: null,
    partnerProfileDraft: partnerProfileDraft || {},
    createdAt: Date.now(), updatedAt: Date.now(),
  });
  device.roomId = roomId; device.role = 'owner'; device.profile = ownerProfile || {};
  await store.putDevice(ownerDeviceId, device);

  console.log(`[PAIRING] createRoom → roomId=${roomId} name="${roomName}" owner=${ownerDeviceId}`);
  return { ok: true, roomId };
}

async function mintInvite(store, { roomId, requestingDeviceId, pin }) {
  const room = await store.getRoom(roomId);
  if (!room) return { ok: false, reason: 'unknown-room' };
  const isMember = room.ownerDeviceId === requestingDeviceId || room.partnerDeviceId === requestingDeviceId;
  if (!isMember) return { ok: false, reason: 'not-a-room-member' };

  // A new invite invalidates any prior unredeemed one for this room.
  if (room.pendingInvite) await store.deleteInvite(room.pendingInvite.token);

  const token = newToken();
  await store.putInvite(token, roomId);
  room.pendingInvite = { token, createdAt: Date.now(), pin: pin || null };
  room.updatedAt = Date.now();
  await store.putRoom(roomId, room);
  console.log(`[PAIRING] mintInvite roomId=${roomId} requestedBy=${requestingDeviceId} pinSet=${!!pin}`);
  return { ok: true, token };
}

async function redeemInvite(store, { roomId, token, deviceId, pin }) {
  const room = await store.getRoom(roomId);
  if (!room) return { ok: false, reason: 'unknown-room' };

  // Check against the room's current pending invite BEFORE the invite-store lookup:
  // a consumed/superseded token is deleted from the store, so checking store presence
  // first would misreport a reused token as 'invalid-token' instead of 'already-used'.
  if (!room.pendingInvite || room.pendingInvite.token !== token) {
    return { ok: false, reason: 'already-used' };
  }

  const invite = await store.getInvite(token);
  if (!invite || invite.roomId !== roomId) return { ok: false, reason: 'invalid-token' };

  if (room.pendingInvite.pin && room.pendingInvite.pin !== pin) {
    console.warn(`[PAIRING] redeemInvite roomId=${roomId} — PIN mismatch`);
    return { ok: false, reason: 'pin-mismatch' };
  }

  const device = await store.getDevice(deviceId);
  if (!device) return { ok: false, reason: 'unknown-device' };
  if (device.roomId && device.roomId !== roomId) return { ok: false, reason: 'device-already-in-room' };

  // Normal join (empty partner slot) vs. device-loss recovery (replacing whichever
  // side isn't the requester who minted this invite — partner slot is the common case).
  const replacedRole = 'partner';
  room.partnerDeviceId = deviceId;
  device.roomId = roomId; device.role = 'partner';
  device.profile = { ...(room.partnerProfileDraft || {}), ...(device.profile || {}) };

  await store.deleteInvite(token);
  room.pendingInvite = null;
  room.updatedAt = Date.now();
  await store.putRoom(roomId, room);
  await store.putDevice(deviceId, device);

  const ownerDevice = await store.getDevice(room.ownerDeviceId);
  console.log(`[PAIRING] redeemInvite roomId=${roomId} newPartner=${deviceId}`);
  return {
    ok: true,
    herProfile: device.profile,
    hisProfile: ownerDevice ? ownerDevice.profile : {},
    replacedRole,
  };
}

async function getRoomView(store, { roomId, deviceId }) {
  const room = await store.getRoom(roomId);
  if (!room) return { ok: false, reason: 'unknown-room' };
  const isMember = room.ownerDeviceId === deviceId || room.partnerDeviceId === deviceId;
  if (!isMember) return { ok: false, reason: 'forbidden' };
  return { ok: true, room };
}

async function setTheme(store, { roomId, deviceId, mode, value }) {
  const view = await getRoomView(store, { roomId, deviceId });
  if (!view.ok) return view;
  view.room.theme = { mode, value: mode === 'manual' ? value : null, setByDeviceId: deviceId, setAt: Date.now() };
  view.room.updatedAt = Date.now();
  await store.putRoom(roomId, view.room);
  console.log(`[PAIRING] setTheme roomId=${roomId} mode=${mode} value=${value} by=${deviceId}`);
  return { ok: true };
}

async function addMessage(store, { roomId, deviceId, pool, text }) {
  const view = await getRoomView(store, { roomId, deviceId });
  if (!view.ok) return view;
  const key = pool === 'nudge' ? 'nudgeMessages' : 'welcomeMessages';
  const entry = { id: newId(), text, authorDeviceId: deviceId, createdAt: Date.now() };
  view.room[key].push(entry);
  view.room.updatedAt = Date.now();
  await store.putRoom(roomId, view.room);
  console.log(`[PAIRING] addMessage roomId=${roomId} pool=${pool} by=${deviceId} text="${text.slice(0, 60)}"`);
  return { ok: true, id: entry.id };
}

async function removeMessage(store, { roomId, deviceId, pool, id }) {
  const view = await getRoomView(store, { roomId, deviceId });
  if (!view.ok) return view;
  const key = pool === 'nudge' ? 'nudgeMessages' : 'welcomeMessages';
  view.room[key] = view.room[key].filter((m) => m.id !== id);
  view.room.updatedAt = Date.now();
  await store.putRoom(roomId, view.room);
  return { ok: true };
}

async function updateDeviceProfile(store, { deviceId, patch }) {
  const device = await store.getDevice(deviceId);
  if (!device) return { ok: false, reason: 'unknown-device' };
  device.profile = { ...device.profile, ...patch.profile };
  if (patch.fcmToken !== undefined) device.fcmToken = patch.fcmToken;
  if (patch.quietHours !== undefined) device.quietHours = patch.quietHours;
  await store.putDevice(deviceId, device);
  console.log(`[PAIRING] updateDeviceProfile ${deviceId} patch=${JSON.stringify(patch)}`);
  return { ok: true };
}

function isQuietNow(device, now = new Date()) {
  const qh = device.quietHours;
  if (!qh) return false;
  const hour = now.getHours();
  const { startHour, endHour } = qh;
  return startHour <= endHour
    ? hour >= startHour && hour < endHour
    : hour >= startHour || hour < endHour; // wraps past midnight
}

function pickRandomMessage(room, pool) {
  const list = pool === 'nudge' ? room.nudgeMessages : room.welcomeMessages;
  if (!list || list.length === 0) return null;
  return list[Math.floor(Math.random() * list.length)].text;
}

async function triggerNudge(store, pushSend, { roomId, triggeringDeviceId }) {
  const room = await store.getRoom(roomId);
  if (!room) return { ok: false, reason: 'unknown-room' };
  const recipientId = room.ownerDeviceId === triggeringDeviceId ? room.partnerDeviceId : room.ownerDeviceId;
  if (!recipientId) return { ok: false, reason: 'no-partner' };

  const recipient = await store.getDevice(recipientId);
  if (!recipient || !recipient.fcmToken) return { ok: false, reason: 'no-push-token' };
  if (isQuietNow(recipient)) {
    console.log(`[NUDGE] roomId=${roomId} suppressed — recipient in quiet hours`);
    return { ok: false, reason: 'quiet-hours' };
  }

  const text = pickRandomMessage(room, 'nudge');
  if (!text) return { ok: false, reason: 'no-messages' };

  const result = await pushSend(recipient.fcmToken, text);
  console.log(`[NUDGE] roomId=${roomId} to=${recipientId} text="${text}" result=${JSON.stringify(result)}`);
  return result.ok ? { ok: true } : { ok: false, reason: result.reason || 'push-failed' };
}

module.exports = {
  createDevice, createRoom, mintInvite, redeemInvite,
  getRoomView, setTheme, addMessage, removeMessage, updateDeviceProfile,
  isQuietNow, pickRandomMessage, triggerNudge,
};
