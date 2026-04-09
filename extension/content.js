// Hotstar Watch Party — content script
// Injected on hotstar.com pages. Handles video sync via WebSocket.

let ws = null;
let video = null;
let role = null; // 'host' | 'guest'
let isSyncing = false; // prevent echo loops
let videoWaitTimer = null; // interval from waitForVideo, so we can cancel on disconnect

const DRIFT_THRESHOLD = 3; // seconds — force seek if drift exceeds this

// Named handlers so they can be removed on disconnect
function onVideoPlay()  { if (isSyncing || role !== 'host') return; send({ type: 'play',  time: video.currentTime }); }
function onVideoPause() { if (isSyncing || role !== 'host') return; send({ type: 'pause', time: video.currentTime }); }
function onVideoSeek()  { if (isSyncing || role !== 'host') return; send({ type: 'seek',  time: video.currentTime }); }

// Listen for messages from popup
chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  if (msg.type === 'start-party') {
    connect(msg.serverUrl, msg.roomId, msg.clientId);
    sendResponse({ ok: true });
  }
  if (msg.type === 'leave-party') {
    disconnect();
    sendResponse({ ok: true });
  }
  if (msg.type === 'status') {
    sendResponse({ connected: ws && ws.readyState === WebSocket.OPEN, role });
  }
});

function connect(serverUrl, roomId, clientId) {
  if (ws) ws.close();

  ws = new WebSocket(serverUrl);

  ws.addEventListener('open', () => {
    ws.send(JSON.stringify({ type: 'join', roomId, clientId }));
    showBadge('Connecting…', '#888');
  });

  ws.addEventListener('message', ({ data }) => {
    let msg;
    try { msg = JSON.parse(data); } catch { return; }
    handleServerMessage(msg);
  });

  ws.addEventListener('close', () => showBadge('Disconnected', '#c00'));
  ws.addEventListener('error', () => showBadge('Error', '#c00'));
}

function disconnect() {
  if (videoWaitTimer) { clearInterval(videoWaitTimer); videoWaitTimer = null; }
  if (video) {
    video.removeEventListener('play',   onVideoPlay);
    video.removeEventListener('pause',  onVideoPause);
    video.removeEventListener('seeked', onVideoSeek);
  }
  if (ws) { ws.close(); ws = null; }
  video = null;
  role = null;
  isSyncing = false;
  removeBadge();
}

function handleServerMessage(msg) {
  if (msg.type === 'joined') {
    role = msg.role;
    showBadge(role === 'host' ? 'Host' : 'Guest', role === 'host' ? '#1a7' : '#17a');
    waitForVideo(v => {
      video = v;
      attachVideoListeners();
    });
    return;
  }

  if (!video) return;

  if (msg.type === 'sync-request' && role === 'host') {
    // Guest just joined — send current state
    send({ type: 'sync-response', time: video.currentTime, paused: video.paused });
    return;
  }

  if (msg.type === 'sync-response' && role === 'guest') {
    applySync(msg.time, msg.paused);
    return;
  }

  if (role === 'guest') {
    if (msg.type === 'play') {
      applySync(msg.time, false);
    } else if (msg.type === 'pause') {
      applySync(msg.time, true);
    } else if (msg.type === 'seek') {
      applySync(msg.time, video.paused);
    }
  }
}

function attachVideoListeners() {
  if (!video) return;
  // Remove first to prevent duplicates if called more than once
  video.removeEventListener('play',   onVideoPlay);
  video.removeEventListener('pause',  onVideoPause);
  video.removeEventListener('seeked', onVideoSeek);
  video.addEventListener('play',   onVideoPlay);
  video.addEventListener('pause',  onVideoPause);
  video.addEventListener('seeked', onVideoSeek);
}

function applySync(time, shouldBePaused) {
  if (!video) return;
  isSyncing = true;

  if (Math.abs(video.currentTime - time) > DRIFT_THRESHOLD) {
    video.currentTime = time;
  }

  if (shouldBePaused && !video.paused) {
    video.pause();
  } else if (!shouldBePaused && video.paused) {
    video.play().catch(() => {});
  }

  setTimeout(() => { isSyncing = false; }, 300);
}

function send(msg) {
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(msg));
  }
}

function waitForVideo(cb) {
  if (videoWaitTimer) clearInterval(videoWaitTimer);
  videoWaitTimer = setInterval(() => {
    const v = document.querySelector('video');
    if (v) { clearInterval(videoWaitTimer); videoWaitTimer = null; cb(v); }
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
      'color:#fff', 'box-shadow:0 2px 8px rgba(0,0,0,.4)', 'transition:background .3s'
    ].join(';');
    document.body.appendChild(badge);
  }
  badge.textContent = '● Watch Party: ' + text;
  badge.style.background = color;
}

function removeBadge() {
  if (badge) { badge.remove(); badge = null; }
}
