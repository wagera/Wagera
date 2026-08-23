#!/usr/bin/env python3
"""
Arayuz cizimini uretir: docs/tema-acik.png ve docs/tema-koyu.png.

Bu bir cihaz ekran goruntusu DEGILDIR. Renkler colors.xml'den, olculer
dimens.xml'den okunur; boylece cizim ile gercek yerlesim arasindaki bag
elle guncellenen bir varsayim degil, dosyadan turetilmis bir sonuc olur.

Kullanim:  python3 tools/docs/render-ui.py
"""

import importlib.util
import math
import os
import random
import re
import xml.etree.ElementTree as ET

import cairosvg
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
RES = os.path.join(ROOT, "app", "src", "main", "res")
DOCS = os.path.join(ROOT, "docs")

# Cizim olcegi: 1dp = SCALE piksel
SCALE = 3
# Referans cihaz: 360 x 800 dp
DP_W, DP_H = 360, 800

# Uygulamanin gercekten pakethledigi yuzler. Cizim ile uygulama ayni
# dosyalari kullansin diye res/font'tan okunur.
FONT_DIR = os.path.join(RES, "font")
F_REG = os.path.join(FONT_DIR, "manrope_regular.ttf")
F_MED = os.path.join(FONT_DIR, "manrope_medium.ttf")
F_BOLD = os.path.join(FONT_DIR, "manrope_semibold.ttf")
F_DISPLAY = os.path.join(FONT_DIR, "spacegrotesk_medium.ttf")
F_DISPLAY_B = os.path.join(FONT_DIR, "spacegrotesk_bold.ttf")
F_MONO = F_DISPLAY


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


_spec = importlib.util.spec_from_file_location(
    "rmark", os.path.join(os.path.dirname(os.path.abspath(__file__)), "render-mark.py"))
_mark = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_mark)


def fmt(template, *args):
    """
    Android bicim dizesini Python'da uygular.

    Android konumlu bicim kullanir (%1$s, %2$.1f); Python bunu anlamaz.
    Burada %N$X -> {N-1:X} cevrimi yapilir, %% ise gercek yuzde isaretine
    doner. Boylece cizim, uygulamanin gordugu metnin aynisini gosterir —
    elle yazilmis bir kopyasini degil.
    """
    def convert(m):
        index = int(m.group(1)) - 1
        spec = m.group(2)
        if spec == "s":
            return "{%d}" % index
        if spec == "d":
            return "{%d:d}" % index
        return "{%d:%s}" % (index, spec)

    out = re.sub(r"%(\d+)\$([.\d]*[sdf])", convert, template)
    out = out.replace("%%", "\x00")
    out = out.format(*args)
    return out.replace("\x00", "%")


def wrap(text_value, font_obj, max_px, drawer):
    """Metni verilen piksel genisligine gore satirlara boler."""
    words = text_value.split()
    lines, current = [], ""
    for word in words:
        trial = (current + " " + word).strip()
        if drawer.textlength(trial, font=font_obj) <= max_px:
            current = trial
        else:
            if current:
                lines.append(current)
            current = word
    if current:
        lines.append(current)
    return lines


DIM = load_dimens(os.path.join(RES, "values", "dimens.xml"))
# Android'in kaynak cozumu gibi: once varsayilan, uzerine Turkce katman.
# Dile bagli olmayan dizeler (marka adi) yalnizca values/ icinde durur.
STR = load_strings(os.path.join(RES, "values", "strings.xml"))
STR.update(load_strings(os.path.join(RES, "values-tr", "strings.xml")))


def px(dp):
    return int(round(dp * SCALE))


def font(path, sp):
    return ImageFont.truetype(path, px(sp))


