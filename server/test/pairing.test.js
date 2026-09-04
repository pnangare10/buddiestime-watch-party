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

test('device-loss recovery: an invite minted by the partner restores a reinstalled OWNER', async () => {
  const store = makeFakeStore();
  const { deviceId: hisId } = await pairing.createDevice(store);
  const { deviceId: herId } = await pairing.createDevice(store);
  const created = await pairing.createRoom(store, { roomName: 'OwnerRecoverRoom', ownerDeviceId: hisId, ownerProfile: {}, partnerProfileDraft: {} });
  const invite1 = await pairing.mintInvite(store, { roomId: created.roomId, requestingDeviceId: hisId });
  await pairing.redeemInvite(store, { roomId: created.roomId, token: invite1.token, deviceId: herId });

  // HE reinstalls (keystore migration) and loses hisId. Her still-paired device mints the invite,
  // so the side to replace is the owner — the one that isn't the requester.
  const invite2 = await pairing.mintInvite(store, { roomId: created.roomId, requestingDeviceId: herId });
  const { deviceId: hisNewId } = await pairing.createDevice(store);
  const redeemed = await pairing.redeemInvite(store, { roomId: created.roomId, token: invite2.token, deviceId: hisNewId });

  assert.strictEqual(redeemed.ok, true);
  assert.strictEqual(redeemed.replacedRole, 'owner');

  const room = await store.getRoom(created.roomId);
  assert.strictEqual(room.ownerDeviceId, hisNewId, 'new device takes the owner slot');
  assert.strictEqual(room.partnerDeviceId, herId, 'partner side untouched');

  const device = await store.getDevice(hisNewId);
  assert.strictEqual(device.roomId, created.roomId);
  assert.strictEqual(device.role, 'owner');
});

test('device-loss recovery: an invite minted by the owner still replaces the partner', async () => {
  const store = makeFakeStore();
  const { deviceId: hisId } = await pairing.createDevice(store);
  const { deviceId: herId } = await pairing.createDevice(store);
  const created = await pairing.createRoom(store, { roomName: 'PartnerRecoverRoom', ownerDeviceId: hisId, ownerProfile: {}, partnerProfileDraft: {} });
  const invite1 = await pairing.mintInvite(store, { roomId: created.roomId, requestingDeviceId: hisId });
  await pairing.redeemInvite(store, { roomId: created.roomId, token: invite1.token, deviceId: herId });

  const invite2 = await pairing.mintInvite(store, { roomId: created.roomId, requestingDeviceId: hisId });
  const { deviceId: herNewId } = await pairing.createDevice(store);
  const redeemed = await pairing.redeemInvite(store, { roomId: created.roomId, token: invite2.token, deviceId: herNewId });

  assert.strictEqual(redeemed.ok, true);
  assert.strictEqual(redeemed.replacedRole, 'partner');

  const room = await store.getRoom(created.roomId);
  assert.strictEqual(room.partnerDeviceId, herNewId);
  assert.strictEqual(room.ownerDeviceId, hisId, 'owner side untouched');
});

