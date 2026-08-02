// WebSocket join authorisation: does this deviceId belong to this room?
//
// The HTTP layer has always checked membership (pairing.getRoomView), but the
// WebSocket layer never did — `roomId` alone was the entire credential, and roomId
// travels in every invite link. This module is the missing half.
//
// Kept out of server.js and free of any socket reference so the policy can be
// table-tested with no I/O (test/wsauth.unit.test.js); server.js only wires it up.
const CACHE_TTL_MS = Number(process.env.WS_AUTH_CACHE_TTL_MS) || 60_000;

// roomId → { ownerDeviceId, partnerDeviceId, at }
// Membership changes in exactly one place (redeemInvite), which calls invalidate(),
// so the TTL is a backstop rather than the primary freshness mechanism.
const cache = new Map();

/** Drop a room's cached membership. Called when redeemInvite changes the partner. */
function invalidate(roomId) {
  cache.delete(roomId);
}

/** Test hook — the cache is module-level state shared across tests. */
function _clearCache() {
  cache.clear();
}

function verdictFor(rec, deviceId, extra) {
  // `deviceId &&` is load-bearing: a room with no partner yet stores
  // partnerDeviceId === null, and an old APK sends no deviceId at all. Without the
  // guard, null === null would make every unidentified client a "member".
  if (deviceId && deviceId === rec.ownerDeviceId)
    return { verdict: "member", role: "owner", ...extra };
  if (deviceId && deviceId === rec.partnerDeviceId)
    return { verdict: "member", role: "partner", ...extra };
  return { verdict: "non-member", ...extra };
}

/**
 * Resolve `deviceId`'s standing in `roomId` against the pairing store.
 *
 * Verdicts:
 *   member       — owner or partner of a real paired room
 *   non-member   — the room exists and this device is not in it
 *   passthrough  — the room has no pairing record at all, so it is an ad-hoc/dev
 *                  room (test-page.html, host-sim.js, the test suite). Keeps
 *                  today's behaviour; not a bypass, since an unregistered roomId
 *                  is an empty unrelated room, not anyone's real one.
 *   store-error  — Upstash was unreachable. Deliberately distinct from
 *                  'non-member': blaming a legitimate device for an outage would,
 *                  under enforce, lock a couple out of their own room.
 */
async function checkMembership(store, roomId, deviceId, now = Date.now()) {
  const hit = cache.get(roomId);
  if (hit && now - hit.at < CACHE_TTL_MS)
    return verdictFor(hit, deviceId, { cached: true });

  let room;
  try {
    room = await store.getRoom(roomId);
  } catch (err) {
    // A stale answer beats no answer: the alternative during an outage is refusing
    // (or blindly allowing) a device we already know the standing of.
    if (hit) return verdictFor(hit, deviceId, { cached: true, stale: true });
    return { verdict: "store-error", cached: false, error: err.message };
  }

  // Never cache a negative — a room created a second later would stay invisible
  // for the whole TTL.
  if (!room) {
    cache.delete(roomId);
    return { verdict: "passthrough", cached: false };
  }

  const rec = {
    ownerDeviceId: room.ownerDeviceId ?? null,
    partnerDeviceId: room.partnerDeviceId ?? null,
    at: now,
  };
  cache.set(roomId, rec);
  return verdictFor(rec, deviceId, { cached: false });
}

/**
 * Turn a verdict into an allow/refuse under the current rollout mode.
 *
 * Pure — no store, no socket, no clock. `mode` is WS_AUTH_MODE
 * (off | observe | enforce) and `failMode` is WS_AUTH_FAIL (open | closed).
 */
function decide(verdict, mode, failMode) {
  if (mode === "off") return { allow: true };
  if (verdict === "member" || verdict === "passthrough") return { allow: true };

  if (verdict === "store-error") {
    // Fail-open by default: an Upstash blip must not brick a couple mid-movie, and
    // allowing degrades to exactly the pre-P0-3 security level, never below it.
    if (mode === "enforce" && failMode === "closed")
      return { allow: false, reason: "store-unavailable" };
    return { allow: true };
  }

  // non-member. Anything that is not literally "enforce" — including a typo in the
  // Render env var — behaves as observe, so a misconfiguration cannot silently
  // start rejecting real users.
  if (mode === "enforce") return { allow: false, reason: "not-a-room-member" };
  return { allow: true };
}

module.exports = {
  CACHE_TTL_MS,
  invalidate,
  checkMembership,
  decide,
  _clearCache,
};
