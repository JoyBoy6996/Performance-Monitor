# FrameScope - State

**Branch:** fix/fps-overlay · **Package:** com.framescope.monitor · **Version:** 1.1.4 (code 6)

## What is being worked on
Real-time FPS of the app in front, in games as well as in ordinary apps. The game
frame rate stayed empty while every other app was measured.

## Root causes found so far (each one verified in code, not guessed)
1. The SurfaceFlinger layer list was walked with `for L in $(...)`, which splits a
   layer name at its spaces. Fixed with `while IFS= read -r L`.
2. A strict rule refused any layer whose name did not carry the package name. A game
   names its surface after its engine. Now the surface that drew last is used.
3. The Shizuku UserService cut every command output at 32 KB. A game with many
   layers produced 40-70 KB, so half of the dump was lost - the layers at the end,
   the ones that were drawing. Every layer now keeps only its newest 30 frames.
4. The frame counters of SurfaceFlinger were only a fallback and their `-enable`
   step had to succeed first, so the path never ran. They are now a source of
   their own, read with `--timestats -dump -clear` (one atomic window).
5. **The probe that actually worked was gone.** The first version of this app
   asked SurfaceFlinger for the latency of the layer that is on the screen -
   `dumpsys SurfaceFlinger --latency` without a layer name - and that is the
   only probe that finds a game, because a game names its surface after its
   engine. The rewrite asked for layers by package name only, and the frame
   rate of every game was empty. The probe is back.

   The same version also asked for *every* layer of the app and kept only the
   first rows of each one; the rewrite kept only the newest layers, and the
   layer that draws is not always the newest one.
6. **The frame counters were never read at all.** `metrics/TimeStats.kt` looked
   for `Total frames rendered:` and `Janky frames:`. That is the text of
   `dumpsys gfxinfo`, not of `dumpsys SurfaceFlinger --timestats`. What
   SurfaceFlinger really prints is in
   `TimeStatsHelper::TimeStatsLayer::toString()`:

   ```
   displayRefreshRate = 144 fps
   renderRate = 120 fps
   uid = 10234
   layerName = SurfaceView[com.pubg.imobile/...](BLAST)#0
   packageName = com.pubg.imobile
   totalFrames = 118
   droppedFrames = 1
   Jank payload for this layer:
   jankyFrames = 4
   averageFPS = 118.532
   ```

   The regex never matched, so the list of readings was always empty and the
   counters - the only source that works for a game - never produced a number.
   The unit test passed because its fixture was invented in the same wrong
   format. The parser now reads the real text, attributes a layer by its
   `packageName` or by the package inside its layer name, and never reads the
   global `totalFrames` of the legacy section, which belongs to the display.
   The counters are the primary source and are read cumulatively: the frame
   rate is the growth between two dumps. `-clear` is not used, because it
   deletes the counters of every layer that has stopped drawing - exactly the
   layer an idle game is measured on.
7. **The outer timeout was shorter than the work inside it.** One sample was
   cancelled after 3 s while the latency dump inside it was allowed 9 s, so the
   sample died before the counters got their turn. The budgets are ordered now:
   counters 4 s, latency 4 s, one sample 12 s.
8. Foreground detection (`dumpsys window` plus usage stats) ran on every poll,
   every 400 ms. A known answer is now trusted for one second.

## Accuracy, second pass (the number itself)
9. **The rate was measured over the gap between two polls.** A game at 120 fps
   presents 48 frames in 400 ms; one dump that arrives 20 ms late turns that
   into 123. The window is now one second - the newest dump of the last two
   seconds that is at least a second old - and the middle of the last three
   windows is reported, so one stray dump cannot move the number.
10. **A counter was identified by its layer name alone.** SurfaceFlinger's
    table is keyed by `{refreshRateBucket, renderRateBucket}` and, inside that,
    by `{uid, layerName, gameMode}` (`TimeStats::setPresentTime` ->
    `flushAvailableRecordsToStatsLocked`). A game that changes its frame rate
    gets a *new* counter that starts at zero while the old one stays in the
    dump. Comparing the two invents a rate neither saw. A reading now carries
    `uid` and a `key` of layer + uid + rates, and a baseline is found by that
    key.
