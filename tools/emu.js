#!/usr/bin/env node
/**
 * emu — screenshot-free Android emulator control.
 *
 * Reads the UIAutomator accessibility tree instead of guessing pixel coordinates,
 * so taps target resource-ids and text that survive layout changes.
 *
 *   node tools/emu.js ui                      compact tree of the current screen
 *   node tools/emu.js tap '#btnCreateRoom'    tap by resource-id
 *   node tools/emu.js --guest ui              same, on the guest device
 *   node tools/emu.js drift                   host vs guest playback delta
 *
 * KNOWN LIMIT (measured, see Learnings/L033): `uiautomator dump` waits for the app
 * to go idle. A sub-second DOM timer keeps the main thread busy forever, so the dump
 * fails permanently — retrying does not help. The guest's player screen runs a 250ms
 * self-check (MainActivity.kt CHECK_MS) and therefore cannot be read with `ui`.
 * Use `logcat` / `drift` / `shot` there instead. The host's 1000ms heartbeat is fine,
 * and active video playback does NOT block the dump.
 */

"use strict";

const { spawnSync } = require("child_process");
const fs = require("fs");
const path = require("path");

const CONFIG_PATH = path.join(__dirname, "emu.config.json");
const PKG = "com.fluffles.watchparty"; // applicationId
const NS = "com.buddiestime.watchparty"; // namespace — activities live here
// Every HWP-* tag in the app. Filtering to only MAIN/WPM silently hides pairing and
// setup failures (HWP-WELCOME, HWP-PAIR-API), which look identical to "nothing happened".
const LOG_TAGS = [
  "HWP-MAIN:D",
  "HWP-WPM:D",
  "HWP-WELCOME:D",
  "HWP-PAIR-API:D",
  "HWP-HOME:D",
  "HWP-SEL:D",
  "HWP-REDEEM:D",
  "HWP-SETTINGS:D",
  "HWP-VOICE:D",
  "HWP-FCM:D",
  "HWP-THEME:D",
  "HWP-HAPTICS:D",
  "chromium:I",
  "*:S",
];

// ---------------------------------------------------------------- adb plumbing

function findAdb() {
  const exe = process.platform === "win32" ? "adb.exe" : "adb";
  const candidates = [
    process.env.ANDROID_HOME &&
      path.join(process.env.ANDROID_HOME, "platform-tools", exe),
    process.env.ANDROID_SDK_ROOT &&
      path.join(process.env.ANDROID_SDK_ROOT, "platform-tools", exe),
    process.env.LOCALAPPDATA &&
      path.join(
        process.env.LOCALAPPDATA,
        "Android",
        "Sdk",
        "platform-tools",
        exe,
      ),
    process.env.HOME &&
      path.join(
        process.env.HOME,
        "Library",
        "Android",
        "sdk",
        "platform-tools",
        exe,
      ),
  ].filter(Boolean);

  for (const c of candidates) if (fs.existsSync(c)) return c;

  const probe = spawnSync(exe, ["version"], { encoding: "utf8" });
  if (!probe.error) return exe; // on PATH

  die(
    "adb not found. Set ANDROID_HOME or put adb on PATH.\nLooked in:\n  " +
      candidates.join("\n  "),
  );
}

const ADB = findAdb();

/**
 * Invoke adb with an argv array — never a shell string. This is what keeps Windows
 * quoting sane: there is no cmd.exe/PowerShell layer to escape for, only the
 * on-device shell (handled by shellQuote where needed).
 */
function adb(serial, args, opts = {}) {
  const argv = serial ? ["-s", serial, ...args] : args;
  const r = spawnSync(ADB, argv, {
    encoding: opts.raw ? "buffer" : "utf8",
    maxBuffer: 64 * 1024 * 1024,
  });
  if (r.error) die(`adb failed to launch: ${r.error.message}`);
  if (r.status !== 0 && !opts.tolerant) {
    die(`adb ${argv.join(" ")} exited ${r.status}\n${r.stderr || ""}`);
  }
  if (opts.raw) return r.stdout;
  // `am start` reports SecurityException on stderr while still exiting 0, so callers
  // that need to detect it must see both streams.
  return opts.combine ? `${r.stdout || ""}\n${r.stderr || ""}` : r.stdout || "";
}

