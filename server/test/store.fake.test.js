const { test } = require('node:test');
const assert = require('node:assert');
const { makeFakeStore } = require('./store.fake');

test('room name reservation is exclusive', async () => {
  const store = makeFakeStore();
  assert.strictEqual(await store.reserveRoomName('SonuKomal', 'room-1'), true);
  assert.strictEqual(await store.reserveRoomName('SonuKomal', 'room-2'), false, 'second reservation must fail');
  assert.strictEqual(await store.findRoomIdByName('SonuKomal'), 'room-1');
});

test('device and room round-trip', async () => {
  const store = makeFakeStore();
  await store.putDevice('d1', { deviceId: 'd1', roomId: null });
  assert.deepStrictEqual(await store.getDevice('d1'), { deviceId: 'd1', roomId: null });
  assert.strictEqual(await store.getDevice('missing'), null);
});

test('invite token round-trip and delete', async () => {
  const store = makeFakeStore();
  await store.putInvite('tok1', 'room-1');
  assert.deepStrictEqual(await store.getInvite('tok1'), { roomId: 'room-1' });
  await store.deleteInvite('tok1');
  assert.strictEqual(await store.getInvite('tok1'), null);
});
