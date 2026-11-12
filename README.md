# FrameScope

A Scene-style FPS meter for Android: live frame rate, frame time, jank and device
telemetry on top of any app - plus session recording, a full stats breakdown and a
modern, responsive Material 3 interface.

FrameScope runs with elevated **shell privileges exclusively through Shizuku UserService**
and never needs root, so it plays nicely with banking and security apps.

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

FrameScope uses the official Shizuku UserService API to execute privileged shell commands:
* **Pure Shizuku Architecture**: No ADB daemon, no local TCP bridge, no root/su fallbacks.
* **Privileged UserService Process**: Spawns an isolated `ShizukuUserService` via Shizuku's server running as UID 2000 (`shell`).
* **Robust Command Execution Layer**: Supports stdout, stderr, exit codes, watchdog timeouts, cancellation cleanup, and execution verification.
* **Automatic Lifecycle Management**: Handles binder reception, binder death, permission checks, and reconnection seamlessly.

---

## Setup

1. Install [Shizuku](https://shizuku.rikka.app/) (available on GitHub or Google Play).
2. Start Shizuku:
   * **Via Wireless Debugging (Android 11+)**: Open the Shizuku app, tap "Pairing" under Wireless Debugging, enter the pairing code, and tap "Start".
   * **Via ADB from a computer**:
     ```bash
     adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/tools/start.sh
     ```
3. Open FrameScope, go to **Settings -> Request permission**, and allow access.

---

## Banking App Compatibility

Banking apps check the device for root, hooking frameworks, accessibility
services and device-admin rights. FrameScope is deliberately built so those
checks stay green:

* No root, no Magisk, no KernelSU, no APatch;
* **No AccessibilityService** and no DeviceAdminReceiver;
* No hooking or TLS interception of any kind;
* Only the standard Android `shell` user provided by Shizuku;
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
