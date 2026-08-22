package com.opeyscaler.engine;

import java.io.IOException;

/** Satir satir (akis halinde) goruntu yazan kodlayici arayuzu. */
public interface ImageWriter {
    void start(int width, int height) throws IOException;

    /** {@code rgb} uzunlugu {@code width * 3} olan bir RGB satiri. */
    void writeRow(byte[] rgb) throws IOException;

    void finish() throws IOException;

    String extension();

    String mimeType();
}