test('a first join into an empty partner slot is unaffected by recovery logic', async () => {
  const store = makeFakeStore();
  const { deviceId: hisId } = await pairing.createDevice(store);
  const { deviceId: herId } = await pairing.createDevice(store);
  const created = await pairing.createRoom(store, { roomName: 'FirstJoinRoom', ownerDeviceId: hisId, ownerProfile: {}, partnerProfileDraft: {} });
  const invite = await pairing.mintInvite(store, { roomId: created.roomId, requestingDeviceId: hisId });
  const redeemed = await pairing.redeemInvite(store, { roomId: created.roomId, token: invite.token, deviceId: herId });

  assert.strictEqual(redeemed.ok, true);
  assert.strictEqual(redeemed.replacedRole, 'partner');
  const room = await store.getRoom(created.roomId);
  assert.strictEqual(room.ownerDeviceId, hisId);
  assert.strictEqual(room.partnerDeviceId, herId);
});
test("recovery: a legacy invite with no mintedBy falls back to the partner slot", async () => {
  const store = makeFakeStore();
  const { deviceId: hisId } = await pairing.createDevice(store);
  const { deviceId: herId } = await pairing.createDevice(store);
  const created = await pairing.createRoom(store, {
    roomName: "LegacyInviteRoom",
    ownerDeviceId: hisId,
    ownerProfile: {},
    partnerProfileDraft: {},
  });
  const invite1 = await pairing.mintInvite(store, {
    roomId: created.roomId,
    requestingDeviceId: hisId,
  });
  await pairing.redeemInvite(store, {
    roomId: created.roomId,
    token: invite1.token,
    deviceId: herId,
  });

  // Simulate an invite minted by the previous server build, which recorded no mintedBy.
  const invite2 = await pairing.mintInvite(store, {
    roomId: created.roomId,
    requestingDeviceId: herId,
  });
  const room = await store.getRoom(created.roomId);
  delete room.pendingInvite.mintedBy;
  await store.putRoom(created.roomId, room);

  const { deviceId: newId } = await pairing.createDevice(store);
  const redeemed = await pairing.redeemInvite(store, {
    roomId: created.roomId,
    token: invite2.token,
    deviceId: newId,
  });

  assert.strictEqual(redeemed.ok, true);
  assert.strictEqual(
    redeemed.replacedRole,
    "partner",
    "legacy invites keep their original behavior",
  );
  const after = await store.getRoom(created.roomId);
  assert.strictEqual(
    after.ownerDeviceId,
    hisId,
    "owner must not be silently replaced",
  );
});

test("recovery: owner replacement returns the surviving partner as the counterpart profile", async () => {
  const store = makeFakeStore();
  const { deviceId: hisId } = await pairing.createDevice(store);
  const { deviceId: herId } = await pairing.createDevice(store);
  const created = await pairing.createRoom(store, {
    roomName: "CounterpartRoom",
    ownerDeviceId: hisId,
    ownerProfile: { displayName: "Pranesh" },
    partnerProfileDraft: { displayName: "Komu" },
  });
  const invite1 = await pairing.mintInvite(store, {
    roomId: created.roomId,
    requestingDeviceId: hisId,
  });
  await pairing.redeemInvite(store, {
    roomId: created.roomId,
    token: invite1.token,
    deviceId: herId,
  });

  const invite2 = await pairing.mintInvite(store, {
    roomId: created.roomId,
    requestingDeviceId: herId,
  });
  const { deviceId: hisNewId } = await pairing.createDevice(store);
  const redeemed = await pairing.redeemInvite(store, {
    roomId: created.roomId,
    token: invite2.token,
    deviceId: hisNewId,
  });

  // herProfile is the redeemer's own; hisProfile is the OTHER side. Before the fix the
  // counterpart was always read from ownerDeviceId, which is the redeemer itself here.
  assert.strictEqual(
    redeemed.hisProfile.displayName,
    "Komu",
    "counterpart must be the surviving partner",
  );
  assert.notStrictEqual(
    redeemed.hisProfile.displayName,
    "Pranesh",
    "counterpart must not be the redeemer",
  );
});

test("recovery: the owner re-minting for themselves still replaces the partner", async () => {
  const store = makeFakeStore();
  const { deviceId: hisId } = await pairing.createDevice(store);
  const { deviceId: herId } = await pairing.createDevice(store);
  const created = await pairing.createRoom(store, {
    roomName: "SelfMintRoom",
    ownerDeviceId: hisId,
    ownerProfile: {},
    partnerProfileDraft: {},
  });
  const invite1 = await pairing.mintInvite(store, {
    roomId: created.roomId,
    requestingDeviceId: hisId,
  });
  await pairing.redeemInvite(store, {
    roomId: created.roomId,
    token: invite1.token,
    deviceId: herId,
  });

  const invite2 = await pairing.mintInvite(store, {
    roomId: created.roomId,
    requestingDeviceId: hisId,
  });
  const { deviceId: newId } = await pairing.createDevice(store);
  const redeemed = await pairing.redeemInvite(store, {
    roomId: created.roomId,
    token: invite2.token,
    deviceId: newId,
  });

  assert.strictEqual(redeemed.replacedRole, "partner");
  const after = await store.getRoom(created.roomId);
  assert.strictEqual(after.ownerDeviceId, hisId);
  assert.strictEqual(after.partnerDeviceId, newId);
});

