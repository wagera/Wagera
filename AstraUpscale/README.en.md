# AstraUpscale

**English · [Türkçe](README.md)**

An Android app that upscales **photos and video** on the device — photos from
**2K up to 512K**, video at **2K, 4K, 8K and 16K**. Real-ESRGAN, SwinIR and
Real-CUGAN models ship inside the APK; everything runs on the phone and nothing
ever leaves it.

<img src="docs/tema-koyu.png" width="250" alt="Dark theme" /> <img src="docs/tema-acik.png" width="250" alt="Light theme" />

| | |
|---|---|
| Source | Photo or video, chosen at the top of the screen |
| Photo resolution | 14 presets from 2K to 512K, in three tiers |
| Video resolution | 2K, 4K, 8K, 16K |
| Models | Real-ESRGAN (Fast / x4plus / Anime 6B), SwinIR-S, SwinIR-M, Real-CUGAN 2×/3×/4×, classic Lanczos |
| Passes | The model can run once or twice |
| Noise cleanup | Automatic at 64K and above: the source is cleaned before upscaling |
| Load level | Gentle / Balanced / Full power |
| Languages | Turkish and English, switched from the header |
| Theme | Light and dark; follows the system setting, can be pinned manually |
| Pages | Upscale · History · Requests (bottom navigation) |
| Output | Photo: JPEG (adjustable quality) or lossless PNG. Video: H.265/H.264 MP4 with the audio carried across, or a numbered frame sequence |
| Saved to | Gallery › Pictures › AstraUpscale (photos and frames), Movies › AstraUpscale (video) |

## Install

Copy `release/AstraUpscale.apk` to the phone and open it. Android will ask for
permission to install from unknown sources.

- Android 7.0 (API 24) and above
- **arm64-v8a**; 85 MB with nine models inside

## Resolution tiers

The pipeline streams row by row, so memory use is almost independent of the
output size; the real limits are **file format, storage and time**.

| Tier | Presets |
|---|---|
| Standard | 2K, 3K, 4K, 5K, 6K |
| High | 8K, 10K, 12K, 16K |
| Extreme | 32K, 64K, 128K, 256K, 512K |

| Preset | Size | Pixels | JPEG | Estimated PNG | Desktop time |
|---|---|---|---|---|---|
| 32K | 30720×23040 | 0.71 GP | yes | 0.8 GB | 44 s |
| **64K** | 61440×46080 | 2.83 GP | yes | 3.4 GB | **138 s** (measured) |
| 128K | 122880×92160 | 11.3 GP | **no** | 13.6 GB | ~10 min |
| 256K | 245760×184320 | 45.3 GP | **no** | 54 GB | ~40 min |
| 512K | 491520×368640 | 181 GP | **no** | 217 GB | ~2.5 h |

JPEG stores dimensions in 16 bits, so at most 65535 pixels: **JPEG works up to
64K, 128K and above are PNG only**. The app switches the format itself and
explains why.

256K and 512K really can be produced; the obstacle is storage. Before starting,
the estimated file size is compared against free space, and the job does not
start if it will not fit.

The only memory risk was very wide rows: the sharpening ring grows with output
width. Horizontal box sums are stored as 16-bit integers (a box holds at most
49×255 = 12495) and the ring is capped against a memory budget, so PNG writing
at 512K width runs within a 256 MB heap.

## Video

The same engine, run once per frame. A video is decoded frame by frame, each
frame goes through exactly the photo pipeline — optional noise cleanup, the
neural model, Lanczos to the exact target size, sharpening — and the result is
handed straight to a hardware encoder. Timestamps are carried from the source,
so variable frame rate footage keeps its length, and the audio track is copied
across without being re-encoded.

| Preset | Frame | Pixels per frame | Typical encoder |
|---|---|---|---|
| 2K | 2560×1440 | 3.7 MP | H.265 or H.264 |
| 4K | 3840×2160 | 8.3 MP | H.265 or H.264 |
| 8K | 7680×4320 | 33.2 MP | H.265, flagship chips only |
| 16K | 15360×8640 | 132.7 MP | none — written as a frame sequence |

