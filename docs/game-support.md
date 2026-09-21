# Which games were tried in 3D and immersive mode

Stereoscopic 3D and immersive mode are experimental, and every game brings its own quirks. This is
an honest record of what was actually run in the headset, not a promise of what works.

Fifty well known N64 titles are listed. Almost all of them are **untested**: nobody has started them
in these modes yet. Untested does not mean broken — it means unknown. If you try one, a note in an
issue is welcome, and the list can grow.

How to read the columns:

- **3D** — stereoscopic 3D on the screen quad, each eye its own view.
- **Immersive** — the screen disappears, the game is rendered with the headset's field of view and
  follows your head.
- ✅ works, ⚠️ works with the noted limits, ❓ untested, ❌ known not to work.

Both modes force the GLideN64 video plug-in and need a second walk through each display list, so
they cost roughly twice the graphics work.

## Tested

| Game | 3D | Immersive | Notes |
|---|---|---|---|
| Super Mario 64 | ✅ | ✅ | The reference case. Depth, sky, HUD and cut scenes all behave. The sky is a flat picture covering the game's own view, so its edges are stretched outwards |
| Star Wars Episode I: Racer | ✅ | ⚠️ | Tuned in the headset and ships with its own defaults. Menus, the fly-over before a race and the title screens go back on the screen. Engine glows sit still for the two seconds the camera distance settles after a scene change |
| Mario Kart 64 | ✅ | ⚠️ | Depth needs the depth boost, 50% suits it. Immersive was only checked briefly, and its sky is a flat band that does not reach far |
| Pokémon Stadium | ✅ | ❓ | Depth confirmed on the screen quad. Immersive untested |
| Star Wars: Rogue Squadron | ✅ | ⚠️ | 3D is fine. In immersive mode the picture is blurry at every resolution setting, for reasons not yet found |

## Untested

Everything below has not been started in either mode.

| Game | 3D | Immersive | Notes |
|---|---|---|---|
| The Legend of Zelda: Ocarina of Time | ❓ | ❓ | |
| The Legend of Zelda: Majora's Mask | ❓ | ❓ | |
| GoldenEye 007 | ❓ | ❓ | Its own microcode variant |
| Perfect Dark | ❓ | ❓ | Leans heavily on frame buffer effects |
| Banjo-Kazooie | ❓ | ❓ | |
| Banjo-Tooie | ❓ | ❓ | |
| Donkey Kong 64 | ❓ | ❓ | |
| Conker's Bad Fur Day | ❓ | ❓ | Its own microcode, heavy frame buffer use |
| Star Fox 64 | ❓ | ❓ | |
| F-Zero X | ❓ | ❓ | |
| Super Smash Bros. | ❓ | ❓ | |
| Paper Mario | ❓ | ❓ | Flat sprites in a 3D world |
| Mario Party | ❓ | ❓ | |
| Mario Party 2 | ❓ | ❓ | |
| Mario Party 3 | ❓ | ❓ | |
| Mario Golf | ❓ | ❓ | |
| Mario Tennis | ❓ | ❓ | |
| Diddy Kong Racing | ❓ | ❓ | Its own microcode |
| Wave Race 64 | ❓ | ❓ | |
| 1080° Snowboarding | ❓ | ❓ | |
| Pilotwings 64 | ❓ | ❓ | |
| Kirby 64: The Crystal Shards | ❓ | ❓ | |
| Yoshi's Story | ❓ | ❓ | |
| Pokémon Snap | ❓ | ❓ | Frame buffer capture for its photos |
| Pokémon Stadium 2 | ❓ | ❓ | |
| Ogre Battle 64 | ❓ | ❓ | |
| Harvest Moon 64 | ❓ | ❓ | |
| Sin and Punishment | ❓ | ❓ | |
| Bomberman 64 | ❓ | ❓ | |
| Blast Corps | ❓ | ❓ | |
| Jet Force Gemini | ❓ | ❓ | |
| Killer Instinct Gold | ❓ | ❓ | |
| Turok: Dinosaur Hunter | ❓ | ❓ | |
| Turok 2: Seeds of Evil | ❓ | ❓ | |
| Doom 64 | ❓ | ❓ | |
| Quake II | ❓ | ❓ | |
| Duke Nukem 64 | ❓ | ❓ | |
| Resident Evil 2 | ❓ | ❓ | Pre-rendered backgrounds |
| Castlevania 64 | ❓ | ❓ | |
| Shadow Man | ❓ | ❓ | |
| Rayman 2: The Great Escape | ❓ | ❓ | |
| Beetle Adventure Racing | ❓ | ❓ | |
| Ridge Racer 64 | ❓ | ❓ | |
| San Francisco Rush 2049 | ❓ | ❓ | |
| Top Gear Rally | ❓ | ❓ | |
| World Driver Championship | ❓ | ❓ | Runs on the `ZSortBOSS` microcode, which is deliberately not hooked: it can write the matrix back into the game's own memory. Expect no effect at all |
| Stunt Racer 64 | ❓ | ❓ | Same microcode, same expectation |
| Indiana Jones and the Infernal Machine | ❓ | ❓ | Factor 5 microcode, hooked, but never run |
| Star Wars Episode I: Battle for Naboo | ❓ | ❓ | Factor 5 microcode, hooked, but never run |
| WWF No Mercy | ❓ | ❓ | |

## What to look for when trying a game

1. **Does anything change at all?** If not, the game may bring its own microcode that installs
   matrices directly. [The note on stereoscopic 3D](stereo-3d.md) lists which ones are hooked.
2. **Do both eyes show the same scene?** Missing layers in one eye are uncomfortable and cannot be
   fixed by tuning numbers.
3. **In immersive mode:** does the world end when you look to the side, does the sky follow the
   horizon, and does the HUD sit somewhere you can read it? The world size and HUD size settings
   are per game for exactly this reason.
