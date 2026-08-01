const { test } = require('node:test');
const assert = require('node:assert');
const http = require('node:http');
const { startServer } = require('./harness');

function req(method, url, body) {
  return new Promise((resolve, reject) => {
    const data = body ? JSON.stringify(body) : null;
    const u = new URL(url);
    const r = http.request(u, { method, headers: data ? { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(data) } : {} }, (res) => {
      let out = '';
      res.on('data', (d) => (out += d));
      res.on('end', () => resolve({ status: res.statusCode, json: out ? JSON.parse(out) : null }));
    });
    r.on('error', reject);
    if (data) r.write(data);
    r.end();
  });
}

test('full pairing round trip over HTTP', async () => {
  const srv = await startServer({ port: 8095, env: { UPSTASH_REDIS_REST_URL: 'fake', UPSTASH_REDIS_REST_TOKEN: 'fake', HWP_STORE: 'fake' } });
  try {
    const hisDevice = await req('POST', srv.baseUrl + '/api/devices');
    assert.strictEqual(hisDevice.status, 200);
    const herDevice = await req('POST', srv.baseUrl + '/api/devices');

    const room = await req('POST', srv.baseUrl + '/api/rooms', {
      deviceId: hisDevice.json.deviceId, roomName: 'HttpTestRoom',
      ownerProfile: { displayName: 'Sonu' }, partnerProfileDraft: { displayName: 'Komal' },
    });
    assert.strictEqual(room.status, 200);

    const invite = await req('POST', `${srv.baseUrl}/api/rooms/${room.json.roomId}/invite`, { deviceId: hisDevice.json.deviceId });
    assert.strictEqual(invite.status, 200);

    const join = await req('POST', `${srv.baseUrl}/api/rooms/${room.json.roomId}/join`, {
      deviceId: herDevice.json.deviceId, token: invite.json.token,
    });
    assert.strictEqual(join.status, 200);
    assert.strictEqual(join.json.herProfile.displayName, 'Komal');

    const second = await req('POST', `${srv.baseUrl}/api/rooms/${room.json.roomId}/join`, {
      deviceId: 'someone-else', token: invite.json.token,
    });
    assert.strictEqual(second.status, 409, 'reused token must be rejected');
  } finally {
    await srv.stop();
  }
});