### Nothing is held in memory

An 8K frame is 100 MB as RGB, and there are thirty of them a second. So a whole
upscaled frame is **never** materialised: `YuvWriter` takes each row as the
engine produces it and writes it straight into the encoder's own input planes,
converting RGB to YUV 4:2:0 on the way. The only extra memory is one previous
row, kept because chroma is averaged over 2×2 blocks.

### The encoder ceiling is asked, not guessed

Photos can reach 512K because the app writes the file itself. Video cannot:
the frame goes through a hardware encoder, and that encoder has a ceiling —
usually 4K, 8K on the best chips, and never 16K. `VideoCodecs` asks the device
rather than assuming, by shrinking the requested size until the codec says yes.

When the requested resolution does not fit, the app does not refuse. It writes
**numbered image files** instead — `frame_000001.png` and onwards, in a folder
whose name carries the frame rate — which is the format colour grading and
finishing actually use. Those can be rebuilt into a video on a computer:

```
ffmpeg -framerate 30 -i frame_%06d.png -c:v libx265 -crf 18 out.mp4
```

Frame sequences have no audio and no encoder ceiling, and lose nothing to
compression.

### Colour is carried, not assumed

The decoder is asked which colour standard the source uses (BT.601, BT.709 or
BT.2020, limited or full range); the same one is used when writing, and it is
tagged on the output so players do not have to guess. Where the source says
nothing, the standard is picked from its resolution. `tools/desktop/YuvTest.java`
round-trips the conversion through I420, NV12 and stride-padded layouts in all
four colour spaces: worst-case error is 3 levels out of 255, and the primaries
come back in the right channels.

### What it costs

Per-frame work is multiplied by the frame count, and this is the number the
interface puts in front of you before you start. A frame that takes two seconds
is an hour of work over a thirty-second clip. The model is loaded once and the
thread pool is shared across frames, so the per-frame overhead is the upscaling
itself and nothing else. Measured throughput and a real remaining time — not an
estimate, a measurement — appear in the progress readout and the notification
once the first frames are done.

Source frames above roughly 9 MP fall back to classic Lanczos: at that size the
neural model takes minutes per frame, and starting such a job is not helping
anyone.

## Noise cleanup

At 60× magnification a single noisy pixel in the source becomes a palm-sized
blotch in the output. At 64K and above the source therefore passes through an
edge-preserving bilateral filter **before** upscaling (`engine/Denoiser.java`).
It streams row by row and keeps only a few source rows in memory.

Measured on a photo with synthetic noise (σ ≈ 12 levels):

| | PSNR vs clean reference | Mean edge steepness |
|---|---|---|
| Clean reference | — | 4.53 |
| Noisy | 26.8 dB | 10.68 (noise inflates it) |
| Cleaned | **32.8 dB** | 4.14 |

The setting has three states: Auto (64K+), On, Off.

## User requests

The second page is for feedback: bug reports, feature ideas or general
comments. Messages reach a Discord channel. With no internet the message is
written to disk and sent once a connection appears; if sending fails it is
retried **every 10 seconds**. Even with the app closed, Android's job scheduler
drains the queue when the network returns, and because the queue lives on disk
nothing is lost across reboots.

Sent along with the message: app version, language, Android version, device
model, CPU and memory. **Photos are never sent.**

## Device profile and load level

At startup the app reads core count and maximum frequencies
(`/sys/devices/system/cpu/*/cpufreq`), memory, the app heap limit, ABI, SoC name
and Vulkan support, and shows them at the bottom.

| Level | Threads | Tile | Effect |
|---|---|---|---|
| Gentle | ⅓ of cores | 96 px | stays cool, short pause after each tile |
| Balanced | big cores | 128 px | everyday use |
| Full power | all cores | 128–160 px | fastest, the device heats up |

