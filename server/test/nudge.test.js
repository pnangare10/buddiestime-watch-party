const { test } = require('node:test');
const assert = require('node:assert');
const { makeFakeStore } = require('./store.fake');
const pairing = require('../pairing');

test('triggerNudge sends to the partner using a message from the nudge pool', async () => {
  const store = makeFakeStore();
  const { deviceId: hisId } = await pairing.createDevice(store);
  const { deviceId: herId } = await pairing.createDevice(store);
  const created = await pairing.createRoom(store, { roomName: 'NudgeRoom', ownerDeviceId: hisId, ownerProfile: {}, partnerProfileDraft: {} });
  await pairing.mintInvite(store, { roomId: created.roomId, requestingDeviceId: hisId }).then((inv) =>
    pairing.redeemInvite(store, { roomId: created.roomId, token: inv.token, deviceId: herId }));
  await pairing.addMessage(store, { roomId: created.roomId, deviceId: hisId, pool: 'nudge', text: 'come watch with me 😏' });
  const herDevice = await store.getDevice(herId);
  herDevice.fcmToken = 'her-fcm-token';
  await store.putDevice(herId, herDevice);

  const sent = [];
  const fakePush = async (token, body) => { sent.push({ token, body }); return { ok: true }; };

  const result = await pairing.triggerNudge(store, fakePush, { roomId: created.roomId, triggeringDeviceId: hisId });
  assert.strictEqual(result.ok, true);
  assert.strictEqual(sent.length, 1);
  assert.strictEqual(sent[0].token, 'her-fcm-token');
  assert.strictEqual(sent[0].body, 'come watch with me 😏');
});

test('triggerNudge suppresses entirely during quiet hours', async () => {
  const store = makeFakeStore();
  const { deviceId: hisId } = await pairing.createDevice(store);
  const { deviceId: herId } = await pairing.createDevice(store);
  const created = await pairing.createRoom(store, { roomName: 'QuietRoom', ownerDeviceId: hisId, ownerProfile: {}, partnerProfileDraft: {} });
  await pairing.mintInvite(store, { roomId: created.roomId, requestingDeviceId: hisId }).then((inv) =>
    pairing.redeemInvite(store, { roomId: created.roomId, token: inv.token, deviceId: herId }));
  await pairing.addMessage(store, { roomId: created.roomId, deviceId: hisId, pool: 'nudge', text: 'hey' });
  const herDevice = await store.getDevice(herId);
  herDevice.fcmToken = 'her-fcm-token';
  herDevice.quietHours = { startHour: 0, endHour: 23 }; // effectively always quiet, for a deterministic test
  await store.putDevice(herId, herDevice);

  const sent = [];
  const fakePush = async (token, body) => { sent.push({ token, body }); return { ok: true }; };
  const result = await pairing.triggerNudge(store, fakePush, { roomId: created.roomId, triggeringDeviceId: hisId });
  assert.strictEqual(result.ok, false);
  assert.strictEqual(result.reason, 'quiet-hours');
  assert.strictEqual(sent.length, 0, 'must not send at all, not defer');
});
