/**
 * Convergence tests for the guest correction loop.
 *
 * These run the REAL script that ships inside the Android app: SYNC_SCRIPT is extracted
 * verbatim from MainActivity.kt, so the behaviour under test is the behaviour that runs
 * on the device. Around it sits a simulated <video> whose seeks cost real buffering time
 * and a virtual clock.
 *
 * Scope, honestly: this proves the correction loop converges and holds under latency,
 * slow seeks and rebuffering. It does NOT reproduce the field report of a guest stuck
 * 5-10s behind — the strongest suspect there is the URL gate on the Kotlin side, which
 * bypassed this loop entirely and is covered by SyncPolicyTest instead. Real convergence
 * on a DRM player still has to be confirmed on two devices; see TESTING.md.
 *
 *   node --test tests/sync-convergence.test.js
 */

const { test } = require("node:test");
const assert = require("node:assert");
const fs = require("fs");
const path = require("path");
const vm = require("vm");

const MAIN_ACTIVITY = path.join(
  __dirname,
  "..",
  "android",
  "app",
  "src",
  "main",
  "kotlin",
  "com",
  "buddiestime",
  "watchparty",
  "MainActivity.kt",
);

function extractSyncScript() {
  const src = fs.readFileSync(MAIN_ACTIVITY, "utf8");
  const m = src.match(
    /private val SYNC_SCRIPT = """([\s\S]*?)"""\.trimIndent\(\)/,
  );
  assert.ok(m, "SYNC_SCRIPT not found in MainActivity.kt");
  return m[1];
}

/** Virtual clock: timers fire in order, and time only advances when we say so. */
function makeClock() {
  let now = 1_000_000;
  let seq = 0;
  const timers = new Map();

  const api = {
    now: () => now,
    setTimeout(fn, delay) {
      const id = ++seq;
      timers.set(id, { fn, at: now + (delay || 0), every: null });
      return id;
    },
    setInterval(fn, every) {
      const id = ++seq;
      timers.set(id, { fn, at: now + (every || 0), every: every || 1 });
      return id;
    },
    clear(id) {
      timers.delete(id);
    },
    /** Advance the clock, firing every timer due along the way. */
    advance(ms) {
      const end = now + ms;
      for (;;) {
        let next = null;
        for (const [id, t] of timers) {
          if (t.at <= end && (next === null || t.at < next[1].at))
            next = [id, t];
        }
        if (!next) break;
        const [id, t] = next;
        now = t.at;
        if (t.every) t.at = now + t.every;
        else timers.delete(id);
        t.fn();
      }
      now = end;
    },
  };
  return api;
}

/**
 * A video element that behaves like a real streaming player: writing currentTime starts
 * a seek that takes `seekCostSec` of wall time to resume, during which playback does not
 * advance. That cost is precisely why seeking to the host's raw timestamp can never catch up.
 */
function makeVideo(
  clock,
  { seekCostSec = 3.0, startTime = 0, paused = false } = {},
) {
  const listeners = {};
  let _t = startTime;
  let seeking = false;

  const v = {
    duration: 7200,
    readyState: 4,
    paused,
    get currentTime() {
      return _t;
    },
    set currentTime(target) {
      seeking = true;
      const settleAt = clock.now() + seekCostSec * 1000;
      _t = target;
      clock.setTimeout(() => {
        seeking = false;
        if (!v.paused) emit("playing");
        emit("seeked");
      }, settleAt - clock.now());
    },
    play() {
      v.paused = false;
      emit("play");
      if (!seeking) emit("playing");
      return Promise.resolve();
    },
    pause() {
      v.paused = true;
      emit("pause");
    },
    addEventListener(name, fn) {
      (listeners[name] = listeners[name] || []).push(fn);
    },
    /** Wall time passes; playback advances only when playing and not buffering. */
    tickWall(ms) {
      if (!v.paused && !seeking) _t += ms / 1000;
    },
    isSeeking: () => seeking,
  };
  function emit(name) {
    (listeners[name] || []).forEach((fn) => fn());
  }
  return v;
}

