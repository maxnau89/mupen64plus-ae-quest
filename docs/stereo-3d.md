# Idea: real stereoscopic 3D for N64 games

Dolphin VR gives GameCube and Wii games a per-eye view they never had: it takes the projection
matrix the game hands the GPU, shifts the camera left and right, and renders the scene twice. The
result is genuine depth, not a flat picture on a big screen.

The same trick should be possible on the N64. This note records what I found while looking for the
hook points, so the work can start from something concrete instead of from scratch.

## Why the N64 is a good candidate

The N64 has no hardware transform stage that a plugin has to reverse engineer. GLideN64 emulates
the RSP vertex pipeline **on the CPU**, and it keeps the matrices the game pushed:

- `gSP.matrix.projection` and `gSP.matrix.modelView[]` in
  [`gSP.h`](../mupen64plus-video-gliden64/upstream/src/gSP.h)
- `_gSPCombineMatrices()` in [`gSP.cpp`](../mupen64plus-video-gliden64/upstream/src/gSP.cpp)
  multiplies them into `gSP.matrix.combined`
- `gSPProcessVertex()` transforms every vertex with that matrix before anything reaches the GPU

So there is exactly one place where a per-eye offset has to be applied. That is the part Dolphin had
to fight for, and here it is already in plain sight.

The camera offset itself is a shear on the combined matrix rather than a translation of the eye:
shifting the eye sideways also has to shift the projection centre, otherwise the two images
converge at infinity and the picture hurts. Screen-space geometry (`G_TEXRECT`, anything drawn
with `gSPTextureRectangle`) must be left alone.

## The hard part: drawing the frame twice

Dolphin can replay the command FIFO. GLideN64 has no equivalent: the display list is walked once per
frame by `RSP_ProcessDList()` in
[`RSP.cpp`](../mupen64plus-video-gliden64/upstream/src/RSP.cpp), driven by the core.

Two ways out:

**Replay the display list.** The list lives in RDRAM and the CPU does not run while the plugin
works, so walking it a second time should see identical data. The risk is the side effects: DP
commands, framebuffer emulation writing back into RDRAM, depth buffer copies, the microcode state
set up at the top of `RSP_ProcessDList`. A second pass would need those suppressed, and the
framebuffer would have to be switched between the passes. This is the honest route to real stereo.

**Reproject from depth.** Render once, keep the depth buffer, warp the image into two eyes. Much
cheaper and entirely inside our own code, but everything the camera could not see stays hidden, so
edges smear. It looks like depth, it does not hold up to head movement.

## What it would need on our side

The VR module is ready for it. `quest_xr.cpp` already creates per-eye swapchains and submits a
stereo projection layer for the 3D controller, so a stereo game view needs a second game quad or a
projection layer fed from two textures rather than new plumbing.

## Suggested order

1. **Prove the matrix hook.** Apply a fixed shear to `gSP.matrix.combined` and render one eye. If
   the whole scene shifts but the HUD stays put, the hook point and the 2D exclusion are right.
2. **Find out whether the list can be replayed.** Call the walk twice into two framebuffers with the
   side effects disabled, and see which games survive. This decides whether the idea is real.
3. **Wire it into the VR layer** and add a convergence and separation setting, because the right
   values differ per game.
4. **Measure.** Two passes double the graphics cost. N64 emulation is cheap on a Quest 3, but
   framebuffer effects are not.

Step 2 is where this lives or dies. Worth finding out early, before building anything around it.
