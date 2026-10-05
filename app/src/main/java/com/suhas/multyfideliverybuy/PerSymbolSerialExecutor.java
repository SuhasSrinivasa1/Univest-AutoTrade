package com.suhas.multyfideliverybuy;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Serializes official execution events per symbol while allowing different symbols to proceed concurrently.
 * This prevents an ENTRY/REENTRY/EXIT race for the same stock without slowing unrelated Univest signals.
 */
final class PerSymbolSerialExecutor {
    private final ExecutorService backend;
    private final Map<String, Lane> lanes = new ConcurrentHashMap<>();

    PerSymbolSerialExecutor(int threads) {
        backend = Executors.newFixedThreadPool(Math.max(2, threads));
    }

    void execute(String symbol, Runnable task) {
        if (task == null) return;
        final String key = symbol == null || symbol.trim().isEmpty() ? "__GLOBAL__" : symbol.trim().toUpperCase();
        Lane lane = lanes.computeIfAbsent(key, k -> new Lane(k));
        lane.enqueue(task);
    }

    void shutdown() {
        backend.shutdown();
    }

    private final class Lane {
        private final String key;
        private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        private boolean running;

        Lane(String key) { this.key = key; }

        synchronized void enqueue(Runnable task) {
            queue.addLast(task);
            if (!running) {
                running = true;
                scheduleNext();
            }
        }

        private synchronized void scheduleNext() {
            Runnable next = queue.pollFirst();
            if (next == null) {
                running = false;
                lanes.remove(key, this);
                return;
            }
            backend.execute(() -> {
                try { next.run(); }
                finally { scheduleNext(); }
            });
        }
    }
}
