package com.opeyscaler.engine;

import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Satir satir yazan PNG (8 bit RGB, kayipsiz) kodlayici.
 * Tum goruntuyu bellekte tutmaz: her satir Paeth filtresinden gecirilip
 * dogrudan Deflater'a verilir ve 32 KB'lik IDAT parcalari halinde diske akitilir.
 */
public final class PngWriter implements ImageWriter {

    private static final int CHUNK = 32 * 1024;

    private final OutputStream out;
    private final int compressionLevel;
    private Deflater deflater;
    private byte[] deflateBuf;
    private byte[] prevRow;
    private byte[] filtered;
    private int width;
    private int rowBytes;

    public PngWriter(OutputStream out, int compressionLevel) {
        this.out = out;
        this.compressionLevel = compressionLevel;
    }

    @Override public void start(int width, int height) throws IOException {
        this.width = width;
        this.rowBytes = width * 3;
        this.prevRow = new byte[rowBytes];
        this.filtered = new byte[rowBytes + 1];
        this.deflater = new Deflater(compressionLevel);
        this.deflateBuf = new byte[CHUNK];

        out.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        byte[] ihdr = new byte[13];
        putInt(ihdr, 0, width);
        putInt(ihdr, 4, height);
        ihdr[8] = 8;  // bit derinligi
        ihdr[9] = 2;  // renk tipi: truecolor
        ihdr[10] = 0; // sikistirma
        ihdr[11] = 0; // filtre
        ihdr[12] = 0; // interlace yok
        writeChunk("IHDR", ihdr, 0, ihdr.length);
        // sRGB renk uzayi + gamma bilgisi
        writeChunk("sRGB", new byte[]{0}, 0, 1);
        byte[] gama = new byte[4];
        putInt(gama, 0, 45455);
        writeChunk("gAMA", gama, 0, 4);
    }

    @Override public void writeRow(byte[] rgb) throws IOException {
        // Paeth (5 numarali) satir filtresi: fotograflarda iyi sikisma saglar.
        filtered[0] = 4;
        for (int i = 0; i < rowBytes; i++) {
            int a = i >= 3 ? (rgb[i - 3] & 0xFF) : 0;
            int b = prevRow[i] & 0xFF;
            int c = i >= 3 ? (prevRow[i - 3] & 0xFF) : 0;
            int p = a + b - c;
            int pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
            int pred = (pa <= pb && pa <= pc) ? a : (pb <= pc ? b : c);
            filtered[i + 1] = (byte) ((rgb[i] & 0xFF) - pred);
        }
        System.arraycopy(rgb, 0, prevRow, 0, rowBytes);
        deflater.setInput(filtered, 0, rowBytes + 1);
        drain(false);
    }

    private void drain(boolean finish) throws IOException {
        if (finish) {
            while (!deflater.finished()) {
                int n = deflater.deflate(deflateBuf, 0, deflateBuf.length);
                if (n > 0) writeChunk("IDAT", deflateBuf, 0, n);
            }
        } else {
            while (!deflater.needsInput()) {
                int n = deflater.deflate(deflateBuf, 0, deflateBuf.length);
                if (n > 0) writeChunk("IDAT", deflateBuf, 0, n);
                else break;
            }
        }
    }

    @Override public void finish() throws IOException {
        deflater.finish();
        drain(true);
        deflater.end();
        writeChunk("IEND", new byte[0], 0, 0);
        out.flush();
    }

    private void writeChunk(String type, byte[] data, int off, int len) throws IOException {
        byte[] header = new byte[8];
        putInt(header, 0, len);
        for (int i = 0; i < 4; i++) header[4 + i] = (byte) type.charAt(i);
        out.write(header);
        out.write(data, off, len);
        CRC32 crc = new CRC32();
        crc.update(header, 4, 4);
        crc.update(data, off, len);
        byte[] c = new byte[4];
        putInt(c, 0, (int) crc.getValue());
        out.write(c);
    }

    private static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    @Override public String extension() { return "png"; }

    @Override public String mimeType() { return "image/png"; }
}
