'use strict';

const video = document.getElementById('video');
const jpeg = document.getElementById('mjpeg');
const placeholder = document.getElementById('placeholder');
const errorBox = document.getElementById('error');
const state = document.getElementById('state');
const dot = document.getElementById('dot');
const modeLabel = document.getElementById('mode');
const fpsLabel = document.getElementById('fps');
const delayLabel = document.getElementById('delay');
const screen = document.getElementById('screen');
let socket = null;
let media = null;
let buffer = null;
let objectUrl = null;
let chunks = [];
let active = false;
let connecting = false;
let retryTimer = null;
let renderedFrames = 0;
let lastFpsTime = performance.now();
let attemptId = 0;

function showStatus(text, online) {
  state.textContent = text;
  dot.classList.toggle('active', online);
}
function showError(text) { errorBox.textContent = text; }
function showPicture() { placeholder.hidden = true; }

function closeStream() {
  attemptId++;
  clearTimeout(retryTimer);
  if (socket) { const old = socket; socket = null; old.onclose = null; old.close(); }
  if (buffer && media && media.readyState === 'open') {
    try { media.endOfStream(); } catch (_) { /* may be updating */ }
  }
  buffer = null;
  media = null;
  chunks = [];
  video.removeAttribute('src');
  video.load();
  if (objectUrl) URL.revokeObjectURL(objectUrl);
  objectUrl = null;
  jpeg.removeAttribute('src');
  jpeg.hidden = true;
  video.hidden = false;
  placeholder.hidden = false;
  active = false;
  connecting = false;
}

function pump() {
  if (!buffer || buffer.updating || !media || media.readyState !== 'open') return;
  if (chunks.length) {
    try { buffer.appendBuffer(chunks.shift()); }
    catch (err) { showError('Video buffer rejected this frame: ' + err.message); }
    return;
  }
  if (video.buffered.length > 0) {
    const liveEnd = video.buffered.end(video.buffered.length - 1);
    const behind = liveEnd - video.currentTime;
    if (behind > .65 || video.paused) {
      try { video.currentTime = Math.max(0, liveEnd - .12); } catch (_) {}
    }
    if (video.paused) video.play().catch(() => {});
    delayLabel.textContent = behind > 0 ? 'Buffered ' + behind.toFixed(1) + 's' : 'Live';
    if (video.currentTime > 6 && video.buffered.start(0) < video.currentTime - 3) {
      try { buffer.remove(0, video.currentTime - 2); } catch (_) {}
    }
  }
}

function configureMse(mime) {
  if (!window.MediaSource || !MediaSource.isTypeSupported(mime)) {
    showError('Your browser cannot play this H.264 format. Use Chrome or Edge, or restart Pochi in Compatibility (MJPEG) mode.');
    return;
  }
  chunks = [];
  if (objectUrl) URL.revokeObjectURL(objectUrl);
  media = new MediaSource();
  objectUrl = URL.createObjectURL(media);
  video.src = objectUrl;
  media.addEventListener('sourceopen', () => {
    try {
      buffer = media.addSourceBuffer(mime);
      buffer.mode = 'segments';
      buffer.addEventListener('updateend', () => { showPicture(); pump(); });
      buffer.addEventListener('error', () => showError('H.264 playback failed. Switch to Compatibility (MJPEG) mode.'));
      pump();
    } catch (err) { showError('Cannot initialize video: ' + err.message); }
  }, { once: true });
}

async function connect() {
  closeStream();
  const current = attemptId;
  showStatus('Connecting…', false);
  showError('');
  connecting = true;
  try {
    const response = await fetch('/status', { cache: 'no-store' });
    if (!response.ok || current !== attemptId) return;
    const data = await response.json();
    if (current !== attemptId) return;
    if (data.mode === 'mjpeg') {
      modeLabel.textContent = 'Compatibility · MJPEG';
      video.hidden = true;
      jpeg.hidden = false;
      jpeg.onload = () => { showPicture(); showStatus('Connected', true); };
      jpeg.onerror = () => showError('JPEG stream interrupted. Retry or restart sharing on Android.');
      jpeg.src = '/mjpeg?nonce=' + Date.now();
      showStatus('Receiving MJPEG…', true);
      fpsLabel.textContent = 'MJPEG';
      return;
    }
    modeLabel.textContent = 'Balanced · H.264';
    const ws = new WebSocket('ws://' + location.host + '/ws');
    socket = ws;
    ws.binaryType = 'arraybuffer';
    ws.onopen = () => { showStatus('Receiving H.264…', true); connecting = false; };
    ws.onmessage = (evt) => {
      if (typeof evt.data === 'string') {
        try {
          const info = JSON.parse(evt.data);
          if (info.type === 'init') configureMse(info.mime);
        } catch (err) { showError('Invalid video configuration: ' + err.message); }
      } else if (buffer || media) {
        // Init segment arrives before media fragments; preserve arrival order.
        if (chunks.length >= 100) chunks.splice(0, chunks.length - 50);
        chunks.push(evt.data);
        pump();
      }
    };
    ws.onerror = () => showError('USB stream interrupted. Check the cable and sharing status.');
    ws.onclose = () => {
      if (current !== attemptId) return;
      showStatus('Disconnected', false);
      retryTimer = setTimeout(connect, 2000);
    };
  } catch (err) {
    if (current !== attemptId) return;
    showStatus('Disconnected', false);
    showError('Cannot reach Pochi. Check USB tethering. ' + err.message);
    retryTimer = setTimeout(connect, 2000);
  } finally { connecting = false; }
}

video.addEventListener('error', () => showError('H.264 decoder error. Restart capture in Compatibility (MJPEG) mode.'));
video.addEventListener('playing', () => { showPicture(); showStatus('Live', true); });
function countFrames() {
  renderedFrames++;
  video.requestVideoFrameCallback(countFrames);
}
if (video.requestVideoFrameCallback) video.requestVideoFrameCallback(countFrames);
setInterval(() => {
  if (!jpeg.hidden) return;
  const now = performance.now();
  fpsLabel.textContent = Math.round(renderedFrames * 1000 / (now - lastFpsTime)) + ' FPS';
  renderedFrames = 0;
  lastFpsTime = now;
}, 1500);

document.getElementById('fullscreen').addEventListener('click', () => {
  if (document.fullscreenElement) document.exitFullscreen();
  else screen.requestFullscreen?.().catch(() => {});
});
document.getElementById('reconnect').addEventListener('click', connect);
connect();