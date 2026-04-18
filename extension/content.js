// Watch Party — content script
// Injected on supported streaming pages. Handles video sync via WebSocket.

let ws = null;
let video = null;
let role = null; // 'host' | 'guest'
let isSyncing = false;
let videoWaitTimer = null;
let stateTimer = null; // host: periodic state-update interval

// Stored connection params (needed for pendingJoin after redirect)
let serverUrl = null;
let roomId    = null;
let clientId  = null;
let platform  = null;

// Tracks the last URL we redirected to — prevents re-redirecting to the same URL
// but allows redirect when host switches to a genuinely different video
let lastRedirectedUrl = null;

const DRIFT_THRESHOLD = 2; // seconds — correct guest if drift exceeds this

const log = (...args) => console.log('[HWP]', ...args);
const warn = (...args) => console.warn('[HWP]', ...args);

// ── Popup message handler ───────────────────────────────────────────────────

chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  if (msg.type === 'start-party') {
    log(`start-party received: serverUrl=${msg.serverUrl} roomId=${msg.roomId} clientId=${msg.clientId} platform=${msg.platform}`);
    connect(msg.serverUrl, msg.roomId, msg.clientId, msg.platform);
    sendResponse({ ok: true });
  }
  if (msg.type === 'leave-party') {
    log('leave-party received');
    disconnect();
    sendResponse({ ok: true });
  }
  if (msg.type === 'status') {
    sendResponse({ connected: ws && ws.readyState === WebSocket.OPEN, role });
  }
});

// ── Connection ──────────────────────────────────────────────────────────────

function connect(serverUrlArg, roomIdArg, clientIdArg, platformArg) {
  serverUrl = serverUrlArg;
  roomId    = roomIdArg;
  clientId  = clientIdArg;
  platform  = platformArg;

  log(`connect() → server=${serverUrl} roomId=${roomId} clientId=${clientId} platform=${platform}`);

  if (ws) { log('closing existing WS before reconnect'); ws.close(); }
  ws = new WebSocket(serverUrl);

  ws.addEventListener('open', () => {
    const videoUrl = window.location.hostname + window.location.pathname + window.location.search;
    const joinMsg = { type: 'join', roomId, clientId, platform, videoUrl };
    log('WS open — sending join:', JSON.stringify(joinMsg));
    ws.send(JSON.stringify(joinMsg));
    showBadge('Connecting…', '#888');
  });

  ws.addEventListener('message', ({ data }) => {
    let msg;
    try { msg = JSON.parse(data); } catch { warn('failed to parse WS message:', data); return; }
    log('WS message received:', JSON.stringify(msg));
    handleServerMessage(msg);
  });

  ws.addEventListener('close', (e) => {
    log(`WS closed — code=${e.code} reason=${e.reason}`);
    showBadge('Disconnected', '#c00');
  });
  ws.addEventListener('error', (e) => {
    warn('WS error:', e);
    showBadge('Error', '#c00');
  });
}

function disconnect() {
  log('disconnect() called');
  if (videoWaitTimer) { clearInterval(videoWaitTimer); videoWaitTimer = null; }
  if (stateTimer)     { clearInterval(stateTimer);     stateTimer     = null; }
  if (video) {
    video.removeEventListener('play',   onVideoPlay);
    video.removeEventListener('pause',  onVideoPause);
    video.removeEventListener('seeked', onVideoSeek);
  }
  if (ws) { ws.close(); ws = null; }
  video = null;
  role  = null;
  isSyncing = false;
  removeBadge();
}

// ── Server message handler ──────────────────────────────────────────────────

