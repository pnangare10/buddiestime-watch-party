// Hotstar Watch Party — Bookmarklet source
// This runs in mobile Chrome on hotstar.com.
// Minified version goes into bookmarklet.min.js

(function () {
  if (window.__hwp) { alert('Watch Party already running'); return; }
  window.__hwp = true;

  // ── Config from URL params (set by install.html link) ──────────────────────
  const params = new URLSearchParams(location.search);
  const SERVER = params.get('hwpServer') || prompt('Server URL (wss://...):', 'wss://hotstar-watch-party.onrender.com');
  const ROOM   = params.get('hwpRoom')   || prompt('Room ID:');

  if (!SERVER || !ROOM) { window.__hwp = false; return; }

  const DRIFT = 3;
  let ws, video, role, isSyncing = false;

  // ── Badge ──────────────────────────────────────────────────────────────────
  const badge = document.createElement('div');
  badge.style.cssText = 'position:fixed;bottom:16px;right:16px;z-index:999999;'
    + 'padding:7px 16px;border-radius:20px;font:bold 13px/1 sans-serif;'
    + 'color:#fff;box-shadow:0 2px 8px rgba(0,0,0,.5);background:#888;';
  badge.textContent = '● Connecting…';
  document.body.appendChild(badge);

  function setBadge(text, color) { badge.textContent = '● ' + text; badge.style.background = color; }

  // ── WebSocket ──────────────────────────────────────────────────────────────
  ws = new WebSocket(SERVER);
  const clientId = 'bm-' + Math.random().toString(36).slice(2, 7);

  ws.onopen  = () => ws.send(JSON.stringify({ type: 'join', roomId: ROOM, clientId }));
  ws.onclose = () => setBadge('Disconnected', '#c00');
  ws.onerror = () => setBadge('Error', '#c00');

  ws.onmessage = ({ data }) => {
    let msg; try { msg = JSON.parse(data); } catch { return; }

    if (msg.type === 'joined') {
      role = msg.role;
      setBadge(role === 'host' ? 'Host' : 'Guest — Synced', role === 'host' ? '#1a7' : '#17a');
      waitForVideo(v => { video = v; if (role === 'host') attachListeners(); });
      return;
    }

    if (!video) return;

    if (msg.type === 'sync-request' && role === 'host') {
      send({ type: 'sync-response', time: video.currentTime, paused: video.paused });
      return;
    }
    if (msg.type === 'sync-response' && role === 'guest') { applySync(msg.time, msg.paused); return; }

    if (role === 'guest') {
      if (msg.type === 'play')  applySync(msg.time, false);
      if (msg.type === 'pause') applySync(msg.time, true);
      if (msg.type === 'seek')  applySync(msg.time, video.paused);
    }
  };

  function attachListeners() {
    video.addEventListener('play',   () => { if (!isSyncing) send({ type: 'play',  time: video.currentTime }); });
    video.addEventListener('pause',  () => { if (!isSyncing) send({ type: 'pause', time: video.currentTime }); });
    video.addEventListener('seeked', () => { if (!isSyncing) send({ type: 'seek',  time: video.currentTime }); });
  }

  function applySync(time, paused) {
    isSyncing = true;
    if (Math.abs(video.currentTime - time) > DRIFT) video.currentTime = time;
    if (paused && !video.paused) video.pause();
    else if (!paused && video.paused) video.play().catch(() => {});
    setTimeout(() => { isSyncing = false; }, 300);
  }

  function send(msg) { if (ws.readyState === 1) ws.send(JSON.stringify(msg)); }

  function waitForVideo(cb) {
    const t = setInterval(() => { const v = document.querySelector('video'); if (v) { clearInterval(t); cb(v); } }, 500);
  }
})();