11. **The layer-by-layer latency probe asked for a name with quotes in it.**
    The name was wrapped in single quotes and then in double quotes, so the
    argument SurfaceFlinger received began with a literal `'`, matched no
    layer, and the probe never returned a row. The single layer is now quoted
    once.
12. **The latency rows were read from the wrong end.** `FrameTracker::dumpStats`
    prints its 128 records oldest first, so `head -40` kept the oldest frames -
    the ones no longer presented. One layer now keeps the whole dump; a sweep
    of many layers keeps `tail -60`.
13. **The exact frame times are used when the build keeps them.** Once the
    counters name the layer, `--latency <layer>` is asked for that one layer.
    When it answers with fresh timestamps, the rate, the frame time and the
    1%/5% lows come from the frames themselves - the same data and the same
    arithmetic a desktop frame meter uses. When the build has deprecated the
    frame tracker (aconfig `deprecate_frame_tracker`, checked in
    `Layer::dumpFrameStats`), it answers with nothing and the counters remain.
14. A rate above the refresh rate SurfaceFlinger recorded for that layer is
    clamped to it. That rate is bucketed in 30 Hz steps
    (`REFRESH_RATE_BUCKET_WIDTH = 30`), so 144 Hz is printed as 150 and a
    correct 148 is never clipped.
15. **The overlay judged the frame rate against 45.** That setting is a warning
    threshold, not a frame rate any game is supposed to reach: on a 144 Hz
    phone every reading above 45 was green and the graph flattened at 56. The
    colour and the graph now use the display's own refresh rate, which is also
    the value the `REFRESH` row prints.
16. The `Raw probe` now prints the counters twice, two seconds apart, so the
    frames a layer really presented in that time can be counted by hand, and
    it prints the *last* rows of `--latency` with their row count instead of
    the first ones. That is the evidence needed to tell whether this phone
    keeps the frame timestamps at all.
17. **Every `--latency` call asked for a layer that does not exist.** This was
    the bug behind "every layer answers with one row", and it is proven in
    AOSP `android16-release`, not guessed:

    ```cpp
    // SurfaceFlinger::dumpStats()
    StringAppendF(&result, "%" PRId64 "\n", mScheduler->getPacesetterVsyncPeriod().ns());
    if (args.size() < 2) return;

    const auto name = String8(args[1]);
    traverseLegacyLayers([&](Layer* layer) {
        if (layer->getName() == name.c_str()) {   // exact, character for character
            layer->dumpFrameStats(result);
        }
    });
    ```

    A name that does not match prints nothing but that first line - the
    pacesetter vsync period - and no frames. On a 144 Hz phone that line is
    `6944444`, which is exactly what every probe of every layer answered with,
    including layers that were visibly animating. The name was wrong because
    `--list` does not print names:

    ```cpp
    // RequestedLayerState::getDebugString()
    debug << "RequestedLayerState{" << name;
    if (parentId != UNASSIGNED_LAYER_ID) debug << " parentId=" << parentId;
    if (!handleAlive) debug << " !handle";
    if (z != 0) debug << " z=" << z;
    ...
    debug << "}";
    ```

    The descriptor was handed over whole, so the wrapper and the attributes
    became part of the name. They are now removed (`surfaceLayerName`, tested
    against the exact lines the phone printed) and the creation stamp inside
    the name is kept, because it is part of it.
18. A window in which the counters stand still is no longer published as the
    display rate straight away. A game owns several surfaces, so the counters
    can name a still one while another draws; the app's own layers are asked
    for their timestamps first (every three seconds, so an idle screen stays
    quiet), and only then is the display rate shown. This is the case that
    used to read 144 inside a game running at 90.

## Known limitation
`jankyFrames` per layer is counted from the frame timeline, and a game does not
fill one. When the build has deprecated the frame tracker, `--latency` of a
game is empty as well, so the counters give the frame rate and the frame time
but the jank count reads zero. When the frame tracker is there, the exact frame
times give jank and the 1%/5% lows as well.

## Verification on the phone
Nothing here can be verified in the sandbox - there is no device. The phone has to
answer two things:
- The Monitor tab, "Frame source" panel, after a game was in front.
- The Monitor tab, "Raw probe" button, which prints what SurfaceFlinger answers.
  It now asks for `--timestats -enable` and prints the first three `layerName`
  blocks, which is the text the parser reads. It also prints the row count of
  `--latency` for the counters' layer name (about 127 rows when the name is
  right, 1 row when it is not) next to the row count for the `--list`
  descriptor, so the wrong way and the right way sit side by side in one text.

