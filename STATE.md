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
  blocks, which is the text the parser reads.

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
