# Stereoscopic 3D for N64 games

Dolphin VR gives GameCube and Wii games a per-eye view they never had: it takes the projection
matrix the game hands the GPU, shifts the camera left and right, and renders the scene twice. The
result is genuine depth, not a flat picture on a big screen.

This does the same for the N64. **It works**, as an experimental per ROM setting. This note is both
the design record and the handover: what is built, what is verified, and what is left.

## Status

Working end to end in the Quest headset with Super Mario 64 and Star Wars Episode I: Racer. The
display list is walked once per eye, both eye views come out side by side in one image, and the VR
layer hands each eye its half. Real depth is visible and the framebuffer/DMEM fixes made it markedly
more consistent, but it is still experimental and not enabled by default.

The current full-resolution test build has a regression: games render at the visibly sharper
1280x480 SBS resolution (640x480 per eye), but no 3D effect is perceptible in the headset. The last
known-good stereoscopic behavior is commit `77bbf56ed`; the full-resolution and FoV work after that
commit is intentionally kept as a separate step.

The first headset tests found two separate classes of problems:

- Super Mario 64 can lose its skybox and overlays. The first implementation kept only one anonymous
  left-eye framebuffer, although a frame can touch several N64 color images. Copies are now keyed by
  their N64 framebuffer address, and a failed capture duplicates the right eye instead of sending
  unrelated halves of a mono image to the eyes. The corrected pairing is verified in the headset.
- Racer uses a very different clip-space scale. Even a convergence value of 50 could move the pod by
  more than a complete clip unit and make it disappear. The setting can now be adjusted in steps of
  one and per-vertex parallax is bounded in screen space. The pod remains visible in headset tests.

## How it works

**The shear.** GLideN64 emulates the RSP vertex pipeline on the CPU and keeps the matrices the game
pushed, so there is one place where an eye offset has to be applied:

- `gSP.matrix.projection` and `gSP.matrix.modelView[]` in
  [`gSP.h`](../mupen64plus-video-gliden64/upstream/src/gSP.h)
- `_gSPCombineMatrices()` multiplies them into `gSP.matrix.combined`
- `gSPProcessVertex()` transforms every vertex with that matrix before anything reaches the GPU

`gSPApplyStereo()` in [`gSP.cpp`](../mupen64plus-video-gliden64/upstream/src/gSP.cpp) adds the eye
shear to that matrix, and `gSPApplyStereoConvergence()` finishes it after vertex transformation:

```
x' = x + separation * (w - convergence)
```

which is the eye offset plus the matching frustum shear. Geometry at the convergence depth does not
move, nearer geometry gets crossed parallax, and distant geometry approaches a constant offset. The
per-eye shift is capped to the magnitude of the parallax at infinity. Without that cap, a game whose
microcode keeps `w` near one can have its entire foreground shifted outside the clip volume by a
convergence value that is modest in another game. Vertices are transformed as a row vector, so x
reads column 0 and w reads column 3.

Screen space geometry (`G_TEXRECT` and friends) never reaches the vertex transform, so HUDs and 2D
overlays are excluded for free. Confirmed: Super Mario 64's life counter and "PRESS START" stay on
identical pixels in both eyes.

**The double walk.** `RSP_ProcessDList()` in
[`RSP.cpp`](../mupen64plus-video-gliden64/upstream/src/RSP.cpp) walks the list twice when the mode is
*Both eyes*. The list lives in RDRAM and the CPU does not run in between. DMEM, which some microcodes
use as mutable scratch memory, is restored to the original task input for the second walk, then put
back to the first walk's result so the replay is not visible to the emulated CPU. RDP and framebuffer
side effects still need auditing.

**Keeping both eyes.** Both walks draw into the same N64 frame buffer, so
[`StereoFrames`](../mupen64plus-video-gliden64/upstream/src/StereoFrames.cpp) blits each color image
into a texture keyed by its N64 framebuffer address after the first walk. `FrameBufferList::renderBuffer()`
then draws matching pairs side by side: the kept left eye into the left half of the output and the
live buffer, which is the right eye, into the right half. If no matching capture exists, it duplicates
the right eye so XR never receives unrelated halves of one mono image.

**The VR layer.** `quest_xr.cpp` submits the game quad twice when stereo is on, once per eye, using
`XrCompositionLayerQuad::eyeVisibility` and a `subImage.imageRect` covering one half of the image.
The quad keeps its normal size, so each half is stretched back to full width. No extra swapchain is
needed. `QuestXr.setStereoGame()` turns it on, called from `GameActivity` when the ROM's stereo mode
is *Both eyes*.

**Full per-eye resolution.** The stereo render target is twice the normal video width. Crucially,
`GameActivity` gives that same doubled width to both the emulator core and the XR producer surface;
doubling only the XR swapchain leaves GLideN64 drawing both eyes into its left half. The XR layer
uses half of the doubled texture for each eye, so each view retains the complete configured 4:3
resolution. The higher resolution is verified visually, but this path currently loses the visible
stereo effect somewhere after the per-eye vertex transforms.

## Current handoff: full-resolution stereo regression

The diagnostic build installed on the Quest logs the Java settings, XR/core dimensions, GLideN64
settings, first left-eye capture, and a few transformed vertices for each eye. A live Mario 64 run
on 2026-09-18 reported:

