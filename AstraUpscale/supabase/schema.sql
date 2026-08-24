-- AstraUpscale — Keşfet içeriği için Supabase şeması
--
-- Bu dosyayı Supabase panelinde SQL Editor'e yapıştırıp çalıştırın.
--
-- GÜVENLİK NOTU — ÖNCE BUNU OKUYUN
--
-- Uygulamaya gömülen anahtar "publishable" (anon) anahtardır. Bu anahtar
-- TASARIMI GEREĞİ herkese açıktır: APK'yı indiren herkes onu birkaç
-- dakikada çıkarabilir. Bu bir sorun DEĞİLDİR — ama yalnızca Row Level
-- Security açıksa. RLS kapalı olsaydı, o anahtarla herkes bütün
-- tablolarınızı okuyup yazabilirdi.
--
-- Aşağıdaki politika anon rolüne YALNIZCA published = true satırları
-- OKUMA izni verir. Yazma, güncelleme, silme için hiçbir politika
-- tanımlanmadığı için hepsi reddedilir (RLS'te politikasız işlem = yasak).
--
-- service_role anahtarı ASLA uygulamaya konmamalıdır: o anahtar RLS'i
-- tümüyle atlar ve veritabanında tam yetki verir.


-- ── Tablo ────────────────────────────────────────────────────────────
create table if not exists public.discover_items (
    id          bigint generated always as identity primary key,

    -- Kart türü. Uygulama her türü farklı çizer.
    --   tip    : kullanım ipucu
    --   model  : bir modelin ne işe yaradığı
    --   note   : duyuru / sürüm notu
    kind        text        not null check (kind in ('tip', 'model', 'note')),

    -- Dil. Uygulama cihazın diline göre süzer, bulamazsa 'en'e düşer.
    locale      text        not null default 'tr' check (locale in ('tr', 'en')),

    title       text        not null,
    body        text        not null,

    -- İsteğe bağlı üst satır: "Real-ESRGAN · 4×" gibi
    meta        text,

    -- Sıralama; küçük olan üstte
    sort        integer     not null default 0,

    -- Yayında mı. Varsayılan FALSE: yanlışlıkla yarım bir kayıt
    -- yayınlanmasın diye kasıtlı olarak kapalı başlar.
    published   boolean     not null default false,

    created_at  timestamptz not null default now()
);

-- Uygulamanın yaptığı sorgu tam olarak budur: yayındakiler, dile göre,
-- sıraya göre. İndeks o sorguya göre kuruldu.
create index if not exists discover_items_feed_idx
    on public.discover_items (locale, sort, id)
    where published;


-- ── Row Level Security ───────────────────────────────────────────────
alter table public.discover_items enable row level security;

-- Yeniden çalıştırılabilir olsun diye önce düşürülür
drop policy if exists "anon reads published discover items" on public.discover_items;

create policy "anon reads published discover items"
    on public.discover_items
    for select
    to anon
    using (published = true);

-- INSERT / UPDATE / DELETE için politika YOK.
-- RLS açıkken politikası olmayan işlem reddedilir; yani anon anahtarıyla
-- kimse bu tabloya yazamaz. İçeriği siz panelden ya da service_role ile
-- eklersiniz.


-- ── Örnek içerik ─────────────────────────────────────────────────────
-- İlk açılışta sayfa boş görünmesin diye birkaç kayıt.
insert into public.discover_items (kind, locale, title, body, meta, sort, published)
values
    ('tip', 'tr', 'Önce karşılaştırın',
     'Büyütme bittiğinde Karşılaştır''a dokunun ve çift dokunuşla 1:1''e geçin. Büyütmenin gerçekten ne yaptığı ancak 1:1''de görünür.',
     'İpucu', 10, true),

    ('model', 'tr', 'Fotoğraf mı, çizim mi?',
     'Real-ESRGAN x4plus gerçek fotoğraflarda; Anime 6B ve Real-CUGAN çizim, anime ve düz renkli görsellerde belirgin biçimde daha iyi sonuç verir. Yanlış model, dokuyu ya fazla yumuşatır ya plastikleştirir.',
     'Model seçimi', 20, true),

    ('tip', 'tr', '64K üstünde gürültü önce temizlenir',
     'Çok yüksek büyütmelerde kaynaktaki gren de büyür. Uygulama 64K ve üzerinde, büyütmeden önce kenar koruyan bir temizlik uygular. İşlem zinciri satırından hangi adımların çalışacağını görebilirsiniz.',
     'Gürültü', 30, true),

    ('note', 'tr', 'Fotoğraflarınız cihazdan çıkmaz',
     'Bütün işlem telefonun içinde olur. Hiçbir görüntü, küçük resim veya dosya yolu dışarı gönderilmez. Dışarı giden tek şey, sizin yazdığınız geri bildirimlerdir.',
     'Gizlilik', 40, true),

    ('tip', 'en', 'Compare before you judge',
     'When a job finishes, tap Compare and double-tap to reach 1:1. What the upscaling actually did is only visible at 1:1.',
     'Tip', 10, true),

    ('model', 'en', 'Photograph or illustration?',
     'Real-ESRGAN x4plus suits real photographs; Anime 6B and Real-CUGAN are markedly better on drawings, anime and flat-colour art. The wrong model either over-smooths texture or turns it plastic.',
     'Choosing a model', 20, true),

    ('note', 'en', 'Your photos never leave the device',
     'Everything runs on the phone. No image, thumbnail or file path is sent anywhere. The only thing that goes out is the feedback you type.',
     'Privacy', 40, true)
on conflict do nothing;
