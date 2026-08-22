package com.astraupscale.app;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import android.os.StatFs;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Locale;

/**
 * Cihazin isleme kapasitesini olcer. Bu bilgiler hem kullaniciya gosterilir
 * hem de {@link LoadLevel} ile birlikte is parcacigi sayisi ve doseme
 * boyutunu belirler.
 */
public final class DeviceProfile {

    public final int cores;
    public final int bigCores;
    public final int maxFreqMHz;
    public final long totalRamBytes;
    public final long heapLimitBytes;
    public final boolean vulkan;
    public final String abi;
    public final String socModel;

    private DeviceProfile(int cores, int bigCores, int maxFreqMHz, long totalRam,
                          long heapLimit, boolean vulkan, String abi, String soc) {
        this.cores = cores;
        this.bigCores = bigCores;
        this.maxFreqMHz = maxFreqMHz;
        this.totalRamBytes = totalRam;
        this.heapLimitBytes = heapLimit;
        this.vulkan = vulkan;
        this.abi = abi;
        this.socModel = soc;
    }

    public static DeviceProfile scan(Context ctx) {
        int cores = Runtime.getRuntime().availableProcessors();

        // Cekirdek basina azami frekans: buyuk cekirdekleri ayirt etmek icin
        int[] freq = new int[cores];
        int maxFreq = 0;
        for (int i = 0; i < cores; i++) {
            freq[i] = readInt("/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_max_freq") / 1000;
            if (freq[i] > maxFreq) maxFreq = freq[i];
        }
        int big = 0;
        for (int f : freq) {
            // En hizli cekirdegin %85'inden hizli olanlari "buyuk" sayiyoruz
            if (maxFreq > 0 && f >= maxFreq * 0.85) big++;
        }
        if (big == 0) big = cores;

        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        long heap = (long) am.getLargeMemoryClass() << 20;

        String abi = Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0
                ? Build.SUPPORTED_ABIS[0] : Build.CPU_ABI;
        String soc = Build.VERSION.SDK_INT >= 31 && Build.SOC_MODEL != null
                && !Build.UNKNOWN.equals(Build.SOC_MODEL) ? Build.SOC_MODEL : Build.HARDWARE;

        return new DeviceProfile(cores, big, maxFreq, mi.totalMem, heap,
                NativeSr.gpuAvailable(), abi, soc);
    }

    /** Cihazin o anki isinma durumu (API 29+); bilinmiyorsa -1. */
    public static int thermalStatus(Context ctx) {
        if (Build.VERSION.SDK_INT < 29) return -1;
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        return pm == null ? -1 : pm.getCurrentThermalStatus();
    }

    public static int thermalTextRes(int status) {
        switch (status) {
            case 0: return R.string.thermal_none;
            case 1: return R.string.thermal_light;
            case 2: return R.string.thermal_moderate;
            case 3: return R.string.thermal_severe;
            case 4: case 5: case 6: return R.string.thermal_critical;
            default: return R.string.thermal_unknown;
        }
    }

    /** Cikis dosyasi icin kullanilabilir alan. */
    public static long freeSpaceBytes(File dir) {
        try {
            StatFs fs = new StatFs(dir.getAbsolutePath());
            return fs.getAvailableBytes();
        } catch (Throwable t) {
            return Long.MAX_VALUE;
        }
    }

    private static int readInt(String path) {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(path));
            return Integer.parseInt(r.readLine().trim());
        } catch (Throwable t) {
            return 0;
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public String summary(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append(socModel).append("  ·  ").append(abi).append('\n');
        sb.append(ctx.getString(R.string.device_cores, cores, bigCores));
        if (maxFreqMHz > 0) sb.append(String.format(Locale.getDefault(), "  ·  %.1f GHz", maxFreqMHz / 1000.0));
        sb.append('\n').append(ctx.getString(R.string.device_memory,
                totalRamBytes / 1073741824.0, heapLimitBytes >> 20));
        sb.append('\n').append(ctx.getString(vulkan ? R.string.device_vulkan : R.string.device_no_vulkan));
        return sb.toString();
    }
}