def backdrop(size, theme):
    """
    Sinematik zemin.

    Backdrop.java ile ayni matematik: kosegen taban gecisi, iki yumusak
    isik patlamasi, kenar karartmasi ve gren. Degerler orada da burada da
    elle tutuldugu icin biri degisirse digeri de degismeli.
    """
    w, h = size
    base_top, base_bottom, cool, warm, vig, grain_up = (
        ((13, 14, 17), (4, 4, 5), (46, 56, 78), (44, 36, 32), 0.30, True)
        if theme == "dark" else
        ((255, 255, 255), (234, 236, 242), (222, 228, 240), (236, 231, 226), 0.06, False)
    )
    img = Image.new("RGB", (w, h))
    px = img.load()
    rnd = random.Random(20260823)

    for y in range(h):
        v = y / (h - 1.0)
        for x in range(w):
            u = x / (w - 1.0)
            t = min(1.0, max(0.0, v * 0.78 + u * 0.22))
            r = base_top[0] + (base_bottom[0] - base_top[0]) * t
            g = base_top[1] + (base_bottom[1] - base_top[1]) * t
            b = base_top[2] + (base_bottom[2] - base_top[2]) * t

            for (cx, cy, rad, col, strength) in (
                (w * 0.18, h * 0.10, w * 1.05, cool, 0.40),
                (w * 0.92, h * 0.74, w * 0.78, warm, 0.30),
            ):
                dx, dy = (x - cx) / rad, (y - cy) / rad
                d = math.sqrt(dx * dx + dy * dy)
                if d < 1.0:
                    f = (1 - d) ** 2 * strength
                    if theme == "dark":
                        r += col[0] * f
                        g += col[1] * f
                        b += col[2] * f
                    else:
                        r = r * (1 - f) + col[0] * f
                        g = g * (1 - f) + col[1] * f
                        b = b * (1 - f) + col[2] * f

            dx, dy = (u - 0.5) * 2, (v - 0.5) * 2
            edge = min(1.0, math.sqrt(dx * dx + dy * dy) / 1.35)
            k = 1.0 - vig * edge * edge
            r, g, b = r * k, g * k, b * k

            n = rnd.gauss(0, 2.0) * (1 if grain_up else -1)
            px[x, y] = (max(0, min(255, int(r + n))),
                        max(0, min(255, int(g + n))),
                        max(0, min(255, int(b + n))))
    return img


def mark_image(size, color):
    """Isareti mark_astra.xml'den okuyup PNG olarak dondurur."""
    svg = _mark.to_svg(_mark.VECTOR, fill=color, bg="none")
    svg = svg.replace('fill="none"', 'fill-opacity="0"')
    out = "/tmp/_ui_mark_%d.png" % size
    cairosvg.svg2png(bytestring=svg.encode("utf-8"), write_to=out,
                     output_width=size, output_height=size)
    return Image.open(out).convert("RGBA")


