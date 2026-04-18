const DEFAULT_SERVER = 'ws://localhost:8080';

const $ = id => document.getElementById(id);

let selectedPlatform = null;
let currentRole = null;
let currentRoom = null;
let currentServer = null;

const PLATFORM_DOMAINS = {
  hotstar:    ['hotstar.com', 'jiohotstar.com'],
  youtube:    ['youtube.com'],
  primevideo: ['primevideo.com', 'amazon.com', 'amazon.in'],
  netflix:    ['netflix.com'],
};

// Patterns that confirm a host is on an actual video page (not just the platform home)
const VIDEO_URL_PATTERNS = {
  hotstar:    /\/(sports|movies|shows|episodes|live)\//i,
  youtube:    /[?&]v=/,
  primevideo: /\/(detail|dp|video\/detail)\//i,
  netflix:    /\/watch\//,
};

// Restore saved room name; auto-generate if none saved
chrome.storage.local.get(['serverUrl', 'roomName'], ({ roomName }) => {
  $('room-name').value = roomName || randomRoomId();
});

// Platform card selection
document.querySelectorAll('.platform-card').forEach(card => {
  card.addEventListener('click', () => {
    document.querySelectorAll('.platform-card').forEach(c => c.classList.remove('selected'));
    card.classList.add('selected');
    selectedPlatform = card.dataset.platform;
  });
});

$('btn-create').addEventListener('click', () => startParty('host'));
$('btn-join').addEventListener('click', () => startParty('guest'));
$('btn-leave').addEventListener('click', leaveParty);
$('btn-copy').addEventListener('click', () => {
  $('share-url').select();
  document.execCommand('copy');
  showMsg('Copied!', 'green');
});

function startParty(role) {
  if (!selectedPlatform) { showMsg('Select a platform first!', 'red'); return; }

  const serverUrl = DEFAULT_SERVER;
  const roomId = $('room-name').value.trim() || randomRoomId();
  $('room-name').value = roomId;
  chrome.storage.local.set({ serverUrl, roomName: roomId });

  const clientId = role + '-' + Math.random().toString(36).slice(2, 7);

  chrome.tabs.query({ active: true, currentWindow: true }, ([tab]) => {
    const domains = PLATFORM_DOMAINS[selectedPlatform] || [];
    const onCorrectSite = domains.some(d => tab?.url?.includes(d));

    if (role === 'host' && !onCorrectSite) {
      showMsg(`Open ${selectedPlatform} and navigate to a video first!`, 'red');
      return;
    }

    const videoPattern = VIDEO_URL_PATTERNS[selectedPlatform];
    if (role === 'host' && videoPattern && !videoPattern.test(tab?.url || '')) {
      showMsg(`Navigate to a specific video on ${selectedPlatform} first!`, 'red');
      return;
    }

    chrome.tabs.sendMessage(tab.id, {
      type: 'start-party',
      serverUrl,
      roomId,
      clientId,
      platform: selectedPlatform
    }, resp => {
      if (chrome.runtime.lastError || !resp?.ok) {
        showMsg('Could not connect. Reload the page and try again.', 'red');
        return;
      }
      currentRole = role;
      currentRoom = roomId;
      currentServer = serverUrl;
      showConnectedView(role, roomId, serverUrl);
    });
  });
}

function leaveParty() {
  chrome.tabs.query({ active: true, currentWindow: true }, ([tab]) => {
    if (tab) chrome.tabs.sendMessage(tab.id, { type: 'leave-party' }, () => {});
  });
  showServiceView();
}

function showConnectedView(role, roomId, serverUrl) {
  $('view-service').classList.add('hidden');
  $('view-connected').classList.remove('hidden');

  $('status-line').textContent = role === 'host' ? '● You are the Host' : '● You are a Guest';
  $('status-line').style.color = role === 'host' ? '#1a7' : '#17a';
  $('room-info').textContent = 'Room: ' + roomId;

  if (role === 'host') {
    const wsHttpUrl = serverUrl.replace(/^wss:\/\//, 'https://').replace(/^ws:\/\//, 'http://');
    $('share-url').value = wsHttpUrl + '/room/' + encodeURIComponent(roomId);
    $('share-section').classList.remove('hidden');
  }
}

function showServiceView() {
  $('view-service').classList.remove('hidden');
  $('view-connected').classList.add('hidden');
  currentRole = null; currentRoom = null;
}

function showMsg(text, color) {
  const el = $('msg');
  el.textContent = text;
  el.style.color = color;
  el.classList.remove('hidden');
  setTimeout(() => el.classList.add('hidden'), 3000);
}

function randomRoomId() {
  return Math.random().toString(36).slice(2, 8);
}
