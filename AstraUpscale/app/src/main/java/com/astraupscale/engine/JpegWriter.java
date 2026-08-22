package com.astraupscale.engine;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Sifirdan yazilmis baseline (ardisik) JPEG kodlayici, 4:4:4 ornekleme.
 *
 * Goruntuyu 8 satirlik MCU bantlari halinde isler, bu yuzden 16K cikislar bile
 * sabit bellekle diske akitilabilir (Android'in Bitmap tabanli kodlayicisi
 * bu boyutlarda tum goruntuyu RAM'de tutmak zorunda kalir).
 */
public final class JpegWriter implements ImageWriter {

    private final OutputStream out;
    private final int quality;

    private int width, height;
    private int mcuCols;
    private int rowInBand;

    private byte[][] band;   // 8 satirlik RGB tamponu
    private final float[] blockY = new float[64];
    private final float[] blockCb = new float[64];
    private final float[] blockCr = new float[64];
    private final int[] quantized = new int[64];

    private final int[] quantLum = new int[64];
    private final int[] quantChr = new int[64];
    private final float[] fdctLum = new float[64];
    private final float[] fdctChr = new float[64];

    private int prevDcY, prevDcCb, prevDcCr;

    // Bit tamponu
    private int bitBuffer;
    private int bitCount;
    private final byte[] outBuf = new byte[1 << 16];
    private int outPos;

    public JpegWriter(OutputStream out, int quality) {
        this.out = out;
        this.quality = Math.max(1, Math.min(100, quality));
    }

    // ---------------------------------------------------------------- tablolar

    private static final int[] ZIGZAG = {
            0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5,
            12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28,
            35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51,
            58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63};

    private static final int[] STD_LUM_QUANT = {
            16, 11, 10, 16, 24, 40, 51, 61,
            12, 12, 14, 19, 26, 58, 60, 55,
            14, 13, 16, 24, 40, 57, 69, 56,
            14, 17, 22, 29, 51, 87, 80, 62,
            18, 22, 37, 56, 68, 109, 103, 77,
            24, 35, 55, 64, 81, 104, 113, 92,
            49, 64, 78, 87, 103, 121, 120, 101,
            72, 92, 95, 98, 112, 100, 103, 99};

    private static final int[] STD_CHR_QUANT = {
            17, 18, 24, 47, 99, 99, 99, 99,
            18, 21, 26, 66, 99, 99, 99, 99,
            24, 26, 56, 99, 99, 99, 99, 99,
            47, 66, 99, 99, 99, 99, 99, 99,
            99, 99, 99, 99, 99, 99, 99, 99,
            99, 99, 99, 99, 99, 99, 99, 99,
            99, 99, 99, 99, 99, 99, 99, 99,
            99, 99, 99, 99, 99, 99, 99, 99};

    private static final double[] AAN = {
            1.0, 1.387039845, 1.306562965, 1.175875602,
            1.0, 0.785694958, 0.541196100, 0.275899379};

    private static final int[] DC_LUM_BITS = {0, 0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0};
    private static final int[] DC_LUM_VALS = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};
    private static final int[] DC_CHR_BITS = {0, 0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0};
    private static final int[] DC_CHR_VALS = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};

    private static final int[] AC_LUM_BITS = {0, 0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7d};
    private static final int[] AC_LUM_VALS = {
            0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07,
            0x22, 0x71, 0x14, 0x32, 0x81, 0x91, 0xa1, 0x08, 0x23, 0x42, 0xb1, 0xc1, 0x15, 0x52, 0xd1, 0xf0,
            0x24, 0x33, 0x62, 0x72, 0x82, 0x09, 0x0a, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x25, 0x26, 0x27, 0x28,
            0x29, 0x2a, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49,
            0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69,
            0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89,
            0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7,
            0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3, 0xc4, 0xc5,
            0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda, 0xe1, 0xe2,
            0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf1, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8,
            0xf9, 0xfa};

    private static final int[] AC_CHR_BITS = {0, 0, 2, 1, 2, 4, 4, 3, 4, 7, 5, 4, 4, 0, 1, 2, 0x77};
    private static final int[] AC_CHR_VALS = {
            0x00, 0x01, 0x02, 0x03, 0x11, 0x04, 0x05, 0x21, 0x31, 0x06, 0x12, 0x41, 0x51, 0x07, 0x61, 0x71,
            0x13, 0x22, 0x32, 0x81, 0x08, 0x14, 0x42, 0x91, 0xa1, 0xb1, 0xc1, 0x09, 0x23, 0x33, 0x52, 0xf0,
            0x15, 0x62, 0x72, 0xd1, 0x0a, 0x16, 0x24, 0x34, 0xe1, 0x25, 0xf1, 0x17, 0x18, 0x19, 0x1a, 0x26,
            0x27, 0x28, 0x29, 0x2a, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48,
            0x49, 0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68,
            0x69, 0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87,
            0x88, 0x89, 0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5,
            0xa6, 0xa7, 0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3,
            0xc4, 0xc5, 0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda,
            0xe2, 0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8,
            0xf9, 0xfa};

    /** [deger] -> {kod, uzunluk} */
    private int[][] dcLumTable, acLumTable, dcChrTable, acChrTable;

    private static int[][] buildHuffTable(int[] bits, int[] values) {
        int[][] table = new int[256][];
        int code = 0, k = 0;
        for (int len = 1; len <= 16; len++) {
            for (int i = 0; i < bits[len]; i++) {
                table[values[k++]] = new int[]{code, len};
                code++;
            }
            code <<= 1;
        }
        return table;
    }

    private void buildQuantTables() {
        int scale = quality < 50 ? 5000 / quality : 200 - quality * 2;
        for (int i = 0; i < 64; i++) {
            quantLum[i] = clampQuant((STD_LUM_QUANT[i] * scale + 50) / 100);
            quantChr[i] = clampQuant((STD_CHR_QUANT[i] * scale + 50) / 100);
        }
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                int i = r * 8 + c;
                double s = AAN[r] * AAN[c] * 8.0;
                fdctLum[i] = (float) (1.0 / (quantLum[i] * s));
                fdctChr[i] = (float) (1.0 / (quantChr[i] * s));
            }
        }
    }

    private static int clampQuant(int v) {
        return v < 1 ? 1 : Math.min(v, 255);
    }

    // ---------------------------------------------------------------- akis

    /** JPEG basligindaki boyut alanlari 16 bittir. */
    public static final int MAX_EDGE = 65535;

    @Override public void start(int width, int height) throws IOException {
        if (width > MAX_EDGE || height > MAX_EDGE) {
            throw new IOException("JPEG en fazla " + MAX_EDGE + " piksel olabilir ("
                    + width + "x" + height + "); bu boyutta PNG kullanin.");
        }
        this.width = width;
        this.height = height;
        this.mcuCols = (width + 7) / 8;
        this.band = new byte[8][width * 3];
        this.dcLumTable = buildHuffTable(DC_LUM_BITS, DC_LUM_VALS);
        this.acLumTable = buildHuffTable(AC_LUM_BITS, AC_LUM_VALS);
        this.dcChrTable = buildHuffTable(DC_CHR_BITS, DC_CHR_VALS);
        this.acChrTable = buildHuffTable(AC_CHR_BITS, AC_CHR_VALS);
        buildQuantTables();
        writeHeaders();
    }

    @Override public void writeRow(byte[] rgb) throws IOException {
        System.arraycopy(rgb, 0, band[rowInBand], 0, width * 3);
        rowInBand++;
        if (rowInBand == 8) {
            encodeBand(8);
            rowInBand = 0;
        }
    }

    @Override public void finish() throws IOException {
        if (rowInBand > 0) {
            encodeBand(rowInBand);
            rowInBand = 0;
        }
        flushBits();
        emit(0xFF);
        emit(0xD9); // EOI
        flushOut();
        out.flush();
    }

    /** Bir MCU satirini (8 piksel yuksekliginde bant) kodlar. */
    private void encodeBand(int validRows) throws IOException {
        for (int mx = 0; mx < mcuCols; mx++) {
            int x0 = mx * 8;
            for (int y = 0; y < 8; y++) {
                byte[] row = band[Math.min(y, validRows - 1)];
                for (int x = 0; x < 8; x++) {
                    int sx = x0 + x;
                    if (sx >= width) sx = width - 1;
                    int p = sx * 3;
                    float r = row[p] & 0xFF;
                    float g = row[p + 1] & 0xFF;
                    float b = row[p + 2] & 0xFF;
                    int i = y * 8 + x;
                    blockY[i] = 0.299f * r + 0.587f * g + 0.114f * b - 128f;
                    blockCb[i] = -0.168736f * r - 0.331264f * g + 0.5f * b;
                    blockCr[i] = 0.5f * r - 0.418688f * g - 0.081312f * b;
                }
            }
            prevDcY = encodeBlock(blockY, fdctLum, dcLumTable, acLumTable, prevDcY);
            prevDcCb = encodeBlock(blockCb, fdctChr, dcChrTable, acChrTable, prevDcCb);
            prevDcCr = encodeBlock(blockCr, fdctChr, dcChrTable, acChrTable, prevDcCr);
        }
    }

    private int encodeBlock(float[] block, float[] fdctTable, int[][] dcTable, int[][] acTable, int prevDc)
            throws IOException {
        forwardDct(block);
        for (int i = 0; i < 64; i++) {
            float v = block[i] * fdctTable[i];
            quantized[i] = (int) (v < 0 ? v - 0.5f : v + 0.5f);
        }

        int dc = quantized[0];
        int diff = dc - prevDc;
        if (diff == 0) {
            writeCode(dcTable[0]);
        } else {
            int size = magnitude(diff);
            writeCode(dcTable[size]);
            writeAmplitude(diff, size);
        }

        int end = 63;
        while (end > 0 && quantized[ZIGZAG[end]] == 0) end--;
        int run = 0;
        for (int k = 1; k <= end; k++) {
            int v = quantized[ZIGZAG[k]];
            if (v == 0) {
                run++;
                continue;
            }
            while (run > 15) {
                writeCode(acTable[0xF0]); // ZRL
                run -= 16;
            }
            int size = magnitude(v);
            writeCode(acTable[(run << 4) | size]);
            writeAmplitude(v, size);
            run = 0;
        }
        if (end < 63) writeCode(acTable[0x00]); // EOB
        return dc;
    }

    private static int magnitude(int v) {
        int a = v < 0 ? -v : v;
        int size = 0;
        while (a != 0) {
            a >>= 1;
            size++;
        }
        return size;
    }

    private void writeAmplitude(int v, int size) throws IOException {
        int value = v < 0 ? v - 1 : v;
        writeBits(value & ((1 << size) - 1), size);
    }

    /** AAN hizli ileri DCT (libjpeg jfdctflt ile ayni yapida). */
    private static void forwardDct(float[] b) {
        for (int i = 0; i < 8; i++) {
            int o = i * 8;
            float t0 = b[o] + b[o + 7], t7 = b[o] - b[o + 7];
            float t1 = b[o + 1] + b[o + 6], t6 = b[o + 1] - b[o + 6];
            float t2 = b[o + 2] + b[o + 5], t5 = b[o + 2] - b[o + 5];
            float t3 = b[o + 3] + b[o + 4], t4 = b[o + 3] - b[o + 4];

            float t10 = t0 + t3, t13 = t0 - t3;
            float t11 = t1 + t2, t12 = t1 - t2;
            b[o] = t10 + t11;
            b[o + 4] = t10 - t11;
            float z1 = (t12 + t13) * 0.707106781f;
            b[o + 2] = t13 + z1;
            b[o + 6] = t13 - z1;

            t10 = t4 + t5;
            t11 = t5 + t6;
            t12 = t6 + t7;
            float z5 = (t10 - t12) * 0.382683433f;
            float z2 = 0.541196100f * t10 + z5;
            float z4 = 1.306562965f * t12 + z5;
            float z3 = t11 * 0.707106781f;
            float z11 = t7 + z3, z13 = t7 - z3;
            b[o + 5] = z13 + z2;
            b[o + 3] = z13 - z2;
            b[o + 1] = z11 + z4;
            b[o + 7] = z11 - z4;
        }
        for (int i = 0; i < 8; i++) {
            float t0 = b[i] + b[i + 56], t7 = b[i] - b[i + 56];
            float t1 = b[i + 8] + b[i + 48], t6 = b[i + 8] - b[i + 48];
            float t2 = b[i + 16] + b[i + 40], t5 = b[i + 16] - b[i + 40];
            float t3 = b[i + 24] + b[i + 32], t4 = b[i + 24] - b[i + 32];

            float t10 = t0 + t3, t13 = t0 - t3;
            float t11 = t1 + t2, t12 = t1 - t2;
            b[i] = t10 + t11;
            b[i + 32] = t10 - t11;
            float z1 = (t12 + t13) * 0.707106781f;
            b[i + 16] = t13 + z1;
            b[i + 48] = t13 - z1;

            t10 = t4 + t5;
            t11 = t5 + t6;
            t12 = t6 + t7;
            float z5 = (t10 - t12) * 0.382683433f;
            float z2 = 0.541196100f * t10 + z5;
            float z4 = 1.306562965f * t12 + z5;
            float z3 = t11 * 0.707106781f;
            float z11 = t7 + z3, z13 = t7 - z3;
            b[i + 40] = z13 + z2;
            b[i + 24] = z13 - z2;
            b[i + 8] = z11 + z4;
            b[i + 56] = z11 - z4;
        }
    }

    // ---------------------------------------------------------------- bit/bayt cikisi

    private void writeCode(int[] code) throws IOException {
        writeBits(code[0], code[1]);
    }

    private void writeBits(int value, int length) throws IOException {
        if (length == 0) return;
        bitBuffer = (bitBuffer << length) | (value & ((1 << length) - 1));
        bitCount += length;
        while (bitCount >= 8) {
            int b = (bitBuffer >>> (bitCount - 8)) & 0xFF;
            emit(b);
            if (b == 0xFF) emit(0x00); // bayt doldurma
            bitCount -= 8;
        }
        bitBuffer &= (1 << bitCount) - 1;
    }

    private void flushBits() throws IOException {
        if (bitCount > 0) {
            int pad = 8 - bitCount;
            writeBits((1 << pad) - 1, pad);
        }
        bitBuffer = 0;
        bitCount = 0;
    }

    private void emit(int b) throws IOException {
        outBuf[outPos++] = (byte) b;
        if (outPos == outBuf.length) flushOut();
    }

    private void flushOut() throws IOException {
        if (outPos > 0) {
            out.write(outBuf, 0, outPos);
            outPos = 0;
        }
    }

    // ---------------------------------------------------------------- basliklar

    private void writeHeaders() throws IOException {
        emit(0xFF); emit(0xD8); // SOI

        // JFIF APP0
        emit(0xFF); emit(0xE0);
        emit(0); emit(16);
        emit('J'); emit('F'); emit('I'); emit('F'); emit(0);
        emit(1); emit(1);   // surum 1.1
        emit(0);            // birim yok
        emit(0); emit(1); emit(0); emit(1); // en/boy orani 1:1
        emit(0); emit(0);   // kucuk resim yok

        // DQT
        emit(0xFF); emit(0xDB);
        emit(0); emit(132);
        emit(0);
        for (int i = 0; i < 64; i++) emit(quantLum[ZIGZAG[i]]);
        emit(1);
        for (int i = 0; i < 64; i++) emit(quantChr[ZIGZAG[i]]);

        // SOF0 (baseline, 3 bilesen, 4:4:4)
        emit(0xFF); emit(0xC0);
        emit(0); emit(17);
        emit(8);
        emit((height >> 8) & 0xFF); emit(height & 0xFF);
        emit((width >> 8) & 0xFF); emit(width & 0xFF);
        emit(3);
        emit(1); emit(0x11); emit(0);
        emit(2); emit(0x11); emit(1);
        emit(3); emit(0x11); emit(1);

        writeDht(0x00, DC_LUM_BITS, DC_LUM_VALS);
        writeDht(0x10, AC_LUM_BITS, AC_LUM_VALS);
        writeDht(0x01, DC_CHR_BITS, DC_CHR_VALS);
        writeDht(0x11, AC_CHR_BITS, AC_CHR_VALS);

        // SOS
        emit(0xFF); emit(0xDA);
        emit(0); emit(12);
        emit(3);
        emit(1); emit(0x00);
        emit(2); emit(0x11);
        emit(3); emit(0x11);
        emit(0); emit(63); emit(0);
    }

    private void writeDht(int id, int[] bits, int[] values) throws IOException {
        emit(0xFF); emit(0xC4);
        int len = 2 + 1 + 16 + values.length;
        emit((len >> 8) & 0xFF); emit(len & 0xFF);
        emit(id);
        for (int i = 1; i <= 16; i++) emit(bits[i]);
        for (int v : values) emit(v);
    }

    @Override public String extension() { return "jpg"; }

    @Override public String mimeType() { return "image/jpeg"; }
}
