# Model donusum araclari

Bu klasordeki betikler, hazir ncnn karsiligi olmayan modelleri uygulamanin
kullandigi bicime cevirir. Ureteceklerini `app/src/main/assets/models/` altina
koymak yeterlidir.

## BSRGAN

`bsrgan_to_ncnn.py` - BSRGAN'in ureteci Real-ESRGAN x4plus ile ayni RRDBNet
oldugundan (23 blok, 351 evrisim), x4plus'in `.param` grafigi yeniden kullanilir
ve yalnizca `.bin` yazilir. Betik once 351 evrisimin tamaminin boyut olarak
eslestigini dogrular.

```bash
python3 bsrgan_to_ncnn.py BSRGAN.pth realesrgan-x4plus.param bsrgan-x4.bin
cp realesrgan-x4plus.param bsrgan-x4.param
```

## SwinIR

SwinIR dogrudan cevrilemez: pencere bolme ve dikkat hesabi 5-6 rank'li yeniden
sekillendirmeler ve toplu (batch) boyutunu yer degistiren permute'lar kullanir.
ncnn'de ise toplu boyut daima 0. boyuttur ve kalan boyut sayisi 4'u gecemez;
pnnx bu islemleri ifade edemeyip **parametresiz** `Reshape` katmanlari uretir
(model sessizce bos cikti verir).

`swinir_ncnn_patch.py` ayni matematigi toplu boyutu hic oynatmadan ve en fazla
4 gercek boyut kullanarak yeniden yazar:

- `window_partition` / `window_reverse` adim adim, her ara tensor <= 4 boyut
- fuzelenmis `qkv` yerine uc ayri dogrusal izdusum (boylece `permute(2,0,3,1,4)`
  gerekmez)
- goreli konum yanliligi ve pencere maskesi yayinim (broadcast) ile eklenir

Kullanimi:

```bash
# SwinIR deposundan ag tanimini al
curl -O https://raw.githubusercontent.com/JingyunLiang/SwinIR/main/models/network_swinir.py
sed -i 's/from timm.models.layers import/from timm_shim import/' network_swinir.py

python3 swinir_verify_patch.py S 128   # yamanin ozgun modelle ayni sonucu verdigini dogrular
python3 swinir_to_ncnn.py S 128        # swin_S_128.ncnn.param/.bin uretir
```

Not: `ncnnoptimize` ile fp16'ya cevrilen SwinIR modelleri calisma aninda
cokuyor; pnnx ciktisi dogrudan kullanilmalidir. APK'da dosyalar zaten
sikistirilarak tasindigi icin boyut farki kucuktur.

Izleme sabit girdi boyutuyla yapilir (128x128), cunku kaydirmali pencere maskesi
izleme sirasinda sabitlenir. Uygulama da modele her zaman 128x128 doseme verir.