**Quality is identical at every level** — only speed and heat change. The
current thermal status (API 29+) is shown as well.

## Update gate and background work

At startup, if there is internet, `version.json` in the repository is read. The
**moment** a newer version is seen, that fact is stored permanently and the app
will not open until the update is installed — the lock survives losing internet,
and the back button exits the app. Installing the update clears it
automatically.

With no internet and no pending version, the app runs normally and shows when
the last check happened.

Photo and video jobs run in separate foreground services, each holding a partial
wake lock, so they continue with the screen off or another app in front — and
they can run at the same time, because a photo takes minutes and a video takes
hours. Network access is only
used for the update check and for feedback delivery; image processing never
touches the network.

## Interface

A single-colour design with no accent hue. The rule is: **the selected element
is painted in the content colour and its text turns the background colour.**
That inverts by itself in both themes — white on black in dark, black on white
in light. Separations are 1-pixel hairlines and depth comes from surfaces that
step apart one notch at a time.

Colours are semantic tokens; `values` carries the light theme and
`values-night` the dark one. There is not a single hard-coded colour in the app.

| Token | Light | Dark | Used for |
|---|---|---|---|
| `bg` | `#F6F7F9` | `#050506` | page background |
| `surface_low` | `#F0F1F4` | `#0B0C0E` | header and bottom bar |
| `surface` | `#FFFFFF` | `#0F1013` | card |
| `surface_high` | `#EDEEF2` | `#16171B` | chip, secondary button |
| `hairline` | `#E3E5EA` | `#1D1F23` | separator |
| `content` | `#14161C` | `#F5F6F8` | primary text, selected fill |
| `content_dim` / `content_faint` / `content_ghost` | `#697079` / `#8E949E` / `#B6BAC3` | `#8B9098` / `#5F646C` / `#3A3E45` | decreasing importance |

Three pages are reached from the bottom navigation: **Upscale**, **History**
(saved photos) and **Requests**. A short onboarding screen appears on first run.

### Layout of the Upscale page

The page has **one focal point**: the stage. The empty state, the chosen
photo, the running progress and the result preview all share the **same
rectangle** — none of them stacks below another. Tapping the stage opens the
gallery.

Directly under the stage, the target readout and **Start** sit side by side,
so the primary action is always above the fold rather than something you
scroll to find.

Every setting collapses into an accordion row — Resolution, Engine, Settings,
Device. Each row **carries its current value on the right even when closed**,
so the state reads without opening anything. Only one stays open at a time.

Measured result: the Upscale page lays out **33 views on first paint instead
of 60** (a 45% drop). On top of that, the 9 model rows, 3 tiers of preset
chips and 3 load-level buttons that used to be permanently open now live
inside collapsed panels and are not laid out at all on first paint.

Dimensions derive from a single 4dp grid (`values/dimens.xml`): 20dp page
gutter, 10dp between rows, 24dp between sections; type scale 10 / 11.5 / 13 /
15 / 20 / 34sp — six steps, nothing in between.

When the page opens, elements rise into place in sequence — header → stage →
action bar → setting rows, the order the eye needs to read them. If the user
has turned animations off in system settings, nothing is played.

### Design language

The language moved from "cinematic glass" to **instrument**. In the old one
surfaces were translucent, edges soft, corners generously rounded; everything
appeared to float. In the new one surfaces are opaque, separations are
1-pixel rules, and corners are nearly square (14–18dp radius → **3dp**). The
aim is the face of a measuring instrument: precision rather than softness.

Three changes carry the whole look:

**No glass, no blur.** Surfaces are separated by lines, not by transparency.
The cinematic backdrop (diagonal gradient plus two light blooms plus a
vignette) gave way to a flat ground with a faint **measurement grid** over it.

