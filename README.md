# Pochi 🐾 — Android screen mirroring over USB or Wi-Fi

**Pochi v0.5** is a lightweight, native Android app that shares your phone screen with a browser on Windows, macOS, or Linux over a **USB tether network or your local Wi-Fi network**. You do not install anything on the viewing computer.

## What's new in v0.5

- **Monochrome UI:** both the Android app and the browser receiver use only black, white, and grayscale. Error messages are the only red UI elements.
- **Browser volume slider:** after clicking **Enable audio**, a 0–100% slider appears beside the button. The slider controls a Web Audio `GainNode` immediately and disappears when audio is muted.
- All v0.5 features—USB/Wi-Fi, rotation handling, custom video quality/FPS/bitrate, and optional internal audio—remain available.

## v0.5 — Glass Edition

The Android sender and browser receiver now share a polished monochrome design inspired by premium desktop and mobile controls. Both have layered panels, soft highlights, rounded controls and deliberate motion. The browser uses frosted-glass CSS backdrop blur; Android uses efficient native gradient drawables and light elevation (no expensive continuous background blur). Key Android actions use responsive press animations and settings cards enter with a gentle stagger. Audio controls still reveal the real 0–100% Web Audio volume slider after clicking **Enable audio**.

**Color rule:** black, white and grays only; red is reserved for error messages. Web transitions respect the reduced-motion accessibility preference.

## v0.5 — live controls, secure-by-default link sharing and connection settings

- **Live button states:** the Start live button becomes a green, disabled **You are live** indicator only after screen sharing succeeds and a browser URL is ready. A red **Stop live** button appears beneath it, and disappears when sharing stops. While Android capture permission is processing, the start button displays **Starting live…**.
- **Copy or share viewer link:** a polished link card appears only during a confirmed session. **Copy link** copies the entire URL (including its temporary access token) to the clipboard; **Share** opens Android's share sheet so you can send the same link to another app. Don't share the link outside your trusted USB/Wi-Fi network.
- **Network settings shortcut:** when USB is selected, attempts to open Android's tethering settings via `android.settings.TETHER_SETTINGS`; if unsupported it tries the system tethering activity, then Settings with the manual guidance `Portable hotspot → USB tethering`. When Wi-Fi is selected, the button opens Wi-Fi settings. Xiaomi/HyperOS may block deep links, so no third-party app can guarantee a direct jump to the USB tethering toggle.

The glass visual style, hardware-encoded H.264, optional MJPEG, local USB/Wi-Fi streaming, rotation and experimental internal audio remain in place.

## Features

- **USB or Wi-Fi transport:** The sender listens only on the selected Android interface. For USB, plug in a data cable and turn on USB tethering. For Wi-Fi, connect sender and viewer to the *same trusted local Wi-Fi network*.
- **Portrait ↔ landscape:** The existing Android VirtualDisplay is resized on phone rotation without requesting screen capture a second time. In H.264 mode, the browser automatically reconnects to a fresh video stream.
- **Custom settings:** 640/960/1280/1600/1920 maximum long edge; 15/24/30/60 FPS; H.264 bitrate from 750 to 10000 kbps; MJPEG quality 40–95%. Actual frame rates depend on phone hardware.
- **H.264 video:** Hardware AVC encoder → fragmented MP4 → WebSocket → browser MediaSource. Use Chrome or Edge for best support.
- **MJPEG video:** Alternate compatibility mode using JPEG over HTTP.
- **Optional experimental internal audio:** Android 10+ `AudioPlaybackCapture` captures audio from *participating* media/game apps (not the mic). Uncompressed 48 kHz mono PCM16 travels over a separate WebSocket and is played by browser Web Audio when you click **Enable audio**.
- **Local viewer authorization:** Each share session creates a random 128-bit token in its browser URL; accessing the screen, audio, or status without the token is refused.

## Setup

1. Install the Android APK on your phone (Android 8+). For optional internal audio, use Android 10+.
2. On the phone open Pochi and select your **connection**:
   - **USB:** Connect a data-capable cable to the viewer and enable Android **USB tethering**.
   - **Wi-Fi:** Make sure both devices are on the same **Wi-Fi** network, with no client isolation. Do not use mobile data alone.
3. Choose **H.264** or **MJPEG**, resolution, FPS, and relevant bitrate/quality. Optionally enable **Internal audio** and grant Android's recording permission.
4. Tap **Start sharing**, approve Android's screen capture dialog, and copy the **entire URL including `?token=...`**.
5. Open that URL in Chrome or Edge on the computer. For sound, click **Enable audio** in the viewer. Browser sound autoplay requires this user interaction.
6. Rotate the phone if desired. H.264 viewers should reconnect automatically. Stop sharing from Pochi when finished.

## Performance notes

- Defaults: 1280 max edge, 24 FPS, 2.5 Mbps H.264 video. Larger output, more frames and higher bitrates consume more CPU, battery and bandwidth.
- Internal audio is uncompressed mono PCM16 (approximately 0.77 Mbps). Sound and picture use **independent transport streams**. This is **experimental** and some audio/video sync skew is possible.
- This is a **local HTTP application**. The random token blocks casual access, **but local network traffic is not encrypted**. Only use trusted USB and Wi-Fi networks; anyone who intercepts the link or network traffic can access your stream.
- Protected video and audio (DRM, apps prohibiting capture, calls, microphone, system notification sounds) might be absent or black/silent.
- On Android 14+ select **Entire screen** for best rotation coverage; capturing one specific app may have different aspect ratio behavior.
- This app does **not** activate USB-C DisplayPort Alt Mode or a passive USB-C/HDMI adapter.

## Build

GitHub Actions → **Build and publish Pochi APK** runs `:app:testDebugUnitTest` and `:app:assembleDebug` (Gradle 8.11.1, Android Gradle Plugin 8.9.2, Kotlin 2.1.20, Android 35). Successful pushes to `main` publish a **preview GitHub Release APK** and the workflow artifact contains the debug APK. You can also open this project in Android Studio with JDK 17.

Debug builds are signed with Android's debug key (not intended as production-signed releases). Uninstall earlier APKs if they were signed with a different key.

## Troubleshooting

- **No USB address:** Enable USB tethering after connecting the USB data cable and verify that Windows sees a new USB Ethernet/RNDIS connection.
- **No Wi-Fi address:** Connect phone and viewer to the same Wi-Fi (not just cellular data). The Wi-Fi must permit device-to-device traffic; guest Wi-Fi and AP isolation often block it.
- **403 Forbidden:** Use the **full new session URL** containing `?token=...` from the app; old links stop working when you restart capture.
- **No sound:** Select Internal audio on Android before starting; grant recording permission; click Enable audio in the viewer. Some apps do not allow audio playback capture and remain silent.
- **Video stutters:** Lower resolution to 960, FPS to 24, and bitrate to 1500 kbps. MJPEG will use more CPU/bandwidth than H.264.
- **H.264 black picture after rotation:** Wait for automatic reconnect. If needed click Reconnect, or switch to MJPEG mode.

## Status

**v0.5 preview — CI build and unit tests; device-specific rotation/audio/Wi-Fi behavior should be tested on physical phones.** See `QUICKSTART-fa.md` for the Persian guide.

MIT License.