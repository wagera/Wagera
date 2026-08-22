package com.opeyscaler.engine;

/** Uzun suren islemler icin ilerleme geri bildirimi. */
public interface Progress {
    /** @param fraction 0..1 arasi ilerleme, @param stage kullaniciya gosterilecek asama adi */
    void onProgress(float fraction, String stage);

    /** true donerse islem iptal edilir. */
    boolean isCancelled();

    Progress NONE = new Progress() {
        @Override public void onProgress(float fraction, String stage) { }
        @Override public boolean isCancelled() { return false; }
    };
}
