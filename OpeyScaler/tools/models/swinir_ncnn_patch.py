"""SwinIR'i ncnn'e cevrilebilir hale getiren yamalar.

ncnn'de bir tensorun toplu (batch) boyutu daima 0. boyuttur ve kalan boyut
sayisi 4'u gecemez. Ozgun SwinIR ise pencere bolme ve dikkat hesabinda 5-6
rank'li yeniden sekillendirmeler ve toplu boyutu yer degistiren permute'lar
kullanir; pnnx bunlari ifade edemeyip parametresiz Reshape uretir.

Buradaki yamalar ayni matematigi, toplu boyutu hic oynatmadan ve en fazla 4
gercek boyut kullanarak yeniden yazar. Toplu boyut 1 varsayilir (izleme boyle
yapilir). Sayisal sonuc ozgun modelle ayni kalir.
"""
import torch
import torch.nn as nn

import network_swinir as ns  # SwinIR deposundan alinir (bkz. README)


def window_partition_ncnn(x, ws):
    """(1,H,W,C) -> (1,nW,ws*ws,C); tum ara adimlar en fazla 4 gercek boyut."""
    B, H, W, C = x.shape
    Hb, Wb = H // ws, W // ws
    x = x.reshape(B, Hb, ws, W, C)            # [hb][i][w][c]
    x = x.permute(0, 1, 3, 2, 4)              # [hb][w][i][c]
    x = x.reshape(B, Hb, Wb, ws * ws * C)     # [hb][wb][j,(i,c)]
    x = x.reshape(B, Hb * Wb, ws, ws * C)     # [n][j][(i,c)]
    x = x.reshape(B, Hb * Wb, ws, ws, C)      # [n][j][i][c]
    x = x.permute(0, 1, 3, 2, 4)              # [n][i][j][c]
    return x.reshape(B, Hb * Wb, ws * ws, C)  # [n][i*ws+j][c]


def window_reverse_ncnn(x, ws, H, W):
    """(1,nW,ws*ws,C) -> (1,H,W,C); window_partition_ncnn'in tersi."""
    B = x.shape[0]
    C = x.shape[3]
    Hb, Wb = H // ws, W // ws
    x = x.reshape(B, Hb * Wb, ws, ws, C)      # [n][i][j][c]
    x = x.permute(0, 1, 3, 2, 4)              # [n][j][i][c]
    x = x.reshape(B, Hb * Wb, ws, ws * C)     # [n][j][(i,c)]
    x = x.reshape(B, Hb, Wb, ws * ws * C)     # [hb][wb][j,(i,c)]
    x = x.reshape(B, Hb, Wb * ws, ws * C)     # [hb][w][(i,c)]
    x = x.reshape(B, Hb, W, ws, C)            # [hb][w][i][c]
    x = x.permute(0, 1, 3, 2, 4)              # [hb][i][w][c]
    return x.reshape(B, H, W, C)


def attention_forward_ncnn(self, x, mask=None):
    """
    x: (1, nW, N, C). Fuzelenmis qkv yerine uc ayri dogrusal katman kullanilir;
    boylece toplu boyutu yer degistiren permute(2,0,3,1,4) gerekmez.
    """
    B, nW, N, C = x.shape
    nH = self.num_heads
    d = C // nH

    def project(weight, bias):
        y = torch.nn.functional.linear(x, weight, bias)   # (1,nW,N,C)
        y = y.reshape(B, nW, N, nH, d).permute(0, 1, 3, 2, 4)  # (1,nW,nH,N,d)
        return y.reshape(B, nW * nH, N, d)

    w = self.qkv.weight
    b = self.qkv.bias
    q = project(w[0:C], None if b is None else b[0:C])
    k = project(w[C:2 * C], None if b is None else b[C:2 * C])
    v = project(w[2 * C:3 * C], None if b is None else b[2 * C:3 * C])

    q = q * self.scale
    attn = torch.matmul(q, k.transpose(-2, -1))            # (1,nW*nH,N,N)

    bias_table = self.relative_position_bias_table[self.relative_position_index.reshape(-1)]
    rpb = bias_table.reshape(N, N, nH).permute(2, 0, 1)     # (nH,N,N)

    attn = attn.reshape(B, nW, nH, N, N)
    attn = attn + rpb.reshape(1, 1, nH, N, N)
    if mask is not None:
        attn = attn + mask.reshape(1, nW, 1, N, N)
    attn = attn.reshape(B, nW * nH, N, N)

    attn = self.softmax(attn)
    out = torch.matmul(attn, v)                            # (1,nW*nH,N,d)
    out = out.reshape(B, nW, nH, N, d).permute(0, 1, 3, 2, 4)
    out = out.reshape(B, nW, N, C)
    return self.proj(out)


def block_forward_ncnn(self, x, x_size):
    """x: (1, H*W, C). Pencere islemleri ncnn uyumlu surumlerle yapilir."""
    H, W = x_size
    B, L, C = x.shape
    ws = self.window_size

    shortcut = x
    x = self.norm1(x).reshape(B, H, W, C)

    if self.shift_size > 0:
        x = torch.roll(x, shifts=(-self.shift_size, -self.shift_size), dims=(1, 2))

    x_windows = window_partition_ncnn(x, ws)               # (1,nW,N,C)
    mask = self.attn_mask if self.input_resolution == x_size else self.calculate_mask(x_size).to(x.device)
    attn_windows = self.attn(x_windows, mask=mask)         # (1,nW,N,C)
    x = window_reverse_ncnn(attn_windows, ws, H, W)        # (1,H,W,C)

    if self.shift_size > 0:
        x = torch.roll(x, shifts=(self.shift_size, self.shift_size), dims=(1, 2))

    x = x.reshape(B, H * W, C)
    x = shortcut + x
    return x + self.mlp(self.norm2(x))


def apply():
    """Yamalar bir kez uygulanir."""
    ns.WindowAttention.forward = attention_forward_ncnn
    ns.SwinTransformerBlock.forward = block_forward_ncnn
