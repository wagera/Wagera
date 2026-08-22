"""SwinIR'in timm'den kullandigi uc yardimci. DropPath degerlendirme modunda
birim islevdir; trunc_normal_ yalnizca ilk deger atamasinda kullanilir ve biz
egitilmis agirliklari yukledigimiz icin onemsizdir."""
import torch.nn as nn


class DropPath(nn.Module):
    def __init__(self, drop_prob=None):
        super().__init__()
        self.drop_prob = drop_prob

    def forward(self, x):
        return x


def to_2tuple(x):
    return x if isinstance(x, tuple) else (x, x)


def trunc_normal_(tensor, mean=0., std=1., a=-2., b=2.):
    with_no_grad = getattr(tensor, "normal_", None)
    if with_no_grad is not None:
        tensor.data.normal_(mean, std).clamp_(a, b)
    return tensor
