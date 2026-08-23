#!/usr/bin/env python3
"""
Uyarlanabilir baslatici simgesini baslaticinin uyguladigi maskelerle cizer.

Android 8+ cihazlarda gorunen simge budur — mipmap-*/ic_launcher.png degil.
Bir surum boyunca buradaki geometri yanlisti (isaret tuvalin %28'ini
kapliyor ve 9.32 birim kaymis durumdaydi) ve bu hicbir yerde gorulmedi,
cunku on plan tek basina hicbir zaman goze bakilmadi.

Cizim, XML'in kendisinden okunur: group'un translate/scale degerleri ve
path'ler dogrudan alinir. Boylece dosya degistiginde onizleme de degisir.

Kullanim:  python3 tools/docs/render-adaptive.py [cikti.png]
"""

import os
import sys
import xml.etree.ElementTree as ET

import cairosvg
from PIL import Image, ImageDraw

NS = "{http://schemas.android.com/apk/res/android}"
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
RES = os.path.join(ROOT, "app", "src", "main", "res")

FOREGROUND = os.path.join(RES, "drawable", "ic_launcher_foreground.xml")
BACKGROUND = os.path.join(RES, "drawable", "ic_launcher_background.xml")

# Baslaticilarin kullandigi maske bicimleri
MASKS = ("daire", "yuvarlak kare", "kare", "sikistirilmis daire")


def paths_as_svg(el, transform=""):
    """Bir <path> ogesini SVG'ye cevirir."""
    d = " ".join(el.get(NS + "pathData").split())
    alpha = el.get(NS + "fillAlpha", "1")
    rule = el.get(NS + "fillType", "nonZero")
    rule = "evenodd" if rule.lower() == "evenodd" else "nonzero"
    fill = el.get(NS + "fillColor", "#FFFFFFFF")
    if len(fill) == 9:                      # #AARRGGBB -> #RRGGBB
        fill = "#" + fill[3:]
    return ('<path d="%s" fill="%s" fill-opacity="%s" fill-rule="%s"%s/>'
            % (d, fill, alpha, rule, transform))


def foreground_svg(size):
    """On plani, XML'deki group donusumlerini uygulayarak SVG'ye cevirir."""
    root = ET.parse(FOREGROUND).getroot()
    vw = float(root.get(NS + "viewportWidth"))
    vh = float(root.get(NS + "viewportHeight"))
    parts = []

    def walk(node, transform):
        for child in node:
            if child.tag == "path":
                parts.append(paths_as_svg(child, ' transform="%s"' % transform
                                          if transform else ""))
            elif child.tag == "group":
                t = transform
                tx = child.get(NS + "translateX")
                ty = child.get(NS + "translateY")
                sx = child.get(NS + "scaleX")
                rot = child.get(NS + "rotation")
                if tx or ty:
                    t += " translate(%s %s)" % (tx or 0, ty or 0)
                if sx:
                    t += " scale(%s)" % sx
                if rot:
                    t += " rotate(%s %s %s)" % (rot, child.get(NS + "pivotX", "0"),
                                                child.get(NS + "pivotY", "0"))
                walk(child, t.strip())

    walk(root, "")
    svg = ('<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" '
           'viewBox="0 0 %g %g">%s</svg>' % (size, size, vw, vh, "".join(parts)))
    out = "/tmp/_adaptive_fg.png"
    cairosvg.svg2png(bytestring=svg.encode("utf-8"), write_to=out)
    return Image.open(out).convert("RGBA")


def background_image(size):
    """Zemin: radyal gecis, ic_launcher_background.xml'den okunur."""
    g = ET.parse(BACKGROUND).getroot().find("gradient")

    def rgb(name):
        v = g.get(NS + name).lstrip("#")
        if len(v) == 8:
            v = v[2:]
        return tuple(int(v[i:i + 2], 16) for i in (0, 2, 4))

    start, center, end = rgb("startColor"), rgb("centerColor"), rgb("endColor")
    img = Image.new("RGB", (size, size))
    d = ImageDraw.Draw(img)
    cx, cy, radius = size * 0.5, size * 0.42, size * 0.60
    for i in range(size * 2, 0, -1):
        t = i / (size * 2.0)
        if t > 0.5:
            u = (t - 0.5) * 2
            col = tuple(int(center[k] + (end[k] - center[k]) * u) for k in range(3))
        else:
            u = t * 2
            col = tuple(int(start[k] + (center[k] - start[k]) * u) for k in range(3))
        r = radius * t * 2
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=col)
    return img.convert("RGBA")


def mask_for(shape, size):
    """Baslaticinin uyguladigi kirpma maskesi."""
    m = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(m)
    if shape == "daire":
        d.ellipse([0, 0, size - 1, size - 1], fill=255)
    elif shape == "yuvarlak kare":
        d.rounded_rectangle([0, 0, size - 1, size - 1], radius=int(size * 0.22), fill=255)
    elif shape == "kare":
        d.rounded_rectangle([0, 0, size - 1, size - 1], radius=int(size * 0.06), fill=255)
    else:                                   # sikistirilmis daire
        d.rounded_rectangle([0, 0, size - 1, size - 1], radius=int(size * 0.40), fill=255)
    return m


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "/tmp/adaptive.png"
    # Uyarlanabilir simgede 108 birimlik tuvalin ortadaki 72'si gorunur:
    # yani baslatici tuvali 108/72 = 1.5 kat buyutup ortadan kirpar.
    canvas = 384
    visible = int(canvas / 1.5)

    fg = foreground_svg(canvas)
    bg = background_image(canvas)
    full = Image.alpha_composite(bg, fg)
    crop = (canvas - visible) // 2
    view = full.crop((crop, crop, crop + visible, crop + visible))

    gap = 24
    strip = Image.new("RGB", (visible * len(MASKS) + gap * (len(MASKS) + 1),
                              visible + gap * 2), (28, 28, 32))
    x = gap
    for shape in MASKS:
        tile = view.copy()
        tile.putalpha(mask_for(shape, visible))
        strip.paste(tile, (x, gap), tile)
        x += visible + gap
    strip.save(out)
    print("yazildi:", out)
    print("tuval %d px, gorunen alan %d px (108:72 orani)" % (canvas, visible))
    print("maskeler:", ", ".join(MASKS))


if __name__ == "__main__":
    main()