function handleServerMessage(msg) {
  if (msg.type === 'joined') {
    role = msg.role;
    log(`joined as ${role} | videoUrl=${msg.videoUrl} time=${msg.time} paused=${msg.paused}`);

    // Guest: redirect to host's video if on a different URL
    if (role === 'guest' && msg.videoUrl) {
      const currentUrl = window.location.hostname + window.location.pathname + window.location.search;
      log(`guest URL check: currentUrl="${currentUrl}" hostUrl="${msg.videoUrl}" lastRedirectedUrl="${lastRedirectedUrl}"`);
      if (msg.videoUrl !== currentUrl && msg.videoUrl !== lastRedirectedUrl) {
        log(`URLs differ — redirecting to https://${msg.videoUrl}`);
        lastRedirectedUrl = msg.videoUrl;
        chrome.storage.local.set({ pendingJoin: { serverUrl, roomId, clientId, platform } });
        window.location.href = 'https://' + msg.videoUrl;
        return;
      }
      if (msg.videoUrl !== currentUrl && msg.videoUrl === lastRedirectedUrl) {
        warn(`URLs still differ after redirect — proceeding anyway (currentUrl="${currentUrl}" hostUrl="${msg.videoUrl}")`);
      }
    }

    showBadge(role === 'host' ? 'Host' : 'Guest', role === 'host' ? '#1a7' : '#17a');
    log(`waitForVideo starting — role=${role}`);
    waitForVideo(v => {
      video = v;
      log(`video element found: duration=${v.duration?.toFixed(2)}s readyState=${v.readyState} src=${v.currentSrc?.slice(0,80)}`);
      attachVideoListeners();
      if (role === 'host') {
        log('starting host state-update interval (every 2s)');
        startStateUpdates();
      } else {
        log(`guest: applying initial state from joined msg — time=${msg.time} paused=${msg.paused}`);
        if (msg.time != null) applySync(msg.time, msg.paused);
      }
    });
    return;
  }

  // Guest: incoming state-update from host (forwarded by server)
  if (msg.type === 'state-update' && role === 'guest') {
    log(`guest received state-update: time=${msg.time?.toFixed(2)}s paused=${msg.paused} videoUrl=${msg.videoUrl}`);

    if (!video) {
      warn('state-update received but video element not found yet — skipping');
      return;
    }

    // If host switched to a different video, redirect
    const currentUrl = window.location.hostname + window.location.pathname + window.location.search;
    if (msg.videoUrl && msg.videoUrl !== currentUrl && msg.videoUrl !== lastRedirectedUrl) {
      log(`host switched video — redirecting to https://${msg.videoUrl}`);
      lastRedirectedUrl = msg.videoUrl;
      chrome.storage.local.set({ pendingJoin: { serverUrl, roomId, clientId, platform } });
      window.location.href = 'https://' + msg.videoUrl;
      return;
    }

    const drift = Math.abs(video.currentTime - msg.time);
    const pauseMismatch = video.paused !== msg.paused;
    log(`guest sync check: guestTime=${video.currentTime?.toFixed(2)}s hostTime=${msg.time?.toFixed(2)}s drift=${drift?.toFixed(2)}s guestPaused=${video.paused} hostPaused=${msg.paused} pauseMismatch=${pauseMismatch}`);

    if (drift > DRIFT_THRESHOLD) {
      log(`drift ${drift.toFixed(2)}s > threshold ${DRIFT_THRESHOLD}s — seeking + applying pause state`);
      applySync(msg.time, msg.paused);
    } else if (pauseMismatch) {
      log(`pause state mismatch — correcting (no seek)`);
      applySync(video.currentTime, msg.paused);
    } else {
      log('guest in sync — no correction needed');
    }
    return;
  }
}

// ── Host: periodic state push ───────────────────────────────────────────────

function startStateUpdates() {
  if (stateTimer) clearInterval(stateTimer);
  stateTimer = setInterval(() => {
    if (!video) { warn('stateTimer fired but video is null'); return; }
    const state = {
      type:     'state-update',
      time:     video.currentTime,
      paused:   video.paused,
      videoUrl: window.location.hostname + window.location.pathname + window.location.search,
    };
    log(`HOST sending state-update: time=${state.time?.toFixed(2)}s paused=${state.paused} videoUrl=${state.videoUrl} wsState=${ws?.readyState}`);
    send(state);
  }, 2000);
}

// ── Video listeners (host: send immediate state on play/pause/seek) ─────────

function onVideoPlay() {
  if (isSyncing || role !== 'host') return;
  log('HOST video play event — sending immediate state-update');
  send({ type: 'state-update', time: video.currentTime, paused: false, videoUrl: window.location.hostname + window.location.pathname + window.location.search });
}
function onVideoPause() {
  if (isSyncing || role !== 'host') return;
  log('HOST video pause event — sending immediate state-update');
  send({ type: 'state-update', time: video.currentTime, paused: true, videoUrl: window.location.hostname + window.location.pathname + window.location.search });
}
function onVideoSeek() {
  if (isSyncing || role !== 'host') return;
  log('HOST video seeked event — sending immediate state-update');
  send({ type: 'state-update', time: video.currentTime, paused: video.paused, videoUrl: window.location.hostname + window.location.pathname + window.location.search });
}

