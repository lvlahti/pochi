# Pochi

Pochi mirrors an **Android screen to a Windows browser with a USB data cable**, using USB tethering for local network communication. No receiver installation, internet, or Wi-Fi is required.

## Quick start

1. Connect your Android phone to Windows using a USB *data* cable.
2. Enable **USB tethering** on the phone (Settings → Portable hotspot → USB tethering on Xiaomi/MIUI; menu names vary).
3. Launch **Pochi**, select **Balanced (H.264)** and tap **Start sharing**.
4. Accept Android's screen-capture permission.
5. Open the address shown in Pochi in **Chrome** or **Edge** on the PC. (Port 8822.)
6. Tap **Stop sharing** when finished. If the browser can't play H.264, stop and restart in **Compatibility (MJPEG)** mode.

## Design

- **Native Kotlin Android**, no third-party runtime libraries.
- **Balanced**: MediaProjection → hardware MediaCodec AVC encoder → fragmented ISO BMFF/MP4 (MSE) via WebSocket → HTML5 video in Windows browser. Target ~24 FPS; max long dimension 1280px; ~2.5 Mbps.
- **Compatibility**: MediaProjection → ImageReader → JPEG → multipart MJPEG browser stream. Target ~10 FPS, more CPU/bandwidth.
- Embedded HTTP server listens **only on Android's USB tethering IPv4 interface** (`rndis*`, `usb*`, `ncm*`). On phones that use other interface names, update `UsbNetwork.kt` and rebuild.
- No cloud, storage, Wi-Fi, root, PC software, or permanent screen-recording permission. A foreground notification is visible during capture.

**Limitations:** video only (no audio), no touch/keyboard control of Android, DRM video may be black. This is an **experimental prototype**; hardware H.264 paths need testing on the actual Redmi Note 12 Pro 5G. A regular USB-C→HDMI adapter is not made functional by this app.

## Build APK

**Option A — GitHub Actions:** Upload this project to `lvlahti/pochi`, open **Actions → Build Android APK → Run workflow**, then download the `pochi-debug-apk` artifact after it succeeds.

**Option B — Android Studio:** Open project using JDK 17 and Android SDK 35. Sync Gradle and choose **Build → Build APK(s)**. Command-line build uses **Gradle 8.11.1** (`gradle :app:assembleDebug`); Android Gradle Plugin 8.9.2, Kotlin 2.1.20.

The project omits Gradle wrapper binaries (as they are binary artifacts); GitHub Actions installs Gradle automatically. Build prerequisites are downloaded by Gradle/Android Studio.

## Security and privacy

Only start sharing with a trusted PC connected over USB. Everyone with access to the local USB tether link and the URL can view the unencrypted stream while capture is on. There is no authentication or encryption on that local link. There is no external network upload. The user must consent to each Android screen-capture session.

## Troubleshooting

- **No URL:** enable USB tethering *before* launching capture; confirm the Windows USB Ethernet adapter appears, then restart sharing.
- **Can't open address:** make sure the Windows PC is connected to the tether network, Chrome/Edge uses the URL shown on the phone (not `localhost`).
- **Playback stalls:** stop capture, switch to **Compatibility**, and grant capture consent again.
- **USB tethering requires mobile data:** some ROMs restrict the toggle; Pochi doesn't need internet, but it cannot bypass OS tethering restrictions.

License: MIT.
