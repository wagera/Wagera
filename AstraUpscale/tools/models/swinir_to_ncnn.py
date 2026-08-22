"""Yamali SwinIR'i pnnx ile ncnn'e cevirir."""
import sys, torch
import swinir_ncnn_patch as ncnn_patch
ncnn_patch.apply()
import network_swinir as ns

variant, size = sys.argv[1], int(sys.argv[2])
if variant == "S":
    model = ns.SwinIR(upscale=4, in_chans=3, img_size=64, window_size=8, img_range=1.,
                      depths=[6,6,6,6], embed_dim=60, num_heads=[6,6,6,6],
                      mlp_ratio=2, upsampler='pixelshuffledirect', resi_connection='1conv')
    path = "/opt/deps/swinir/002_lightweightSR_DIV2K_s64w8_SwinIR-S_x4.pth"; key = 'params'
else:
    model = ns.SwinIR(upscale=4, in_chans=3, img_size=64, window_size=8, img_range=1.,
                      depths=[6]*6, embed_dim=180, num_heads=[6]*6,
                      mlp_ratio=2, upsampler='nearest+conv', resi_connection='1conv')
    path = "/opt/deps/swinir/003_realSR_BSRGAN_DFO_s64w8_SwinIR-M_x4_GAN.pth"; key = 'params_ema'

sd = torch.load(path, map_location='cpu', weights_only=True)
model.load_state_dict(sd.get(key, sd.get('params', sd)), strict=True)
model.eval()
print("parametre: %.2f M" % (sum(p.numel() for p in model.parameters())/1e6))

x = torch.rand(1, 3, size, size)
import pnnx
pnnx.export(model, "swin_%s_%d.pt" % (variant, size), x)
print("bitti")
