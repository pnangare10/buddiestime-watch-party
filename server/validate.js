// Message-boundary validation for the WebSocket protocol.
//
// Everything here is pure and synchronous so it can be reasoned about (and tested)
// without a socket. The rule the rest of the server relies on: a handler may only
// read values that came back from one of these functions, never off `msg` directly.
// That is what keeps a hostile or buggy client from reaching a log line, a Map, or
// an Android WebView with a value of the wrong shape.

const MAX_URL_LEN = 2048;
const MAX_PLATFORM_LEN = 32;
// A video position beyond a week is not a real playback time; it is a client bug
// or an attempt to poison the projection math in projectedTime().
const MAX_TIME_SEC = 604800;

/**
 * Is this a URL we are willing to hand to Android's WebView.loadUrl?
 *
 * Only http/https. A `javascript:` URL passed to loadUrl executes inside whatever
 * page is currently loaded — i.e. the user's logged-in Hotstar/Netflix session,
 * with the HwpBridge JS interface attached. `file:`/`content:` reach local storage,
 * `intent:` reaches other apps, and `data:` is a same-origin script smuggler.
 */
function isNavigableUrl(raw) {
  if (typeof raw !== "string" || raw.length === 0 || raw.length > MAX_URL_LEN) {
    return false;
  }
  try {
    const u = new URL(raw);
    return u.protocol === "http:" || u.protocol === "https:";
  } catch {
    return false; // not absolute / unparseable
  }
}

/**
 * Fields a client may set on the room's shared playback state.
 *
 * Returns the sanitised values, never the originals. `videoUrl` is dropped rather
 * than rejected outright when it is unusable — a bad URL should not stop two people
 * staying in sync on the video they already have open.
 */
function parseStateUpdate(msg) {
  const time = msg.time;
  if (
    typeof time !== "number" ||
    !Number.isFinite(time) ||
    time < 0 ||
    time > MAX_TIME_SEC
  ) {
    return { ok: false, reason: "bad-time" };
  }
  if (typeof msg.paused !== "boolean") {
    return { ok: false, reason: "bad-paused" };
  }
  return {
    ok: true,
    time,
    paused: msg.paused,
    // undefined means "leave whatever the room already had" — see server.js.
    videoUrl: isNavigableUrl(msg.videoUrl) ? msg.videoUrl : undefined,
  };
}

/**
 * The content fields carried on `join`, which seed a brand-new room's state.
 *
 * This path matters as much as state-update: an unvalidated videoUrl written here
 * is served to every LATER joiner in their `joined` payload and leaks through the
 * unauthenticated GET /api/room/:id — no host role or state-update required.
 */
function parseJoinContent(msg) {
  return {
    platform:
      typeof msg.platform === "string" &&
      msg.platform.length > 0 &&
      msg.platform.length <= MAX_PLATFORM_LEN
        ? msg.platform
        : null,
    videoUrl: isNavigableUrl(msg.videoUrl) ? msg.videoUrl : null,
  };
}

/** Format a number for a log line without ever being able to throw. */
function fmt(n) {
  return typeof n === "number" && Number.isFinite(n) ? n.toFixed(2) : String(n);
}

module.exports = {
  isNavigableUrl,
  parseStateUpdate,
  parseJoinContent,
  fmt,
  MAX_URL_LEN,
  MAX_TIME_SEC,
};