/**
 * "08-01 20:29:57.526 D/HWP-WPM ..." → epoch ms, using the current year (logcat's `time`
 * format omits it). Returns null if the line has no timestamp.
 */
function parseLogTime(line) {
  const m = String(line).match(
    /(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})(?:\.(\d{3}))?/,
  );
  if (!m) return null;
  const [, mo, d, h, mi, s, ms] = m;
  return new Date(
    new Date().getFullYear(),
    Number(mo) - 1,
    Number(d),
    Number(h),
    Number(mi),
    Number(s),
    Number(ms || 0),
  ).getTime();
}

/** Quote a string for the *on-device* sh. */
function shellQuote(s) {
  return `'${String(s).replace(/'/g, `'\\''`)}'`;
}

// ------------------------------------------------------------ device selection

function listDevices() {
  return adb(null, ["devices"], { tolerant: true })
    .split("\n")
    .slice(1)
    .map((l) => l.trim().split(/\s+/))
    .filter((p) => p.length >= 2 && p[1] === "device")
    .map((p) => p[0]);
}

function loadConfig() {
  try {
    return JSON.parse(fs.readFileSync(CONFIG_PATH, "utf8"));
  } catch {
    return {};
  }
}

/**
 * Roles are pinned to serials in emu.config.json, never to `adb devices` order —
 * that order is not stable across reconnects, and a silent host/guest swap would
 * produce test failures that look like sync bugs.
 */
function resolveSerial(role, explicit) {
  const online = listDevices();
  if (!online.length)
    die("No devices. Start an emulator, or run: node tools/emu.js boot <avd>");

  if (explicit) {
    if (!online.includes(explicit))
      die(
        `Device ${explicit} is not online. Online: ${online.join(", ") || "(none)"}`,
      );
    return explicit;
  }

  const cfg = loadConfig();
  const pinned = cfg[role];
  if (pinned) {
    if (!online.includes(pinned)) {
      die(
        `Config pins ${role} to ${pinned}, but it is not online.\nOnline: ${online.join(", ")}\nFix ${CONFIG_PATH} or boot that device.`,
      );
    }
    return pinned;
  }

  if (role === "host") {
    if (online.length > 1) {
      die(
        `${online.length} devices online and no role pinning configured.\nRun: node tools/emu.js devices --save\nOr pass -d <serial>.`,
      );
    }
    return online[0];
  }
  die(
    `No serial pinned for role "${role}". Run: node tools/emu.js devices --save`,
  );
}

// -------------------------------------------------------------- tree handling

function dumpXml(serial) {
  const out = adb(serial, ["exec-out", "uiautomator", "dump", "/dev/tty"], {
    tolerant: true,
  })
    .replace(/UI hierchary dumped to: \/dev\/tty\s*/g, "") // Android's own typo
    .trim();

  if (!out || /^ERROR/.test(out)) {
    const reason = out || "(empty response)";
    die(
      `Could not read the accessibility tree: ${reason}\n\n` +
        `This almost always means the screen never goes idle — a sub-second timer\n` +
        `(e.g. the guest's 250ms CHECK_MS self-check) blocks uiautomator's idle wait.\n` +
        `It is a steady state, not a race, so retrying will not help.\n` +
        `Use "logcat", "drift", or "shot" for this screen instead.`,
    );
  }
  return out;
}

function parseNodes(xml) {
  const nodes = [];
  for (const m of xml.matchAll(/<node ([^>]*?)\/?>/g)) {
    const a = {};
    for (const p of m[1].matchAll(/([\w-]+)="([^"]*)"/g))
      a[p[1]] = decodeEntities(p[2]);

    const b = (a.bounds || "").match(/\[(\d+),(\d+)\]\[(\d+),(\d+)\]/);
    if (!b) continue;

    nodes.push({
      cls: (a.class || "").split(".").pop(),
      rid: (a["resource-id"] || "").split("/").pop(),
      text: a.text || "",
      desc: a["content-desc"] || "",
      cx: (Number(b[1]) + Number(b[3])) >> 1,
      cy: (Number(b[2]) + Number(b[4])) >> 1,
      tappable:
        a.clickable === "true" ||
        a.scrollable === "true" ||
        a.checkable === "true" ||
        a["long-clickable"] === "true",
      checked: a.checked === "true",
      enabled: a.enabled !== "false",
    });
  }
  return nodes;
}

function decodeEntities(s) {
  return s
    .replace(/&amp;/g, "&")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&#(\d+);/g, (_, d) => String.fromCodePoint(Number(d)));
}

function formatNode(n) {
  let s = `${n.cls} @${n.cx},${n.cy}`;
  if (n.rid) s += ` #${n.rid}`;
  if (n.text) s += ` "${n.text}"`;
  if (n.desc) s += ` [${n.desc}]`;
  if (n.tappable) s += " <tap>";
  if (n.checked) s += " CHECKED";
  if (!n.enabled) s += " DISABLED";
  return s;
}

/**
 * Selector grammar:
 *   #resId        resource-id suffix
 *   #resId[2]     nth match, 0-based, when a screen legitimately repeats an id
 *   "exact text"  exact text or content-desc
 *   ~substring    case-insensitive substring of text or content-desc
 *   400,1200      raw coordinates (escape hatch)
 */
function resolveSelector(nodes, sel) {
  const raw = sel.match(/^(\d+)\s*,\s*(\d+)$/);
  if (raw)
    return { cx: Number(raw[1]), cy: Number(raw[2]), cls: "raw", text: sel };

  let index = null;
  const idx = sel.match(/^(.*)\[(\d+)\]$/);
  if (idx) {
    sel = idx[1];
    index = Number(idx[2]);
  }

  let matches;
  if (sel.startsWith("#")) {
    const id = sel.slice(1);
    matches = nodes.filter((n) => n.rid === id);
  } else if (sel.startsWith("~")) {
    const q = sel.slice(1).toLowerCase();
    matches = nodes.filter(
      (n) =>
        n.text.toLowerCase().includes(q) || n.desc.toLowerCase().includes(q),
    );
  } else {
    const q = sel.replace(/^"|"$/g, "");
    matches = nodes.filter((n) => n.text === q || n.desc === q);
  }

  if (!matches.length)
    die(`No node matched selector: ${sel}\nRun "ui" to see what is on screen.`);

  if (index !== null) {
    if (index >= matches.length)
      die(
        `Selector ${sel}[${index}] out of range — only ${matches.length} match(es).`,
      );
    return matches[index];
  }

  if (matches.length > 1) {
    // Never silently pick the first — that is how coordinate drift bugs come back.
    die(
      `Selector "${sel}" is ambiguous — ${matches.length} matches:\n` +
        matches.map((n, i) => `  [${i}] ${formatNode(n)}`).join("\n") +
        `\nDisambiguate with an index, e.g. ${sel}[0]`,
    );
  }
  return matches[0];
}

// ------------------------------------------------------------------- commands

const cmds = {
  devices(serial, args) {
    const online = listDevices();
    const cfg = loadConfig();
    if (!online.length) return console.log("(no devices online)");

    for (const s of online) {
      const role = Object.keys(cfg).find((k) => cfg[k] === s);
      const model = adb(s, ["shell", "getprop", "ro.product.model"], {
        tolerant: true,
      }).trim();
      console.log(`${s}\t${model}\t${role ? `[${role}]` : "(unpinned)"}`);
    }

    if (args.includes("--save")) {
      const cfgNew = { host: online[0] };
      if (online[1]) cfgNew.guest = online[1];
      fs.writeFileSync(CONFIG_PATH, JSON.stringify(cfgNew, null, 2) + "\n");
      console.log(
        `\nPinned roles written to ${CONFIG_PATH}:\n${JSON.stringify(cfgNew, null, 2)}`,
      );
    }
  },

  boot(serial, args) {
    const avd = args[0];
    if (!avd)
      die("Usage: emu boot <avd-name>   (list them with: emulator -list-avds)");
    const emuBin = path.join(
      path.dirname(path.dirname(ADB)),
      "emulator",
      process.platform === "win32" ? "emulator.exe" : "emulator",
    );
    if (!fs.existsSync(emuBin)) die(`Emulator binary not found at ${emuBin}`);

    const child = require("child_process").spawn(
      emuBin,
      ["-avd", avd, "-netdelay", "none", "-netspeed", "full"],
      {
        detached: true,
        stdio: "ignore",
      },
    );
    child.unref();
    console.log(`Booting ${avd}… (detached; poll with "emu devices")`);
  },

  ui(serial) {
    const nodes = parseNodes(dumpXml(serial)).filter(
      (n) => n.text || n.desc || n.tappable,
    );
    if (!nodes.length)
      return console.log("(tree readable but no labelled or tappable nodes)");
    console.log(nodes.map(formatNode).join("\n"));
  },

  tap(serial, args) {
    if (!args[0]) die('Usage: emu tap <#resId | "text" | ~substr | x,y>');
    const node = resolveSelector(parseNodes(dumpXml(serial)), args[0]);
    adb(serial, ["shell", "input", "tap", String(node.cx), String(node.cy)]);
    console.log(`tapped ${node.cx},${node.cy} → ${formatNode(node)}`);
  },

  type(serial, args) {
    const text = args.join(" ");
    if (!text) die("Usage: emu type <text>");
    // `input text` reads a space as an argument separator; %s is its escape for one.
    const payload = shellQuote(text.replace(/ /g, "%s"));
    adb(serial, ["shell", `input text ${payload}`]);
    console.log(`typed: ${text}`);
  },

  key(serial, args) {
    const map = {
      back: "KEYCODE_BACK",
      home: "KEYCODE_HOME",
      enter: "KEYCODE_ENTER",
      tab: "KEYCODE_TAB",
      del: "KEYCODE_DEL",
      space: "KEYCODE_SPACE",
      up: "KEYCODE_DPAD_UP",
      down: "KEYCODE_DPAD_DOWN",
      left: "KEYCODE_DPAD_LEFT",
      right: "KEYCODE_DPAD_RIGHT",
      play: "KEYCODE_MEDIA_PLAY_PAUSE",
      app_switch: "KEYCODE_APP_SWITCH",
    };
    const k = args[0] && (map[args[0].toLowerCase()] || args[0].toUpperCase());
    if (!k) die(`Usage: emu key <${Object.keys(map).join("|")}|KEYCODE_*>`);
    adb(serial, ["shell", "input", "keyevent", k]);
    console.log(`key: ${k}`);
  },

  swipe(serial, args) {
    const [from, to, ms = "300"] = args;
    const a = from && from.match(/^(\d+),(\d+)$/);
    const b = to && to.match(/^(\d+),(\d+)$/);
    if (!a || !b) die("Usage: emu swipe <x1,y1> <x2,y2> [durationMs]");
    adb(serial, [
      "shell",
      "input",
      "swipe",
      a[1],
      a[2],
      b[1],
      b[2],
      String(ms),
    ]);
    console.log(`swiped ${from} → ${to} over ${ms}ms`);
  },

  /**
   * With no argument, enters through the launcher — the only reliable way in.
   * Only RoomsHomeActivity (LAUNCHER) and PairingRedeemActivity (deep link) are
   * exported. MainActivity, ServiceSelectorActivity, WelcomeSetupActivity and
   * SettingsActivity are exported="false", so `am start -n` on them throws
   * SecurityException regardless of how the component is spelled. Reach those by
   * launching and then navigating with `tap`.
   */
  launch(serial, args) {
    const activity = args.find((a) => !a.startsWith("-"));
    if (args.includes("--restart"))
      adb(serial, ["shell", "am", "force-stop", PKG], { tolerant: true });

    if (!activity) {
      adb(
        serial,
        [
          "shell",
          "monkey",
          "-p",
          PKG,
          "-c",
          "android.intent.category.LAUNCHER",
          "1",
        ],
        { tolerant: true },
      );
      console.log(`launched ${PKG} via launcher intent`);
      return;
    }

    const component = `${PKG}/${activity.startsWith(".") ? NS + activity : NS + "." + activity}`;
    const out = adb(serial, ["shell", "am", "start", "-n", component], {
      tolerant: true,
      combine: true,
    });
    if (/SecurityException|not exported/i.test(out)) {
      die(
        `${activity} is exported="false" — adb cannot start it directly.\n` +
          `Enter through the launcher and navigate instead:\n` +
          `  node tools/emu.js launch --restart\n` +
          `  node tools/emu.js ui\n` +
          `  node tools/emu.js tap '<selector>'`,
      );
    }
    if (/Error|does not exist/i.test(out))
      die(`Launch failed for ${component}\n${out}`);
    console.log(`launched ${component}`);
  },

  shot(serial, args) {
    const dest = args[0] || `emu-${Date.now()}.png`;
    fs.writeFileSync(
      dest,
      adb(serial, ["exec-out", "screencap", "-p"], { raw: true }),
    );
    console.log(`saved ${dest}`);
  },

  /** Screen size as "W H" — for computing fallback coordinates on screens `ui` cannot read. */
  size(serial) {
    const m = adb(serial, ["shell", "wm", "size"], { tolerant: true }).match(
      /(\d+)x(\d+)/,
    );
    if (!m) die("Could not read screen size from `wm size`.");
    console.log(`${m[1]} ${m[2]}`);
  },

  logcat(serial, args) {
    if (args.includes("-c")) {
      adb(serial, ["logcat", "-c"], { tolerant: true });
      return console.log("logcat cleared");
    }
    const n = Number((args.find((a) => /^-n\d+$/.test(a)) || "-n80").slice(2));
    const filter = args.find((a) => !a.startsWith("-"));
    let lines = adb(serial, ["logcat", "-d", "-v", "time", ...LOG_TAGS], {
      tolerant: true,
    }).split("\n");
    if (filter) lines = lines.filter((l) => l.includes(filter));
    console.log(
      lines.filter(Boolean).slice(-n).join("\n") || "(no matching lines)",
    );
  },

  /**
   * Playback delta between host and guest. Reads logcat rather than the tree because
   * the guest's player screen cannot be dumped (see L033) — and because the app already
   * logs authoritative positions on both sides.
   */
  drift(serial, args, flags) {
    const host = resolveSerial("host", flags.device);
    const guest = resolveSerial("guest", null);

    // Device wall-clock, to age the log lines below against. The format string contains a
    // space, so it must be quoted for the on-device shell or adb's arg-joining splits it.
    const clockCache = new Map();
    const nowOn = (s) => {
      if (!clockCache.has(s)) {
        const out = adb(s, ["shell", `date +${shellQuote("%m-%d %H:%M:%S")}`], {
          tolerant: true,
        }).trim();
        clockCache.set(s, parseLogTime(out));
      }
      return clockCache.get(s);
    };

    const read = (s, re) => {
      const lines = adb(s, ["logcat", "-d", "-v", "time", ...LOG_TAGS], {
        tolerant: true,
      }).split("\n");
      for (let i = lines.length - 1; i >= 0; i--) {
        const m = lines[i].match(re);
        if (m) {
          const stamp = parseLogTime(lines[i]);
          const now = nowOn(s);
          return {
            t: Number(m[1]),
            paused: m[2] === "true",
            line: lines[i].trim(),
            // Both sides must parse, or we cannot claim an age at all.
            ageSec:
              stamp === null || now === null ? null : (now - stamp) / 1000,
          };
        }
      }
      return null;
    };

    // The *app* role is assigned by the server to whichever client joins first, so it
    // does not necessarily match the tooling role pinned in emu.config.json. Read the
    // role each device actually got (WatchPartyManager.kt logs "joined as role=…")
    // rather than assuming, or the two regexes below get applied to the wrong devices.
    const roleOf = (s) => {
      const lines = adb(s, ["logcat", "-d", "-v", "time", ...LOG_TAGS], {
        tolerant: true,
      }).split("\n");
      for (let i = lines.length - 1; i >= 0; i--) {
        const m = lines[i].match(/joined as role=(\w+)/);
        if (m) return m[1];
      }
      return null;
    };

    const roles = { [host]: roleOf(host), [guest]: roleOf(guest) };
    const appHost = Object.keys(roles).find((s) => roles[s] === "host");
    const appGuest = Object.keys(roles).find((s) => roles[s] === "guest");

    if (!appHost || !appGuest) {
      die(
        `Could not determine app roles from logcat.\n` +
          `  ${host}: ${roles[host] || "no join logged"}\n` +
          `  ${guest}: ${roles[guest] || "no join logged"}\n` +
          `Both devices must be joined to the same room. Check with: emu logcat "joined as role"`,
      );
    }

    // Host's real player position, pushed from JS once per HEARTBEAT_MS (MainActivity.kt).
    const h = read(appHost, /JsBridge\.onStateUpdate t=([\d.]+) paused=(\w+)/);
    // The guest's liveness signal. NOTE: this is msg.optDouble("time") — the HOST's position
    // as received (WatchPartyManager.kt), NOT the guest's own currentTime. Differencing it
    // against `h` would compare the host to a relayed copy of itself and measure nothing.
    const g = read(appGuest, /state-update: t=([\d.]+) paused=(\w+)/);

    if (!h)
      die(
        `No position in logcat on ${appHost} (app role: host). Is a video loaded and playing?`,
      );
    if (!g)
      die(
        `No state-update in logcat on ${appGuest} (app role: guest). Is it joined to the same room?`,
      );

    const fmt = (r) =>
      `t=${r.t.toFixed(2)} paused=${r.paused}` +
      (r.ageSec === null ? "" : ` (${r.ageSec.toFixed(1)}s ago)`);
    console.log(`host  ${appHost}: ${fmt(h)}  [own position]`);
    console.log(`guest ${appGuest}: ${fmt(g)}  [host position as received]`);

    // A device that stopped reporting leaves its last line frozen in logcat. Differencing
    // against it produces a delta that grows with wall-clock time and looks exactly like
    // runaway drift — but the real story is "that side stopped talking" (crash, WS drop,
    // paused JS). Refuse to report a number rather than diagnose a sync bug that isn't there.
    // The host beats once a second, so anything older than STALE_SEC is not live.
    const STALE_SEC = 5;
    const stale = [
      ["host", appHost, h],
      ["guest", appGuest, g],
    ].filter(([, , r]) => r.ageSec !== null && r.ageSec > STALE_SEC);

    if (stale.length) {
      console.log("");
      for (const [role, s, r] of stale) {
        console.log(
          `STALE: ${role} ${s} last reported ${r.ageSec.toFixed(1)}s ago — it is not currently reporting.`,
        );
      }
      console.log(
        `Not reporting a drift figure: differencing a frozen reading measures elapsed time, not drift.\n` +
          `Check that side with:  node tools/emu.js ${stale[0][0] === "guest" ? "--guest " : ""}logcat -n30\n` +
          `and for a crash:       adb -s ${stale[0][1]} logcat -d | grep -iE "FATAL|has died"`,
      );
      process.exit(1);
    }

    // The guest's *own* error is only ever emitted by the injected JS, and only when it
    // exceeds DRIFT and a correction fires (MainActivity.kt: "[HWP] correcting (…) err=…").
    // There is no continuous guest-position log, so the absence of recent corrections while
    // both sides are live IS the in-sync signal — that is what this reports.
    const threshold = Number(args[0] || 1.0); // MainActivity.kt DRIFT / SyncPolicy.DRIFT_SEC
    const WINDOW_SEC = 60;
    const now = nowOn(appGuest);
    const corrections = adb(
      appGuest,
      ["logcat", "-d", "-v", "time", ...LOG_TAGS],
      { tolerant: true },
    )
      .split("\n")
      .map((l) => ({
        l,
        m: l.match(
          /\[HWP\] (?:correcting|pause mismatch) \(([^)]*)\): err=(-?[\d.]+)/,
        ),
      }))
      .filter((x) => x.m)
      .map((x) => ({
        reason: x.m[1],
        err: Number(x.m[2]),
        ageSec: (() => {
          const t = parseLogTime(x.l);
          return t === null || now === null ? null : (now - t) / 1000;
        })(),
      }))
      .filter((c) => c.ageSec === null || c.ageSec <= WINDOW_SEC);

    console.log("");
    if (!corrections.length) {
      console.log(
        `OK: guest logged no correction in the last ${WINDOW_SEC}s, so its own position stayed\n` +
          `within ±${threshold}s of the host. (The app only reports the guest's error when it\n` +
          `exceeds that threshold — there is no continuous guest-position log to read.)`,
      );
      return;
    }

    const worst = corrections.reduce((a, b) =>
      Math.abs(b.err) > Math.abs(a.err) ? b : a,
    );
    console.log(
      `${corrections.length} correction(s) in the last ${WINDOW_SEC}s — the guest drifted past ±${threshold}s:`,
    );
    for (const c of corrections.slice(-5)) {
      console.log(
        `  err=${c.err > 0 ? "+" : ""}${c.err.toFixed(2)}s (${c.reason})` +
          (c.ageSec === null ? "" : ` ${c.ageSec.toFixed(1)}s ago`),
      );
    }
    console.log(`worst: ${worst.err > 0 ? "+" : ""}${worst.err.toFixed(2)}s`);
    process.exit(1);
  },
};

