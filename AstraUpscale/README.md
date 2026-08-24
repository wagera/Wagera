# AstraUpscale

**[English](README.en.md) · Türkçe**

Fotoğrafları cihaz üzerinde **2K'dan 512K'ya** kadar büyüten Android uygulaması.
Real-ESRGAN, SwinIR ve Real-CUGAN modelleri APK'nın içinde taşınır; işlem
tamamen telefonda yapılır ve hiçbir görüntü dışarı çıkmaz.

<img src="docs/tema-koyu.png" width="250" alt="Koyu tema" /> <img src="docs/tema-acik.png" width="250" alt="Açık tema" />

| | |
|---|---|
| Çözünürlük | 2K'dan 512K'ya 14 ön ayar, üç kademede |
| Modeller | Real-ESRGAN (Hızlı / x4plus / Anime 6B), SwinIR-S, SwinIR-M, Real-CUGAN 2×/3×/4×, klasik Lanczos |
| Geçiş | Model tek ya da çift geçiş çalıştırılabilir |
| Gürültü temizleme | 64K ve üzerinde otomatik: kaynak, büyütmeden önce temizlenir |
| Zorlama seviyesi | Sakin / Dengeli / Tam güç |
| Diller | Türkçe ve İngilizce; başlıktaki düğmeyle değiştirilir |
| Tema | Açık ve koyu; sistem ayarını izler, elle de sabitlenebilir |
| Sayfalar | Büyüt · Geçmiş · İstekler (alt gezinme çubuğu) |
| Çıkış | JPEG (kalite ayarlanabilir) veya kayıpsız PNG |
| Kayıt yeri | Galeri › Pictures › AstraUpscale |

## Kurulum

`release/AstraUpscale.apk` dosyasını telefona kopyalayıp aç. Android
"bilinmeyen kaynaklardan yükleme" izni ister.

- Android 7.0 (API 24) ve üzeri
- **arm64-v8a**; 85 MB, dokuz model içeride

## Çözünürlük kademeleri

Hat satır satır aktığı için bellek kullanımı çıkış boyutundan neredeyse
bağımsızdır; sınırları **dosya biçimi, depolama ve süre** koyar.

| Kademe | Ön ayarlar |
|---|---|
| Standart | 2K, 3K, 4K, 5K, 6K |
| Yüksek | 8K, 10K, 12K, 16K |
| Uç | 32K, 64K, 128K, 256K, 512K |

| Ön ayar | Boyut | Piksel | JPEG | Tahmini PNG | Masaüstünde süre |
|---|---|---|---|---|---|
| 32K | 30720×23040 | 0,71 GP | evet | 0,8 GB | 44 sn |
| **64K** | 61440×46080 | 2,83 GP | evet | 3,4 GB | **138 sn** (ölçüldü) |
| 128K | 122880×92160 | 11,3 GP | **hayır** | 13,6 GB | ~10 dk |
| 256K | 245760×184320 | 45,3 GP | **hayır** | 54 GB | ~40 dk |
| 512K | 491520×368640 | 181 GP | **hayır** | 217 GB | ~2,5 saat |

JPEG başlığındaki boyut alanları 16 bittir, yani en fazla 65535 piksel:
**64K'ya kadar JPEG yazılabilir, 128K ve üzeri yalnızca PNG**. Uygulama bu
durumda biçimi kendisi PNG'ye çevirir ve nedenini yazar.

256K ve 512K gerçekten üretilebilir; asıl engel depolamadır. Başlamadan önce
tahmini dosya boyutu ile boş alan karşılaştırılır, yetmiyorsa işlem başlamaz.

## Kullanıcı istekleri

İkinci sayfa geri bildirim içindir: hata bildirimi, öneri ya da genel yorum.
Mesaj bir Discord kanalına ulaşır. İnternet yoksa mesaj diske yazılır ve
bağlantı gelince gönderilir; gönderim başarısız olursa **10 saniyede bir**
yeniden denenir. Uygulama kapalıyken bile Android'in iş planlayıcısı ağ
geldiğinde kuyruğu boşaltır, kuyruk diskte durduğu için cihaz yeniden başlasa
da kayıtlar kaybolmaz.

Mesajla birlikte gönderilenler: uygulama sürümü, dil, Android sürümü, cihaz
modeli, işlemci ve bellek. **Fotoğraflar asla gönderilmez.**

