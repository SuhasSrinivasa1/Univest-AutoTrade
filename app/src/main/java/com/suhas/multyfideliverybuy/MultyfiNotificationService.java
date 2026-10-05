package com.suhas.multyfideliverybuy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;


/**
 * Historical class name retained so Android keeps the existing notification-listener grant on upgrade.
 * v2.2 accepts notifications ONLY from the official Univest Android package com.univest.capp.
 */
public class MultyfiNotificationService extends NotificationListenerService {
    static final String UNIVEST_PACKAGE = "com.univest.capp";

    @Override public void onCreate() {
        super.onCreate();
        AppPrefs.setNotificationListenerHeartbeat(getApplicationContext(), "CREATED");
        UnivestHistoryDb.ensureInitialized(getApplicationContext());
        PreMarketReadinessScheduler.ensureScheduled(getApplicationContext());
        DurableOfficialSignalQueue.recoverPending(getApplicationContext());
        OfficialSignalRecoveryScheduler.scheduleNow(getApplicationContext());
        new Thread(() -> {
            try { UnivestManager.reconcileAll(getApplicationContext()); }
            catch (Throwable t) { DiagnosticsStore.error(getApplicationContext(), "STARTUP_RECONCILIATION_ERROR", "", "Campaign reconciliation failed.", t); }
        }, "official-startup-reconcile").start();
    }

    @Override public void onListenerConnected() {
        super.onListenerConnected();
        AppPrefs.setNotificationListenerHeartbeat(getApplicationContext(), "CONNECTED");
        ResearchScheduler.ensureScheduled(getApplicationContext());
        ResearchMonitorScheduler.ensureScheduled(getApplicationContext());
        PreMarketReadinessScheduler.ensureScheduled(getApplicationContext());
        DurableOfficialSignalQueue.recoverPending(getApplicationContext());
        OfficialSignalRecoveryScheduler.scheduleNow(getApplicationContext());
        new Thread(() -> {
            try { UnivestManager.reconcileAll(getApplicationContext()); }
            catch (Throwable t) { DiagnosticsStore.error(getApplicationContext(), "LISTENER_RECONNECT_RECONCILIATION_ERROR", "", "Broker reconciliation after notification-listener reconnect failed.", t); }
        }, "official-reconnect-reconcile").start();
    }