// ----------------------------------------------------------------------- main

function die(msg) {
  console.error(`emu: ${msg}`);
  process.exit(1);
}

function usage() {
  console.log(`emu — screenshot-free Android emulator control

  Flags:  --host (default) | --guest | -d <serial>

  devices [--save]        list devices; --save pins host/guest roles by serial
  boot <avd>              start an AVD detached
  ui                      compact accessibility tree of the current screen
  tap <selector>          #resId | #resId[n] | "exact text" | ~substring | x,y
  type <text>             type into the focused field
  key <name>              back|home|enter|tab|del|play|…
  swipe <x1,y1> <x2,y2> [ms]
  launch [Activity] [--restart]
  shot [file.png]         screenshot (for screens 'ui' cannot read)
  size                    screen dimensions as "W H"
  logcat [filter] [-nN] [-c]
  drift [threshold]       host vs guest playback delta, exits 1 if exceeded

  Note: 'ui' cannot read screens with sub-second timers (the guest player's 250ms
  self-check). That is a hard limit of uiautomator's idle wait, not a bug — use
  logcat/drift/shot there. See Learnings/L033.`);
}

function main() {
  const argv = process.argv.slice(2);
  if (!argv.length || argv[0] === "-h" || argv[0] === "--help") {
    usage();
    return;
  }

  const flags = { role: "host", device: null };
  const rest = [];
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === "--host") flags.role = "host";
    else if (argv[i] === "--guest") flags.role = "guest";
    else if (argv[i] === "-d") flags.device = argv[++i];
    else rest.push(argv[i]);
  }

  const cmd = rest.shift();
  if (!cmds[cmd]) {
    console.error(`emu: unknown command "${cmd}"\n`);
    usage();
    process.exit(1);
  }

  // These manage or span devices, so they resolve serials themselves.
  const selfRouting = ["devices", "boot", "drift"];
  const serial = selfRouting.includes(cmd)
    ? null
    : resolveSerial(flags.role, flags.device);

  cmds[cmd](serial, rest, flags);
}

main();