test("recovery: a replaced owner keeps room contents (theme, messages, name)", async () => {
  const store = makeFakeStore();
  const { deviceId: hisId } = await pairing.createDevice(store);
  const { deviceId: herId } = await pairing.createDevice(store);
  const created = await pairing.createRoom(store, {
    roomName: "KeepContentsRoom",
    ownerDeviceId: hisId,
    ownerProfile: {},
    partnerProfileDraft: {},
  });
  const invite1 = await pairing.mintInvite(store, {
    roomId: created.roomId,
    requestingDeviceId: hisId,
  });
  await pairing.redeemInvite(store, {
    roomId: created.roomId,
    token: invite1.token,
    deviceId: herId,
  });
  await pairing.addMessage(store, {
    roomId: created.roomId,
    deviceId: hisId,
    pool: "nudge",
    text: "come here",
  });
  await pairing.setTheme(store, {
    roomId: created.roomId,
    deviceId: hisId,
    mode: "manual",
    value: "blush",
  });

  const invite2 = await pairing.mintInvite(store, {
    roomId: created.roomId,
    requestingDeviceId: herId,
  });
  const { deviceId: hisNewId } = await pairing.createDevice(store);
  await pairing.redeemInvite(store, {
    roomId: created.roomId,
    token: invite2.token,
    deviceId: hisNewId,
  });

  const after = await store.getRoom(created.roomId);
  assert.strictEqual(after.roomId, created.roomId, "same room, not a new one");
  assert.strictEqual(after.roomName, "KeepContentsRoom");
  assert.strictEqual(
    after.nudgeMessages.length,
    1,
    "nudge pool survives owner recovery",
  );
  assert.strictEqual(
    after.theme.value,
    "blush",
    "theme survives owner recovery",
  );
});

test("recovery: the recovered owner can immediately nudge the partner", async () => {
  const store = makeFakeStore();
  const { deviceId: hisId } = await pairing.createDevice(store);
  const { deviceId: herId } = await pairing.createDevice(store);
  const created = await pairing.createRoom(store, {
    roomName: "PostRecoveryNudgeRoom",
    ownerDeviceId: hisId,
    ownerProfile: {},
    partnerProfileDraft: {},
  });
  const invite1 = await pairing.mintInvite(store, {
    roomId: created.roomId,
    requestingDeviceId: hisId,
  });
  await pairing.redeemInvite(store, {
    roomId: created.roomId,
    token: invite1.token,
    deviceId: herId,
  });

  const invite2 = await pairing.mintInvite(store, {
    roomId: created.roomId,
    requestingDeviceId: herId,
  });
  const { deviceId: hisNewId } = await pairing.createDevice(store);
  await pairing.redeemInvite(store, {
    roomId: created.roomId,
    token: invite2.token,
    deviceId: hisNewId,
  });

  const herDevice = await store.getDevice(herId);
  herDevice.fcmToken = "her-token";
  await store.putDevice(herId, herDevice);

  const sent = [];
  const result = await pairing.triggerNudge(
    store,
    async (t, b) => {
      sent.push({ t, b });
      return { ok: true };
    },
    {
      roomId: created.roomId,
      triggeringDeviceId: hisNewId,
    },
  );

  assert.strictEqual(
    result.ok,
    true,
    "recovered owner must pass the membership guard",
  );
  assert.strictEqual(
    sent[0].t,
    "her-token",
    "nudge routes to the partner, not back to the redeemer",
  );
});