**One signal colour.** The old language had no accent at all; a selected item
was painted in the content colour. Now exactly one thing on the page carries
signal, and the eye does not hunt for where to press. The colour turns with
its ground: electric lime `#D8FF3E` in the dark theme, deep olive `#4A5A00`
in light — bright lime is invisible on paper, dark olive is invisible on
black.

**Resolution became a scale.** The 14 presets are no longer chips inside a
drawer but ticks along a single horizontal scale. The whole range is visible
at once and selection is one tap; nobody opens a section to find where 8K
went. Each tick's height grows with its tier (standard / high / extreme), so
the eye reads from the shape that the work gets heavier to the right.

The rest of the page was rebuilt too:

| | |
|---|---|
| **Status strip** | The device's present state on one line: engine, threads, thermal. The gauge on an instrument's top panel. |
| **Viewfinder** | The photo no longer sits in a rounded card but inside four corner brackets. The full edge is never drawn; the frame does not compete with the photo. |
| **Specification** | Engine, passes, format, quality, sharpening, denoise and device are one listing rather than four drawers. Label left, value right, rule beneath. |
| **Action** | A single full-width, square-cornered bar in the signal colour. |

The large hero headline ("Every pixel, resolved.") is gone: an instrument
does not introduce itself, it reports its state. The entrance ladder shortened
with it and now finishes in ~0.9s.

### The mark

A crossbarless **"A"**: the left leg descends in shrinking steps, the right
leg is a straight diagonal. The steps are the before — pixels; the straight
edge is the after — resolved. The mark is at once the brand's letter and the
work the app does.

The previous mark was a four-pointed star. Not bad in itself, but not
distinctive: every AI app uses a star.

The step count settled at 5. At 200 pixels the progression reads clearly; at
36 the silhouette still holds as an "A". At 6 the steps merge at small sizes;
at 4 the sense of progression weakens. In the adaptive icon the mark spans 56
units (inside the 72-unit safe zone): the new glyph is wider than the star, so
at 62 units its legs touched the edge under the circle mask.

These decisions were made by looking — `tools/docs/render-mark.py`,
`render-launcher.py` and `render-adaptive.py` render the mark at real usage
sizes and under the four masks a launcher may apply.

### Before / after comparison

The whole value of an upscaler is in detail you notice later with the naked
eye. Showing the result as a small thumbnail makes that value **invisible**.
In the comparison screen the two images share one viewport: source left of
the divider, result right of it. Zoom and pan apply to both, so the same
region is always being compared. A double tap toggles between fit and
**1:1** — what the upscaling actually did is only visible at 1:1.

**Two images, two different methods.** The result can be gigapixels and will
not fit in memory, so only the visible region is read through
`BitmapRegionDecoder`. The source is at most a few megapixels and is decoded
once and kept — the source side is always shown magnified, so it needs no
region reads. Measured: on an 8K output the fit view reads 2.8 MP, and at 1:1
the region read is 1.7 MP, i.e. screen-sized.

**Alignment.** The engine writes its output with the EXIF rotation applied
(`BitmapPixelSource(bitmap, orientation)`), so the result file is upright.
The comparison decodes the source with the same rotation; otherwise a photo
carrying EXIF would show the two sides 90 degrees apart.

**The maths is separate and tested.** Zoom and pan are too easy to get wrong
to check by eye: a sign error slides the image out from under your finger, a
bounds error shows what lies outside it. Since the app cannot be run in this
environment (no KVM), the maths lives in `engine/Viewport.java`, which has no
Android dependency, and is tested directly by
`tools/desktop/ViewportTest.java` — 22 checks: the focus point staying put,
clamping at the edges, zoom limits, sample size, source/result alignment, and
a small image staying centred. `CompareView` uses that class and keeps no
second copy.

### Persistence of selection and settings

Nothing was stored before, and there was no `onSaveInstanceState` either: let
the app sit in the background long enough and Android ends the process, so on
return both the chosen photo and every setting were gone. Preparing a 512K
job and putting the phone in a pocket was enough to trigger it.

