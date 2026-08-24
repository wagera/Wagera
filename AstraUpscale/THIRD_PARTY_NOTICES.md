# Üçüncü taraf bildirimleri

AstraUpscale, aşağıdaki açık kaynak bileşenleri paketler. Her biri kendi
lisansı altındadır ve bu lisansların tamamı, **ikili dağıtımda telif
bildiriminin yeniden üretilmesini zorunlu kılar** — yani bu dosyanın içeriği
APK ile birlikte dağıtılmak zorundadır. Uygulama içinde *Ayarlar → Lisanslar*
ekranından da okunabilir.

Buradaki lisans türleri ezberden yazılmadı; her biri yayınlandığı depodan
doğrulandı. Doğrulama tarihi: 2026-08-24.

---

## Çıkarım motoru

### ncnn
Telif hakkı © 2017 Tencent. Tüm hakları saklıdır.
**BSD 3-Clause License**
https://github.com/Tencent/ncnn

> ncnn kendi içinde farklı lisanslara tabi üçüncü taraf bileşenler taşır
> (`neon_mathfun.h`, `sse_mathfun.h`, `avx_mathfun.h` — zlib lisansı).
> Tam metin için ncnn deposundaki `LICENSE.txt` dosyasına bakınız.

---

## Sinir ağı modelleri

### Real-ESRGAN
Telif hakkı © xinntao
**BSD 3-Clause License**
https://github.com/xinntao/Real-ESRGAN

Paketlenen ağırlıklar: `realesrgan-x4plus`, `realesrgan-x4plus-anime`,
`realesr-animevideov3-x4`.

### Real-CUGAN
Telif hakkı © 2022 bilibili
**MIT License**
https://github.com/bilibili/ailab/tree/main/Real-CUGAN

Paketlenen ağırlıklar: `realcugan-up2x-conservative`,
`realcugan-up2x-denoise3x`, `realcugan-up3x-conservative`,
`realcugan-up4x-conservative`.

### SwinIR
Telif hakkı © JingyunLiang
**Apache License 2.0**
https://github.com/JingyunLiang/SwinIR

Paketlenen ağırlıklar: `swinir-s-x4`, `swinir-m-x4`.

**Yapılan değişiklik bildirimi (Apache 2.0, Madde 4b):** SwinIR ağırlıkları
PyTorch biçiminden ncnn biçimine çevrildi (`tools/models/swinir_to_ncnn.py`).
Çevrim sırasında pnnx'in parametresiz `Reshape` katmanları ürettiği bir sorun
`tools/models/swinir_ncnn_patch.py` ile giderildi. Ağ mimarisi ve ağırlık
değerleri değiştirilmedi; çevrimin doğruluğu özgün PyTorch çıktısına karşı
sınandı.

### BSRGAN
Telif hakkı © cszn
**Apache License 2.0**
https://github.com/cszn/BSRGAN

> BSRGAN depoda bulunur ancak **yayınlanan APK'ya dahil edilmez** (APK
> boyutunu GitHub'ın 100 MB sınırının altında tutmak için). Kaynaktan
> derleyip dahil edenler bu bildirimi de taşımalıdır.

**Yapılan değişiklik bildirimi (Apache 2.0, Madde 4b):** BSRGAN ağırlıkları
ncnn biçimine çevrildi (`tools/models/bsrgan_to_ncnn.py`).

---

## Yazı tipleri

### Space Grotesk
Telif hakkı © 2020 The Space Grotesk Project Authors
**SIL Open Font License 1.1**
https://github.com/floriankarsten/space-grotesk

### Manrope
Telif hakkı © 2018 The Manrope Project Authors
**SIL Open Font License 1.1**
https://github.com/sharanda/manrope

> OFL 1.1, yazı tiplerinin gömülü olarak dağıtılmasına izin verir. Yazı
> tipi dosyalarının kendisi satılamaz ve Ayrılmış Yazı Tipi Adı varsa
> değiştirilmiş sürümlerde kullanılamaz. AstraUpscale yazı tiplerini
> değiştirmeden gömer.

---

## Lisans metinleri

Aşağıda, yukarıda anılan lisansların tam metinleri yer alır.

### BSD 3-Clause License

```
Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice,
   this list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

3. Neither the name of the copyright holder nor the names of its contributors
   may be used to endorse or promote products derived from this software
   without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
POSSIBILITY OF SUCH DAMAGE.
```

### MIT License

```
Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

### Apache License 2.0

Tam metin: https://www.apache.org/licenses/LICENSE-2.0
Bu deponun kökündeki `LICENSE` dosyası da Apache 2.0 metnini içerir.

### SIL Open Font License 1.1

Tam metin: https://openfontlicense.org