function attachVideoListeners() {
  if (!video) return;
  video.removeEventListener('play',   onVideoPlay);
  video.removeEventListener('pause',  onVideoPause);
  video.removeEventListener('seeked', onVideoSeek);
  video.addEventListener('play',   onVideoPlay);
  video.addEventListener('pause',  onVideoPause);
  video.addEventListener('seeked', onVideoSeek);
  log('video event listeners attached (play, pause, seeked)');
}

// ── Sync application ────────────────────────────────────────────────────────

function applySync(time, shouldBePaused) {
  if (!video) { warn('applySync called but video is null'); return; }
  log(`applySync: seeking to ${time?.toFixed(2)}s, shouldBePaused=${shouldBePaused} (currentTime=${video.currentTime?.toFixed(2)}s paused=${video.paused})`);
  isSyncing = true;

  if (Math.abs(video.currentTime - time) > DRIFT_THRESHOLD) {
    video.currentTime = time;
  }

  if (shouldBePaused && !video.paused) {
    video.pause();
    log('applySync: paused video');
  } else if (!shouldBePaused && video.paused) {
    video.play().catch(e => warn('applySync: play() rejected:', e.message));
    log('applySync: playing video');
  }

  setTimeout(() => {
    isSyncing = false;
    log(`applySync done — video is now at ${video?.currentTime?.toFixed(2)}s paused=${video?.paused}`);
  }, 500);
}

function send(msg) {
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(msg));
  } else {
    warn(`send() called but WS not open (readyState=${ws?.readyState}) — msg type=${msg.type}`);
  }
}

// ── Video element detection ─────────────────────────────────────────────────

function waitForVideo(cb) {
  if (videoWaitTimer) clearInterval(videoWaitTimer);
  let attempts = 0;
  videoWaitTimer = setInterval(() => {
    attempts++;
    const all = [...document.querySelectorAll('video')];
    const candidates = all.filter(v => v.readyState > 0 && v.duration > 1);
    log(`waitForVideo attempt #${attempts}: found ${all.length} video elements, ${candidates.length} candidates (readyState>0 && duration>1)`);
    all.forEach((v, i) => log(`  video[${i}]: readyState=${v.readyState} duration=${v.duration?.toFixed(2)} src=${v.currentSrc?.slice(0,60)}`));

    if (candidates.length > 0) {
      const v = candidates.reduce((best, cur) => cur.duration > best.duration ? cur : best);
      log(`waitForVideo: selected video with duration=${v.duration?.toFixed(2)}s`);
      clearInterval(videoWaitTimer);
      videoWaitTimer = null;
      cb(v);
    }
  }, 500);
}

// ── Badge UI ────────────────────────────────────────────────────────────────

let badge = null;

function showBadge(text, color) {
  if (!badge) {
    badge = document.createElement('div');
    badge.id = 'hwp-badge';
    badge.style.cssText = [
      'position:fixed', 'bottom:20px', 'right:20px', 'z-index:999999',
      'padding:6px 14px', 'border-radius:20px', 'font:bold 13px/1 sans-serif',
      'color:#fff', 'box-shadow:0 2px 8px rgba(0,0,0,.4)', 'transition:background .3s',
    ].join(';');
    document.body.appendChild(badge);
  }
  badge.textContent = '● Watch Party: ' + text;
  badge.style.background = color;
}

function removeBadge() {
  if (badge) { badge.remove(); badge = null; }
}

// ── Auto-reconnect after guest redirect navigation ──────────────────────────

chrome.storage.local.get(['pendingJoin'], ({ pendingJoin }) => {
  if (pendingJoin) {
    log('pendingJoin found in storage — auto-reconnecting after redirect:', JSON.stringify(pendingJoin));
    // Mark current URL as the one we just redirected to — prevents immediately re-redirecting here
    lastRedirectedUrl = window.location.hostname + window.location.pathname + window.location.search;
    chrome.storage.local.remove('pendingJoin');
    connect(pendingJoin.serverUrl, pendingJoin.roomId, pendingJoin.clientId, pendingJoin.platform);
  } else {
    log('no pendingJoin in storage — waiting for start-party message from popup');
  }
});
