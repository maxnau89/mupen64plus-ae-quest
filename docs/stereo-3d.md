# Stereoscopic 3D for N64 games

Dolphin VR gives GameCube and Wii games a per-eye view they never had: it takes the projection
matrix the game hands the GPU, shifts the camera left and right, and renders the scene twice. The
result is genuine depth, not a flat picture on a big screen.

This does the same for the N64. **It works**, as an experimental per ROM setting. This note is both
the design record and the handover: what is built, what is verified, and what is left.

On top of that sits **immersive mode**, which goes one step further: the screen disappears, the game
is rendered with the headset's own field of view, and turning your head turns the camera. It is
described in its own section below.

## Status

Working in the Quest headset at full per-eye resolution (640x480 per eye) with Super Mario 64,
Star Wars Episode I: Racer, Mario Kart 64 and Pokémon Stadium. It is switched on per game from the
game page and stays marked experimental. The game page cycles off, 3D on a screen, and immersive.

The first headset tests found two classes of problems, both fixed:

- Super Mario 64 could lose its skybox and overlays because only one anonymous left-eye copy was
  kept although a frame can touch several N64 color images. Copies are keyed by their N64 frame
  buffer address.
- Racer uses a very different clip-space scale, and a convergence value that suits other games
  moved its pod outside the clip volume. Per-vertex parallax is bounded in screen space.

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
[`StereoFrames`](../mupen64plus-video-gliden64/upstream/src/StereoFrames.cpp) blits every color image
the first walk made current into a texture keyed by its N64 address, when the walk leaves it and at
the end of the walk. `FrameBufferList::renderBuffer()` then draws the pair side by side: the kept
left eye into the left half of the output and the live buffer, which is the right eye, into the
right half. If no copy exists, it duplicates the right eye so XR never receives unrelated halves of
one mono image.

Two rules there are easy to get wrong, and each one cost a regression:

- **A copy stays until its buffer is drawn again.** With double buffering the image on screen is
  the one the *previous* display list drew, and games at 30 fps show every image twice. Dropping
  copies once shown left every displayed frame without a pair.
- **Only images the first walk entered are kept.** When the walk starts, the current image is the
  one the previous frame's right eye drew. Keeping that on leave overwrote its left copy with right
  eye content, so 30 fps games alternated between stereo and flat every other refresh.

**The VR layer.** `quest_xr.cpp` submits the game quad twice when stereo is on, once per eye, using
`XrCompositionLayerQuad::eyeVisibility` and a `subImage.imageRect` covering one half of the image.
The quad keeps its normal size, so each half is stretched back to full width. No extra swapchain is
needed. `QuestXr.setStereoGame()` turns it on, called from `GameActivity` when the ROM's stereo mode
is *Both eyes*.

**Full per-eye resolution.** The stereo render target is twice the normal video width. Crucially,
`GameActivity` gives that same doubled width to both the emulator core and the XR producer surface;
doubling only the XR swapchain leaves GLideN64 drawing both eyes into its left half. The XR layer
uses half of the doubled texture for each eye, so each view retains the complete configured 4:3
resolution. The higher resolution is verified visually.

**Depth that suits each game.** The scale of w differs per game, so convergence 0 means automatic:
each frame collects a histogram of log2(w) over the perspective vertices and puts convergence at its
10th percentile, smoothed in log space. Nearly everything then lies behind the screen plane, like a
window. Orthographic geometry has no depth: drawn before the first perspective geometry of a walk it
is a background such as Super Mario 64's skybox and keeps the parallax at infinity; drawn after, it
is an overlay and sits on the screen plane. The optional depth boost raises convergence/w to an
exponent between 1 and 0.3, which spreads deep scenes such as a racing track that would otherwise
sit almost at infinity.

## Immersive mode

A game on a screen two metres away covers about 53 degrees. The game itself was drawn for a much
wider view — Star Wars Episode I: Racer's camera sees 100 degrees — so the picture was squeezed into
a third of the angle it was made for. Immersive mode renders each frame for the head pose the
headset reports, with the headset's field of view, and submits it as a projection layer with that
pose. The compositor then holds the world still between emulated frames, which is what makes 20 to
30 frames per second bearable while the head keeps moving.