```text
XR swapchain: 1280x480
core: 1280x480
GLideN64 mode=3 passes=2 separation=0.025 convergence=500 fov=1.100
left framebuffer captured=1
left vertex x=2782.6399, w=-678.2900
right vertex x=2748.7253, w=-678.2900
```

This rules out the most obvious failures:

- the app does select GLideN64;
- `StereoMode=3` reaches the plug-in;
- both display-list walks run;
- the left framebuffer blit succeeds;
- the matrix shear produces different clip-space x coordinates for the two eyes;
- Java, the GLideN64 screen, and the XR game swapchain all agree on 1280x480.

The remaining fault is therefore likely in `FrameBufferList::renderBuffer()` after the per-eye
textures are selected, the final overscan/default-framebuffer copy, `copyQuad()`'s SurfaceTexture
copy, or the two OpenXR sub-image rectangles. The next high-value diagnostic is a pixel comparison
of the final 1280x480 image immediately before `wnd.swapBuffers()`: hash each 640x480 half and
measure their mean absolute pixel difference. If the halves differ there, inspect `copyQuad()` and
the submitted `XrCompositionLayerQuad` structures; if they are identical, inspect the final
GLideN64 blits and overscan buffer dimensions. The VR dock's Photo action was proposed as a quick
way to acquire this SBS image but was not completed before handoff.

The saved per-ROM separation in the tested profile is 25, not the new default of 8. That is large
enough that the measured left/right difference should be unmistakable; a too-small default does not
explain the missing effect.

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
| Eye separation | Parallax at infinity, in thousandths of a clip unit. 0..120, default 8 |
| Convergence depth | Depth that keeps zero parallax, in the game's own units. 0..5000, default 500 |
| 3D field of view | Tangent-space projection scale. 100..130%, default 110% |

The single-eye modes render one view for both eyes. They give no depth and exist for tuning: switch
between left and right and watch how far things move.

Convergence is in the same units as the clip space w, which on the N64 is whatever scale the game
chose for its world — near one in Racer's Factor 5 microcode, hundreds or thousands in other games.
That is why it is per ROM. The slider uses steps of one; Racer cannot safely use the old step size
of 50.

The field-of-view control scales the clip-space x and y coordinates of 3D vertices before the eye
offset is added. At 100% the game's projection is unchanged; larger values widen it without
stretching the final image. Screen-space rectangles such as most HUD elements are unaffected.
Games may cull geometry using their original camera, so high values can expose missing objects,
skybox edges, or pop-in. Treat it as a per-game experimental control, not a universal correction.

Start headset tuning at separation 5–8. First confirm that both eyes contain the same skybox, HUD,
and effects, then raise convergence from zero until the intended subject sits on the physical screen
plane. Do not compensate for a missing layer or a one-eye rendering bug with convergence: that
creates binocular rivalry and cannot be made comfortable with numeric tuning.

Config options reaching the plugin: `StereoMode`, `StereoSeparation`, `StereoConvergence`, and
`StereoFovScale` in the `Video-GLideN64` section, written by `NativeConfigFiles`.

## What is left

1. **Locate the full-resolution stereo regression.** Compare the two final framebuffer halves before
   swap, then compare them again after `copyQuad()`. The per-eye geometry is already proven distinct.
   Restore visible stereo without giving up the verified 640x480 per-eye resolution.
2. **Restore all replay-sensitive state.** DMEM is restored to the task input before the right-eye
   replay and to the first pass result afterwards. RDP state, framebuffer side effects, and RDRAM
   writes still need an audit; replaying a display list is not inherently side-effect free.
3. **Measure the cost.** Two walks double the graphics work and the vertex transform runs on the
   CPU. Untested.
4. **Match physical angular FoV.** The default 2 m wide screen at 2 m
   distance covers about 53 degrees horizontally. A 2.31 m screen at that distance covers about 60
   degrees. Tune the physical screen first; changing an N64 projection can expose culled geometry
   and does not automatically fix skyboxes or HUD layouts.
5. **Try more games.** Games that lean on framebuffer effects, or write
   back to RDRAM mid-frame, may not take a second walk so quietly.
6. **`ZSortBOSS` is not hooked.** It can store the combined matrix back to RDRAM, and a sheared
   matrix written into the game's own memory would corrupt its state. It needs the unsheared matrix
   kept alongside first.
7. **Per game defaults.** Good separation, convergence, and FoV values differ wildly. A small table keyed
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
| `mupen64plus-video-gliden64/upstream/src/Config.cpp/.h`, `mupenplus/Config_mupenplus.cpp` | the four config options |
| `quest-xr/src/main/cpp/quest_xr.cpp` | one quad per eye, `nativeSetStereoGame` |
| `app/.../game/xr/QuestXr.java` | `setStereoGame()` |
| `app/.../game/GameActivity.java` | turns it on for the ROM |
| `app/.../persistent/GamePrefs.java` | the four settings, forces GLideN64 |
| `app/.../util/Plugin.java` | constructor for a plug-in the app picks itself |
| `app/.../jni/NativeConfigFiles.java` | writes the options into mupen64plus.cfg |
| `app/src/main/res/xml/preferences_game.xml` | the settings screen |