Kuyruk `tools/desktop/QueueTest.java` ile masaüstünde sınandı; 11 denetimin
tamamı geçti: Türkçe metin ve emoji kayıpsız gidip geliyor, bozuk bir satır
kuyruğun tamamını bozmuyor, gönderilen kayıtlar doğru siliniyor ve 500 kayıtlık
üst sınır en yenileri koruyor.

## Arayüz

Tek renkli bir tasarım; vurgu rengi yoktur. Kural şudur: **seçili öge içerik
rengine boyanır, yazısı zemin rengine döner.** Bu kural iki temada da
kendiliğinden tersine döner — koyu temada beyaz zeminli siyah yazı, açık temada
siyah zeminli beyaz yazı. Ayrımlar 1 piksellik saç çizgileriyle, derinlik ise
birbirinden bir tık ayrılan yüzeylerle kurulur.

Renkler anlamsal belirteçlerle tanımlıdır; `values` açık, `values-night` koyu
temayı taşır. Uygulamada tek bir renk sabiti yoktur.

| Belirteç | Açık | Koyu | Kullanım |
|---|---|---|---|
| `bg` | `#F6F7F9` | `#050506` | sayfa zemini |
| `surface_low` | `#F0F1F4` | `#0B0C0E` | başlık ve alt gezinme çubuğu |
| `surface` | `#FFFFFF` | `#0F1013` | kart |
| `surface_high` | `#EDEEF2` | `#16171B` | çip, ikincil düğme |
| `hairline` | `#E3E5EA` | `#1D1F23` | ayrım çizgisi |
| `content` | `#14161C` | `#F5F6F8` | birincil yazı, seçili zemin |
| `content_dim` / `content_faint` / `content_ghost` | `#697079` / `#8E949E` / `#B6BAC3` | `#8B9098` / `#5F646C` / `#3A3E45` | azalan önem |

Üç sayfa alt gezinme çubuğuyla dolaşılır: **Büyüt**, **Geçmiş** (kaydedilen
fotoğraflar) ve **İstekler**. İlk açılışta kısa bir tanıtım ekranı çıkar.

### Büyüt sayfasının yerleşimi

Sayfanın **tek bir odak noktası** vardır: sahne. Boş durum, seçilen fotoğraf,
işlem sırasındaki ilerleme ve sonuç önizlemesi hep **aynı dikdörtgeni**
paylaşır; hiçbiri diğerinin altına yığılmaz. Sahneye dokunmak galeriyi açar.

Sahnenin hemen altında hedef okuması ve **Başlat** yan yana durur; birincil
eylem her zaman katlamanın üstündedir, aranmaz.

Bütün ayarlar kapalı akordeon satırlarına iner — Çözünürlük, Motor, Ayarlar,
Cihaz. Her satır **kapalıyken bile geçerli değerini sağında taşır**, böylece
durum panel açılmadan okunur. Aynı anda yalnızca biri açık kalır.

Ölçülen sonuç: Büyüt sayfası ilk çizimde **60 yerine 33 görünüm** yerleştiriyor
(%45 azalma). Üstelik eskiden hep açık duran 9 model satırı, 3 kademelik çip
serisi ve 3 zorlama düğmesi artık kapalı panellerin içinde — ilk çizimde hiç
yok.

Ölçüler tek bir 4dp ızgarasından türer (`values/dimens.xml`): sayfa kenarı
20dp, satır arası 10dp, bölüm arası 24dp; punto ölçeği 10 / 11.5 / 13 / 15 /
20 / 34sp — altı basamak, arası yok.

Sayfa açıldığında öğeler sırayla yükselerek belirir. Sıra, gözün okuması
gereken sırayla aynıdır: başlık → sahne → eylem çubuğu → ayar satırları.
Kullanıcı sistem ayarlarından animasyonları kapattıysa hiçbir şey oynatılmaz.

### Tasarım dili

Dil "sinematik cam"dan **"alet"e** geçti. Önceki dilde yüzeyler yarı
saydamdı, kenarlar yumuşak, köşeler geniş yarıçaplıydı; her şey yüzüyormuş
gibi dururdu. Yenisinde yüzeyler opak, ayrımlar 1 piksellik kesin çizgiler,
köşeler neredeyse dik (14–18dp yarıçap → **3dp**). Amaç bir ölçüm aletinin
yüzü: yumuşaklık değil kesinlik.

