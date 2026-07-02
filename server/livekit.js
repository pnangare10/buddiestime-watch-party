// LiveKit token minter. Short-lived JWTs used by clients to connect to LiveKit Cloud.
// Credentials come from env: LIVEKIT_URL, LIVEKIT_API_KEY, LIVEKIT_API_SECRET.
// Never ships secrets to clients — only the signed token + the public wss URL.

const { AccessToken } = require('livekit-server-sdk');

const LK_URL    = process.env.LIVEKIT_URL;
const LK_KEY    = process.env.LIVEKIT_API_KEY;
const LK_SECRET = process.env.LIVEKIT_API_SECRET;

const LK_READY = !!(LK_URL && LK_KEY && LK_SECRET);
if (!LK_READY) {
  console.warn('[LIVEKIT] credentials missing — voice-token-request will return error');
  console.warn('[LIVEKIT]   LIVEKIT_URL set?', !!LK_URL);
  console.warn('[LIVEKIT]   LIVEKIT_API_KEY set?', !!LK_KEY);
  console.warn('[LIVEKIT]   LIVEKIT_API_SECRET set?', !!LK_SECRET);
} else {
  console.log('[LIVEKIT] ready — url=' + LK_URL);
}

// roomId → voiceRoomName: prefix so our room ids don't collide with any other LiveKit usage.
function voiceRoomName(roomId) { return 'hwp-' + roomId; }

async function mintToken(roomId, clientId, displayName) {
  console.log(`[LIVEKIT] mintToken roomId=${roomId} clientId=${clientId} name="${displayName}"`);
  if (!LK_READY) {
    console.warn('[LIVEKIT]   → refusing: credentials missing');
    return { ok: false, reason: 'voice-not-configured' };
  }
  if (!roomId || !clientId || !displayName) {
    console.warn('[LIVEKIT]   → refusing: missing required field');
    return { ok: false, reason: 'missing-field' };
  }

  const lkRoom = voiceRoomName(roomId);
  const at = new AccessToken(LK_KEY, LK_SECRET, {
    identity: clientId,
    name: displayName,
    ttl: '1h',
  });
  at.addGrant({
    roomJoin: true,
    room: lkRoom,
    canPublish: true,
    canSubscribe: true,
    canPublishData: false,
  });

  const token = await at.toJwt();
  console.log(`[LIVEKIT]   → minted token for lkRoom=${lkRoom} (len=${token.length})`);
  return { ok: true, token, lkUrl: LK_URL, lkRoom };
}

module.exports = { mintToken, voiceRoomName, LK_READY };
