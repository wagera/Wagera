#!/usr/bin/env python3
"""
Arayuz cizimini uretir: docs/tema-acik.png ve docs/tema-koyu.png.

Bu bir cihaz ekran goruntusu DEGILDIR. Renkler colors.xml'den, olculer
dimens.xml'den okunur; boylece cizim ile gercek yerlesim arasindaki bag
elle guncellenen bir varsayim degil, dosyadan turetilmis bir sonuc olur.

Kullanim:  python3 tools/docs/render-ui.py
"""

import os
import re
import xml.etree.ElementTree as ET
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
RES = os.path.join(ROOT, "app", "src", "main", "res")
DOCS = os.path.join(ROOT, "docs")

# Cizim olcegi: 1dp = SCALE piksel
SCALE = 3
# Referans cihaz: 360 x 800 dp
DP_W, DP_H = 360, 800

FONT_DIR = "/usr/share/fonts/truetype/dejavu"
F_REG = os.path.join(FONT_DIR, "DejaVuSans.ttf")
F_BOLD = os.path.join(FONT_DIR, "DejaVuSans-Bold.ttf")
F_MONO = os.path.join(FONT_DIR, "DejaVuSansMono.ttf")


def load_colors(path):
    """colors.xml'i #AARRGGBB -> (r,g,b,a) sozlugune cevirir."""
    out = {}
    for el in ET.parse(path).getroot():
        if el.tag != "color":
            continue
        v = (el.text or "").strip().lstrip("#")
        if len(v) == 8:
            a, r, g, b = (int(v[i:i + 2], 16) for i in (0, 2, 4, 6))
        elif len(v) == 6:
            r, g, b = (int(v[i:i + 2], 16) for i in (0, 2, 4))
            a = 255
        else:
            continue
        out[el.get("name")] = (r, g, b, a)
    return out


def load_dimens(path):
    """dimens.xml'den dp ve sp degerlerini okur."""
    out = {}
    for el in ET.parse(path).getroot():
        if el.tag != "dimen":
            continue
        m = re.match(r"([0-9.]+)(dp|sp)", (el.text or "").strip())
        if m:
            out[el.get("name")] = float(m.group(1))
    return out


def load_strings(path):
    out = {}
    for el in ET.parse(path).getroot():
        if el.tag == "string":
            out[el.get("name")] = "".join(el.itertext()).replace("\\'", "'")
    return out


DIM = load_dimens(os.path.join(RES, "values", "dimens.xml"))
# Android'in kaynak cozumu gibi: once varsayilan, uzerine Turkce katman.
# Dile bagli olmayan dizeler (marka adi) yalnizca values/ icinde durur.
STR = load_strings(os.path.join(RES, "values", "strings.xml"))
STR.update(load_strings(os.path.join(RES, "values-tr", "strings.xml")))


def px(dp):
    return int(round(dp * SCALE))


def font(path, sp):
    return ImageFont.truetype(path, px(sp))