Üç değişiklik görünümü baştan aşağı taşır:

**Cam ve bulanıklık yok.** Yüzeyler birbirinden saydamlıkla değil çizgiyle
ayrılır. Sinematik zemin (köşegen geçiş + iki ışık patlaması + kenar
karartması) yerini düz bir zemine ve üzerindeki soluk bir **ölçü ızgarasına**
bıraktı.

**Tek bir sinyal rengi.** Önceki dilde vurgu rengi hiç yoktu; seçili öge
içerik rengine boyanırdı. Artık sayfada tek bir şey sinyal taşır ve göz
nereye dokunacağını aramaz. Renk kendi zeminine göre döner: karanlık temada
elektrik limonu `#D8FF3E`, aydınlıkta koyu zeytin `#4A5A00` — parlak limon
açık zeminde, koyu zeytin siyahta okunmaz.

**Çözünürlük bir ölçek oldu.** 14 ön ayar artık bir çekmecede duran çipler
değil, tek bir yatay ölçek üzerinde yan yana duran çentikler. Bütün aralık
aynı anda görünür ve seçim tek dokunuşla yapılır; kullanıcı "8K nerede" diye
bir bölüm açmaz. Her çentiğin yüksekliği kademesine göre artar (standart /
yüksek / uç), yani ölçeğe bakan göz sağa gidildikçe işin ağırlaştığını
biçimden okur.

Sayfanın geri kalanı da yeniden kuruldu:

| | |
|---|---|
| **Durum şeridi** | Cihazın o anki hali tek satırda: motor, iş parçacığı, sıcaklık. Bir aletin üst panelindeki gösterge. |
| **Vizör** | Fotoğraf artık yuvarlak köşeli bir kartta değil, dört köşe ayracı içinde. Kenarın tamamı çizilmez; çerçeve fotoğrafın önüne geçmez. |
| **Künye** | Motor, geçiş, biçim, kalite, keskinlik, gürültü ve cihaz dört açılır çekmecede değil, tek bir dökümde. Solda etiket, sağda değer, altında çizgi. |
| **Eylem** | Tam genişlikte, dik köşeli, sinyal renginde tek bir çubuk. |

Büyük kahraman başlık ("Her piksel, yerli yerinde.") kaldırıldı: bir alet
kendini tanıtmaz, durumunu gösterir. Açılış merdiveni de kısaldı, ~0.9
saniyede biter.

### İşaret

Kroksuz bir **"A"**: sol bacak küçülen basamaklarla iner, sağ bacak düz bir
diyagonaldir. Basamaklar işin öncesidir — piksel; düz kenar sonrası —
çözülmüş. Yani işaret hem markanın harfi, hem uygulamanın yaptığı iş.

Önceki işaret dört kollu bir yıldızdı. Kendi başına fena değildi ama ayırt
edici değildi: her yapay zekâ uygulaması yıldız kullanıyor.

