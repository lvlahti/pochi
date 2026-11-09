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
const audioButton = document.getElementById('audioToggle');
const volumeControl = document.getElementById('volumeControl');
const volumeSlider = document.getElementById('audioVolume');
const volumeValue = document.getElementById('volumeValue');
const audioLabel = document.getElementById('audioStatus');
const token = new URLSearchParams(location.search).get('token');
const suffix = '?token=' + encodeURIComponent(token || '');
let socket = null;
let audioSocket = null;
let media = null;
let buffer = null;
let objectUrl = null;
let chunks = [];
let retryTimer = null;
let renderedFrames = 0;
let lastFpsTime = performance.now();
let attemptId = 0;
let audioAvailable = false;
let audioEnabled = false;
let audioContext = null;
let audioGain = null;
let audioPlayHead = 0;

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
  if (audioSocket) { const old = audioSocket; audioSocket = null; old.onclose = null; old.close(); }
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
  audioPlayHead = 0;
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
    showError('Browser does not support this H.264 codec. Use Chrome/Edge or MJPEG mode.');
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
      buffer.addEventListener('error', () => showError('H.264 playback failed. Try MJPEG mode.'));
      pump();
    } catch (err) { showError('Cannot initialize video: ' + err.message); }
  }, { once: true });
}

function audioSamples(arrayBuffer) {
  if (!audioContext || audioContext.state !== 'running' || !audioEnabled) return;
  const view = new DataView(arrayBuffer);
  const samples = Math.floor(view.byteLength / 2);
  if (!samples) return;
  const sound = audioContext.createBuffer(1, samples, 48000);
  const channel = sound.getChannelData(0);
  for (let i = 0; i < samples; i++) channel[i] = view.getInt16(i * 2, true) / 32768;
  const source = audioContext.createBufferSource();
  source.buffer = sound;
  source.connect(audioGain || audioContext.destination);
  const now = audioContext.currentTime;
  if (!audioPlayHead || audioPlayHead < now || audioPlayHead > now + .45) {
    audioPlayHead = now + .14; // keep queue short, drop late audio instead of accumulating lag
  }
  source.start(audioPlayHead);
  audioPlayHead += sound.duration;
}

function connectAudio() {
  if (!audioAvailable || !audioEnabled || !audioContext || audioSocket) return;
  const ws = new WebSocket('ws://' + location.host + '/audio' + suffix);
  ws.binaryType = 'arraybuffer';
  audioSocket = ws;
  audioLabel.textContent = 'Connecting audio…';
  ws.onopen = () => { audioLabel.textContent = 'Internal audio on'; audioPlayHead = 0; };
  ws.onmessage = event => { if (event.data instanceof ArrayBuffer) audioSamples(event.data); };
  ws.onclose = () => {
    if (audioSocket === ws) audioSocket = null;
    audioLabel.textContent = audioEnabled ? 'Audio disconnected — reconnect' : 'Audio off';
  };
  ws.onerror = () => { audioLabel.textContent = 'Audio unavailable'; showError('Audio connection error. Check the local network.'); };
}

function updateVolume() {
  const percent = Math.max(0, Math.min(100, Number(volumeSlider.value) || 0));
  volumeValue.textContent = percent + '%';
  volumeSlider.setAttribute('aria-valuetext', percent + '%');
  if (audioContext && audioGain) {
    audioGain.gain.setTargetAtTime(percent / 100, audioContext.currentTime, .015);
  }
}
volumeSlider.addEventListener('input', updateVolume);
updateVolume();

audioButton.addEventListener('click', async () => {
  if (!audioAvailable) return;
  if (audioEnabled) {
    audioEnabled = false;
    audioButton.textContent = 'Enable audio';
    audioButton.setAttribute('aria-pressed', 'false');
    volumeControl.hidden = true;
    audioLabel.textContent = 'Audio off';
    if (audioSocket) { audioSocket.close(); audioSocket = null; }
    if (audioContext) await audioContext.suspend();
    return;
  }
  try {
    if (!audioContext) {
      const AudioCtx = window.AudioContext || window.webkitAudioContext;
      if (!AudioCtx) throw new Error('Web Audio is not supported');
      audioContext = new AudioCtx();
    }
    if (!audioGain) {
      audioGain = audioContext.createGain();
      audioGain.connect(audioContext.destination);
    }
    updateVolume();
    await audioContext.resume(); // Browser permits sound only after a user gesture.
    audioEnabled = true;
    audioButton.textContent = 'Mute audio';
    audioButton.setAttribute('aria-pressed', 'true');
    volumeControl.hidden = false;
    connectAudio();
  } catch (error) { showError('Cannot enable audio: ' + error.message); }
});

async function connect() {
  closeStream();
  const current = attemptId;
  showStatus('Connecting…', false);
  showError('');
  try {
    const response = await fetch('/status' + suffix, { cache: 'no-store' });
    if (current !== attemptId) return;
    if (!response.ok) throw new Error('Invalid viewer link or sharing has stopped');
    const data = await response.json();
    if (current !== attemptId) return;
    audioAvailable = !!data.audio;
    audioButton.hidden = !audioAvailable;
    volumeControl.hidden = !audioAvailable || !audioEnabled;
    audioLabel.textContent = audioAvailable ? (audioEnabled ? 'Connecting audio…' : 'Optional internal app audio') : 'Video only';
    if (audioEnabled) connectAudio();
    document.getElementById('transport').textContent = data.transport === 'wifi' ? 'Wi-Fi · local network' : 'USB tethering';
    if (data.mode === 'mjpeg') {
      modeLabel.textContent = 'Compatibility · MJPEG · up to ' + data.fps + ' FPS';
      video.hidden = true;
      jpeg.hidden = false;
      jpeg.onload = () => { showPicture(); showStatus('Connected', true); };
      jpeg.onerror = () => { showError('MJPEG interrupted. Reconnecting…'); retryTimer = setTimeout(connect, 1300); };
      jpeg.src = '/mjpeg' + suffix;
      showStatus('Receiving MJPEG…', true);
      fpsLabel.textContent = 'MJPEG';
      return;
    }
    modeLabel.textContent = 'H.264 · up to ' + data.fps + ' FPS';
    const ws = new WebSocket('ws://' + location.host + '/ws' + suffix);
    socket = ws;
    ws.binaryType = 'arraybuffer';
    ws.onopen = () => showStatus('Receiving H.264…', true);
    ws.onmessage = event => {
      if (typeof event.data === 'string') {
        try {
          const info = JSON.parse(event.data);
          if (info.type === 'init') configureMse(info.mime);
        } catch (error) { showError('Invalid video configuration: ' + error.message); }
      } else if (buffer || media) {
        if (chunks.length >= 100) chunks.splice(0, chunks.length - 50);
        chunks.push(event.data);
        pump();
      }
    };
    ws.onerror = () => showError('Video connection interrupted. Check your local network.');
    ws.onclose = () => {
      if (current !== attemptId) return;
      showStatus('Reconnecting…', false);
      retryTimer = setTimeout(connect, 1100);
    };
  } catch (error) {
    if (current !== attemptId) return;
    showStatus('Disconnected', false);
    showError('Cannot reach Pochi: ' + error.message);
    retryTimer = setTimeout(connect, 1800);
  }
}

video.addEventListener('error', () => showError('H.264 decoder error. Try MJPEG or lower resolution.'));
video.addEventListener('playing', () => { showPicture(); showStatus('Live', true); });
function countFrames() { renderedFrames++; video.requestVideoFrameCallback(countFrames); }
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