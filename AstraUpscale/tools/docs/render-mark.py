#!/usr/bin/env python3
"""
Isareti (mark_astra.xml) PNG'ye cevirip gozle denetlenebilir hale getirir.

VectorDrawable ile SVG'nin pathData sozdizimi ayni oldugu icin yollar
dogrudan tasinabilir; group/rotation ise SVG transform'una cevrilir.
Amac, isareti derleyip telefona atmadan once gercekten nasil gorundugunu
gormek — "herhalde iyidir" demeden.

Kullanim:  python3 tools/docs/render-mark.py [cikti.png]
"""

import os
import sys
import xml.etree.ElementTree as ET

import cairosvg

NS = "{http://schemas.android.com/apk/res/android}"
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
VECTOR = os.path.join(ROOT, "app", "src", "main", "res", "drawable", "mark_astra.xml")


def to_svg(vector_path, fill="#F5F6F8", bg="#050506", size=512):
    root = ET.parse(vector_path).getroot()
    vw = float(root.get(NS + "viewportWidth"))
    vh = float(root.get(NS + "viewportHeight"))

    parts = []

    def emit_path(el, transform=""):
        d = " ".join(el.get(NS + "pathData").split())
        alpha = el.get(NS + "fillAlpha", "1")
        rule = el.get(NS + "fillType", "nonZero")
        rule = "evenodd" if rule.lower() == "evenodd" else "nonzero"
        parts.append(
            '<path d="%s" fill="%s" fill-opacity="%s" fill-rule="%s"%s/>'
            % (d, fill, alpha, rule, transform)
        )

    for el in root:
        if el.tag == "path":
            emit_path(el)
        elif el.tag == "group":
            rot = el.get(NS + "rotation", "0")
            px = el.get(NS + "pivotX", "0")
            py = el.get(NS + "pivotY", "0")
            t = ' transform="rotate(%s %s %s)"' % (rot, px, py)
            for child in el:
                if child.tag == "path":
                    emit_path(child, t)

    return (
        '<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" '
        'viewBox="0 0 %g %g"><rect width="%g" height="%g" fill="%s"/>%s</svg>'
        % (size, size, vw, vh, vw, vh, bg, "".join(parts))
    )


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "/tmp/mark.png"
    cairosvg.svg2png(bytestring=to_svg(VECTOR).encode("utf-8"), write_to=out)
    print("yazildi:", out)


if __name__ == "__main__":
    main()