Basamak sayısı 5'te sabitlendi. 200 pikselde ilerleme açıkça okunuyor, 36
pikselde siluet hâlâ "A" olarak duruyor; 6 basamakta küçük boyda basamaklar
birbirine giriyor, 4'te ilerleme duygusu zayıflıyor. Uyarlanabilir simgede
işaret 56 birim kaplar (72'lik güvenli alanın içinde): yeni glif yıldızdan
geniş olduğu için 62 birimde daire maskesinde bacaklar kenara değiyordu.

Bu kararlar `tools/docs/render-mark.py`, `render-launcher.py` ve
`render-adaptive.py` ile gerçek kullanım ölçülerinde ve başlatıcının
uyguladığı dört maskede bakılarak verildi.

### Öncesi / sonrası karşılaştırma

Bir büyütme uygulamasının bütün değeri, sonradan çıplak gözle bakıldığında
göze çarpan detaydadır. Sonucu küçük bir küçük resim olarak göstermek o
değeri **görünmez** bırakır. Karşılaştırma ekranında iki görüntü aynı bakış
penceresini paylaşır: bölme çizgisinin solunda kaynak, sağında sonuç.
Yakınlaştırma ve kaydırma ikisine birden uygulanır, yani her an aynı bölge
karşılaştırılır. Çift dokunuş sığdırma ile **1:1** arasında gider gelir —
büyütmenin gerçekten ne yaptığı ancak 1:1'de görünür.

**İki görüntü, iki farklı yöntem.** Sonuç gigapiksel olabilir, belleğe
sığmaz; yalnızca ekranda görünen bölge `BitmapRegionDecoder` ile okunur.
Kaynak ise en fazla birkaç megapikseldir, bir kez çözülüp tutulur — kaynak
tarafı zaten büyütülmüş gösterileceği için bölge okumaya gerek yoktur.
Ölçüldü: 8K bir çıkışta sığdırma görünümü 2.8 MP okuyor, 1:1'de okunan bölge
1.7 MP (yani ekran kadar).

**Hizalama.** Motor çıkışı EXIF dönüşünü uygulayarak yazar
(`BitmapPixelSource(bitmap, orientation)`), yani sonuç dosyası doğru
yöndedir. Karşılaştırmada kaynak da aynı dönüşle çözülür; aksi halde EXIF
taşıyan bir fotoğrafta iki taraf birbirine göre 90 derece kayardı.

**Matematik ayrı ve sınanmış.** Yakınlaştırma ve kaydırma gözle
denetlenemeyecek kadar hataya açıktır: bir işaret hatası görüntüyü parmağın
altından kaçırır, bir sınır hatası görüntünün dışını gösterir. Uygulama bu
ortamda çalıştırılamadığı için (KVM yok) matematik Android'e bağlı olmayan
`engine/Viewport.java` sınıfına çıkarıldı ve
`tools/desktop/ViewportTest.java` ile doğrudan sınanıyor — 22 denetim:
odak noktasının sabit kalması, sınırlara dayanma, yakınlık sınırları,
örnekleme aralığı, kaynak/sonuç hizası ve sığan bir görüntünün ortalı
kalması. `CompareView` bu sınıfı kullanır; ikinci bir kopya tutmaz.

### Seçim ve ayarların kalıcılığı

Önceden hiçbir şey saklanmıyordu ve `onSaveInstanceState` da yoktu:
uygulama arka planda yeterince beklerse Android süreci sonlandırır ve
kullanıcı geri döndüğünde seçtiği fotoğraf da, kurduğu bütün ayarlar da
gitmiş olurdu. 512K'lık bir işi hazırlayıp telefonu cebe koymak bunu
tetiklemeye yeterdi.

`Session.java` seçimleri kalıcı depolamaya yazar — instance state yalnızca
aynı süreç içinde yaşar, süreç ölümünden sonra geri gelmez. Saklanan
birkaç yüz bayttır; fotoğrafın kendisi değil, yalnızca ona işaret eden
adres.

Geri yüklerken iki tuzak vardı ve ikisi de kapatıldı:

1. **Yarım durum yazma.** SeekBar dinleyicisi `fromUser` ayrımı yapmadan
   `refreshTexts()` çağırır, o da durumu yazar. Geri yüklerken
   `setProgress` bunu tetikliyor ve keskinlik değeri okunmadan önce
   varsayılanla eziliyordu. Artık bütün değerler *uygulanmadan önce*
   okunuyor ve bir `restoring` bayrağı yükleme bitene kadar yazmayı
   kapatıyor.
2. **Silinmiş fotoğraf.** Adresin varlığı yetmez; gerçekten okunabildiği
   doğrulanır. Okunamıyorsa yalnızca o adres unutulur, ayarlar korunur —
   kullanıcının kurduğu her şey tek bir silinmiş dosya yüzünden gitmesin.

### Açılış ekranı

Ayrı bir Activity değil, temanın `windowBackground`'u. Android pencereyi
oluşturur oluşturmaz onu çizer; yani uygulama daha ilk karesini hazırlarken
ekranda zaten işaret durur. Ayrı bir açılış Activity'si kurmak bunun aksine
bir kare daha ekler ve açılışı yavaşlatır.

Android 12 ve üstünde sistem kendi açılış ekranını zorlar ve bu yöntemi yok
sayar. Karşı koymak yerine `values-v31/themes.xml` içinde sistemin kendi
`windowSplashScreen*` nitelikleriyle aynı işaret ve aynı zemin verilir;
böylece 8.0'dan 15'e kadar açılış aynı görünür.

Açılış işareti (`mark_launch.xml`) `mark_astra.xml` ile aynı geometridedir
ama tint taşımaz: açılış ekranı çizilirken tema henüz çözülmemiştir,
dolayısıyla `@color/content`'e bağlı bir tint orada güvenilir değildir.

### Bildirimler

**İki ayrı kanal.** Devam eden iş sessizdir (`IMPORTANCE_LOW`, ses ve
titreşim kapalı): uzun süren bir işlemin her yüzde değişiminde ses çıkarması
rahatsız edicidir. Sonuç ise kullanıcının beklediği haberdir, kendi kanalı ve
varsayılan önemi vardır. Tek kanal olsaydı kullanıcı birini susturmak için
diğerini de susturmak zorunda kalırdı.

**Devam eden iş** başlığında hedefi söyler ("8K'ya büyütülüyor"), yüzdeyi alt
metinde taşır, belirli bir ilerleme çubuğu ve bir **İptal** eylemi gösterir.
`setOnlyAlertOnce` sayesinde her güncellemede yeniden uyarmaz.

**Sonuç** bildirimi çözünürlük, megapiksel, dosya boyutu ve süreyi verir;
dokununca fotoğrafı açar, **Paylaş** eylemi taşır. Hata durumunda sebep
`BigTextStyle` ile tam olarak görünür. İptalde hiçbir şey bildirilmez —
kullanıcı zaten kendisi iptal etmiştir, ona bunu haber vermek gürültüdür.

Durum çubuğu simgesi artık `android.R.drawable.ic_menu_gallery` değil —
o sistemin genel galeri simgesiydi, markayla ilgisi yoktu. Yerine
`ic_stat_astra` geldi. Android durum çubuğu simgelerini tek renge indirger,
o yüzden bu çizimde işaretin ikinci (soluk) ışın dizisi yoktur: %38
örtücülükteki o katman düz beyaza çevrildiğinde siluete yapışır. Merkez
açıklığı da biraz geniştir, çünkü dar bir delik küçültmede kapanır.

`tools/desktop/FormatStringsTest.java` bütün biçim dizelerini kodun verdiği
gerçek argümanlarla formatlar. Bir biçim uyuşmazlığı derlemede yakalanmaz;
kod ancak o dize kullanıldığında çöker — bildirimlerde bu, iş bittiği anda,
yani en kötü anda olur.

### Sürüm denetimi

**Bu bir süre boyunca hiç çalışmadı.** Kod
`.../AstraUpscale/surum.json` adresini istiyordu; depoda o dosya yok, adı
`version.json`. Her denetim 404 alıyor, `getResponseCode() != 200` dalında
**sessizce** dönüyordu. Sonuç: hiçbir cihaz hiçbir güncellemeyi görmedi ve
hiçbir yerde iz kalmadı.

Üç şey düzeltildi:

1. Adres `version.json` oldu.
2. Başarısızlık artık sessiz değil: sonuçta bir `failure` alanı var ve
   çevrimiçiyken denetim başarısız olursa bu arayüzde yazıyor.
3. İstemci tarafında önbelleğe takılma kapatıldı (`setUseCaches(false)`
   ve no-cache başlıkları). GitHub'ın ham dosya sunucusu yanıtları
   `max-age=300` ile önbellekler — yeni bir sürüm yayınlandıktan sonra beş
   dakika kadar eskisi dönebilir, bu kendiliğinden düzelir. Düzelmeyen,
   istemcinin kendi yanıt önbelleğine takılmış bir cevaptır.
4. `tools/desktop/UpdateUrlTest.java` bir gerileme sınamasıdır — adresi
   `UpdateChecker.java` kaynağından okur (ikinci bir kopya tutmaz, yoksa
   ikisi ayrışır ve sınama yalan söyler), 200 döndüğünü ve `versionCode`
   alanının okunabildiğini doğrular.

> Bu düzeltmenin eski kuruluma kendiliğinden ulaşamayacağını unutmayın:
> telefondaki sürüm hâlâ bozuk denetleyiciyi çalıştırıyor. Yeni APK bir kez
> elle kurulmalı; ondan sonrası kendiliğinden işler.

### Fotoğraf seçme ve izin

Fotoğraf seçimi uygulamanın içinde olur: `READ_MEDIA_IMAGES` izniyle cihazdaki
son fotoğraflar bir ızgarada gösterilir, kullanıcı uygulamadan çıkmadan seçer.

İzin **zorunlu değildir**. Verilmezse uygulama çalışmaya devam eder; seçim
sistemin belge seçicisine düşer ("Dosyalar"), o da hiçbir izin gerektirmez.

**Fotoğraflar yalnızca cihazda okunur.** Ne görüntü, ne küçük resim, ne de
dosya yolu cihazdan dışarı çıkar — sunucuya da, Discord webhook'una da,
başka bir uygulamaya da. Dışarı giden tek şey kullanıcının kendi yazdığı
geri bildirimdir; onun yanında da yalnızca yukarıda sayılan cihaz bilgileri
gider.

İşaret dört kollu, kolları içbükey kesilen bir yıldızdır; uygulama simgesinde,
başlıkta, boş önizlemede ve sonuç kartında aynı vektör kullanılır.

Yukarıdaki `docs/tema-koyu.png` ve `docs/tema-acik.png` cihaz ekran görüntüsü
**değildir**: `tools/docs/render-ui.py` bunları `colors.xml`, `dimens.xml` ve
`strings.xml` dosyalarından okuyarak üretir. Yani çizim ile gerçek yerleşim
arasındaki bağ elle güncellenen bir varsayım değil, dosyadan türetilmiş bir
sonuçtur; yerleşim değişince `python3 tools/docs/render-ui.py` çalıştırmak
yeter.

## Gereksinim

Android 8.0 (API 26) ve üzeri. Alt sınır 24'ten 26'ya çıkarıldı: `res/font`
kaynak aileleri ve uyarlanabilir başlatıcı simgesi bu sürümde geldi. 24'te
bırakılsaydı `aapt2` sorun çıkarmaz, ama Android 7 cihazda `@font/*`
çözülmez ve tipografi sessizce varsayılana düşerdi.

## Nasıl çalışıyor

```
kaynak ──► [1] gürültü temizleme (64K ve üzeri)
              │
              ▼
           [2] sinir ağı modeli (ncnn, 2×/3×/4×, isteğe bağlı iki geçiş)
                 döşemeli, komşu piksellerden bağlam alarak → dikişsiz
              │
              ▼
           [3] Lanczos-3 yeniden örnekleme (doğrusal ışıkta)
              │
              ▼
           [4] ölçeğe göre keskinleştirme + akış halinde JPEG/PNG kodlama
```

Çıkış satır satır üretilip doğrudan diske akıtılır. 32K (708 megapiksel) çıktı
62 MB yığınla, 64K (2,83 gigapiksel) çıktı 111 MB yığınla üretildi.

Ayrıntılar — model dönüşümleri, SE blokları, ölçümler — için
[English README](README.en.md) dosyasına bakın; teknik bölümler orada da aynıdır.

## Derleme

```bash
export ANDROID_SDK_ROOT=/opt/android-sdk
bash tools/build-apk.sh          # -> build/AstraUpscale.apk
```

Gerekenler: JDK 17+, `platforms/android-34`, `build-tools/35.0.0`.

Model ve mimari seçilerek daha küçük paket üretilebilir; uygulama
paketlenmemiş modelleri listede göstermez:

```bash
MODELS="realesr-animevideov3-x4 realcugan-up2x-conservative" ABIS="arm64-v8a" \
APK_NAME="AstraUpscale-lite" bash tools/build-apk.sh
```

## Sınırlar ve bilinen noktalar

- Uygulama gerçek bir telefonda çalıştırılarak test **edilmedi** (derleme
  ortamında emülatör için KVM yok). Motor, modeller, gürültü temizleme, aşırı
  genişlikte PNG yazımı ve Discord gönderimi masaüstünde doğrulandı.
- **Discord webhook adresleri APK'nın içindedir ve çıkarılabilir.** Bu
  adresler parola gibi korunamaz; kötüye kullanım olursa Discord'dan webhook'u
  silip yenisini üretmek gerekir.
- BSRGAN varsayılan pakette yok: eklendiğinde APK, GitHub'ın 100 MB dosya
  sınırını aşıyordu. Dosyaları ve dönüşüm betiği depoda.
- 256K/512K bugünün telefonlarında depolama nedeniyle pratikte kullanılamaz.
- Paket arm64-v8a içindir; armeabi-v7a derlenebilir ama ağır modeller o
  cihazlarda pratik değil.
