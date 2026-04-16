const DEFAULT_SERVER = 'ws://192.168.0.102:8080';

const $ = id => document.getElementById(id);

let currentRole = null;
let currentRoom = null;
let currentServer = null;

// Restore saved server URL
chrome.storage.local.get(['serverUrl', 'roomId'], ({ serverUrl, roomId }) => {
  if (serverUrl) $('server-url').value = serverUrl;
  if (roomId) $('room-id').value = roomId;
});

$('btn-host').addEventListener('click', () => startParty('host'));
$('btn-guest').addEventListener('click', () => startParty('guest'));
$('btn-leave').addEventListener('click', leaveParty);
$('btn-copy').addEventListener('click', () => {
  $('share-url').select();
  document.execCommand('copy');
  showMsg('Copied!', 'green');
});

function startParty(role) {
  const serverUrl = $('server-url').value.trim() || DEFAULT_SERVER;
  const roomId = $('room-id').value.trim() || randomRoomId();

  $('room-id').value = roomId;
  chrome.storage.local.set({ serverUrl, roomId });

  const clientId = role + '-' + Math.random().toString(36).slice(2, 7);

  chrome.tabs.query({ active: true, currentWindow: true }, ([tab]) => {
    if (!tab || (!tab.url.includes('hotstar.com') && !tab.url.includes('jiohotstar.com'))) {
      showMsg('Open a Hotstar page first!', 'red');
      return;
    }

    chrome.tabs.sendMessage(tab.id, {
      type: 'start-party',
      serverUrl,
      roomId,
      clientId
    }, resp => {
      if (chrome.runtime.lastError || !resp?.ok) {
        showMsg('Could not connect. Reload the Hotstar page and try again.', 'red');
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
  showSetupView();
}

function showConnectedView(role, roomId, serverUrl) {
  $('view-setup').classList.add('hidden');
  $('view-connected').classList.remove('hidden');

  $('status-line').textContent = role === 'host' ? '● You are the Host' : '● You are a Guest';
  $('status-line').style.color = role === 'host' ? '#1a7' : '#17a';
  $('room-info').textContent = 'Room: ' + roomId;

  if (role === 'host') {
    // Build shareable URL for partner's bookmarklet
    const wsHttpUrl = serverUrl.replace(/^wss:\/\//, 'https://').replace(/^ws:\/\//, 'http://');
    const bookmarkletUrl = wsHttpUrl + '/install.html?room=' + encodeURIComponent(roomId) + '&server=' + encodeURIComponent(serverUrl);
    $('share-url').value = bookmarkletUrl;
    $('share-section').classList.remove('hidden');
  }
}

function showSetupView() {
  $('view-setup').classList.remove('hidden');
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
