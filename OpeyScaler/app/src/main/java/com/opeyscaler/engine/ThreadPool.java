package com.opeyscaler.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Satir/sutun araliklarini cekirdekler arasinda bolen basit paralel dongu yardimcisi. */
public final class ThreadPool {

    public interface RangeTask {
        void run(int from, int to);
    }

    private final ExecutorService executor;
    private final int threads;

    public ThreadPool(int threads) {
        this.threads = Math.max(1, threads);
        this.executor = this.threads == 1 ? null : Executors.newFixedThreadPool(this.threads, r -> {
            Thread t = new Thread(r, "opeyscaler-worker");
            t.setDaemon(true);
            return t;
        });
    }

    public static ThreadPool auto() {
        return new ThreadPool(Runtime.getRuntime().availableProcessors());
    }

    public int threads() { return threads; }

    public void forRange(int count, RangeTask task) {
        if (executor == null || count < 4096) {
            task.run(0, count);
            return;
        }
        int chunk = (count + threads - 1) / threads;
        List<Future<?>> futures = new ArrayList<>(threads);
        for (int start = chunk; start < count; start += chunk) {
            final int from = start;
            final int to = Math.min(count, start + chunk);
            futures.add(executor.submit((Callable<Void>) () -> {
                task.run(from, to);
                return null;
            }));
        }
        task.run(0, Math.min(chunk, count));
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    public void shutdown() {
        if (executor != null) executor.shutdownNow();
    }
}
