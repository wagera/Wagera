#!/usr/bin/env python3
"""BSRGAN agirliklarini ncnn bicimine cevirir.

BSRGAN'in ureteci Real-ESRGAN x4plus ile ayni RRDBNet mimarisidir (23 blok,
64 kanal, 32 buyume, 351 evrisim). Bu yuzden ONNX'e ugramadan, x4plus'in
.param dosyasi aynen kullanilip yalnizca .bin yeniden yazilabilir.

ncnn .bin duzeni (her evrisim icin sirayla):
    [4 bayt etiket][agirliklar][sapmalar]
Etiket 0x01306B47 fp16 agirlik demektir; sapmalar her zaman ham fp32'dir.

Kullanim:
    python3 bsrgan_to_ncnn.py BSRGAN.pth realesrgan-x4plus.param cikti.bin
"""

import re
import struct
import sys

import numpy as np
import torch

FP16_TAG = 0x01306B47


def canonical_order(blocks=23):
    """RRDBNet'in ileri gecis sirasindaki evrisim adlari (BSRGAN adlandirmasi)."""
    names = ["conv_first"]
    for b in range(blocks):
        for rdb in range(1, 4):
            for conv in range(1, 6):
                names.append("RRDB_trunk.%d.RDB%d.conv%d" % (b, rdb, conv))
    names += ["trunk_conv", "upconv1", "upconv2", "HRconv", "conv_last"]
    return names


def parse_param(path):
    """param dosyasindaki Convolution katmanlarini (ad, cikis, agirlik sayisi) dondurur."""
    convs = []
    for line in open(path).read().splitlines()[2:]:
        parts = line.split()
        if not parts or parts[0] != "Convolution":
            continue
        opts = {}
        for tok in parts[4:]:
            if "=" in tok and not tok.startswith("-"):
                k, v = tok.split("=", 1)
                opts[k] = v
        convs.append((parts[1], int(opts["0"]), int(opts["6"])))
    return convs


def main():
    if len(sys.argv) != 4:
        print(__doc__)
        return 2
    pth, param, out_bin = sys.argv[1:4]

    state = torch.load(pth, map_location="cpu", weights_only=True)
    for key in ("params_ema", "params", "state_dict"):
        if key in state:
            state = state[key]
            break

    convs = parse_param(param)
    names = canonical_order()
    if len(convs) != len(names):
        print("uyusmazlik: param %d evrisim, beklenen %d" % (len(convs), len(names)))
        return 1

    # Once tum boyutlari dogrula: tek bir uyusmazlik bile donusumu gecersiz kilar.
    for (layer, num_out, weight_size), name in zip(convs, names):
        w = state["%s.weight" % name]
        b = state["%s.bias" % name]
        if w.numel() != weight_size or b.numel() != num_out:
            print("boyut uyusmazligi %s <-> %s: %d/%d vs %d/%d"
                  % (layer, name, w.numel(), b.numel(), weight_size, num_out))
            return 1
    print("351 evrisimin tamami boyut olarak eslesti")

    with open(out_bin, "wb") as f:
        for name in names:
            w = state["%s.weight" % name].numpy().astype(np.float16)
            b = state["%s.bias" % name].numpy().astype(np.float32)
            f.write(struct.pack("<I", FP16_TAG))
            f.write(w.tobytes())
            f.write(b.tobytes())
    print("yazildi:", out_bin)
    return 0


if __name__ == "__main__":
    sys.exit(main())