def render(theme):
    """theme: 'light' -> values/colors.xml, 'dark' -> values-night/colors.xml"""
    folder = "values" if theme == "light" else "values-night"
    C = load_colors(os.path.join(RES, folder, "colors.xml"))

    W, H = px(DP_W), px(DP_H)
    img = backdrop((W, H), theme).convert("RGBA")
    d = ImageDraw.Draw(img, "RGBA")

    def glass(x, y, w, h, radius, pressed=False):
        """Cam panel: yari saydam gecis + sac cizgisi + ust isik cizgisi."""
        box = [px(x), px(y), px(x + w), px(y + h)]
        top = C["glass_press"] if pressed else C["glass_top"]
        bottom = C["glass_bottom"]
        # Dikey gecisi satir satir cizip yuvarlak koseli maskeyle kirp
        panel = Image.new("RGBA", (box[2] - box[0], box[3] - box[1]))
        pd = ImageDraw.Draw(panel)
        for row in range(panel.height):
            t = row / max(1, panel.height - 1)
            col = tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3))
            a = int(top[3] + (bottom[3] - top[3]) * t)
            pd.line([(0, row), (panel.width, row)], fill=col + (a,))
        mask = Image.new("L", panel.size, 0)
        ImageDraw.Draw(mask).rounded_rectangle(
            [0, 0, panel.width - 1, panel.height - 1], radius=px(radius), fill=255)
        img.paste(panel, (box[0], box[1]), mask)
        d.rounded_rectangle(box, radius=px(radius), outline=C["glass_stroke"],
                            width=max(1, SCALE // 3))
        # Ust kenardaki isik cizgisi
        if C["glass_sheen"][3] > 0:
            inset = px(radius * 0.8)
            d.line([(box[0] + inset, box[1] + 1), (box[2] - inset, box[1] + 1)],
                   fill=C["glass_sheen"], width=max(1, SCALE // 3))

    def text(x, y, s, f, color, anchor="la"):
        d.text((px(x), px(y)), s, font=f, fill=color[:3] + (255,), anchor=anchor)

    gutter = DIM["gutter"]
    inner = DP_W - 2 * gutter

    # ── Baslik cubugu ────────────────────────────────────────────────
    hh = DIM["header_height"]
    mark_px = px(22)
    img.alpha_composite(mark_image(mark_px, "#%02X%02X%02X" % C["content"][:3]),
                        (px(gutter), px(hh / 2) - mark_px // 2))
    bx = px(gutter) + mark_px + px(9)
    f_b1, f_b2 = font(F_BOLD, 11), font(F_REG, 11)
    d.text((bx, px(hh / 2)), STR["brand_first"], font=f_b1,
           fill=C["content"][:3] + (255,), anchor="lm")
    bx += int(d.textlength(STR["brand_first"], font=f_b1)) + px(3)
    d.text((bx, px(hh / 2)), STR["brand_second"], font=f_b2,
           fill=C["content_dim"][:3] + (255,), anchor="lm")
    # Gercek uygulama sembol degil metin kullanir; cizim de oyle olsun
    for i, label in enumerate((STR["theme_dark"], "TR")):
        bw = 38 if i == 0 else 30
        bxx = DP_W - gutter - (30 + 6) * (1 - i) - bw if i == 0 else DP_W - gutter - bw
        glass(bxx, hh / 2 - 15, bw, 30, 8)
        text(bxx + bw / 2, hh / 2, label, font(F_REG, 9), C["content_soft"], anchor="mm")

    y = hh + DIM["gap_block"]

    # ── Kahraman baslik ──────────────────────────────────────────────
    f_disp = font(F_DISPLAY, DIM["text_display"])
    line_h = DIM["text_display"] * 1.06
    text(gutter, y, STR["hero_line_1"], f_disp, C["content"])
    y += line_h
    d.text((px(gutter), px(y)), STR["hero_line_2"], font=f_disp,
           fill=C["content"][:3] + (158,))          # ikinci satir %62 ortuculuk
    y += line_h + 12

    f_copy = font(F_REG, DIM["text_body"])
    for line in wrap(STR["hero_copy"], f_copy, px(inner), d):
        text(gutter, y, line, f_copy, C["content_dim"])
        y += DIM["text_body"] * 1.42
    y += DIM["gap_section"] - DIM["text_body"] * 0.42

    # ── Sahne ────────────────────────────────────────────────────────
    stage_h = DIM["stage_min"]
    glass(gutter, y, inner, stage_h, DIM["radius_stage"])
    cx, cy = DP_W / 2, y + stage_h / 2
    d.rounded_rectangle([px(cx - 15), px(cy - 30), px(cx + 15), px(cy - 8)],
                        radius=px(3), outline=C["content_ghost"], width=max(1, SCALE // 3))
    d.line([(px(cx - 15), px(cy - 13)), (px(cx - 5), px(cy - 21)),
            (px(cx + 2), px(cy - 16)), (px(cx + 8), px(cy - 21)),
            (px(cx + 15), px(cy - 13))],
           fill=C["content_ghost"], width=max(1, SCALE // 3))
    text(cx, cy + 6, STR["no_photo"], font(F_MED, DIM["text_body"]),
         C["content_dim"], anchor="mm")
    text(cx, cy + 23, STR["stage_tap_hint"], font(F_REG, DIM["text_fine"]),
         C["content_ghost"], anchor="mm")
    y += stage_h + DIM["gap_block"]

    # ── Eylem cubugu ─────────────────────────────────────────────────
    cta_h = DIM["cta_height"]
    half = (inner - DIM["gap_row"]) / 2
    glass(gutter, y, half, cta_h, DIM["radius_card"])
    text(gutter + 14, y + cta_h / 2 - 8, "4K", font(F_DISPLAY_B, DIM["text_row"]),
         C["content"], anchor="lm")
    text(gutter + 14, y + cta_h / 2 + 11, STR["pick_photo_first"][:24],
         font(F_DISPLAY, 8), C["content_faint"], anchor="lm")
    # Birincil eylem: icerik rengine boyanir, yazisi zemin rengine doner
    d.rounded_rectangle(
        [px(gutter + half + DIM["gap_row"]), px(y),
         px(gutter + half + DIM["gap_row"] + half), px(y + cta_h)],
        radius=px(DIM["radius_card"]), fill=C["content"][:3] + (255,))
    text(gutter + half + DIM["gap_row"] + half / 2, y + cta_h / 2,
         STR["start"], font(F_BOLD, DIM["text_row"]), C["bg"], anchor="mm")
    y += cta_h + DIM["gap_section"]

    # ── Akordeon satirlari ───────────────────────────────────────────
    rows = (
        (STR["row_resolution"], "4K"),
        (STR["row_engine"], "Real-ESRGAN 4x"),
        (STR["row_settings"], "JPEG \u00b7 95"),
        (STR["row_device"], STR["load_balanced"]),
    )
    rh = DIM["row_height"]
    for title, value in rows:
        glass(gutter, y, inner, rh, DIM["radius_card"])
        text(gutter + 14, y + rh / 2, title, font(F_MED, DIM["text_row"]),
             C["content"], anchor="lm")
        text(DP_W - gutter - 30, y + rh / 2, value, font(F_REG, DIM["text_body"]),
             C["content_dim"], anchor="rm")
        ax = DP_W - gutter - 20
        d.line([(px(ax - 4), px(y + rh / 2 - 2)), (px(ax), px(y + rh / 2 + 2)),
                (px(ax + 4), px(y + rh / 2 - 2))],
               fill=C["content_faint"], width=max(1, SCALE // 3))
        y += rh + DIM["gap_row"]

    # ── Alt gezinme ──────────────────────────────────────────────────
    nav_h = 62
    ny = DP_H - nav_h
    d.rectangle([0, px(ny), W, H], fill=C["glass_bottom"])
    d.line([(0, px(ny)), (W, px(ny))], fill=C["glass_stroke"],
           width=max(1, SCALE // 3))
    tabs = (STR["nav_upscale"], STR["nav_history"], STR["nav_requests"])
    pad = 10
    slot = (DP_W - 2 * pad) / 3
    for i, label in enumerate(tabs):
        left = pad + i * slot
        active = i == 0
        if active:
            glass(left + 3, ny + 6, slot - 6, nav_h - 12, 11)
        text(left + slot / 2, ny + nav_h / 2, label,
             font(F_BOLD if active else F_REG, 11),
             C["content"] if active else C["content_faint"], anchor="mm")

    return img.convert("RGB")



def render_launch(theme):
    """
    Acilis ekrani.

    Gercekte bu bir Activity degil, temanin windowBackground'u: pencere
    olusur olusmaz cizilir. Zemin her iki temada da ayni koyu tondur,
    cunku acilis ani temanin cozulmesinden oncedir.
    """
    C = load_colors(os.path.join(RES, "values", "colors.xml"))
    W, H = px(DP_W), px(DP_H)
    img = Image.new("RGBA", (W, H), C["launch_bg"][:3] + (255,))
    mark_px = px(96)
    img.alpha_composite(mark_image(mark_px, "#F5F6F8"),
                        ((W - mark_px) // 2, (H - mark_px) // 2))
    return img.convert("RGB")


def render_notification(theme):
    """
    Bildirim golgesi: devam eden is ve sonuc, yan yana.

    Cizim Android'in kendi bildirim duzenini taklit eder — sistem simgesi
    solda, baslik, metin, ilerleme cubugu ve eylemler. Amac bildirimlerin
    gercekten nasil okundugunu gormek.
    """
    folder = "values" if theme == "light" else "values-night"
    C = load_colors(os.path.join(RES, folder, "colors.xml"))
    W = px(DP_W)
    H = px(248)   # iki bildirim + kenar boslugu kadar
    img = backdrop((W, H), theme).convert("RGBA")
    d = ImageDraw.Draw(img, "RGBA")

    def shade(x, y, w, h, radius):
        box = [px(x), px(y), px(x + w), px(y + h)]
        d.rounded_rectangle(box, radius=px(radius),
                            fill=C["glass_top"], outline=C["glass_stroke"],
                            width=max(1, SCALE // 3))

    gutter = 12
    inner = DP_W - 2 * gutter
    y = 16

    f_title = font(F_BOLD, DIM["text_body"])
    f_body = font(F_REG, DIM["text_fine"])
    f_action = font(F_BOLD, DIM["text_fine"])

    # ── Devam eden is ────────────────────────────────────────────────
    card_h = 104
    shade(gutter, y, inner, card_h, 14)
    mark_px = px(16)
    img.alpha_composite(mark_image(mark_px, "#%02X%02X%02X" % C["content"][:3]),
                        (px(gutter + 12), px(y + 14)))
    d.text((px(gutter + 34), px(y + 14)), "AstraUpscale",
           font=font(F_REG, 9), fill=C["content_faint"], anchor="la")
    d.text((px(gutter + 12), px(y + 32)), fmt(STR["notif_running_title"], "8K"),
           font=f_title, fill=C["content"][:3] + (255,), anchor="la")
    d.text((px(gutter + 12), px(y + 50)), fmt(STR["notif_running_text"], 45, "Buyutuluyor"),
           font=f_body, fill=C["content_dim"], anchor="la")
    # Belirli ilerleme cubugu
    bar_y = y + 70
    d.rounded_rectangle([px(gutter + 12), px(bar_y), px(DP_W - gutter - 12), px(bar_y + 2)],
                        radius=px(1), fill=C["content_ghost"])
    d.rounded_rectangle([px(gutter + 12), px(bar_y),
                         px(gutter + 12 + (inner - 24) * 0.45), px(bar_y + 2)],
                        radius=px(1), fill=C["content"][:3] + (255,))
    d.text((px(gutter + 12), px(y + 82)), STR["cancel"].upper(),
           font=f_action, fill=C["content"][:3] + (255,), anchor="la")
    y += card_h + 10

    # ── Sonuc ────────────────────────────────────────────────────────
    card_h = 108
    shade(gutter, y, inner, card_h, 14)
    img.alpha_composite(mark_image(mark_px, "#%02X%02X%02X" % C["content"][:3]),
                        (px(gutter + 12), px(y + 14)))
    d.text((px(gutter + 34), px(y + 14)), "AstraUpscale",
           font=font(F_REG, 9), fill=C["content_faint"], anchor="la")
    d.text((px(gutter + 12), px(y + 32)), STR["notif_done_title"],
           font=f_title, fill=C["content"][:3] + (255,), anchor="la")
    detail = fmt(STR["notif_done_text"], 7680, 5760, 44.2, "162 MB", 138.0)
    yy = y + 50
    for line in wrap(detail, f_body, px(inner - 24), d):
        d.text((px(gutter + 12), px(yy)), line, font=f_body, fill=C["content_dim"], anchor="la")
        yy += DIM["text_fine"] * 1.5
    d.text((px(gutter + 12), px(y + 86)), STR["share"].upper(),
           font=f_action, fill=C["content"][:3] + (255,), anchor="la")

    return img.convert("RGB")


def main():
    os.makedirs(DOCS, exist_ok=True)
    for theme, name in (("light", "tema-acik.png"), ("dark", "tema-koyu.png")):
        out = os.path.join(DOCS, name)
        render(theme).save(out)
        print("yazildi: docs/%s" % name)

    render_launch("dark").save(os.path.join(DOCS, "acilis.png"))
    print("yazildi: docs/acilis.png")

    for theme, name in (("dark", "bildirim-koyu.png"), ("light", "bildirim-acik.png")):
        render_notification(theme).save(os.path.join(DOCS, name))
        print("yazildi: docs/%s" % name)


if __name__ == "__main__":
    main()