`Session.java` writes the choices to persistent storage — instance state only
lives inside the same process and does not come back after process death.
What is stored is a few hundred bytes: not the photo, only the address that
points at it.

Restoring had two traps, both now closed:

1. **Writing half a state.** The SeekBar listener calls `refreshTexts()`
   without checking `fromUser`, and that writes the state. During a restore
   `setProgress` triggered it, and the sharpening value was overwritten with
   the default before it had been read. Every value is now read *before* any
   is applied, and a `restoring` flag suppresses writing until the load
   finishes.
2. **A deleted photo.** The address existing is not enough; it is verified as
   actually readable. If it is not, only that address is forgotten and the
   settings are kept — nobody should lose everything they set up over one
   deleted file.

### The launch screen

Not a separate Activity — the theme's `windowBackground`. Android paints it
the moment the window is created, so the mark is on screen while the app is
still preparing its first frame. A dedicated splash Activity would do the
opposite: add one more frame and slow the launch down.

Android 12 and above force their own splash screen and ignore this approach.
Rather than fight it, `values-v31/themes.xml` gives the system the same mark
and the same background through its own `windowSplashScreen*` attributes, so
the launch looks identical from 8.0 to 15.

The launch mark (`mark_launch.xml`) shares its geometry with `mark_astra.xml`
but carries no tint: the theme is not resolved yet when the launch screen is
drawn, so a tint bound to `@color/content` is not dependable there.

### Notifications

**Two separate channels.** The running job is silent (`IMPORTANCE_LOW`, sound
and vibration off): a long operation making a noise on every percent change is
an irritation. The result is the news the user is waiting for, so it gets its
own channel at default importance. On one channel, silencing either would mean
silencing both.

**The running job** names its target in the title ("Upscaling to 8K"), carries
the percentage in the secondary line, shows a determinate progress bar and a
**Cancel** action. `setOnlyAlertOnce` keeps it from re-alerting on every update.

**The result** notification gives resolution, megapixels, file size and
elapsed time; tapping opens the photo, and it carries a **Share** action. On
failure the reason is shown in full through `BigTextStyle`. A cancellation
posts nothing at all — the user cancelled it themselves, and telling them so
is noise.

The status-bar icon is no longer `android.R.drawable.ic_menu_gallery` — the
system's generic gallery glyph, unrelated to the brand. `ic_stat_astra`
replaces it. Android reduces status-bar icons to a single colour, so this
drawing omits the mark's second, faint ray set: at 38% opacity that layer
collapses onto the silhouette once flattened to white. The centre aperture is
slightly wider too, because a narrow hole closes up when scaled down.

`tools/desktop/FormatStringsTest.java` formats every format string with the
real arguments the code passes. A format mismatch is not caught at compile
time; the code only crashes when that string is used — and for a notification
that is the moment the job finishes, the worst possible moment.

### The update check

**This never worked.** The code requested
`.../AstraUpscale/surum.json`; no such file exists in the repository — it is
named `version.json`. Every check took a 404 and returned **silently** from the
`getResponseCode() != 200` branch. The result: no device ever saw an update,
and nothing anywhere recorded it.

Three things were fixed:

1. The URL is now `version.json`.
2. Failure is no longer silent: the result carries a `failure` field, and a
   failed check while online is now shown in the UI.
3. Client-side caching is disabled (`setUseCaches(false)` plus no-cache
   headers). GitHub's raw file server caches responses with `max-age=300`,
   so for about five minutes after publishing it can still serve the old
   file — that resolves itself. What does not resolve itself is a response
   pinned in the client's own HTTP cache.
4. `tools/desktop/UpdateUrlTest.java` is a regression test — it reads the URL
   out of `UpdateChecker.java` itself (keeping no second copy, which would
   drift and make the test lie) and asserts a 200 with a readable `versionCode`.

> Note that this fix cannot reach an old install by itself: the build on the
> phone is still running the broken checker. The new APK has to be installed
> by hand once; after that it takes care of itself.

