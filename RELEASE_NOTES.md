# Pochi v0.4 — Glass Edition

A visual refresh of the Android sender and browser receiver. The same USB/Wi-Fi, rotation, H.264/MJPEG, custom quality/FPS, and experimental internal audio capabilities from v0.3 are retained.

## What's new

- A monochrome, Apple-inspired visual language with translucent layered cards, glossy buttons, soft shadows, rounded corners, and subtle highlights.
- Reorganized native Android settings into four clear stages: Connection, Video Studio, Sound Space, and Go Live.
- Native pressed-state animation for key actions and a staggered entry motion for panels.
- Browser viewer with frosted-glass panels (CSS backdrop blur), responsive control islands, more readable stream telemetry, and subtle orb/status animations.
- Existing browser audio volume slider remains next to Enable audio, and applies the volume to Web Audio's GainNode.
- Every UI color is black, white, or gray; **red is only used for error states**.
- Reduced-motion preference respected by the web UI.

## Technical notes

Browser frosted glass is implemented with `backdrop-filter`, which gracefully degrades when unavailable. Native Android panels use hardware-friendly layered gray gradients, strokes, and elevation rather than expensive real-time background blur. Only UI files and the APK version change; the transport and capture implementations are unchanged.

Android debug APKs built by GitHub Actions are for testing. If updating an older debug APK fails, uninstall the older debug APK first because GitHub build signing keys can differ. Capturing protected DRM content and internal audio from nonparticipating apps remains unsupported. Test behavior on your device.