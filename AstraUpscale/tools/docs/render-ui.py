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
    Sayfa zemini.

    Alet dilinde zemin duzdur. Onceki dilde kosegen bir gecis, iki isik
    patlamasi ve kenar karartmasi vardi — hepsi "sinematik" bir derinlik
    icindi. Yeni dil derinlik degil kesinlik ariyor, o yuzden zemin tek
    renk ve uzerine ince bir olcu izgarasi geliyor.
    """
    w, h = size
    folder = "values" if theme == "light" else "values-night"
    C = load_colors(os.path.join(RES, folder, "colors.xml"))
    img = Image.new("RGB", (w, h), C["bg"][:3])
    d = ImageDraw.Draw(img, "RGBA")

    # Olcu izgarasi: 8dp araliklarla, cok soluk
    step = px(8)
    grid = C["grid"]
    for x in range(0, w, step):
        d.line([(x, 0), (x, h)], fill=grid, width=1)
    for y in range(0, h, step):
        d.line([(0, y), (w, y)], fill=grid, width=1)
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
    """Buyut sayfasi — alet paneli."""
    folder = "values" if theme == "light" else "values-night"
    C = load_colors(os.path.join(RES, folder, "colors.xml"))

    W, H = px(DP_W), px(DP_H)
    img = backdrop((W, H), theme).convert("RGBA")
    d = ImageDraw.Draw(img, "RGBA")

    def rule(y, x0=0, x1=DP_W):
        d.line([(px(x0), px(y)), (px(x1), px(y))], fill=C["rule"],
               width=max(1, SCALE // 3))

    def text(x, y, s_, f, color, anchor="la"):
        d.text((px(x), px(y)), s_, font=f, fill=color[:3] + (255,), anchor=anchor)

    def tracked_width(s_, f, spacing):
        """Harf araligi acilmis metnin toplam genisligi (piksel)."""
        if not s_:
            return 0
        return sum(d.textlength(ch, font=f) for ch in s_) + spacing * (len(s_) - 1)

    def tracked(x, y, s_, f, color, spacing, anchor="la"):
        """
        Harf araligi acilmis metin.

        PIL letterSpacing bilmez, harfler tek tek yerlestirilir. Hizalama
        icin once toplam genislik olculur; onceki surumde metin once yanlis
        yere cizilip uzeri zeminle boyanmaya calisiliyordu ve ekranda iz
        birakiyordu.
        """
        cx = px(x)
        if anchor == "ra":
            cx -= tracked_width(s_, f, spacing)
        elif anchor == "ma":
            cx -= tracked_width(s_, f, spacing) / 2
        for ch in s_:
            d.text((cx, px(y)), ch, font=f, fill=color[:3] + (255,), anchor="la")
            cx += d.textlength(ch, font=f) + spacing

    gutter = DIM["gutter"]
    inner = DP_W - 2 * gutter

    # ── Baslik cubugu ────────────────────────────────────────────────
    hh = DIM["header_height"]
    mark_px = px(22)
    img.alpha_composite(mark_image(mark_px, "#%02X%02X%02X" % C["content"][:3]),
                        (px(gutter), px(hh / 2) - mark_px // 2))
    bx = px(gutter) + mark_px + px(10)
    f_b1, f_b2 = font(F_BOLD, DIM["text_body"]), font(F_REG, DIM["text_body"])
    d.text((bx, px(hh / 2)), STR["brand_first"], font=f_b1,
           fill=C["content"][:3] + (255,), anchor="lm")
    bx += int(d.textlength(STR["brand_first"], font=f_b1)) + px(3)
    d.text((bx, px(hh / 2)), STR["brand_second"], font=f_b2,
           fill=C["content_dim"][:3] + (255,), anchor="lm")
    for i, label in enumerate((STR["theme_dark"], "TR")):
        bw = 38 if i == 0 else 30
        bxx = (DP_W - gutter - (30 + 6) - bw) if i == 0 else (DP_W - gutter - bw)
        d.rounded_rectangle([px(bxx), px(hh / 2 - 15), px(bxx + bw), px(hh / 2 + 15)],
                            radius=px(3), outline=C["rule"], width=max(1, SCALE // 3))
        text(bxx + bw / 2, hh / 2, label, font(F_REG, DIM["text_fine"]),
             C["content_soft"], anchor="mm")
    rule(hh)

    # ── Durum seridi ─────────────────────────────────────────────────
    strip_h = 30
    d.rectangle([0, px(hh), W, px(hh + strip_h)], fill=C["surface_low"])
    dy = hh + strip_h / 2
    d.ellipse([px(gutter), px(dy - 3), px(gutter + 6), px(dy + 3)], fill=C["signal"])
    tracked(gutter + 14, dy - 5, fmt(STR["status_format"], "GPU", 6),
            font(F_DISPLAY, 10), C["content_dim"], px(0.4))
    tracked(DP_W - gutter, dy - 5, STR["thermal_none"], font(F_DISPLAY, 10),
            C["content_faint"], px(0.4), anchor="ra")
    rule(hh + strip_h)

    y = hh + strip_h

    # ── Vizor ────────────────────────────────────────────────────────
    stage_h = DIM["stage_min"]
    d.rectangle([0, px(y), W, px(y + stage_h)], fill=C["surface_low"])
    # Kose ayraclari
    arm, thick = px(18), max(2, int(2 * SCALE / 3))
    for cx, cy, sx, sy in ((0, y, 1, 1), (DP_W, y, -1, 1),
                           (0, y + stage_h, 1, -1), (DP_W, y + stage_h, -1, -1)):
        X, Y = px(cx), px(cy)
        d.rectangle([min(X, X + sx * arm), min(Y, Y + sy * thick),
                     max(X, X + sx * arm), max(Y, Y + sy * thick)], fill=C["rule_strong"])
        d.rectangle([min(X, X + sx * thick), min(Y, Y + sy * arm),
                     max(X, X + sx * thick), max(Y, Y + sy * arm)], fill=C["rule_strong"])

    cx, cy = DP_W / 2, y + stage_h / 2
    d.rounded_rectangle([px(cx - 13), px(cy - 28), px(cx + 13), px(cy - 9)],
                        radius=px(2), outline=C["content_ghost"], width=max(1, SCALE // 3))
    d.line([(px(cx - 13), px(cy - 13)), (px(cx - 4), px(cy - 20)),
            (px(cx + 3), px(cy - 15)), (px(cx + 13), px(cy - 22))],
           fill=C["content_ghost"], width=max(1, SCALE // 3))
    f_empty = font(F_DISPLAY, DIM["text_fine"])
    tracked(cx, cy + 2, STR["no_photo"].upper(), f_empty, C["content_dim"], px(1.0),
            anchor="ma")
    text(cx, cy + 22, STR["stage_tap_hint"], font(F_REG, DIM["text_fine"]),
         C["content_faint"], anchor="mm")
    y += stage_h
    rule(y)

    # ── Kaynak kunyesi ───────────────────────────────────────────────
    cap_h = 38
    bw = 84
    d.rounded_rectangle([px(DP_W - gutter - bw), px(y + 5), px(DP_W - gutter), px(y + 33)],
                        radius=px(3), outline=C["rule"], width=max(1, SCALE // 3))
    text(DP_W - gutter - bw / 2, y + cap_h / 2, STR["pick_photo"],
         font(F_REG, DIM["text_fine"]), C["content_soft"], anchor="mm")
    y += cap_h
    rule(y)

    # ── Cozunurluk olcegi ────────────────────────────────────────────
    y += DIM["gap_block"]
    tracked(gutter, y, STR["row_resolution"].upper(), font(F_DISPLAY, DIM["text_label"]),
            C["content_faint"], px(1.4))
    text(DP_W - gutter, y - 4, "8K", font(F_DISPLAY_B, DIM["text_read"]),
         C["content"], anchor="ra")

    y += 22
    scale_h = 52
    ticks = ["2K", "2.5K", "3K", "4K", "5K", "6K", "8K", "10K", "12K", "16K",
             "32K", "64K", "128K", "256K"]
    slot = inner / len(ticks)
    selected = 6
    for i, label in enumerate(ticks):
        tier = 0 if i < 5 else (1 if i < 9 else 2)
        bar_h = 18 + tier * 9
        tx = gutter + slot * (i + 0.5)
        col = C["signal"] if i == selected else C["content_ghost"]
        d.rectangle([px(tx - 1.5), px(y + scale_h - bar_h), px(tx + 1.5), px(y + scale_h)],
                    fill=col)
        if i == selected:
            text(tx, y + scale_h + 9, label, font(F_DISPLAY, 9), C["signal"], anchor="mm")
    y += scale_h + 18

    for i, (label, align) in enumerate(((STR["tier_standard"], "la"),
                                        (STR["tier_high"], "mm"),
                                        (STR["tier_extreme"], "ra"))):
        pos = gutter if i == 0 else (DP_W / 2 if i == 1 else DP_W - gutter)
        tracked(pos, y, label.upper(), font(F_DISPLAY, 9), C["content_ghost"], px(1.0),
                anchor={"la": "la", "mm": "ma", "ra": "ra"}[align])
    y += 20

    text(gutter, y, "7680 × 5760  ·  44.2 MP", font(F_DISPLAY, DIM["text_fine"]),
         C["content_dim"])
    y += 26

    # ── Eylem cubugu ─────────────────────────────────────────────────
    cta_h = DIM["cta_height"]
    d.rounded_rectangle([px(gutter), px(y), px(DP_W - gutter), px(y + cta_h)],
                        radius=px(3), fill=C["signal"])
    text(DP_W / 2, y + cta_h / 2, STR["start"], font(F_BOLD, DIM["text_row"]),
         C["signal_on"], anchor="mm")
    y += cta_h + DIM["gap_section"]

    # ── Kunye ────────────────────────────────────────────────────────
    tracked(gutter, y, STR["spec_title"].upper(), font(F_DISPLAY, DIM["text_label"]),
            C["content_faint"], px(1.4))
    y += 18

    rows = ((STR["row_engine"], "Real-ESRGAN 4x"),
            (STR["row_settings"], "JPEG · 95"),
            (STR["row_device"], STR["load_balanced"]))
    rh = DIM["row_height"]
    for title, value in rows:
        tracked(gutter, y + rh / 2 - 5, title.upper(), font(F_DISPLAY, DIM["text_fine"]),
                C["content_faint"], px(1.0))
        text(DP_W - gutter - 22, y + rh / 2, value, font(F_MED, DIM["text_body"]),
             C["content"], anchor="rm")
        ax = DP_W - gutter - 8
        d.line([(px(ax - 4), px(y + rh / 2 - 2)), (px(ax - 1), px(y + rh / 2 + 1)),
                (px(ax + 2), px(y + rh / 2 - 2))],
               fill=C["content_faint"], width=max(1, SCALE // 3))
        y += rh
        rule(y)

    # ── Alt gezinme ──────────────────────────────────────────────────
    nav_h = 62
    ny = DP_H - nav_h
    d.rectangle([0, px(ny), W, H], fill=C["bg"])
    rule(ny)
    tabs = (STR["nav_upscale"], STR["nav_history"], STR["nav_requests"])
    pad = 10
    slot = (DP_W - 2 * pad) / 3
    for i, label in enumerate(tabs):
        left = pad + i * slot
        active = i == 0
        if active:
            d.rectangle([px(left + 14), px(ny), px(left + slot - 14), px(ny + 2)],
                        fill=C["signal"])
        text(left + slot / 2, ny + nav_h / 2, label,
             font(F_BOLD if active else F_REG, DIM["text_fine"]),
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
                            fill=C["surface"], outline=C["rule"],
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



def render_compare(theme):
    """
    Oncesi/sonrasi karsilastirma ekrani.

    Iki taraf ayni bakis penceresini paylasir; solda kaynak ayni olcude
    buyutulmus, sagda sonuc. Cizimde fark, sol tarafa bulaniklik ve sag
    tarafa keskinlik uygulanarak temsil edilir — gercek uygulamada bu fark
    modelin kendi ciktisidir.
    """
    folder = "values" if theme == "light" else "values-night"
    C = load_colors(os.path.join(RES, folder, "colors.xml"))
    W, H = px(DP_W), px(DP_H)
    img = Image.new("RGBA", (W, H), (8, 9, 11, 255))
    d = ImageDraw.Draw(img, "RGBA")

    hh = DIM["header_height"]
    gutter = DIM["gutter"]

    # ── Baslik ───────────────────────────────────────────────────────
    d.text((px(gutter), px(hh / 2)), STR["compare_title"],
           font=font(F_BOLD, DIM["text_row"]), fill=(245, 246, 248, 255), anchor="lm")
    for i, (label, w) in enumerate((("1:1", 52), ("", 32))):
        bx = DP_W - gutter - (52 + 6) * (1 - i) - w if i == 0 else DP_W - gutter - w
        d.rounded_rectangle([px(bx), px(hh / 2 - 16), px(bx + w), px(hh / 2 + 16)],
                            radius=px(9), outline=(60, 64, 72, 255), width=max(1, SCALE // 3))
        if label:
            d.text((px(bx + w / 2), px(hh / 2)), label, font=font(F_DISPLAY, DIM["text_fine"]),
                   fill=(197, 201, 207, 255), anchor="mm")
        else:
            cx, cy, a = px(bx + w / 2), px(hh / 2), px(5)
            d.line([(cx - a, cy - a), (cx + a, cy + a)], fill=(197, 201, 207, 255), width=SCALE)
            d.line([(cx + a, cy - a), (cx - a, cy + a)], fill=(197, 201, 207, 255), width=SCALE)

    # ── Govde: yapay bir detay dokusu ────────────────────────────────
    body_top, body_bottom = hh + 8, DP_H - 62
    bw, bh = W, px(body_bottom - body_top)
    rnd = random.Random(7)
    detail = Image.new("RGB", (bw // 6, bh // 6))
    dp_ = detail.load()
    for yy in range(detail.height):
        for xx in range(detail.width):
            # Ic ice halkalar + gren: buyutmenin fark ettigi turden detay
            r = math.hypot(xx - detail.width * 0.42, yy - detail.height * 0.46)
            v = 128 + 92 * math.sin(r * 0.44) + rnd.gauss(0, 16)
            v = max(0, min(255, int(v)))
            dp_[xx, yy] = (v, int(v * 0.94), int(v * 0.86))
    detail = detail.resize((bw, bh), Image.LANCZOS)

    split = int(bw * 0.46)
    # Sol: kaynak — buyutulmus, yumusak
    left = detail.crop((0, 0, split, bh))
    left = left.resize((max(1, split // 5), max(1, bh // 5)), Image.BILINEAR)
    left = left.resize((split, bh), Image.BILINEAR)
    img.paste(left, (0, px(body_top)))
    # Sag: sonuc — keskin
    img.paste(detail.crop((split, 0, bw, bh)), (split, px(body_top)))

    # Bolme cizgisi ve tutamagi
    d.line([(split, px(body_top)), (split, px(body_bottom))],
           fill=(255, 255, 255, 255), width=max(2, int(1.5 * SCALE)))
    hy = px((body_top + body_bottom) / 2)
    r = px(17)
    d.ellipse([split - r, hy - r, split + r, hy + r], fill=(255, 255, 255, 255))
    a = r * 0.42
    for sx in (-1, 1):
        d.line([(split + sx * a * 0.4, hy - a * 0.55), (split + sx * a, hy)],
               fill=(16, 18, 21, 255), width=max(1, int(2 * SCALE)))
        d.line([(split + sx * a * 0.4, hy + a * 0.55), (split + sx * a, hy)],
               fill=(16, 18, 21, 255), width=max(1, int(2 * SCALE)))

    # ── Alt etiketler ────────────────────────────────────────────────
    # Etiketler kendi satirinda, ipucu altta ortali
    ly = DP_H - 46
    d.text((px(gutter), px(ly)), STR["compare_before"], font=font(F_BOLD, DIM["text_body"]),
           fill=(197, 201, 207, 255), anchor="lm")
    d.text((px(DP_W - gutter), px(ly)), STR["compare_after"], font=font(F_BOLD, DIM["text_body"]),
           fill=(197, 201, 207, 255), anchor="rm")
    d.text((px(DP_W / 2), px(ly + 20)), STR["compare_hint"], font=font(F_REG, DIM["text_fine"]),
           fill=(95, 100, 108, 255), anchor="mm")

    return img.convert("RGB")



def render_discover(theme):
    """
    Kesfet sayfasi.

    Icerik Supabase'den gelir; burada supabase/schema.sql icindeki ornek
    kayitlarin aynisi cizilir, boylece cizim uydurma bir icerik gostermez.
    """
    folder = "values" if theme == "light" else "values-night"
    C = load_colors(os.path.join(RES, folder, "colors.xml"))
    W, H = px(DP_W), px(DP_H)
    img = backdrop((W, H), theme).convert("RGBA")
    d = ImageDraw.Draw(img, "RGBA")

    def tracked_width(s_, f, sp):
        return sum(d.textlength(ch, font=f) for ch in s_) + sp * (len(s_) - 1) if s_ else 0

    def tracked(x, y, s_, f, color, sp, anchor="la"):
        cx = px(x)
        if anchor == "ra":
            cx -= tracked_width(s_, f, sp)
        for ch in s_:
            d.text((cx, px(y)), ch, font=f, fill=color[:3] + (255,), anchor="la")
            cx += d.textlength(ch, font=f) + sp

    def text(x, y, s_, f, color, anchor="la"):
        d.text((px(x), px(y)), s_, font=f, fill=color[:3] + (255,), anchor=anchor)

    gutter = DIM["gutter"]
    inner = DP_W - 2 * gutter
    hh = DIM["header_height"]

    # Baslik cubugu
    mark_px = px(22)
    img.alpha_composite(mark_image(mark_px, "#%02X%02X%02X" % C["content"][:3]),
                        (px(gutter), px(hh / 2) - mark_px // 2))
    bx = px(gutter) + mark_px + px(10)
    f_b1 = font(F_BOLD, DIM["text_body"])
    d.text((bx, px(hh / 2)), STR["brand_first"], font=f_b1,
           fill=C["content"][:3] + (255,), anchor="lm")
    bx += int(d.textlength(STR["brand_first"], font=f_b1)) + px(3)
    d.text((bx, px(hh / 2)), STR["brand_second"], font=font(F_REG, DIM["text_body"]),
           fill=C["content_dim"][:3] + (255,), anchor="lm")
    d.line([(0, px(hh)), (W, px(hh))], fill=C["rule"], width=max(1, SCALE // 3))

    y = hh + DIM["gap_block"]
    tracked(gutter, y, STR["nav_discover"].upper(), font(F_DISPLAY, DIM["text_label"]),
            C["content_faint"], px(1.4))
    bw = 66
    d.rounded_rectangle([px(DP_W - gutter - bw), px(y - 7), px(DP_W - gutter), px(y + 21)],
                        radius=px(3), outline=C["rule"], width=max(1, SCALE // 3))
    text(DP_W - gutter - bw / 2, y + 7, STR["history_refresh"],
         font(F_REG, DIM["text_fine"]), C["content_soft"], anchor="mm")
    y += 30

    # Kartlar: schema.sql icindeki ornek kayitlar
    cards = [
        (STR.get("_m1", "İPUCU"), "Önce karşılaştırın",
         "Büyütme bittiğinde Karşılaştır'a dokunun ve çift dokunuşla 1:1'e "
         "geçin. Büyütmenin gerçekten ne yaptığı ancak 1:1'de görünür."),
        ("MODEL SEÇİMİ", "Fotoğraf mı, çizim mi?",
         "Real-ESRGAN x4plus gerçek fotoğraflarda; Anime 6B ve Real-CUGAN "
         "çizim, anime ve düz renkli görsellerde belirgin biçimde daha iyi "
         "sonuç verir."),
        ("GÜRÜLTÜ", "64K üstünde gürültü önce temizlenir",
         "Çok yüksek büyütmelerde kaynaktaki gren de büyür. Uygulama 64K ve "
         "üzerinde, büyütmeden önce kenar koruyan bir temizlik uygular."),
    ]
    f_meta = font(F_DISPLAY, 10)
    f_title = font(F_MED, 15)
    f_body = font(F_REG, DIM["text_body"])

    for meta, title, body in cards:
        lines = wrap(body, f_body, px(inner - 32), d)
        card_h = 14 + 12 + 6 + 20 + 7 + len(lines) * 19 + 16
        d.rounded_rectangle([px(gutter), px(y), px(DP_W - gutter), px(y + card_h)],
                            radius=px(3), fill=C["surface"], outline=C["rule"],
                            width=max(1, SCALE // 3))
        yy = y + 14
        tracked(gutter + 16, yy, meta, f_meta, C["signal"], px(1.2))
        yy += 18
        text(gutter + 16, yy, title, f_title, C["content"])
        yy += 24
        for line in lines:
            text(gutter + 16, yy, line, f_body, C["content_dim"])
            yy += 19
        y += card_h + 8

    # Yasal satiri
    y += DIM["gap_section"] - 8
    d.line([(0, px(y)), (W, px(y))], fill=C["rule"], width=max(1, SCALE // 3))
    rh = DIM["row_height"]
    tracked(gutter, y + rh / 2 - 5, STR["legal_title"].upper(),
            font(F_DISPLAY, DIM["text_fine"]), C["content_faint"], px(1.0))
    text(DP_W - gutter - 22, y + rh / 2, STR["legal_value"], font(F_MED, DIM["text_body"]),
         C["content"], anchor="rm")
    ax = DP_W - gutter - 8
    d.line([(px(ax - 4), px(y + rh / 2 - 2)), (px(ax - 1), px(y + rh / 2 + 1)),
            (px(ax + 2), px(y + rh / 2 - 2))],
           fill=C["content_faint"], width=max(1, SCALE // 3))
    y += rh
    d.line([(0, px(y)), (W, px(y))], fill=C["rule"], width=max(1, SCALE // 3))

    # Alt gezinme: dort sekme
    nav_h = 62
    ny = DP_H - nav_h
    d.rectangle([0, px(ny), W, H], fill=C["bg"])
    d.line([(0, px(ny)), (W, px(ny))], fill=C["rule"], width=max(1, SCALE // 3))
    tabs = (STR["nav_upscale"], STR["nav_history"], STR["nav_discover"],
            STR["nav_requests"])
    pad = 10
    slot = (DP_W - 2 * pad) / 4
    for i, label in enumerate(tabs):
        left = pad + i * slot
        active = i == 2
        if active:
            d.rectangle([px(left + 12), px(ny), px(left + slot - 12), px(ny + 2)],
                        fill=C["signal"])
        text(left + slot / 2, ny + nav_h / 2, label,
             font(F_BOLD if active else F_REG, DIM["text_fine"]),
             C["content"] if active else C["content_faint"], anchor="mm")

    return img.convert("RGB")


def main():
    os.makedirs(DOCS, exist_ok=True)
    for theme, name in (("light", "tema-acik.png"), ("dark", "tema-koyu.png")):
        out = os.path.join(DOCS, name)
        render(theme).save(out)
        print("yazildi: docs/%s" % name)

    render_launch("dark").save(os.path.join(DOCS, "acilis.png"))
    print("yazildi: docs/acilis.png")

    render_compare("dark").save(os.path.join(DOCS, "karsilastirma.png"))
    print("yazildi: docs/karsilastirma.png")

    render_discover("dark").save(os.path.join(DOCS, "kesfet.png"))
    print("yazildi: docs/kesfet.png")

    for theme, name in (("dark", "bildirim-koyu.png"), ("light", "bildirim-acik.png")):
        render_notification(theme).save(os.path.join(DOCS, name))
        print("yazildi: docs/%s" % name)


if __name__ == "__main__":
    main()
