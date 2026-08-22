# OpeyScaler

Fotograflari cihaz uzerinde **2K'dan 16K'ya** kadar buyuten Android uygulamasi.
Yapay zeka modelleri (Real-ESRGAN, Real-CUGAN) APK'nin icinde tasinir; islem
tamamen telefonda yapilir, internet gerekmez ve hicbir goruntu disari cikmaz.

![Real-ESRGAN karsilastirmasi](docs/karsilastirma-realesrgan.png)

*Sol: klasik Lanczos buyutme. Sag: Real-ESRGAN. Ayni 256x256 kaynaktan 4x.*

## Ne yapiyor

| | |
|---|---|
| Cozunurluk secenekleri | 2K, 3K, 4K, 5K, 6K, 8K, 10K, 12K, 16K (uzun kenara gore, en/boy orani korunur) |
| Modeller | Real-ESRGAN Hizli / Genel / Anime, BSRGAN, Real-CUGAN 2x, klasik Lanczos |
| Hizlandirma | Vulkan GPU varsa GPU, yoksa cok cekirdekli CPU |
| Cikis | JPEG (kalite ayarlanabilir) veya kayipsiz PNG |
| Kayit yeri | Galeri > Pictures > OpeyScaler |
| Calisma bicimi | On plan servisi: uygulamadan cikilsa da islem surer, iptal edilebilir |

## Nasil calisiyor

Hat uc asamadan olusur ve **satir satir akar**; cikis goruntusu hicbir zaman
tumuyle bellege alinmaz. 16K (15360 x 11520 = 177 megapiksel) bir cikis bile
birkac on MB ile uretilir.

```
kaynak ──► [1] sinir agi modeli (ncnn, 2x/4x)
                 dosemeli, komsu piksellerden baglam alarak → dikissiz
              │
              ▼
           [2] Lanczos-3 yeniden ornekleme (dogrusal isikta)
                 modelin sabit orani ile secilen hedef arasindaki farki kapatir
              │
              ▼
           [3] olcege gore keskinlestirme + akis halinde JPEG/PNG kodlama
```

**1. Sinir agi katmani** (`native/sr_engine.cpp`) ncnn uzerinde calisir. Goruntu
128 piksellik dosemelere bolunur; her dosemenin cevresine gercek goruntuden
baglam pikselleri eklenir (kenarlarda kenar tekrari ile), model calistirilir ve
sonuc kirpilarak yerine yazilir. Kirpma payi cikis boyutundan olculerek
turetildigi icin hem Real-ESRGAN hem de ic kirpma yapan Real-CUGAN ayni kodla
dogru calisir.

**2. Olcekleme** (`app/src/main/java/com/opeyscaler/engine/Resampler.java`)
Lanczos-3 filtresini dogrusal isikta uygular. Yatayda olceklenmis satirlar bir
halka tamponunda tutulur, dikey gecis bunlar uzerinden yapilir; boylece bellek
kullanimi cikis yuksekligiyle degil filtre genisligiyle orantilidir.

**3. Keskinlestirme ve kodlama.** Keskinlestirme yaricapi buyutme oraniyla
olceklenir (15 kat buyutmede 1 piksellik unsharp hicbir sey yapmaz); kutu
bulanikligi kosan toplamla hesaplandigi icin yaricap ne olursa olsun piksel
basina maliyet sabittir. Kodlayicilar da akis tabanlidir: `PngWriter` Paeth
filtresiyle Deflater'a, `JpegWriter` ise sifirdan yazilmis baseline JPEG
kodlayicisiyla (AAN DCT, standart Huffman tablolari, 4:4:4) 8 satirlik MCU
bantlari halinde diske yazar.

## Kurulum

`release/OpeyScaler-lite.apk` dosyasini telefona kopyalayip acin. Android
"bilinmeyen kaynaklardan yukleme" izni ister; bu izni verdikten sonra kurulum
tamamlanir.

- Android 7.0 (API 24) ve uzeri
- Ince surum: arm64-v8a, 23 MB, uc model (Hizli, Anime, Real-CUGAN)
- Tam surum: iki mimari, bes model, 97 MB - `bash tools/build-apk.sh` ile uretilir

Uygulama paketlenmemis modelleri listede gostermez; bu yuzden ince surum de
eksiksiz calisir.

## Derleme

Depoda dogrulanmis yol Gradle'siz betiktir:

```bash
export ANDROID_SDK_ROOT=/opt/android-sdk
bash tools/build-apk.sh          # -> build/OpeyScaler.apk
```

Gerekenler: JDK 17+, `platforms/android-34`, `build-tools/35.0.0`.
(build-tools 34'teki d8, JDK 21 ile derlenmis enum siniflarinda cokuyor;
betik bu yuzden 35 kullanir.)

Ince surum uretmek icin model ve mimari secilebilir:

```bash
MODELS="realesr-animevideov3-x4 realcugan-up2x-no-denoise realesrgan-x4plus-anime" \
ABIS="arm64-v8a" APK_NAME="OpeyScaler-lite" bash tools/build-apk.sh
```

Yerel kitapligi yeniden derlemek icin NDK 26 ve ncnn'in Android paketi gerekir:

```bash
cmake -S native -B out -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
      -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 \
      -DNCNN_PREBUILT=/yol/ncnn-20240820-android-vulkan -GNinja
cmake --build out
```

