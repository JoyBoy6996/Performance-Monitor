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

## Verification on the phone
Nothing here can be verified in the sandbox - there is no device. The phone has to
answer two things:
- The Monitor tab, "Frame source" panel, after a game was in front.
- The Monitor tab, "Raw probe" button, which prints what SurfaceFlinger answers.

## Rules of this repo
- `git pull --rebase origin fix/fps-overlay` before a commit and before a push.
- Never force push. No merge commits.
- Every change keeps a test. One build is one commit.