## Rules of this repo
- `git pull --rebase origin fix/fps-overlay` before a commit and before a push.
- Never force push. No merge commits.
- Every change keeps a test. One build is one commit.

## 1.1.8 - two numbers that no screen could show

Both were shown on the phone while the frame rate of a game was still missing:

1. **298 FPS on a 144 Hz panel.** The `gfxinfo` path published its number without
   any limit. Every other source is limited to what the screen can present; that
   path was not, and a timestamp that was read wrong became a frame rate. The
   rule now lives in one place (`isPlausibleFps`) and every source has to pass it.
2. **A column that moves twice per refresh period.** The latency dump holds three
   columns. One of them advances twice per refresh period - it is the other column
   of the same row, not the frame time. Read as a frame time it gives twice the
   frame rate of the screen. A column that is faster than the screen can present is
   now refused, and the next column is read.

## Rules of this repo
- `git pull --rebase origin fix/fps-overlay` before a commit and before a push.
- Never force push. No merge commits.
- Every change keeps a test. One build is one commit.

## 1.1.9 - the app is built for Android 16

`compileSdk` and `targetSdk` are 36 now, and the build asks the runner for platform
36 and build-tools 36. The app was built for API 35 while the phone runs Android 16.

## 1.1.10 - the number was the display's, not the game's

On the phone the overlay showed **144 FPS** while the game was set to 120 and the
phone's own frame meter showed **121**. 144 is the refresh rate of the panel: the
counters had not grown, and a layer that stands still is answered with the refresh
rate of the screen.

Two reasons, both fixed here:

1. **The dump of the counters was cut short.** It holds a block for every layer of
   the device - 434 of them on this phone - and that answer does not fit in the time
   a measurement may take. A cut dump has lost the layers that come last, the ones
   that were drawing, and what was left named an idle layer. The blocks of the app in
   front are now picked out in the shell, where the text never travels, and the
   shell prints an end marker: a dump without it was cut and is refused.
2. **A layer of somebody else was answered with the refresh rate of the screen.** The
   refresh rate is only shown when the app in front is the one that is standing
   still. A game at 120 fps must not read as 144.

The counters of the whole device are still asked for every few seconds, for a game
that names its surface after its engine and not after its package.

## 1.1.11 - the recorder took the app down with it

The app crashed on the phone with
`SecurityException: Unable to start service Intent { cmp=com.framescope.monitor/.recorder.ScreenRecorderService }`.
Android 14 and later refuse a foreground service that captures the screen when the
app does not hold `android.permission.PROJECT_MEDIA`. The service then never
promotes itself, the system takes it away after five seconds, and the whole app
dies with it - and with it the frame rate the user came for.

- The permission is declared now.
- A refusal is caught: the service stops quietly instead of killing the app.
- The raw probe prints the counters of the app first, because they are the text
  that decides the frame rate of a game.

## 1.1.12 - the app measured the launcher while the game was in front

The overlay showed **144 FPS** while the game was set to 120 and the phone's own
frame meter showed 91. The probe named `com.android.launcher3` as the app that had
been measured last - and the launcher really does run at 144, so the number was
right for the wrong app.

The window manager can name a window that is on the screen and not in front: an
overlay of another app, a launcher that keeps the focus while a game is drawn over
it. The usage access knows which app was in front a moment ago. The two answers
are now compared, and when they differ clearly the app that was used last is the
one that is measured. A small difference means nothing and the window manager
wins, because it is exact.

The diagnostics now carry the evidence: what the window manager answered, and the
counters the device printed the last time it was asked. A wrong number can now be
explained from the text and not from a guess.

## 1.2.10 - the prompt came back, and the hardware rows needed Shizuku

Three defects, each of them in the way the app reaches the phone.

### 1. The "Allow USB debugging?" dialog came back on every start

The phone shows that dialog for every key it has **not** seen. The app offered its
key once and remembered that only in memory, so the next start of the app offered
it again - and the phone asked again. A ROM that ignores "Always allow from this
computer" never stores the key in its own key store, so the stamp the app wrote
was never set and the loop never ended.