Android Studio ile acmak icin `build.gradle` dosyalari da bulunur.

## Olcumler

Dogrulama masaustunde (4 cekirdek x86_64, GPU yok) yapildi:

| Test | Sonuc |
|---|---|
| 512x384 → 4K (11 MP), klasik hat | 2.1 sn, 15 MB yigin |
| 1024x768 → 16K (177 MP), klasik hat | 13 sn, 34 MB yigin, 21 MB JPEG |
| 256x256 → 4x, Real-ESRGAN x4plus (CPU) | 13 sn |
| 256x256 → 4x, Real-ESRGAN Hizli (CPU) | 0.21 sn |
| 1024x768 → 4x → 16K, tam hat | 2.3 sn + 14 sn |
| Doseme 64 ile doseme 256 ciktisi | 64 dB PSNR (gorunur dikis yok) |
| Kendi JPEG kodlayicimiz ile PNG ciktisi | 46 dB PSNR (q92 icin beklenen) |

Keskinlestirmenin etkisi (512 → 4K, referansa gore olculmustur): ortalama kenar
dikligi Lanczos'ta 1.31, OpeyScaler'da 1.96 (referans 2.72); PSNR neredeyse
degismeden kalir (23.05 → 22.81 dB).

## BSRGAN nasil eklendi

BSRGAN'in ureteci Real-ESRGAN x4plus ile **ayni** RRDBNet mimarisidir: 23 blok,
64 kanal, 32 buyume, toplam 351 evrisim. Ikisi yalnizca agirlik adlandirmasinda
ayrilir (`RRDB_trunk.0.RDB1.conv1` / `body.0.rdb1.conv1`). Bu yuzden ONNX
donusumune gerek kalmadan x4plus'in `.param` grafigi aynen kullanilip yalnizca
`.bin` yeniden yazildi (`tools/models/bsrgan_to_ncnn.py`). Betik once 351
evrisimin tamaminin boyut olarak eslestigini dogrular, sonra ncnn duzeninde
(`[etiket][fp16 agirlik][fp32 sapma]`) yazar.

Dogrulama: ayni goruntude ncnn ciktisi ile PyTorch'un kendi BSRGAN ileri
gecisi karsilastirildi.

| Kosul | PSNR |
|---|---|
| Dolgusuz, fp32 agirlik | **91.4 dB** (piksel basina en fazla 1 seviye fark) |
| Dolgusuz, fp16 agirlik (uygulamadaki gibi) | 65.5 dB |
| Uygulamadaki dosemeli hat (kenar tekrari dolgusu) | 39.6 dB |

Ilk satir donusumun dogrulugunu kanitlar. Ucuncu satirdaki fark bir hata degil,
bilincli bir tercihtir: dosemeli calisirken kenarlar gercek komsu piksellerle
(goruntu sinirinda kenar tekrariyla) doldurulur; PyTorch referansi ise sifir
dolgu kullanir. Real-ESRGAN'in kendi ncnn uygulamasi da ayni yolu izler.

## Modeller ve lisanslar

| Model | Dosya | Boyut | Kaynak |
|---|---|---|---|
| Real-ESRGAN Hizli | `realesr-animevideov3-x4` | 1.2 MB | [Real-ESRGAN](https://github.com/xinntao/Real-ESRGAN) (BSD-3) |
| Real-ESRGAN Genel | `realesrgan-x4plus` | 33 MB | ayni |
| Real-ESRGAN Anime | `realesrgan-x4plus-anime` | 8.9 MB | ayni |
| BSRGAN | `bsrgan-x4` | 33 MB | [BSRGAN](https://github.com/cszn/BSRGAN) (Apache-2.0), bkz. asagi |
| Real-CUGAN 2x | `realcugan-up2x-no-denoise` | 2.5 MB | [Real-CUGAN](https://github.com/nihui/realcugan-ncnn-vulkan) (MIT) |

Cikarim motoru: [ncnn](https://github.com/Tencent/ncnn) (BSD-3).

## Sinirlar ve bilinen noktalar

- **Real-CUGAN** yalnizca SE'siz (`models-nose`) 2x modeliyle gelir. SE'li
  surumler tum goruntu uzerinden istatistik gerektirdigi icin dosemeli akisla
  dogrudan uyumlu degil.
- **SwinIR** eklenmedi: transformer mimarisi (pencere dikkat katmanlari) ncnn'de
  dogrudan karsiligi olmayan islemler iceriyor ve mobil cihazda doseme basina
  saniyeler suruyor. Ayni kalite butcesinde BSRGAN ve Real-ESRGAN cok daha
  kullanilabilir sonuc veriyor.
- **BSRGAN** hazir bir ncnn modeli olmadigi icin donusturuldu (asagiya bakin).
- Sinir agi modelleri icin kaynak 12 MP ile sinirlidir; uzerindeki fotograflar
  once alt orneklenir.
- Uygulama gercek bir telefonda calistirilarak test **edilmedi** (derleme
  ortaminda emulator icin KVM yok). Motor, modeller, doseme mantigi ve
  kodlayicilar masaustunde gercek goruntulerle dogrulandi; APK'nin imzasi,
  yerel simgeleri ve varlik yollari statik olarak denetlendi.