Everything happens in clip space, because on the N64 the camera may sit in the projection or in the
modelview matrix, and some microcodes install a finished matrix of their own. For a perspective
projection, clip space already is the camera's view space up to the scales `sx`, `sy` and the depth
mapping `z = -A*w + B`, all three of which can be read back out of the matrix. `gSPApplyStereo()`
right-multiplies the matrix with the turn, so the whole mode costs one matrix multiply per matrix.

**What the head sees.** `quest_xr.cpp` exports two functions that GLideN64 finds with `dlsym`:
`questxr_immersive_pose()` hands out the current head rotation relative to where the player faced,
the rendered half angles, and an id; `questxr_immersive_present()` reports which id a finished
picture belongs to. The picture reaches XR through a SurfaceTexture, whose timestamp is matched
against the reported ids, so each image is submitted with the pose it was drawn for. *Reset screen*
in the VR menu sets the forward direction anew.

**Things that are not in the scene.** A HUD, a sky and a menu are flat, and the game drew them for
its own screen:

- **Backgrounds**, anything flat drawn before the first 3D of a frame, are placed at infinity at
  their true angular size so they line up with the horizon, and whatever reaches the edge of the
  game's screen is pulled outwards, smearing the last texels instead of leaving the view empty. N64
  skies only cover the game's own narrow view; there is no more sky to be had.
- **Overlays and screen rectangles** are placed where the game's screen was, straight ahead.
- **Letterbox bars**, which Super Mario 64 draws in cut scenes, are left out: the whole view is the
  picture now, so two black strips would just float in front of the player.

**Cut scenes go back on the screen.** A camera that sees less than 30 degrees to the side cannot
fill the headset's view; its picture would sit in the middle of a large empty surround. The same
holds for a frame with nothing in perspective at all, such as a title screen or a menu. Those are
submitted as the ordinary screen quad instead, in stereo, and the mode switches back when the game
does. Going to the screen takes three frames, coming back twenty, because a cut scene shown in the
full view for half a second is what one notices.

**Telling the camera from the objects.** Microcodes that install their own matrix hand over one per
object, and objects can be scaled unevenly, like a stretched engine glow. The scale that most
matrices of a frame agree on is taken to be the camera.

**The settings.** All per game, all only in immersive mode:

| Setting | Meaning |
|---|---|
| Immersive | Off, or on. Forces both eyes and the GLideN64 plug-in |
| Immersive resolution per eye | 1440x1080, 1920x1440 or 2400x1800. The view is spread over twice the angle a screen covers, so it needs the pixels |
| Immersive world size | Shows the scene at a smaller angle, as if the camera had a narrower view. Head turns still match. Below 100% the picture no longer fills the view |
| Immersive HUD size | How wide the HUD is spread. At 100% it takes as much of the view as the game's own camera, which can push it into the corners of the eye |
| Immersive camera distance | Moves the camera back along its own axis, in percent of the nearest depth of the scene. Both depth planes move with it |
| Sharper small textures | xBRZ for tiny HUD fonts, which fall apart when spread over the whole view |

Star Wars Episode I: Racer ships with world 70%, HUD 65%, distance 150%, 1440x1080 and xBRZ, found
by testing in the headset.

**What it cannot do.** Games draw what their own camera sees. Look to the side and the world may
simply end there, and objects the game culled will not appear however wide the view is. A sky is a
flat picture and stays one. Effects a game positions in screen space itself, such as Racer's engine
glow, carry no depth that could be followed when the camera moves back, so they sit still for the
two seconds the camera distance needs to settle after a scene change.

## Diagnostics

The diagnostics are compiled out: `STEREO_DIAGNOSTICS` in
[`Config.h`](../mupen64plus-video-gliden64/upstream/src/Config.h) turns them back on. One of them
reads the frame buffer back, which is why they are not merely quiet by default.

