# Stereoscopic 3D for N64 games

Dolphin VR gives GameCube and Wii games a per-eye view they never had: it takes the projection
matrix the game hands the GPU, shifts the camera left and right, and renders the scene twice. The
result is genuine depth, not a flat picture on a big screen.

This does the same for the N64. **It works**, as an experimental per ROM setting. This note is both
the design record and the handover: what is built, what is verified, and what is left.

## Status

Working end to end on the Android emulator with Super Mario 64: the display list is walked once per
eye, both eye views come out side by side in one image, and the VR layer hands each eye its half.
Verified from plugin logs and from a side-by-side screenshot with visible, depth-dependent parallax.

**Not yet tried in the headset.** Everything below the plugin has only been seen on a flat screen.

## How it works

**The shear.** GLideN64 emulates the RSP vertex pipeline on the CPU and keeps the matrices the game
pushed, so there is one place where an eye offset has to be applied:

- `gSP.matrix.projection` and `gSP.matrix.modelView[]` in
  [`gSP.h`](../mupen64plus-video-gliden64/upstream/src/gSP.h)
- `_gSPCombineMatrices()` multiplies them into `gSP.matrix.combined`
- `gSPProcessVertex()` transforms every vertex with that matrix before anything reaches the GPU

`gSPApplyStereo()` in [`gSP.cpp`](../mupen64plus-video-gliden64/upstream/src/gSP.cpp) shears that
matrix:

```
x' = x + separation * (w - convergence)
```

which is the eye offset plus the matching frustum shear. Geometry at the convergence depth does not
move, nearer geometry gets crossed parallax, distant geometry approaches a constant offset — the
same behaviour as a real stereo camera. Vertices are transformed as a row vector, so x reads column
0 and w reads column 3.

Screen space geometry (`G_TEXRECT` and friends) never reaches the vertex transform, so HUDs and 2D
overlays are excluded for free. Confirmed: Super Mario 64's life counter and "PRESS START" stay on
identical pixels in both eyes.

**The double walk.** `RSP_ProcessDList()` in
[`RSP.cpp`](../mupen64plus-video-gliden64/upstream/src/RSP.cpp) walks the list twice when the mode is
*Both eyes*. Nothing had to be suppressed for the replay: the list lives in RDRAM, the CPU does not
run in between, and the setup block at the top of each walk resets the PC, the matrix stack and the
geometry mode from DMEM, so the second walk starts from the same state as the first.

**Keeping both eyes.** Both walks draw into the same N64 frame buffer, so
[`StereoFrames`](../mupen64plus-video-gliden64/upstream/src/StereoFrames.cpp) blits that buffer into
its own texture after the first walk. `FrameBufferList::renderBuffer()` then draws the pair side by
side: the kept left eye into the left half of the output, the live buffer, which is the right eye,
into the right half.

**The VR layer.** `quest_xr.cpp` submits the game quad twice when stereo is on, once per eye, using
`XrCompositionLayerQuad::eyeVisibility` and a `subImage.imageRect` covering one half of the image.
The quad keeps its normal size, so each half is stretched back to full width. No extra swapchain is
needed. `QuestXr.setStereoGame()` turns it on, called from `GameActivity` when the ROM's stereo mode
is *Both eyes*.

## The trap that cost the most time

**The app's default video plug-in is Glide64mk2, not GLideN64.** All of this lives in GLideN64, so
for a long while none of it ran and every test looked like "nothing happens" — including the first
report from the headset. Worse, an early screenshot was misread as proof that the shear worked when
it was really just what Super Mario 64's title screen looks like.

`GamePrefs` now selects GLideN64 whenever a ROM's stereo mode is not off, overriding the emulation
profile. If you change anything here, check the log line
`CoreInterface: Using plugin for type: M64PLUGIN_GFX:` before trusting any visual result.

## The settings

Per ROM, under **Stereoscopic 3D (experimental)** in a game's settings:

