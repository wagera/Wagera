#!/usr/bin/env python3
"""
Sinematik zemini uretir: drawable-nodpi/backdrop_dark.png ve backdrop_light.png.

Referans tasarimda zemin tam kenardan kenara bir videodur. Burada onun
karsiligi, tek bir duz renk yerine yumusak isik patlamalari tasiyan bir
goruntudur.

Uc katman:
  1. Taban gecis — kosegen boyunca acilan derin bir zemin
  2. Iki isik patlamasi — biri ust solda genis, biri alt sagda dar
  3. Film greni — OLED ekranda gecislerin bant yapmasini engeller;
     grensiz duz gecisler 8 bit panelde gorunur halkalar birakir

Cikti nodpi klasorune yazilir: goruntu tek, olcek cihaza gore esnetilir.
Gecisler yumusak oldugu icin esnetme gorunmez, buna karsilik APK'ya tek
bir dosya girer.

Kullanim:  python3 tools/docs/render-backdrop.py
"""

import math
import os
import random

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "app", "src", "main", "res", "drawable-nodpi")

# Uretim cozunurlugu. Gecisler yumusak oldugu icin dusuk cozunurluk yeter;
# cihazda esnetildiginde fark edilmez ve dosya kucuk kalir.
W, H = 360, 800

# Grenin siddeti (0-255 olceginde standart sapma). 2.2 gozle secilmez ama
# bant olusumunu kirar.
GRAIN = 2.2


def lerp(a, b, t):
    return tuple(a[i] + (b[i] - a[i]) * t for i in range(3))


def bloom(x, y, cx, cy, radius, aspect=1.0):
    """Merkeze olan uzakliga gore 0..1 arasi yumusak dusus."""
    dx = (x - cx) / radius
    dy = (y - cy) / (radius * aspect)
    d = math.sqrt(dx * dx + dy * dy)
    if d >= 1.0:
        return 0.0
    # smoothstep: kenarda turevi sifir, yani halka birakmaz
    t = 1.0 - d
    return t * t * (3 - 2 * t)


def render(base_top, base_bottom, glow_a, glow_a_strength, glow_b, glow_b_strength,
           vignette):
    img = Image.new("RGB", (W, H))
    px = img.load()
    rnd = random.Random(20260823)     # sabit tohum: her calistirmada ayni goruntu

    for y in range(H):
        v = y / (H - 1.0)
        for x in range(W):
            u = x / (W - 1.0)
            # 1. Taban: kosegen boyunca acilir
            t = (v * 0.78 + u * 0.22)
            r, g, b = lerp(base_top, base_bottom, t)

            # 2. Isik patlamalari
            s = bloom(x, y, W * 0.18, H * 0.10, W * 1.05, aspect=0.85)
            r += glow_a[0] * s * glow_a_strength
            g += glow_a[1] * s * glow_a_strength
            b += glow_a[2] * s * glow_a_strength

            s = bloom(x, y, W * 0.92, H * 0.74, W * 0.78, aspect=1.15)
            r += glow_b[0] * s * glow_b_strength
            g += glow_b[1] * s * glow_b_strength
            b += glow_b[2] * s * glow_b_strength

            # 3. Kenar karartmasi
            dx, dy = (u - 0.5) * 2, (v - 0.5) * 2
            edge = min(1.0, math.sqrt(dx * dx + dy * dy) / 1.35)
            k = 1.0 - vignette * edge * edge
            r, g, b = r * k, g * k, b * k

            # 4. Gren
            n = rnd.gauss(0, GRAIN)
            px[x, y] = (
                max(0, min(255, int(r + n))),
                max(0, min(255, int(g + n))),
                max(0, min(255, int(b + n))),
            )
    return img


def main():
    os.makedirs(OUT, exist_ok=True)

    # Koyu tema: siyah agirlikli, patlamalar soguk mavi ve cok hafif sicak
    dark = render(
        base_top=(13, 14, 17), base_bottom=(4, 4, 5),
        glow_a=(34, 42, 58), glow_a_strength=1.0,
        glow_b=(30, 26, 24), glow_b_strength=0.75,
        vignette=0.30,
    )
    dark.save(os.path.join(OUT, "backdrop_dark.png"), optimize=True)

    # Acik tema: ayni kompozisyon, ters yonde — kagit uzerinde isik
    light = render(
        base_top=(255, 255, 255), base_bottom=(236, 238, 243),
        glow_a=(0, 0, 0), glow_a_strength=0.0,
        glow_b=(-14, -12, -8), glow_b_strength=1.0,
        vignette=0.05,
    )
    light.save(os.path.join(OUT, "backdrop_light.png"), optimize=True)

    for name in ("backdrop_dark.png", "backdrop_light.png"):
        size = os.path.getsize(os.path.join(OUT, name))
        print("yazildi: drawable-nodpi/%s (%d KB)" % (name, size // 1024))


if __name__ == "__main__":
    main()
