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