test("recovery: the room view is readable by the recovered owner and not by the dead device", async () => {
  const store = makeFakeStore();
  const { deviceId: hisId } = await pairing.createDevice(store);
  const { deviceId: herId } = await pairing.createDevice(store);
  const created = await pairing.createRoom(store, {
    roomName: "ViewAfterRecoveryRoom",
    ownerDeviceId: hisId,
    ownerProfile: {},
    partnerProfileDraft: {},
  });
  const invite1 = await pairing.mintInvite(store, {
    roomId: created.roomId,
    requestingDeviceId: hisId,
  });
  await pairing.redeemInvite(store, {
    roomId: created.roomId,
    token: invite1.token,
    deviceId: herId,
  });

  const invite2 = await pairing.mintInvite(store, {
    roomId: created.roomId,
    requestingDeviceId: herId,
  });
  const { deviceId: hisNewId } = await pairing.createDevice(store);
  await pairing.redeemInvite(store, {
    roomId: created.roomId,
    token: invite2.token,
    deviceId: hisNewId,
  });

  const asNew = await pairing.getRoomView(store, {
    roomId: created.roomId,
    deviceId: hisNewId,
  });
  assert.strictEqual(asNew.ok, true, "recovered owner can read the room");
  const asOld = await pairing.getRoomView(store, {
    roomId: created.roomId,
    deviceId: hisId,
  });
  assert.strictEqual(asOld.ok, false, "the replaced device loses access");
  assert.strictEqual(asOld.reason, "forbidden");
});
test("recovery: a replaced owner inherits the lost device profile", async () => {
  const store = makeFakeStore();
  const { deviceId: hisId } = await pairing.createDevice(store);
  const { deviceId: herId } = await pairing.createDevice(store);
  const created = await pairing.createRoom(store, {
    roomName: "ProfileInheritRoom",
    ownerDeviceId: hisId,
    ownerProfile: {
      displayName: "Sonu",
      petName: "Pranesh",
      birthday: "10052000",
    },
    partnerProfileDraft: { displayName: "Komu" },
  });
  const inv1 = await pairing.mintInvite(store, {
    roomId: created.roomId,
    requestingDeviceId: hisId,
  });
  await pairing.redeemInvite(store, {
    roomId: created.roomId,
    token: inv1.token,
    deviceId: herId,
  });

  // He reinstalls: brand-new device with no profile at all.
  const inv2 = await pairing.mintInvite(store, {
    roomId: created.roomId,
    requestingDeviceId: herId,
  });
  const { deviceId: hisNewId } = await pairing.createDevice(store);
  const redeemed = await pairing.redeemInvite(store, {
    roomId: created.roomId,
    token: inv2.token,
    deviceId: hisNewId,
  });

  assert.strictEqual(redeemed.replacedRole, "owner");
  assert.strictEqual(
    redeemed.herProfile.displayName,
    "Sonu",
    "display name survives the reinstall",
  );
  assert.strictEqual(redeemed.herProfile.petName, "Pranesh");
  assert.strictEqual(redeemed.herProfile.birthday, "10052000");

  const stored = await store.getDevice(hisNewId);
  assert.strictEqual(
    stored.profile.displayName,
    "Sonu",
    "and is persisted, not just returned",
  );
});

test("recovery: a replaced partner inherits the lost partner profile", async () => {
  const store = makeFakeStore();
  const { deviceId: hisId } = await pairing.createDevice(store);
  const { deviceId: herId } = await pairing.createDevice(store);
  const created = await pairing.createRoom(store, {
    roomName: "PartnerInheritRoom",
    ownerDeviceId: hisId,
    ownerProfile: {},
    partnerProfileDraft: { displayName: "Komu" },
  });
  const inv1 = await pairing.mintInvite(store, {
    roomId: created.roomId,
    requestingDeviceId: hisId,
  });
  await pairing.redeemInvite(store, {
    roomId: created.roomId,
    token: inv1.token,
    deviceId: herId,
  });
  await pairing.updateDeviceProfile(store, {
    deviceId: herId,
    patch: { profile: { petName: "Kimal" } },
  });

  const inv2 = await pairing.mintInvite(store, {
    roomId: created.roomId,
    requestingDeviceId: hisId,
  });
  const { deviceId: herNewId } = await pairing.createDevice(store);
  const redeemed = await pairing.redeemInvite(store, {
    roomId: created.roomId,
    token: inv2.token,
    deviceId: herNewId,
  });

  assert.strictEqual(redeemed.replacedRole, "partner");
  assert.strictEqual(
    redeemed.herProfile.petName,
    "Kimal",
    "pet name survives the reinstall",
  );
  assert.strictEqual(redeemed.herProfile.displayName, "Komu");
});

