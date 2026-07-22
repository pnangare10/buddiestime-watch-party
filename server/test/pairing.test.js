const { test } = require('node:test');
const assert = require('node:assert');
const { makeFakeStore } = require('./store.fake');
const pairing = require('../pairing');

test('createRoom then mintInvite then redeemInvite pairs two devices', async () => {
  const store = makeFakeStore();
  const { deviceId: hisId } = await pairing.createDevice(store);
  const { deviceId: herId } = await pairing.createDevice(store);

  const created = await pairing.createRoom(store, {
    roomName: 'SonuKomal',
    ownerDeviceId: hisId,
    ownerProfile: { displayName: 'Sonu' },
    partnerProfileDraft: { displayName: 'Komal', petName: 'jaanu' },
  });
  assert.strictEqual(created.ok, true);

  const invite = await pairing.mintInvite(store, { roomId: created.roomId, requestingDeviceId: hisId });
  assert.strictEqual(invite.ok, true);

  const redeemed = await pairing.redeemInvite(store, { roomId: created.roomId, token: invite.token, deviceId: herId });
  assert.strictEqual(redeemed.ok, true);
  assert.strictEqual(redeemed.herProfile.petName, 'jaanu');

  const room = await store.getRoom(created.roomId);
  assert.strictEqual(room.partnerDeviceId, herId);
});

test('redeeming a consumed token fails', async () => {
  const store = makeFakeStore();
  const { deviceId: hisId } = await pairing.createDevice(store);
  const { deviceId: herId } = await pairing.createDevice(store);
  const { deviceId: thirdId } = await pairing.createDevice(store);
  const created = await pairing.createRoom(store, { roomName: 'Room2', ownerDeviceId: hisId, ownerProfile: {}, partnerProfileDraft: {} });
  const invite = await pairing.mintInvite(store, { roomId: created.roomId, requestingDeviceId: hisId });
  await pairing.redeemInvite(store, { roomId: created.roomId, token: invite.token, deviceId: herId });

  const second = await pairing.redeemInvite(store, { roomId: created.roomId, token: invite.token, deviceId: thirdId });
  assert.strictEqual(second.ok, false);
  assert.strictEqual(second.reason, 'already-used');
});

test('room name collision is rejected, no auto-suggestion', async () => {
  const store = makeFakeStore();
  const { deviceId: d1 } = await pairing.createDevice(store);
  const { deviceId: d2 } = await pairing.createDevice(store);
  await pairing.createRoom(store, { roomName: 'Taken', ownerDeviceId: d1, ownerProfile: {}, partnerProfileDraft: {} });
  const second = await pairing.createRoom(store, { roomName: 'Taken', ownerDeviceId: d2, ownerProfile: {}, partnerProfileDraft: {} });
  assert.strictEqual(second.ok, false);
  assert.strictEqual(second.reason, 'room-name-taken');
});

test('pairing PIN must match on redeem when set', async () => {
  const store = makeFakeStore();
  const { deviceId: hisId } = await pairing.createDevice(store);
  const { deviceId: herId } = await pairing.createDevice(store);
  const created = await pairing.createRoom(store, { roomName: 'PinRoom', ownerDeviceId: hisId, ownerProfile: {}, partnerProfileDraft: {} });
  const invite = await pairing.mintInvite(store, { roomId: created.roomId, requestingDeviceId: hisId, pin: '4242' });

  const wrong = await pairing.redeemInvite(store, { roomId: created.roomId, token: invite.token, deviceId: herId, pin: '0000' });
  assert.strictEqual(wrong.ok, false);
  assert.strictEqual(wrong.reason, 'pin-mismatch');

  const right = await pairing.redeemInvite(store, { roomId: created.roomId, token: invite.token, deviceId: herId, pin: '4242' });
  assert.strictEqual(right.ok, true);
});

test('device-loss recovery: re-invite on a full room replaces the missing side, keeps the room', async () => {
  const store = makeFakeStore();
  const { deviceId: hisId } = await pairing.createDevice(store);
  const { deviceId: herId } = await pairing.createDevice(store);
  const created = await pairing.createRoom(store, { roomName: 'RecoverRoom', ownerDeviceId: hisId, ownerProfile: {}, partnerProfileDraft: {} });
  const invite1 = await pairing.mintInvite(store, { roomId: created.roomId, requestingDeviceId: hisId });
  await pairing.redeemInvite(store, { roomId: created.roomId, token: invite1.token, deviceId: herId });

  // she reinstalls, loses herId; his still-linked device requests a fresh invite for the SAME room
  const invite2 = await pairing.mintInvite(store, { roomId: created.roomId, requestingDeviceId: hisId });
  assert.notStrictEqual(invite2.token, invite1.token);
  // the old token must no longer work (a new invite invalidates the prior unredeemed/consumed one)
  const staleAttempt = await pairing.redeemInvite(store, { roomId: created.roomId, token: invite1.token, deviceId: 'ghost' });
  assert.strictEqual(staleAttempt.ok, false);

  const { deviceId: herNewId } = await pairing.createDevice(store);
  const redeemed = await pairing.redeemInvite(store, { roomId: created.roomId, token: invite2.token, deviceId: herNewId });
  assert.strictEqual(redeemed.ok, true);

  const room = await store.getRoom(created.roomId);
  assert.strictEqual(room.partnerDeviceId, herNewId, 'new device replaces the lost one');
  assert.strictEqual(room.ownerDeviceId, hisId, 'owner side untouched');
});
