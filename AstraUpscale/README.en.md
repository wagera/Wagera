# AstraUpscale

**English · [Türkçe](README.md)**

An Android app that upscales photos on the device, from **2K up to 512K**.
Real-ESRGAN, SwinIR and Real-CUGAN models ship inside the APK; everything runs
on the phone and no image ever leaves it.

<img src="docs/tema-koyu.png" width="250" alt="Dark theme" /> <img src="docs/tema-acik.png" width="250" alt="Light theme" />

| | |
|---|---|
| Resolution | 14 presets from 2K to 512K, in three tiers |
| Models | Real-ESRGAN (Fast / x4plus / Anime 6B), SwinIR-S, SwinIR-M, Real-CUGAN 2×/3×/4×, classic Lanczos |
| Passes | The model can run once or twice |
| Noise cleanup | Automatic at 64K and above: the source is cleaned before upscaling |
| Load level | Gentle / Balanced / Full power |
| Languages | Turkish and English, switched from the header |
| Theme | Light and dark; follows the system setting, can be pinned manually |
| Pages | Upscale · History · Requests (bottom navigation) |
| Output | JPEG (adjustable quality) or lossless PNG |
| Saved to | Gallery › Pictures › AstraUpscale |

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

The upscaling job runs in a foreground service and holds a partial wake lock, so
it continues with the screen off or another app in front. Network access is only
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

**Backdrop.** Behind the page is not a flat colour but a diagonal base
gradient, two soft light blooms and a vignette. It is not a file: `Backdrop.java`
draws it at runtime.

Why not a file: it was first produced as a PNG. Film grain was layered on so
the gradients would not band on an 8-bit panel, and the file came to 293 KB.
Converting to WebP dropped it to 6 KB — and **destroyed the grain**: measured
local noise fell from 2.2 to 0.48, and only 33 distinct luminance levels were
left across an 800-pixel vertical axis, which is exactly the banding the grain
existed to prevent. Drawing at runtime adds no file to the APK, fits any
screen ratio exactly, and generates the grain per pixel for real.

**Glass.** Panels float over the backdrop: a translucent 235° gradient, a
1-pixel hairline border, and a light line along the top edge. No blur is
applied — the backdrop is already a smooth gradient, and the blur of a smooth
gradient is itself; putting one there would cost something and return nothing.

**Two faces.** Space Grotesk for display, Manrope for the interface; both are
bundled, neither depends on the system font. The display face is used only in
the large headline and in numeric readouts. Both faces carry full Turkish
coverage — ğ, ş, ı, İ, ö, ü, ç included — verified through their cmap with
`fontTools`.

These faces carry no geometric symbols. A sweep found that `✕` (U+2715), used
on the gallery close button, exists in none of the bundled faces — it rendered
as a tofu box on device. It is a vector now.

**Motion.** On open, elements arrive on a one-shot timeline, and the two
headline lines rise into place from inside their own clipping windows. The
order comes from the reference brief; the absolute timings are compressed. The
reference is a landing page, where a 1.7-second reveal is a pleasure; in an
app, paying that on every launch is a cost. The ladder finishes in ~1.1s. If
animations are off in system settings, nothing plays.

### The mark

Four concave blades draw a sharp waist toward the centre, and a second, shorter
set of rays emerges between them. The opening at the centre is true negative
space: the same circle is punched through both paths with `evenOdd`, rather
than a ring drawn on top. The mark is not a solid mass but an aperture that
light passes through.

**The launcher icon was wrong for a release.** The adaptive icon used a scale
of 0.62 and a translate of 29.8: the 48-unit drawing came down to 29.76 units
— only **28%** of the 108-unit canvas — and its centre landed at 44.68 when
the canvas centre is 54. The mark was both far too small and **9.32 units
(8.6%) off toward the top left**. Nothing caught it because the foreground was
never once looked at on its own.

Correct: the mark spans 62 units (inside the 72-unit safe zone), so the scale
is 62/48 = 1.29167 and the translate is (108−62)/2 = 23.
`tools/docs/render-adaptive.py` renders the icon under the four masks a
launcher may apply (circle, rounded square, square, squircle); the offset is
now measurable and zero.

`tools/docs/render-mark.py` renders it to PNG; the aperture diameter was tuned
by looking at it at real usage sizes (14/18/24/48dp). The launcher icon's PNG
variants come from the same vector via `tools/docs/render-launcher.py` — never
drawn by hand, so the two cannot drift apart.

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
3. `tools/desktop/UpdateUrlTest.java` is a regression test — it reads the URL
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