test("a first join is unaffected: no prior device to inherit from", async () => {
  const store = makeFakeStore();
  const { deviceId: hisId } = await pairing.createDevice(store);
  const { deviceId: herId } = await pairing.createDevice(store);
  const created = await pairing.createRoom(store, {
    roomName: "FirstJoinInheritRoom",
    ownerDeviceId: hisId,
    ownerProfile: { displayName: "Sonu" },
    partnerProfileDraft: { displayName: "Komu" },
  });
  const inv = await pairing.mintInvite(store, {
    roomId: created.roomId,
    requestingDeviceId: hisId,
  });
  const redeemed = await pairing.redeemInvite(store, {
    roomId: created.roomId,
    token: inv.token,
    deviceId: herId,
  });

  assert.strictEqual(
    redeemed.herProfile.displayName,
    "Komu",
    "joiner gets the draft, not the owner profile",
  );
});

test("an invite older than the TTL is refused", async () => {
  const store = makeFakeStore();
  const { deviceId: hisId } = await pairing.createDevice(store);
  const { deviceId: herId } = await pairing.createDevice(store);
  const created = await pairing.createRoom(store, {
    roomName: "ExpiryRoom",
    ownerDeviceId: hisId,
    ownerProfile: {},
    partnerProfileDraft: {},
  });
  const inv = await pairing.mintInvite(store, {
    roomId: created.roomId,
    requestingDeviceId: hisId,
  });

  // Backdate the mint past the 15-minute TTL.
  const room = await store.getRoom(created.roomId);
  room.pendingInvite.createdAt = Date.now() - 16 * 60 * 1000;
  await store.putRoom(created.roomId, room);

  const redeemed = await pairing.redeemInvite(store, {
    roomId: created.roomId,
    token: inv.token,
    deviceId: herId,
  });
  assert.strictEqual(redeemed.ok, false);
  assert.strictEqual(redeemed.reason, "already-used");

  const after = await store.getRoom(created.roomId);
  assert.strictEqual(after.pendingInvite, null, "expired invite is cleared");
  assert.strictEqual(after.partnerDeviceId, null, "and no slot was filled");
});

test("an invite inside the TTL still redeems", async () => {
  const store = makeFakeStore();
  const { deviceId: hisId } = await pairing.createDevice(store);
  const { deviceId: herId } = await pairing.createDevice(store);
  const created = await pairing.createRoom(store, {
    roomName: "FreshInviteRoom",
    ownerDeviceId: hisId,
    ownerProfile: {},
    partnerProfileDraft: {},
  });
  const inv = await pairing.mintInvite(store, {
    roomId: created.roomId,
    requestingDeviceId: hisId,
  });
  const room = await store.getRoom(created.roomId);
  room.pendingInvite.createdAt = Date.now() - 14 * 60 * 1000;
  await store.putRoom(created.roomId, room);

  const redeemed = await pairing.redeemInvite(store, {
    roomId: created.roomId,
    token: inv.token,
    deviceId: herId,
  });
  assert.strictEqual(redeemed.ok, true);
});

test("recovery releases the replaced device instead of orphaning it", async () => {
  const store = makeFakeStore();
  const { deviceId: hisId } = await pairing.createDevice(store);
  const { deviceId: herId } = await pairing.createDevice(store);
  const created = await pairing.createRoom(store, {
    roomName: "OrphanRoom",
    ownerDeviceId: hisId,
    ownerProfile: { displayName: "Sonu" },
    partnerProfileDraft: {},
  });
  const inv1 = await pairing.mintInvite(store, {
    roomId: created.roomId,
    requestingDeviceId: hisId,
  });
  await pairing.redeemInvite(store, {
    roomId: created.roomId,
    token: inv1.token,
    deviceId: herId,
  });

  const inv2 = await pairing.mintInvite(store, {
    roomId: created.roomId,
    requestingDeviceId: herId,
  });
  const { deviceId: hisNewId } = await pairing.createDevice(store);
  const redeemed = await pairing.redeemInvite(store, {
    roomId: created.roomId,
    token: inv2.token,
    deviceId: hisNewId,
  });
  assert.strictEqual(redeemed.replacedRole, "owner");

  const oldOwner = await store.getDevice(hisId);
  assert.strictEqual(oldOwner.roomId, null, "old owner is released from the room");
  assert.strictEqual(oldOwner.role, null);
  // and is therefore free to pair somewhere else rather than being wedged
  assert.strictEqual(
    (await store.getRoom(created.roomId)).ownerDeviceId,
    hisNewId,
  );
});