| Setting | Meaning |
|---|---|
| Eye | Off, Left eye, Right eye, or Both eyes (double pass) |
| Eye separation | Parallax at infinity, in thousandths of a clip unit. 0..120, default 25 |
| Convergence depth | Depth that keeps zero parallax, in the game's own units. 0..5000, default 500 |

The single-eye modes render one view for both eyes. They give no depth and exist for tuning: switch
between left and right and watch how far things move.

Convergence is in the same units as the clip space w, which on the N64 is whatever scale the game
chose for its world — hundreds in some games, thousands in others. That is why it is per ROM, and
why the first version's 1..40 range was useless.

Config options reaching the plugin: `StereoMode`, `StereoSeparation`, `StereoConvergence` in the
`Video-GLideN64` section, written by `NativeConfigFiles`.

## What is left

1. **Try it in the headset.** Nothing below the plugin has been seen in VR. The eye assignment may
   well be swapped — if depth looks inverted, swap `XR_EYE_VISIBILITY_LEFT` and `RIGHT` in
   `quest_xr.cpp`, or the sign in `gSPApplyStereo()`.
2. **Measure the cost.** Two walks double the graphics work and the vertex transform runs on the
   CPU. Untested.
3. **Half horizontal resolution.** Both eyes share one image, so each gets half the width. Giving
   the game surface double width would fix it, at the cost of touching the aspect ratio handling in
   `renderBuffer()`.
4. **Try more games.** Only Super Mario 64 so far. Games that lean on framebuffer effects, or write
   back to RDRAM mid-frame, may not take a second walk so quietly.
5. **`ZSortBOSS` is not hooked.** It can store the combined matrix back to RDRAM, and a sheared
   matrix written into the game's own memory would corrupt its state. It needs the unsheared matrix
   kept alongside first.
6. **Per game defaults.** Good separation and convergence values differ wildly. A small table keyed
   by ROM header name, like GLideN64's own `GLideN64.custom.ini`, would spare everyone the tuning.

## Microcodes that bring their own matrix

Most games go through `_gSPCombineMatrices()`, but a few compute the combined matrix themselves and
install it directly, which bypasses the shear. Those need `gSPApplyStereo()` called by hand:

- `F5Indi_Naboo` — Star Wars Episode I Racer, Battle for Naboo. **Hooked.**
- `ZSort` — World Driver Championship and friends. **Hooked.**
- `ZSortBOSS` — **not hooked**, see above.

If a game shows no effect at all, check its microcode first.

## Files

| File | What changed |
|---|---|
| `mupen64plus-video-gliden64/upstream/src/gSP.cpp/.h` | `gSPApplyStereo()`, `gSPSetStereoEye()` |
| `mupen64plus-video-gliden64/upstream/src/RSP.cpp` | `_runDisplayList()` split out, walked once per eye |
| `mupen64plus-video-gliden64/upstream/src/StereoFrames.cpp/.h` | keeps the left eye between walks |
| `mupen64plus-video-gliden64/upstream/src/FrameBuffer.cpp` | side by side output in `renderBuffer()` |
| `mupen64plus-video-gliden64/upstream/src/Config.cpp/.h`, `mupenplus/Config_mupenplus.cpp` | the three config options |
| `quest-xr/src/main/cpp/quest_xr.cpp` | one quad per eye, `nativeSetStereoGame` |
| `app/.../game/xr/QuestXr.java` | `setStereoGame()` |
| `app/.../game/GameActivity.java` | turns it on for the ROM |
| `app/.../persistent/GamePrefs.java` | the three settings, forces GLideN64 |
| `app/.../util/Plugin.java` | constructor for a plug-in the app picks itself |
| `app/.../jni/NativeConfigFiles.java` | writes the options into mupen64plus.cfg |
| `app/src/main/res/xml/preferences_game.xml` | the settings screen |