### Picking a photo, and the permission

Photo selection happens inside the app: with the `READ_MEDIA_IMAGES`
permission the device's recent photos appear in a grid and the user picks one
without leaving the app.

The permission is **not required**. If it is declined the app keeps working;
selection falls back to the system document picker ("Files"), which needs no
permission at all.

**Photos are only ever read on the device.** No image, no thumbnail and no
file path leaves it — not to a server, not to the Discord webhook, not to
another app. The only thing that goes out is the feedback the user types, and
alongside it only the device details listed above.

## Requirement

Android 8.0 (API 26) and above. The floor moved from 24 to 26: `res/font`
resource families and the adaptive launcher icon arrived in that release.
Leaving it at 24 would not trouble `aapt2`, but on an Android 7 device
`@font/*` would fail to resolve and the typography would silently fall back
to the default.

## How it works

```
source ──► [1] noise cleanup (64K and above)
              │
              ▼
           [2] neural model (ncnn, 2×/3×/4×, optional second pass)
                 tiled, taking context from neighbouring pixels → seamless
              │
              ▼
           [3] Lanczos-3 resampling (in linear light)
              │
              ▼
           [4] scale-aware sharpening + streaming JPEG/PNG encoding
```

**1. The neural layer** (`native/sr_engine.cpp`) runs on ncnn. The image is cut
into tiles; each tile is surrounded by real context pixels from the image (edge
replication only at the borders), the model runs, and the result is cropped into
place. The crop margin is derived from the measured output size, so Real-ESRGAN
(no internal crop), Real-CUGAN (crops by the prepadding) and SwinIR (fixed input
size) all work with the same code.

*Real-CUGAN's SE blocks* need a global average over the whole image. The engine
solves it the same way upstream does: in four preparation passes each tile's
`gap` value is summed and averaged, then fed into the final pass as a constant.
Tiled and single-piece processing then match to 63 dB PSNR.

*Two passes*: the first pass output would not fit in memory, so it is streamed
to a raw RGB cache file that the second model reads with random access. Memory
use does not change; the temporary disk space needed is shown up front.

**2. Resampling** (`engine/Resampler.java`) applies a Lanczos-3 filter in linear
light. Horizontally scaled rows sit in a ring buffer, so memory is proportional
to the filter width, not the output height.

**3. Sharpening and encoding.** The sharpening radius scales with the
magnification; the box blur uses running sums, so cost per pixel is constant
whatever the radius. The encoders stream too: `PngWriter` feeds a Deflater
through a Paeth filter, and `JpegWriter` — a baseline encoder written from
scratch (AAN DCT, standard Huffman tables, 4:4:4) — writes 8-row MCU bands
straight to disk.

## Model conversions

Real-ESRGAN and Real-CUGAN have ready-made ncnn models. SwinIR and BSRGAN were
converted here; the scripts live in `tools/models/` and both were verified
against a PyTorch reference.

| Model | ncnn vs PyTorch, same input |
|---|---|
| BSRGAN | 91.4 dB (fp32 weights), 65.5 dB (fp16, as shipped) |
| SwinIR-S | 63.4 dB, at most 1 level per pixel |
| SwinIR-M | 56.6 dB, at most 1 level per pixel |

SwinIR could not be converted directly: window partitioning and attention use
5–6 rank reshapes and permutes that move the batch dimension, which ncnn cannot
express — pnnx leaves them as **parameterless** `Reshape` layers and the model
silently produces empty output. The patch in `tools/models/` rewrites the same
mathematics without ever moving the batch dimension and with at most four real
dimensions; it is first verified to match the original model **exactly** in
PyTorch (difference 0.0).

BSRGAN's generator is the same RRDBNet as Real-ESRGAN x4plus (23 blocks, 351
convolutions), so the `.param` graph is reused and only the `.bin` is rewritten.

## Measurements

On a desktop (4-core x86_64, no GPU) with real images:

