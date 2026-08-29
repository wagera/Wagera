package com.astraupscale.app;

import com.astraupscale.engine.ImageWriter;

import java.io.IOException;

/**
 * Buyutulmus karelerin gittigi yer.
 *
 * <p>Iki gerceklestirmesi var ve ikisi de ayni sozlesmeyi tutar: kare
 * basina bir {@link ImageWriter} verilir, motor satirlari ona yazar, sonra
 * kare kapatilir. Boylece buyutme hatti cikisin bir video kodlayicisina mi
 * yoksa diske ayri dosyalar olarak mi gittigini hic bilmez.
 *
 * @see EncoderSink kodlayici + kap (mp4)
 * @see SequenceSink kare dizisi (png/jpg)
 */
interface FrameSink {

    /** Yeni bir kare acar ve satirlarinin yazilacagi yaziciyi dondurur. */
    ImageWriter begin(long presentationTimeUs) throws IOException;

    /** Acik kareyi kapatir. */
    void end(long presentationTimeUs) throws IOException;

    /** Butun kareler bittikten sonra cikisi tamamlar. */
    void finish() throws IOException;

    /** Kaynaklari birakir; hata yolunda da cagrilir. */
    void release();

    /** Ciktinin galeri adresi (tamamlanmadan once null olabilir). */
    android.net.Uri outputUri();

    /** Kullaniciya gosterilecek cikis adi. */
    String outputName();

    long outputBytes();
}