With them on, the plugin logs to the `GLideN64` tag. `Stereo pairs` counts shown images without a pair,
`Stereo convergence` gives the automatic convergence with the 10th, 50th and 90th percentile of w,
`Stereo eyes` the mean x/w per eye, and `Stereo output` how many channels of the middle row differ
between the two halves of what goes on screen. When something looks flat, check these before
touching the math: every regression so far showed up there as identical halves or missing pairs.
The dock's Photo action saves the full side-by-side image to `/sdcard/Pictures/mupen64plus`.

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
| Eye separation | Parallax at infinity, in thousandths of a clip unit. 0..120, default 30 |
| Convergence depth | Depth that keeps zero parallax, in the game's own units. 0 is automatic, the default |
| 3D field of view | Tangent-space projection scale. 100..130%, default 110% |
| Depth boost | Spreads deep scenes. 0..100%, default 0; 50% suits Mario Kart 64 |

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

Config options reaching the plugin, all in the `Video-GLideN64` section and written by
`NativeConfigFiles`: `StereoMode`, `StereoSeparation`, `StereoConvergence`, `StereoFovScale`,
`StereoDepthBoost`, `StereoImmersive`, `StereoImmersiveDistance`, `StereoImmersiveWorldScale` and
`StereoImmersiveHudScale`.

## What is left

1. **Restore all replay-sensitive state.** DMEM is restored to the task input before the right-eye
   replay and to the first pass result afterwards. RDP state, framebuffer side effects, and RDRAM
   writes still need an audit; replaying a display list is not inherently side-effect free.
2. **Measure the cost.** Two walks double the graphics work and the vertex transform runs on the
   CPU. Untested.
3. **Try more games in immersive mode.** Super Mario 64 and Star Wars Episode I: Racer were tuned
   in the headset. Everything else is untested. Games that lean on framebuffer effects, or write
   back to RDRAM mid-frame, may not take a second walk so quietly either.
4. **Screen space effects and a moved camera.** A game that positions effects in screen space
   itself, as Racer does with its engine glow, gives them no depth to follow. Racer leaves the depth
   of those rectangles at zero, and deriving one from it reads as "right in front of the camera",
   which threw them about. Assuming the near edge of the scene would suit the player's own vehicle
   and overshoot for everything further away.
5. **Rogue Squadron is blurry in immersive mode.** Only there, and at every resolution setting; the
   camera values in the log look sound. Somewhere GLideN64 falls back to a native resolution buffer
   for that game. Unsolved.
6. **`ZSortBOSS` is not hooked.** It can store the combined matrix back to RDRAM, and a sheared
   matrix written into the game's own memory would corrupt its state. It needs the unsheared matrix
   kept alongside first.
7. **Per game defaults from a table.** Good separation, world size and HUD size values differ
   wildly. Star Wars Episode I: Racer has its own in `GamePrefs`, matched by name; a table keyed by
   ROM header name, like GLideN64's own `GLideN64.custom.ini`, would scale better.

## Microcodes that bring their own matrix

Most games go through `_gSPCombineMatrices()`, but a few compute the combined matrix themselves and
install it directly, which bypasses the shear. Those need `gSPApplyStereo()` called by hand:

- `F5Indi_Naboo` — Indiana Jones, Battle for Naboo. **Hooked.** Not Racer: that one runs on plain
  `F3DEX2` and installs its matrices through `gSPForceMatrix()`, which cost an afternoon to find out.
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
| `mupen64plus-video-gliden64/upstream/src/Config.cpp/.h`, `mupenplus/Config_mupenplus.cpp` | the config options and `STEREO_DIAGNOSTICS` |
| `mupen64plus-video-gliden64/upstream/src/GraphicsDrawer.cpp` | screen rectangles and triangles placed for immersive mode |
| `quest-xr/src/main/cpp/quest_xr.cpp` | one quad per eye, the projection layer and the head pose for immersive mode |
| `app/.../game/xr/QuestXr.java` | `setStereoGame()`, `setImmersiveGame()` |
| `app/.../game/GameSurface.java` | photos keep one eye |
| `app/.../game/GameActivity.java` | turns it on for the ROM |
| `app/.../persistent/GamePrefs.java` | the settings, the Racer defaults, forces GLideN64 |
| `app/.../GalleryActivity.java` | the 3D switch on the game page, off / 3D / immersive |
| `app/.../util/Plugin.java` | constructor for a plug-in the app picks itself |
| `app/.../jni/NativeConfigFiles.java` | writes the options into mupen64plus.cfg |
| `app/src/main/res/xml/preferences_game.xml` | the settings screen |