| Test | Result |
|---|---|
| 512×384 → 4K (11 MP), classic path | 2.1 s, 15 MB heap |
| 1024×768 → 16K (177 MP), classic path | 13 s, 34 MB heap |
| 1024×768 → 32K (708 MP), classic path | 44 s, 62 MB heap, 55 MB JPEG |
| 1024×768 → 64K (2.83 GP), classic path | 138 s, 111 MB heap, 162 MB JPEG |
| PNG writing at 512K width (491520 px) | fine within a 256 MB heap |
| 256×256 → 4×, Real-ESRGAN Fast / x4plus | 0.21 s / 13 s |
| 96×96 → 4×, SwinIR-S / SwinIR-M (one tile) | 1.3 s / 7.4 s |
| 256×256 → 2×, Real-CUGAN (incl. 4 preparation passes) | 0.4 s |
| Tile 64 vs tile 256 output (Real-ESRGAN) | 64 dB PSNR — no visible seams |
| Tile 128 vs single tile (Real-CUGAN, SE) | 63 dB PSNR |
| Memory source vs file source (two-pass path) | bit-identical |
| Our JPEG encoder vs PNG output | 46 dB PSNR — expected at q92 |
| Noise cleanup (σ ≈ 12) | 26.8 → 32.8 dB, edges preserved |
| Report queue (`tools/desktop/QueueTest.java`) | 11 checks pass: Turkish text and emoji round-trip intact, a corrupt line does not break the queue, sent entries are removed correctly, the 500-entry cap keeps the newest |

## Build

```bash
export ANDROID_SDK_ROOT=/opt/android-sdk
bash tools/build-apk.sh          # -> build/AstraUpscale.apk
```

Requires JDK 17+, `platforms/android-34`, `build-tools/35.0.0`. (The d8 in
build-tools 34 crashes on enum classes compiled by JDK 21, so the script uses
35.)

A smaller package can be built by choosing models and ABIs; the app hides models
that are not bundled:

```bash
MODELS="realesr-animevideov3-x4 realcugan-up2x-conservative" ABIS="arm64-v8a" \
APK_NAME="AstraUpscale-lite" bash tools/build-apk.sh
```

Rebuilding the native library needs NDK 26 and the ncnn Android package:

```bash
cmake -S native -B out -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
      -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 \
      -DNCNN_PREBUILT=/path/ncnn-20240820-android-vulkan -GNinja
cmake --build out
```

## Models and licences

| Model | Source |
|---|---|
| Real-ESRGAN (x4plus, x4plus-anime-6B, animevideov3) | [Real-ESRGAN](https://github.com/xinntao/Real-ESRGAN) (BSD-3) |
| Real-CUGAN | [Real-CUGAN](https://github.com/nihui/realcugan-ncnn-vulkan) (MIT) |
| SwinIR | [SwinIR](https://github.com/JingyunLiang/SwinIR) (Apache-2.0) |
| BSRGAN (not in the default package) | [BSRGAN](https://github.com/cszn/BSRGAN) (Apache-2.0) |

Inference engine: [ncnn](https://github.com/Tencent/ncnn) (BSD-3).

## Limits and known points

- The app has **not** been run on a real phone (no KVM for an emulator in the
  build environment). The engine, models, noise cleanup, extreme-width PNG
  writing and Discord delivery were verified on the desktop; the APK signature,
  permissions and asset paths were checked statically.
- **The Discord webhook URLs are inside the APK and can be extracted.** They
  cannot be protected like passwords; if they are abused, delete the webhook in
  Discord and create a new one.
- BSRGAN is not in the default package: with it the APK exceeded GitHub's 100 MB
  file limit. Its files and conversion script are in the repository.
- 256K/512K are not practical on today's phones because of storage.
- The package targets arm64-v8a; armeabi-v7a can be built but the heavy models
  are not practical there.
- The signing key is generated locally on first build (not committed), so APKs
  built on different machines cannot update over each other.