def render(theme):
    """theme: 'light' -> values/colors.xml, 'dark' -> values-night/colors.xml"""
    folder = "values" if theme == "light" else "values-night"
    C = load_colors(os.path.join(RES, folder, "colors.xml"))

    img = Image.new("RGB", (px(DP_W), px(DP_H)), C["bg"][:3])
    d = ImageDraw.Draw(img)

    def rect(x, y, w, h, fill=None, outline=None, radius=0):
        box = [px(x), px(y), px(x + w), px(y + h)]
        d.rounded_rectangle(box, radius=px(radius),
                            fill=fill[:3] if fill else None,
                            outline=outline[:3] if outline else None,
                            width=max(1, SCALE // 3))

    def text(x, y, s, f, color, anchor="la"):
        d.text((px(x), px(y)), s, font=f, fill=color[:3], anchor=anchor)

    gutter = DIM["gutter"]
    inner = DP_W - 2 * gutter

    # ── Baslik cubugu ────────────────────────────────────────────────
    hh = DIM["header_height"]
    # Marka yazisi: parcalar olculen genislige gore ilerler, sabit x ile degil
    f_mark, f_b1, f_b2 = font(F_REG, 13), font(F_BOLD, 11), font(F_REG, 11)
    bx = px(gutter)
    d.text((bx, px(hh / 2)), "✶", font=f_mark, fill=C["content"][:3], anchor="lm")
    bx += int(d.textlength("✶", font=f_mark)) + px(6)
    d.text((bx, px(hh / 2)), STR["brand_first"], font=f_b1,
           fill=C["content"][:3], anchor="lm")
    bx += int(d.textlength(STR["brand_first"], font=f_b1)) + px(3)
    d.text((bx, px(hh / 2)), STR["brand_second"], font=f_b2,
           fill=C["content_dim"][:3], anchor="lm")
    for i, label in enumerate(("◐", "TR")):
        bw = 30
        bx = DP_W - gutter - (2 - i) * (bw + 6) + 6
        rect(bx, hh / 2 - 15, bw, 30, fill=C["surface_high"], radius=8)
        text(bx + bw / 2, hh / 2, label, font(F_MONO, 9), C["content_soft"], anchor="mm")
    d.line([(0, px(hh)), (px(DP_W), px(hh))], fill=C["hairline"][:3], width=max(1, SCALE // 3))

    y = hh + DIM["gap_block"]

    # ── Sahne ────────────────────────────────────────────────────────
    stage_h = DIM["stage_min"]
    rect(gutter, y, inner, stage_h,
         fill=C["surface_low"], outline=C["hairline"], radius=DIM["radius_stage"])
    cx, cy = DP_W / 2, y + stage_h / 2
    # Bos durum: fotograf simgesi + iki satir
    rect(cx - 15, cy - 30, 30, 22, outline=C["content_ghost"], radius=3)
    d.line([(px(cx - 15), px(cy - 13)), (px(cx - 5), px(cy - 21)),
            (px(cx + 2), px(cy - 16)), (px(cx + 8), px(cy - 21)),
            (px(cx + 15), px(cy - 13))],
           fill=C["content_ghost"][:3], width=max(1, SCALE // 3))
    text(cx, cy + 4, STR["no_photo"], font(F_REG, DIM["text_body"]), C["content_dim"], anchor="mm")
    text(cx, cy + 20, STR["stage_tap_hint"], font(F_REG, DIM["text_fine"]),
         C["content_ghost"], anchor="mm")
    y += stage_h + DIM["gap_row"]

    # ── Sahne altyazisi ──────────────────────────────────────────────
    # Bos durumda gorunmez (MainActivity.refreshTexts ile ayni kural).
    y += DIM["gap_block"]

    # ── Eylem cubugu: hedef okumasi + Baslat ─────────────────────────
    cta_h = DIM["cta_height"]
    half = (inner - DIM["gap_row"]) / 2
    rect(gutter, y, half, cta_h, fill=C["surface"], outline=C["hairline"],
         radius=DIM["radius_card"])
    text(gutter + 14, y + cta_h / 2 - 8, "4K", font(F_BOLD, DIM["text_row"]),
         C["content"], anchor="lm")
    text(gutter + 14, y + cta_h / 2 + 10, STR["pick_photo_first"][:22],
         font(F_MONO, 8), C["content_faint"], anchor="lm")
    # Birincil eylem: icerik rengine boyanir, yazisi zemin rengine doner
    rect(gutter + half + DIM["gap_row"], y, half, cta_h,
         fill=C["content"], radius=DIM["radius_card"])
    text(gutter + half + DIM["gap_row"] + half / 2, y + cta_h / 2,
         STR["start"], font(F_BOLD, DIM["text_row"]), C["bg"], anchor="mm")
    y += cta_h + DIM["gap_section"]

    # ── Akordeon satirlari ───────────────────────────────────────────
    rows = (
        (STR["row_resolution"], "4K"),
        (STR["row_engine"], "Real-ESRGAN 4x"),
        (STR["row_settings"], "JPEG · 95"),
        (STR["row_device"], STR["load_balanced"]),
    )
    rh = DIM["row_height"]
    for i, (title, value) in enumerate(rows):
        rect(gutter, y, inner, rh, fill=C["surface"], outline=C["hairline"],
             radius=DIM["radius_card"])
        text(gutter + 14, y + rh / 2, title, font(F_REG, DIM["text_row"]),
             C["content"], anchor="lm")
        text(DP_W - gutter - 30, y + rh / 2, value, font(F_REG, DIM["text_body"]),
             C["content_dim"], anchor="rm")
        # chevron
        ax = DP_W - gutter - 20
        d.line([(px(ax - 4), px(y + rh / 2 - 2)), (px(ax), px(y + rh / 2 + 2)),
                (px(ax + 4), px(y + rh / 2 - 2))],
               fill=C["content_faint"][:3], width=max(1, SCALE // 3))
        y += rh + DIM["gap_row"]

    # ── Alt gezinme ──────────────────────────────────────────────────
    nav_h = 62
    ny = DP_H - nav_h
    rect(0, ny, DP_W, nav_h, fill=C["surface_low"])
    d.line([(0, px(ny)), (px(DP_W), px(ny))], fill=C["hairline"][:3],
           width=max(1, SCALE // 3))
    tabs = (STR["nav_upscale"], STR["nav_history"], STR["nav_requests"])
    pad = 10          # navbar.xml'deki yatay ic bosluk
    slot = (DP_W - 2 * pad) / 3
    for i, label in enumerate(tabs):
        left = pad + i * slot
        active = i == 0
        if active:
            rect(left + 3, ny + 6, slot - 6, nav_h - 12,
                 fill=C["surface_high"], radius=11)
        text(left + slot / 2, ny + nav_h / 2, label,
             font(F_BOLD if active else F_REG, 11),
             C["content"] if active else C["content_faint"], anchor="mm")

    return img


def main():
    os.makedirs(DOCS, exist_ok=True)
    for theme, name in (("light", "tema-acik.png"), ("dark", "tema-koyu.png")):
        out = os.path.join(DOCS, name)
        render(theme).save(out)
        print("yazildi: docs/%s" % name)


if __name__ == "__main__":
    main()