/** Boots SYNC_SCRIPT in a sandbox with a fake DOM, and drives wall time forward. */
function boot({
  seekCostSec,
  guestStartTime,
  hostStartTime,
  hostPaused = false,
}) {
  const clock = makeClock();
  const video = makeVideo(clock, {
    seekCostSec,
    startTime: guestStartTime,
    paused: hostPaused,
  });

  const sent = [];
  const sandbox = {
    console: { log: () => {} },
    Date: { now: clock.now },
    Math,
    Promise,
    setTimeout: clock.setTimeout,
    setInterval: clock.setInterval,
    clearTimeout: clock.clear,
    clearInterval: clock.clear,
    document: {
      querySelectorAll: () => [video],
      contains: () => true,
    },
    HwpBridge: {
      onStateUpdate: (t, p, u) => sent.push({ t, p, u }),
      onUrlChange: () => {},
    },
  };
  sandbox.window = sandbox;
  sandbox.location = { href: "https://www.hotstar.com/in/shows/x/1260/watch" };
  vm.createContext(sandbox);
  vm.runInContext(extractSyncScript(), sandbox);

  // The host's own playhead, advancing in real time.
  const host = { time: hostStartTime, paused: hostPaused };

  return {
    clock,
    video,
    sandbox,
    host,
    sent,
    setRole(role) {
      sandbox.window.HWP_setRole(role);
    },
    /**
     * Advance wall time in small slices, ticking the guest's playback, the host's
     * playhead, and delivering a host heartbeat every second (with `latencyMs` of
     * staleness, exactly like the network path).
     */
    run(totalMs, { latencyMs = 120, heartbeatMs = 1000 } = {}) {
      const STEP = 50;
      let sinceBeat = 0;
      for (let elapsed = 0; elapsed < totalMs; elapsed += STEP) {
        clock.advance(STEP);
        video.tickWall(STEP);
        if (!host.paused) host.time += STEP / 1000;
        sinceBeat += STEP;
        if (sinceBeat >= heartbeatMs) {
          sinceBeat = 0;
          // Sample now, deliver after latency — the sample is stale on arrival.
          const sample = { time: host.time, paused: host.paused };
          clock.setTimeout(() => {
            sandbox.window.HWP_applyHostState(sample.time, sample.paused);
          }, latencyMs);
        }
      }
    },
    error() {
      return video.currentTime - host.time;
    },
  };
}

test("a guest that starts 8s behind converges onto the host and stays there", () => {
  const sim = boot({
    seekCostSec: 3.0,
    guestStartTime: 92,
    hostStartTime: 100,
  });
  sim.setRole("guest");

  sim.run(30_000);

  const err = sim.error();
  assert.ok(
    Math.abs(err) <= 1.5,
    `expected the guest within 1.5s of the host, got ${err.toFixed(2)}s`,
  );
});

test("a slow player whose seeks cost 5s still converges", () => {
  // Guards the seek-lead logic: without aiming past the target by the measured seek
  // cost, a player this slow re-lands behind after every correction.
  const sim = boot({
    seekCostSec: 5.0,
    guestStartTime: 90,
    hostStartTime: 100,
  });
  sim.setRole("guest");

  sim.run(60_000);

  const err = sim.error();
  assert.ok(
    Math.abs(err) <= 1.5,
    `expected convergence despite 5s seeks, got ${err.toFixed(2)}s`,
  );
});

test("an in-sync guest is left alone — no seek churn", () => {
  const sim = boot({
    seekCostSec: 3.0,
    guestStartTime: 100,
    hostStartTime: 100,
  });
  sim.setRole("guest");

  let seeks = 0;
  const realSetter = Object.getOwnPropertyDescriptor(
    sim.video,
    "currentTime",
  ).set;
  Object.defineProperty(sim.video, "currentTime", {
    get: Object.getOwnPropertyDescriptor(sim.video, "currentTime").get,
    set(v) {
      seeks++;
      realSetter.call(sim.video, v);
    },
  });

  sim.run(30_000);

  assert.strictEqual(seeks, 0, `expected no corrective seeks, got ${seeks}`);
  assert.ok(
    Math.abs(sim.error()) <= 0.5,
    `drifted to ${sim.error().toFixed(2)}s`,
  );
});

test("a guest recovers after a mid-playback rebuffer", () => {
  const sim = boot({
    seekCostSec: 2.0,
    guestStartTime: 100,
    hostStartTime: 100,
  });
  sim.setRole("guest");
  sim.run(5_000);

  // Simulate a 6s stall: host keeps playing, guest's playhead freezes.
  for (let i = 0; i < 120; i++) {
    sim.clock.advance(50);
    sim.host.time += 0.05;
  }

  sim.run(40_000);

  const err = sim.error();
  assert.ok(
    Math.abs(err) <= 1.5,
    `expected recovery after a 6s stall, got ${err.toFixed(2)}s`,
  );
});

test("a paused host is matched exactly, with no forward projection", () => {
  const sim = boot({
    seekCostSec: 2.0,
    guestStartTime: 80,
    hostStartTime: 100,
    hostPaused: true,
  });
  sim.setRole("guest");

  sim.run(20_000);

  assert.ok(sim.video.paused, "guest should be paused with the host");
  const err = sim.error();
  assert.ok(
    Math.abs(err) <= 0.3,
    `a paused target must be hit exactly, got ${err.toFixed(2)}s`,
  );
});

test("the host heartbeats every second, not every five", () => {
  const sim = boot({
    seekCostSec: 2.0,
    guestStartTime: 100,
    hostStartTime: 100,
  });
  sim.setRole("host");

  sim.clock.advance(10_000);

  assert.ok(
    sim.sent.length >= 9,
    `expected ~10 heartbeats in 10s, got ${sim.sent.length}`,
  );
});
