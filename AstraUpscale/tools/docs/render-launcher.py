#!/usr/bin/env python3
"""
Baslatici simgesinin PNG surumlerini uretir (mipmap-*/ic_launcher*.png).

Uyarlanabilir simgeyi (mipmap-anydpi-v26) desteklemeyen Android 7 ve
oncesi bu PNG'leri kullanir. Ikisi ayni isaretten uretilsin diye zemin ve
on plan burada da drawable dosyalarindan okunur; elle cizilmez.

Kullanim:  python3 tools/docs/render-launcher.py
"""

import importlib.util
import os
import xml.etree.ElementTree as ET

import cairosvg
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
RES = os.path.join(ROOT, "app", "src", "main", "res")
NS = "{http://schemas.android.com/apk/res/android}"

spec = importlib.util.spec_from_file_location("rm", os.path.join(HERE, "render-mark.py"))
rm = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rm)

# Android'in baslatici olculeri
DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

MARK = os.path.join(RES, "drawable", "mark_astra.xml")
BACKGROUND = os.path.join(RES, "drawable", "ic_launcher_background.xml")


def background_stops():
    """ic_launcher_background.xml'deki radyal gecisin renklerini okur."""
    g = ET.parse(BACKGROUND).getroot().find("gradient")
    return (g.get(NS + "startColor"), g.get(NS + "centerColor"), g.get(NS + "endColor"))


def hex_rgb(argb):
    v = argb.lstrip("#")
    if len(v) == 8:
        v = v[2:]
    return tuple(int(v[i:i + 2], 16) for i in (0, 2, 4))


def make_icon(size, round_icon):
    start, center, end = (hex_rgb(c) for c in background_stops())

    # Radyal zemin: merkez (0.5, 0.42), yaricap %60
    bg = Image.new("RGB", (size, size))
    d = ImageDraw.Draw(bg)
    cx, cy = size * 0.5, size * 0.42
    radius = size * 0.60
    for i in range(size * 2, 0, -1):
        t = i / (size * 2.0)          # 1 = disarisi, 0 = merkez
        if t > 0.5:
            u = (t - 0.5) * 2
            col = tuple(int(center[k] + (end[k] - center[k]) * u) for k in range(3))
        else:
            u = t * 2
            col = tuple(int(start[k] + (center[k] - start[k]) * u) for k in range(3))
        r = radius * t * 2
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=col)

    # Isaret: guvenli alanin %62'si, uyarlanabilir simgeyle ayni oran
    mark_px = int(size * 0.62)
    svg = rm.to_svg(MARK, fill="#F5F6F8", bg="none")
    svg = svg.replace('fill="none"', 'fill-opacity="0"')
    tmp = "/tmp/_mark_launcher.png"
    cairosvg.svg2png(bytestring=svg.encode(), write_to=tmp,
                     output_width=mark_px, output_height=mark_px)
    mark = Image.open(tmp).convert("RGBA")

    icon = bg.convert("RGBA")
    off = (size - mark_px) // 2
    icon.alpha_composite(mark, (off, off))

    if round_icon:
        mask = Image.new("L", (size, size), 0)
        ImageDraw.Draw(mask).ellipse([0, 0, size - 1, size - 1], fill=255)
        icon.putalpha(mask)
    return icon


def main():
    for density, size in DENSITIES.items():
        folder = os.path.join(RES, "mipmap-" + density)
        os.makedirs(folder, exist_ok=True)
        make_icon(size, False).save(os.path.join(folder, "ic_launcher.png"))
        make_icon(size, True).save(os.path.join(folder, "ic_launcher_round.png"))
        print("yazildi: mipmap-%s (%dpx)" % (density, size))


if __name__ == "__main__":
    main()
