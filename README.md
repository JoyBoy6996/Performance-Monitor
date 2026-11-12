# FrameScope

A Scene-style FPS meter for Android: live frame rate, frame time, jank and device
telemetry on top of any app - plus session recording, a full stats breakdown and a
modern, responsive Material 3 interface.

FrameScope runs with **shell (adb) level privileges and never needs root**, so it
plays nicely with banking and security apps.

---

## Features

| Area | What you get |
|---|---|
| **Monitor tab** | Live FPS gauge, frame time, frame-time history graph, CPU / GPU / temperature / battery / RAM / power tiles, live session stats |
| **Overlay tab** | Floating overlay with 4 styles (Compact, Classic, Detailed, RTSS-style), selectable rows, opacity + scale sliders, graph height, drag to reposition, tap to collapse |
| **Sessions tab** | Recorded sessions with avg / max / min FPS, 1% low, 5% low, smoothness score, jank count, peak temperature, per-second graph, CSV export and share |
| **Tools tab** | Device info, screen test (colour patches + touch trail), top-process monitor, thermal sensor list, battery/power detail, shell console for running any command with the active backend |
| **Setup tab** | Backend selector, one-time ADB setup commands with copy buttons, banking app protection, sampling interval, smoothness target, theme control |
| **Extras** | Quick settings tile, foreground app detection, auto-hide overlay inside banking apps, per-session CSV/JSON storage |

Data sources: `dumpsys SurfaceFlinger --latency` (true presented-frame timestamps,
so the graph is frame accurate), `dumpsys gfxinfo` as a fallback, `dumpsys battery`,
`dumpsys thermalservice`, `/proc/stat`, `/proc/meminfo`, `/sys` CPU and GPU clocks.

The latency parser auto-detects both the two-column and the three-column
`SurfaceFlinger` layouts by scoring how tightly the frame deltas cluster, so it
works on Android 8 through 15 without version checks.

---

## One-time setup

Pick **one** of the three routes below. All of them are a single command.

### Route A - Shizuku (recommended)

Shizuku gives FrameScope the `shell` user, exactly the level aShell / aShell You
work at. This is the most compatible option with banking apps.

1. Install Shizuku (F-Droid or the official release).
2. Start it once from a PC:
   ```bash
   adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/tools/start.sh
   ```
   Using **aShell You** instead of a PC? Open it, switch to *Wireless debugging*
   mode and run the same script on the device:
   ```bash
   sh /sdcard/Android/data/moe.shizuku.privileged.api/tools/start.sh
   ```
3. In FrameScope open **Setup -> Request Shizuku permission** and approve.

### Route B - Local ADB bridge (no extra app at all)

1. Enable *Developer options -> USB debugging* and connect the phone over USB.
2. Run once from a PC:
   ```bash
   adb tcpip 5555
   ```
3. Approve the one-time *"Allow USB debugging?"* prompt on the phone.

FrameScope then talks the ADB wire protocol to `adbd` over `127.0.0.1:5555`.
The port resets after a reboot, so re-run the command then. Route B works on
devices where `ro.adb.secure=0` without any prompt at all.

### Route C - Root

If the device is rooted, FrameScope detects `su` on its own. Root is the option
most likely to be flagged by banking apps, so prefer A or B.

---

## Banking app compatibility

Banking apps check the device for root, hooking frameworks, accessibility
services and device-admin rights. FrameScope is deliberately built so those
checks stay green:

* no root, no Magisk, no LSPosed, no Zygisk;
* **no AccessibilityService** and no DeviceAdminReceiver;
* no hooking or TLS interception of any kind;
* only the `shell` user, which is the same identity `adb shell` uses;
* the floating overlay hides itself automatically when a banking, payment or
  security app is in the foreground (pre-populated list plus a live detector
  that scans the installed packages), so those apps never get a window on top.

---

## Building

Requirements: JDK 17, Android SDK with platform 35 and build-tools 35.0.0.

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

A debug build is unsigned-by-default-in-debug variant; install it with:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Project layout

```
app/src/main/java/com/framescope/monitor/
├── core/          settings, models, banking app protection
├── shell/         Shizuku / ADB-TCP / root backends, command parsers
├── metrics/       FPS sampler, system sampler, session store, engine
├── overlay/       foreground overlay service and overlay widgets
└── ui/            Compose screens (Monitor, Overlay, Sessions, Tools, Setup)
```

## License

MIT.
