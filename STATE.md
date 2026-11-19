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

## Known limitation
`jankyFrames` per layer is counted from the frame timeline, and a game does not
fill one. For a game the counters give the frame rate and the frame time, but
the jank count reads zero because there is nothing to count.

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