    @Override public void onListenerDisconnected() {
        AppPrefs.setNotificationListenerHeartbeat(getApplicationContext(), "DISCONNECTED");
        try { requestRebind(new ComponentName(this, MultyfiNotificationService.class)); }
        catch (Throwable ignored) {}
        super.onListenerDisconnected();
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        AppPrefs.setNotificationListenerHeartbeat(getApplicationContext(), "RECEIVING");
        if (sbn == null || sbn.getNotification() == null) return;
        String packageName = sbn.getPackageName() == null ? "" : sbn.getPackageName();
        if (!UNIVEST_PACKAGE.equals(packageName)) return; // hard package source lock

        String combined = collectText(sbn.getNotification());

        // v2.2: every official Univest delivery is archived and evaluated. Notification similarity/history is
        // never an execution gate; broker holdings/open BUY orders and deterministic broker references provide safety.
        UnivestParser.Signal signal = UnivestParser.parse(combined);
        DiagnosticsStore.notification(getApplicationContext(), packageName, combined, signal);
        if (signal != null) ResearchTradeEngine.onOfficialSignal(getApplicationContext(), signal, sbn.getPostTime());
        if (signal == null) {
            DiagnosticsStore.runtime(getApplicationContext(), "UNIVEST_NOTIFICATION_NO_TRADE", "",
                    "Notification archived but did not match an eligible <=3 month equity entry, back-in-range add, or equity book-profit/exit rule.");
            return;
        }

        if (!AppPrefs.isUnivestEnabled(getApplicationContext())) {
            String msg = "Ignored Univest " + signal.type + " • " + signal.symbol + " • automation is DISARMED.";
            AppPrefs.setUnivestStatus(getApplicationContext(), msg);
            DiagnosticsStore.runtime(getApplicationContext(), "SIGNAL_IGNORED_DISARMED", signal.symbol, msg);
            return;
        }

        // Do NOT permanently consume a signal before broker readiness/execution. Earlier builds did this and a
        // temporary auth/readiness problem could cause the only real Univest alert of the day to be marked
        // "already processed". LIVE execution is protected by broker holdings, open-order reconciliation and Groww order
        // reference idempotency; notification-level duplicate filtering is intentionally not an execution gate. A later genuine notification must remain eligible for a retry.

        final long postTime = sbn.getPostTime();
        if (signal.type == UnivestParser.Type.ENTRY || signal.type == UnivestParser.Type.REENTRY) {
            if (AppPrefs.isLiveMode(getApplicationContext()) && !AppPrefs.isReadyForBuy(getApplicationContext())) {
                String msg = "UNIVEST " + signal.type + " BLOCKED • " + signal.symbol
                        + " • LIVE mode requires fresh Groww authentication and static-IP readiness.";
                AppPrefs.setUnivestStatus(getApplicationContext(), msg);
                DiagnosticsStore.error(getApplicationContext(), "BUY_READINESS_BLOCK", signal.symbol, msg, null);
                return;
            }
            int configuredBudget = signal.type == UnivestParser.Type.ENTRY
                    ? AppPrefs.getUnivestBudget(getApplicationContext())
                    : AppPrefs.getUnivestAddBudget(getApplicationContext());
            AppPrefs.setUnivestStatus(getApplicationContext(), "UNIVEST " + signal.type + " DETECTED • " + signal.symbol
                    + " • ₹" + java.text.NumberFormat.getIntegerInstance(new java.util.Locale("en", "IN")).format(configuredBudget)
                    + " CNC " + AppPrefs.getExecutionMode(getApplicationContext()) + " path being durably queued.");
            String queued = DurableOfficialSignalQueue.enqueueAndDispatch(getApplicationContext(), signal, postTime);
            if (queued.isEmpty()) {
                String msg = "UNIVEST " + signal.type + " • " + signal.symbol
                        + " • durable queue write failed; no broker action was attempted.";
                AppPrefs.setUnivestStatus(getApplicationContext(), msg);
                DiagnosticsStore.error(getApplicationContext(), "OFFICIAL_SIGNAL_DURABLE_QUEUE_BLOCK", signal.symbol, msg, null);
            } else {
                showLocalStatus("UNIVEST " + signal.type, AppPrefs.getUnivestStatus(getApplicationContext()));
            }
            return;
        }

        // Official exit remains authoritative in LIVE mode. GrowwClient refreshes auth only if broker rejects a cached token.
        if (AppPrefs.isLiveMode(getApplicationContext()) && !AppPrefs.isReadyForBuy(getApplicationContext())) {
            DiagnosticsStore.runtime(getApplicationContext(), "EXIT_READINESS_WARNING", signal.symbol,
                    "Groww/static-IP readiness flag is stale; official Univest exit will still be attempted to reduce exposure.");
        }
        AppPrefs.setUnivestStatus(getApplicationContext(), "UNIVEST BOOK PROFIT / EXIT DETECTED • " + signal.symbol
                + " • " + AppPrefs.getExecutionMode(getApplicationContext())
                + " exact-symbol full CNC holding exit queued • green-only guard will use live broker average + executable bid.");
        String queued = DurableOfficialSignalQueue.enqueueAndDispatch(getApplicationContext(), signal, postTime);
        if (queued.isEmpty()) {
            String msg = "UNIVEST EXIT • " + signal.symbol + " • durable queue write failed; no broker action was attempted.";
            AppPrefs.setUnivestStatus(getApplicationContext(), msg);
            DiagnosticsStore.error(getApplicationContext(), "OFFICIAL_SIGNAL_DURABLE_QUEUE_BLOCK", signal.symbol, msg, null);
        } else {
            showLocalStatus("UNIVEST EXIT", AppPrefs.getUnivestStatus(getApplicationContext()));
        }
    }

    @Override public void onDestroy() {
        AppPrefs.setNotificationListenerHeartbeat(getApplicationContext(), "DESTROYED");
        super.onDestroy();
    }

    private String collectText(Notification n) {
        StringBuilder sb = new StringBuilder();
        append(sb, n.extras.getCharSequence(Notification.EXTRA_TITLE)); append(sb, n.extras.getCharSequence(Notification.EXTRA_TEXT));
        append(sb, n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)); append(sb, n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT));
        CharSequence[] lines = n.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (lines != null) for (CharSequence line : lines) append(sb, line);
        return sb.toString();
    }
    private void append(StringBuilder sb, CharSequence value) {
        if (value == null) return; String s = value.toString().trim(); if (s.isEmpty()) return;
        if (sb.indexOf(s) >= 0) return; if (sb.length() > 0) sb.append('\n'); sb.append(s);
    }

    private void showLocalStatus(String title, String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE); if (nm == null) return;
        String channelId = "univest_status";
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(new NotificationChannel(channelId, "Univest AutoTrade status", NotificationManager.IMPORTANCE_DEFAULT));
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, channelId) : new Notification.Builder(this);
        b.setContentTitle(title).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim).setAutoCancel(true);
        nm.notify((int) (System.currentTimeMillis() & 0x7FFFFFFF), b.build());
    }

}
