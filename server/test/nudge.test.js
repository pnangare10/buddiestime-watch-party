const { test } = require("node:test");
const assert = require("node:assert");
const { makeFakeStore } = require("./store.fake");
const pairing = require("../pairing");

test("triggerNudge sends to the partner using a message from the nudge pool", async () => {
  const store = makeFakeStore();
  const { deviceId: hisId } = await pairing.createDevice(store);
  const { deviceId: herId } = await pairing.createDevice(store);
  const created = await pairing.createRoom(store, {
    roomName: "NudgeRoom",
    ownerDeviceId: hisId,
    ownerProfile: {},
    partnerProfileDraft: {},
  });
  await pairing
    .mintInvite(store, { roomId: created.roomId, requestingDeviceId: hisId })
    .then((inv) =>
      pairing.redeemInvite(store, {
        roomId: created.roomId,
        token: inv.token,
        deviceId: herId,
      }),
    );
  await pairing.addMessage(store, {
    roomId: created.roomId,
    deviceId: hisId,
    pool: "nudge",
    text: "come watch with me 😏",
  });
  const herDevice = await store.getDevice(herId);
  herDevice.fcmToken = "her-fcm-token";
  await store.putDevice(herId, herDevice);

  const sent = [];
  const fakePush = async (token, body) => {
    sent.push({ token, body });
    return { ok: true };
  };

  const result = await pairing.triggerNudge(store, fakePush, {
    roomId: created.roomId,
    triggeringDeviceId: hisId,
  });
  assert.strictEqual(result.ok, true);
  assert.strictEqual(sent.length, 1);
  assert.strictEqual(sent[0].token, "her-fcm-token");
  assert.strictEqual(sent[0].body, "come watch with me 😏");
});

// Every other pairing mutator proves membership through getRoomView. triggerNudge
// did not, and the `owner ? partner : owner` recipient pick meant a non-member fell
// into the else branch and pushed to the OWNER — a stranger with the roomId could
// spam the owner's phone at will.
test("triggerNudge refuses a device that is not in the room", async () => {
  const store = makeFakeStore();
  const { deviceId: hisId } = await pairing.createDevice(store);
  const { deviceId: herId } = await pairing.createDevice(store);
  const { deviceId: strangerId } = await pairing.createDevice(store);
  const created = await pairing.createRoom(store, {
    roomName: "GuardRoom",
    ownerDeviceId: hisId,
    ownerProfile: {},
    partnerProfileDraft: {},
  });
  await pairing
    .mintInvite(store, { roomId: created.roomId, requestingDeviceId: hisId })
    .then((inv) =>
      pairing.redeemInvite(store, {
        roomId: created.roomId,
        token: inv.token,
        deviceId: herId,
      }),
    );
  await pairing.addMessage(store, {
    roomId: created.roomId,
    deviceId: hisId,
    pool: "nudge",
    text: "hey",
  });
  // Give BOTH members a push token, so a leak would actually fire rather than being
  // masked by the no-push-token path (which is what hid this bug originally).
  for (const id of [hisId, herId]) {
    const d = await store.getDevice(id);
    d.fcmToken = `${id}-fcm`;
    await store.putDevice(id, d);
  }

  const sent = [];
  const fakePush = async (token, body) => {
    sent.push({ token, body });
    return { ok: true };
  };
  const result = await pairing.triggerNudge(store, fakePush, {
    roomId: created.roomId,
    triggeringDeviceId: strangerId,
  });

  assert.strictEqual(result.ok, false);
  assert.strictEqual(result.reason, "not-a-room-member");
  assert.strictEqual(
    sent.length,
    0,
    "a stranger must not be able to buzz anyone in the room",
  );
});