The fact that the key was handed over is now written to the app's own storage,
next to the key itself (`AdbKeyStore.keyWasOffered`). A restart of the app, a
reboot and a failed setup all find the flag and do not offer the key again. Only
an explicit action - "Set up bridge", "Forget key" - may ask the phone again.

### 2. The hardware rows needed Shizuku, although the bridge could answer them

The CPU, GPU, temperature, power and memory rows were read through Shizuku only.
The local ADB bridge was refused, because an unprivileged zero is a fake reading.

That was true for the app's own process, which cannot read the procfs and sysfs
nodes on Android 16 - but not for the bridge. `adbd` runs its commands as the
`shell` user (uid 2000), exactly like the Shizuku UserService, and reads the same
nodes. The bridge now answers the hardware reads as well. The app's own process
still never answers one.

### 3. A refused one-time permission was swallowed

`pm grant` can only hand out a permission that carries the `development` flag,
and `android.permission.DUMP` does - so the app's own shell can read the frame
timelines of every app without Shizuku, once the permission is granted.

The attempt was made once and never repeated, and the reason it failed was
dropped. A refusal - a ROM that blocks `pm grant` from the shell, or a moment
when the shell was not ready yet - was therefore invisible and permanent for the
life of the app. The attempt is now repeated after half a minute, and the reason
belongs in the status, so the app says why the one-time permission is not there.

### Also

- The reader of the app's own shell is given the time to empty the pipe. The
  counters of a game are hundreds of kilobytes, and a reader that was cut short
  lost exactly the layers that were drawing. A reader that is still busy now says
  so, instead of handing over a half answer that looks complete.
- An oversized answer from the UserService no longer throws the connection away.
  It is a bad answer, not a broken session.
- The build workflow asks for platform 36 and build-tools 36.0.0. It asked for 35
  while the app is built for Android 16, and a runner without platform 36
  preinstalled had nothing to compile against.

## 1.2.11 - one screen, one report, and no made-up readings

The details screen of a run showed the same numbers twice. Above the eleven Scene
cards sat a second summary: an "Average frame rate" card, a "Charts | Table"
switch, a "What happened" timeline and a "Details" heading. Two of the cards were
missing from the Scene list altogether - `FPS & Temperature(°C)` and
`Frame Time(ms)` - so the screen and the PDF of the same run did not agree.

### The screen and the report now carry the same eleven cards

- The duplicate block is gone. The screen is the eleven cards, from top to
  bottom, in the order of the PDF, and the two charts that were missing are
  back right after the game header.
- The report draws the name of the app with its version (`BGMI(4.7.0)`) and the
  real launcher icon, and the outer canvas keeps its 40px margin so the top
  labels of the device row are never clipped.

### A video in picture-in-picture can no longer take the name of the game

The run of BGMI was stored as `app.morphe.android.youtube`, because a video in
picture-in-picture holds the focus while the game is drawn behind it. The
foreground query now asks the activity dump for the task that is really
resumed, and a task that is pinned or freeform - and the system bars, the
launcher, the keyboard and the app itself - are never the answer.

Record is also often pressed a moment before the game takes the screen. For the
first twenty seconds of a run the name of the run follows the app that comes to
the front, so the report names the game and not the app that was in front when
the button was pressed.

### The Shell Logs card no longer prints a line of placeholders

`shell_uid=-- | platform=-- | thermal_zone=-- | clusters=--` stood next to real
numbers, and a section heading with `--` under it looked like a measurement. A
line of placeholders is not a reading: the identity line is printed only when
the shell really answered, a section of the log is printed only when it carries
a real reading, and the rows of the Device Detail that have no reading are
left out instead of printing a page of `--`.

### The load and the temperature survive Android 16

Android 16 hides `/proc/stat` from the shell on some builds, and the load of
the device then stayed at zero. The system service is asked instead - `top` and
`dumpsys cpuinfo` - and the load of the game itself is stored with the run. The
CPU temperature falls back to the thermal service, which names the CPU sensor
with its type, when the sysfs zones are hidden. The chip temperature is stored
in both `cpuTempC` and `socTempC`, and the battery temperature stays the
battery's own reading.

The collector was already running on the privileged shell (`uid=2000`, through
the Shizuku UserService, never `Runtime.exec`) on the 1000ms tick; what was
missing was the fallback, not the call.
