package com.suhas.multyfideliverybuy;

import android.content.Context;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Best-effort in-process watcher for a Univest EXIT that arrived while the holding was not green.
 * The persisted OfficialSignalRecovery JobScheduler remains the durable fallback if Vivo kills the
 * process. Polling is intentionally modest (15s) to avoid Groww API-rate-limit pressure.
 */
final class DeferredGreenExitWatcher {
    private static final ScheduledExecutorService EXEC = Executors.newScheduledThreadPool(1);
    private static final Set<String> ACTIVE = Collections.synchronizedSet(new HashSet<>());
    private static final long MAX_WATCH_MS = 30L * 60L * 1000L;

    private DeferredGreenExitWatcher() {}

    static void start(Context context, String symbol) {
        if (symbol == null || symbol.trim().isEmpty()) return;
        String s = symbol.trim().toUpperCase(java.util.Locale.US);
        if (!ACTIVE.add(s)) return;
        Context c = context.getApplicationContext();
        long started = System.currentTimeMillis();
        EXEC.scheduleWithFixedDelay(new Runnable() {
            @Override public void run() {
                try {
                    if (System.currentTimeMillis() - started > MAX_WATCH_MS
                            || !NseTradingCalendar.isRegularMarketOpen(System.currentTimeMillis())
                            || UnivestManager.reconcileDeferredExit(c, s)) {
                        ACTIVE.remove(s);
                        throw new StopWatching();
                    }
                } catch (StopWatching stop) {
                    throw stop;
                } catch (Throwable t) {
                    DiagnosticsStore.error(c, "DEFERRED_GREEN_EXIT_WATCH_ERROR", s,
                            "Deferred green-exit watch check failed.", t);
                }
            }
        }, 15, 15, TimeUnit.SECONDS);
    }

    private static final class StopWatching extends RuntimeException {}
}