test("triggerNudge still routes owner→partner and partner→owner", async () => {
  const store = makeFakeStore();
  const { deviceId: hisId } = await pairing.createDevice(store);
  const { deviceId: herId } = await pairing.createDevice(store);
  const created = await pairing.createRoom(store, {
    roomName: "BothWaysRoom",
    ownerDeviceId: hisId,
    ownerProfile: {},
    partnerProfileDraft: {},
  });
  await pairing
    .mintInvite(store, { roomId: created.roomId, requestingDeviceId: hisId })
    .then((inv) =>
      pairing.redeemInvite(store, {
        roomId: created.roomId,
        token: inv.token,
        deviceId: herId,
      }),
    );
  await pairing.addMessage(store, {
    roomId: created.roomId,
    deviceId: hisId,
    pool: "nudge",
    text: "hey",
  });
  for (const id of [hisId, herId]) {
    const d = await store.getDevice(id);
    d.fcmToken = `${id}-fcm`;
    await store.putDevice(id, d);
  }

  const sent = [];
  const fakePush = async (token, body) => {
    sent.push({ token, body });
    return { ok: true };
  };

  const fromOwner = await pairing.triggerNudge(store, fakePush, {
    roomId: created.roomId,
    triggeringDeviceId: hisId,
  });
  assert.strictEqual(fromOwner.ok, true);
  assert.strictEqual(
    sent.at(-1).token,
    `${herId}-fcm`,
    "owner buzzes the partner",
  );

  const fromPartner = await pairing.triggerNudge(store, fakePush, {
    roomId: created.roomId,
    triggeringDeviceId: herId,
  });
  assert.strictEqual(fromPartner.ok, true);
  assert.strictEqual(
    sent.at(-1).token,
    `${hisId}-fcm`,
    "partner buzzes the owner",
  );
});

test("triggerNudge sends the caller's customText instead of a pool message", async () => {
  const store = makeFakeStore();
  const { deviceId: hisId } = await pairing.createDevice(store);
  const { deviceId: herId } = await pairing.createDevice(store);
  const created = await pairing.createRoom(store, {
    roomName: "CustomTextRoom",
    ownerDeviceId: hisId,
    ownerProfile: {},
    partnerProfileDraft: {},
  });
  await pairing
    .mintInvite(store, { roomId: created.roomId, requestingDeviceId: hisId })
    .then((inv) =>
      pairing.redeemInvite(store, {
        roomId: created.roomId,
        token: inv.token,
        deviceId: herId,
      }),
    );
  // Deliberately no addMessage call — the pool stays empty, proving customText
  // does not depend on it.
  const herDevice = await store.getDevice(herId);
  herDevice.fcmToken = "her-fcm-token";
  await store.putDevice(herId, herDevice);

  const sent = [];
  const fakePush = async (token, body) => {
    sent.push({ token, body });
    return { ok: true };
  };

  const result = await pairing.triggerNudge(store, fakePush, {
    roomId: created.roomId,
    triggeringDeviceId: hisId,
    customText: "dinner's ready, come watch! 🍝",
  });
  assert.strictEqual(result.ok, true);
  assert.strictEqual(sent.length, 1);
  assert.strictEqual(sent[0].body, "dinner's ready, come watch! 🍝");
});

test("triggerNudge suppresses entirely during quiet hours", async () => {
  const store = makeFakeStore();
  const { deviceId: hisId } = await pairing.createDevice(store);
  const { deviceId: herId } = await pairing.createDevice(store);
  const created = await pairing.createRoom(store, {
    roomName: "QuietRoom",
    ownerDeviceId: hisId,
    ownerProfile: {},
    partnerProfileDraft: {},
  });
  await pairing
    .mintInvite(store, { roomId: created.roomId, requestingDeviceId: hisId })
    .then((inv) =>
      pairing.redeemInvite(store, {
        roomId: created.roomId,
        token: inv.token,
        deviceId: herId,
      }),
    );
  await pairing.addMessage(store, {
    roomId: created.roomId,
    deviceId: hisId,
    pool: "nudge",
    text: "hey",
  });
  const herDevice = await store.getDevice(herId);
  herDevice.fcmToken = "her-fcm-token";
  herDevice.quietHours = { startHour: 0, endHour: 23 }; // effectively always quiet, for a deterministic test
  await store.putDevice(herId, herDevice);

  const sent = [];
  const fakePush = async (token, body) => {
    sent.push({ token, body });
    return { ok: true };
  };
  const result = await pairing.triggerNudge(store, fakePush, {
    roomId: created.roomId,
    triggeringDeviceId: hisId,
  });
  assert.strictEqual(result.ok, false);
  assert.strictEqual(result.reason, "quiet-hours");
  assert.strictEqual(sent.length, 0, "must not send at all, not defer");
});
