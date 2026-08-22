package com.astraupscale.app;

import com.astraupscale.engine.PixelSource;

import java.io.IOException;

/**
 * Sinir agi ciktisini {@link PixelSource} olarak sunar.
 *
 * Model, kaynagi satir bantlari halinde buyutur; burada iki bant onbellekte
 * tutulur. Boylece 4x model ciktisi hicbir zaman tumuyle bellege alinmadan
 * akis halinde olceklendirme ve kodlama asamalarina beslenebilir.
 */
public final class NeuralPixelSource implements PixelSource {

    public interface BandListener {
        void onBandDone(int bandsDone, int bandsTotal);

        boolean isCancelled();
    }

    private final NativeSr sr;
    private final int srcW, srcH, scale, bandRows;
    private final int width, height;
    private final int bandsTotal;
    private final byte[][] buffers = new byte[2][];
    private final int[] bufferBand = {-1, -1};
    private final BandListener listener;
    private int bandsDone;

    public NeuralPixelSource(NativeSr sr, int srcW, int srcH, int bandRows, BandListener listener)
            throws IOException {
        this.sr = sr;
        this.srcW = srcW;
        this.srcH = srcH;
        this.scale = sr.scale();
        this.bandRows = bandRows;
        this.width = srcW * scale;
        this.height = srcH * scale;
        this.bandsTotal = (srcH + bandRows - 1) / bandRows;
        this.listener = listener;
        int bandBytes = width * bandRows * scale * 3;
        try {
            buffers[0] = new byte[bandBytes];
            buffers[1] = new byte[bandBytes];
        } catch (OutOfMemoryError e) {
            throw new IOException("Model ciktisi icin bellek ayrilamadi");
        }
    }

    @Override public int width() { return width; }

    @Override public int height() { return height; }

    public int bandsTotal() { return bandsTotal; }

    @Override public void readRow(int y, int[] out) {
        final int outRowsPerBand = bandRows * scale;
        final int band = y / outRowsPerBand;
        final int slot = ensureBand(band);
        final int rowInBand = y - band * outRowsPerBand;
        final byte[] buf = buffers[slot];
        int i = rowInBand * width * 3;
        for (int x = 0; x < width; x++) {
            out[x] = 0xFF000000
                    | ((buf[i] & 0xFF) << 16)
                    | ((buf[i + 1] & 0xFF) << 8)
                    | (buf[i + 2] & 0xFF);
            i += 3;
        }
    }

    /** Istenen bandi hesaplar (gerekiyorsa) ve tamponunun indeksini dondurur. */
    private int ensureBand(int band) {
        for (int s = 0; s < 2; s++) {
            if (bufferBand[s] == band) return s;
        }
        int slot = (bufferBand[0] <= bufferBand[1]) ? 0 : 1;  // en eski bandin uzerine yaz
        int srcY0 = band * bandRows;
        int rows = Math.min(bandRows, srcH - srcY0);
        if (!sr.processBand(srcY0, rows, buffers[slot])) {
            throw new RuntimeException("Model bandi islenemedi: " + sr.lastError());
        }
        bufferBand[slot] = band;
        bandsDone++;
        if (listener != null) {
            listener.onBandDone(Math.min(bandsDone, bandsTotal), bandsTotal);
            if (listener.isCancelled()) throw new RuntimeException("iptal");
        }
        return slot;
    }
}
