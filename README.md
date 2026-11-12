# FrameScope

A Scene-style FPS meter for Android: live frame rate, frame time, jank and device
telemetry on top of any app - plus session recording, a full stats breakdown and a
modern, responsive Material 3 interface.

FrameScope runs shell commands exclusively through the official Shizuku UserService API.
Privileged access is temporary and is available only while Shizuku is running.

---

## Features

| Area | What you get |
|---|---|
| **Monitor tab** | Live FPS gauge, frame time, frame-time history graph, CPU / GPU / temperature / battery / RAM / power tiles, live session stats |
| **Overlay tab** | Floating overlay with 5 styles (Pill, Compact, Classic, Detailed, RTSS-style), selectable rows, opacity + scale sliders, graph height, drag to reposition, tap to collapse |
| **Sessions tab** | Recorded sessions with avg / max / min FPS, 1% low, 5% low, smoothness score, jank count, peak temperature, per-second graph, CSV export and share |
| **Tools tab** | Device info, screen test (colour patches + touch trail), top-process monitor, thermal sensor list, battery/power detail, shell console for running commands through Shizuku |
| **Settings tab** | Shizuku connection status, permission request button, banking app protection, sampling interval, smoothness target, theme control |
| **Extras** | Quick settings tile, foreground app detection, auto-hide overlay inside banking apps, per-session CSV/JSON storage |

Data sources: `dumpsys SurfaceFlinger --latency` (true presented-frame timestamps,
so the graph is frame accurate), `dumpsys gfxinfo` as a fallback, `dumpsys battery`,
`dumpsys thermalservice`, `/proc/stat`, `/proc/meminfo`, `/sys` CPU and GPU clocks.

---

## Architecture: Shizuku UserService

FrameScope uses the official Shizuku UserService API for privileged shell commands:
* **Only Shizuku UserService**: No alternate privileged execution path.
* **Verified connection**: Readiness requires a successful command through the connected UserService.
* **Isolated execution state**: Each command has its own execution ID, output streams, timeout, and cancellation.
* **Lifecycle-aware access**: Permission, Shizuku Binder, UserService connection, and command verification are tracked independently.
* **Temporary shell access**: Android permission grants and Shizuku shell access are distinct; shell access ends when Shizuku stops.

---

## Setup

1. Install and start [Shizuku](https://shizuku.rikka.app/) using its official guide for your device and Android version.
2. Open FrameScope and grant Shizuku permission in **Settings** when prompted.
3. Wait for the UserService connection and command verification to complete. A prior successful setup is remembered, but shell access is unavailable whenever Shizuku is stopped; start Shizuku again and FrameScope reconnects.

### Troubleshooting

If privileged features are unavailable, check that Shizuku is running, grant FrameScope permission in Shizuku, and use **Settings -> Check status**. FrameScope reports permission, Binder, UserService, and command-verification status separately. Restarting Shizuku restores temporary shell access; it does not persistently grant Android permissions.

---

## Banking App Compatibility

Banking apps check the device for root, hooking frameworks, accessibility
services and device-admin rights. FrameScope is deliberately built so those
checks stay green:

* No root, no Magisk, no KernelSU, no APatch;
* **No AccessibilityService** and no DeviceAdminReceiver;
* No hooking or TLS interception of any kind;
* Only the standard Android `shell` user provided temporarily by Shizuku;
* The floating overlay hides itself automatically when a banking, payment or
  security app is in the foreground (pre-populated list plus a live detector
  that scans the installed packages).

---

## Building

Requirements: JDK 17, Android SDK with platform 35 and build-tools 35.0.0.

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

Run unit tests with `./gradlew testDebugUnitTest`.

---

## Project Layout

```
app/src/main/
├── aidl/com/framescope/monitor/shell/   IShizukuService.aidl interface
├── java/com/framescope/monitor/
│   ├── core/          settings, models, banking app protection
│   ├── shell/         Shizuku UserService implementation, runner, commands, parsers
│   ├── metrics/       FPS sampler, system sampler, session store, engine
│   ├── overlay/       foreground overlay service and overlay widgets
│   ├── recorder/      screen recorder service
│   └── ui/            Compose screens (Monitor, Overlay, Sessions, Tools, Settings)
```

## License

MIT.
