package com.suhas.multyfideliverybuy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Low-duty foreground guardian for restrictive OEM firmware (notably Vivo/Funtouch).
 * It does not make trading decisions. It keeps the process foreground-visible and drives the
 * Research orchestrator at a two-minute live cadence while official execution remains notification-driven.
 */
public class MarketGuardianService extends Service {
    private static final String CHANNEL = "market_guardian";
    private static final int NOTIFICATION_ID = 2967;
    private static final long TICK_MS = 2L * 60L * 1000L;
    private static final String PREF = "market_guardian_state";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean busy = new AtomicBoolean(false);

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long now = System.currentTimeMillis();
            getSharedPreferences(PREF, MODE_PRIVATE).edit()
                    .putLong("heartbeat", now)
                    .putBoolean("running", true)
                    .apply();
            if (busy.compareAndSet(false, true)) {
                new Thread(() -> {
                    try {
                        if (DurableOfficialSignalQueue.pendingCount(getApplicationContext()) > 0) {
                            DurableOfficialSignalQueue.recoverPending(getApplicationContext());
                            OfficialSignalRecoveryScheduler.scheduleNow(getApplicationContext());
                        }
                        // Drive all orchestrator stages, not only the live market stage.
                        // This makes Session-3 timeout scoring and post-close replay resilient on
                        // restrictive OEM firmware where the fallback JobScheduler may be delayed.
                        // Same-day broker exits must precede slow scanner operations.
                        try { UnivestSameDayExitManager.tick(getApplicationContext()); }
                        catch (Throwable ignored) { DiagnosticsStore.runtime(getApplicationContext(),
                                "OFFICIAL_EOD_PRIORITY_ERROR", "", "Priority exit check failed."); }
                        try { ResearchTradeEngine.prioritizeSameDayExits(getApplicationContext()); }
                        catch (Throwable ignored) { DiagnosticsStore.runtime(getApplicationContext(),
                                "FORECAST_EOD_PRIORITY_ERROR", "", "Priority Forecast exit check failed."); }
                        ResearchOrchestrator.tick(getApplicationContext());
                        // Broker-risk cleanup has priority over market discovery. Exit only
                        // positions attributable to this app, with fill reconciliation.
                        try {
                            for (UnivestStateStore.State managed :
                                    UnivestStateStore.all(getApplicationContext())) {
                                if (managed == null || managed.symbol.isEmpty()) continue;
                                if (SameDayForecastPolicy.entryAllowed(System.currentTimeMillis()))
                                    SharedIntradayAveraging.refresh(getApplicationContext(), managed.symbol);
                            }
                            UnivestSameDayExitManager.tick(getApplicationContext());
                        } catch (Throwable exitFailure) {
                            DiagnosticsStore.error(getApplicationContext(),
                                    "SAME_DAY_GUARDIAN_EXIT_FAILED", "",
                                    "Broker-aware same-day exit coordinator could not complete.",exitFailure);
                        }
                        // Same 2-minute live cadence for Formula, on a separate worker.
                        // A slow Formula score may not delay official execution or Forecast.
                        new Thread(() -> {
                            try { FormulaEngine.refresh(getApplicationContext(), false); }
                            catch (Throwable formulaFailure) {
                                DiagnosticsStore.error(getApplicationContext(), "FORMULA_GUARDIAN_FAILED", "",
                                        "Independent Formula tick failed.", formulaFailure);
                            }
                        }, "formula-fixed-v1-guardian").start();
                    } catch (Throwable t) {
                        DiagnosticsStore.error(getApplicationContext(), "MARKET_GUARDIAN_TICK_FAILED", "",
                                "Foreground market guardian tick failed.", t);
                    } finally {
                        busy.set(false);
                    }
                }, "market-guardian-tick").start();
            }
            handler.postDelayed(this, TICK_MS);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID, notification());
        getSharedPreferences(PREF, MODE_PRIVATE).edit()
                .putLong("startedAt", System.currentTimeMillis())
                .putBoolean("running", true)
                .apply();
        handler.post(tick);
        DiagnosticsStore.runtime(getApplicationContext(), "MARKET_GUARDIAN_STARTED", "",
                "Foreground guardian active • two-minute Research live cadence • official execution remains notification-driven.");
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(tick);
        getSharedPreferences(PREF, MODE_PRIVATE).edit()
                .putBoolean("running", false)
                .putLong("stoppedAt", System.currentTimeMillis())
                .apply();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    static void ensureRunning(Context context) {
        Context c = context.getApplicationContext();
        try {
            Intent i = new Intent(c, MarketGuardianService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Throwable t) {
            DiagnosticsStore.error(c, "MARKET_GUARDIAN_START_FAILED", "",
                    "Unable to start foreground market guardian; check battery/background permissions.", t);
        }
    }

    static boolean isRecentlyAlive(Context context) {
        long hb = context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getLong("heartbeat", 0L);
        return hb > 0L && System.currentTimeMillis() - hb <= 5L * 60L * 1000L;
    }

    static String statusText(Context context) {
        Context c = context.getApplicationContext();
        long hb = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getLong("heartbeat", 0L);
        if (hb <= 0L) return "NOT SEEN";
        long sec = Math.max(0L, (System.currentTimeMillis() - hb) / 1000L);
        return isRecentlyAlive(c) ? "ACTIVE • heartbeat " + sec + "s ago" : "STALE • last heartbeat " + sec + "s ago";
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Univest market guardian",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Keeps Univest AutoTrade available on restrictive Android firmware.");
        nm.createNotificationChannel(ch);
    }

    private Notification notification() {
        Intent open = new Intent(this, DashboardActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 2967, open, flags);
        String text = NseTradingCalendar.isRegularMarketOpen(System.currentTimeMillis())
                ? "Active • official notifications protected • Forecast scans every 2 min"
                : "Active • background protection ready for the next NSE session";
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return b.setContentTitle("Univest AutoTrade active")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }
}
