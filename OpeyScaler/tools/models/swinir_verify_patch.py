"""Yamali modelin ozgun SwinIR ile ayni sonucu verdigini dogrular."""
import copy, sys, torch
import network_swinir as ns

def build(variant):
    if variant == "S":
        m = ns.SwinIR(upscale=4, in_chans=3, img_size=64, window_size=8, img_range=1.,
                      depths=[6,6,6,6], embed_dim=60, num_heads=[6,6,6,6],
                      mlp_ratio=2, upsampler='pixelshuffledirect', resi_connection='1conv')
        path="/opt/deps/swinir/002_lightweightSR_DIV2K_s64w8_SwinIR-S_x4.pth"; key='params'
    else:
        m = ns.SwinIR(upscale=4, in_chans=3, img_size=64, window_size=8, img_range=1.,
                      depths=[6]*6, embed_dim=180, num_heads=[6]*6,
                      mlp_ratio=2, upsampler='nearest+conv', resi_connection='1conv')
        path="/opt/deps/swinir/003_realSR_BSRGAN_DFO_s64w8_SwinIR-M_x4_GAN.pth"; key='params_ema'
    sd = torch.load(path, map_location='cpu', weights_only=True)
    sd = sd.get(key, sd.get('params', sd))
    m.load_state_dict(sd, strict=True)
    m.eval()
    return m

variant = sys.argv[1]; size = int(sys.argv[2])
torch.manual_seed(0)
x = torch.rand(1, 3, size, size)

orig = build(variant)
with torch.no_grad():
    y_ref = orig(x)

import swinir_ncnn_patch as ncnn_patch
ncnn_patch.apply()
patched = build(variant)
with torch.no_grad():
    y_new = patched(x)

d = (y_ref - y_new).abs()
print("ozgun vs yamali: maks fark %.3e, ortalama %.3e" % (d.max().item(), d.mean().item()))
print("SONUC:", "AYNI" if d.max().item() < 1e-4 else "FARKLI")
