package com.suhas.multyfideliverybuy;

import android.content.Context;
import android.provider.Settings;

import java.util.List;

/**
 * Pre-market warm-up so the notification path does not spend its first seconds doing avoidable
 * housekeeping. Live broker quantity and executable price are still refreshed at an EXIT because
 * those values can change after pre-market validation.
 */
final class PreMarketReadiness {
    private PreMarketReadiness() {}

    static boolean run(Context context, String phase) {
        Context c = context.getApplicationContext();
        long started = System.currentTimeMillis();
        boolean queueOk = false, listenerOk = false, ipOk = false, authOk = false, instrumentsOk = false;
        StringBuilder detail = new StringBuilder();
        try {
            UnivestHistoryDb.ensureInitialized(c);

            DurableOfficialSignalQueue.recoverPending(c);
            queueOk = DurableOfficialSignalQueue.awaitIdle(30_000L);
            detail.append("queue=").append(queueOk ? "ready" : "pending");

            String enabled = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
            listenerOk = enabled != null && enabled.contains(c.getPackageName());
            detail.append(" • listener=").append(listenerOk ? "ready" : "off");

            NetworkCheck.Result ip = NetworkCheck.detectAndCompare(c);
            ipOk = ip.match;
            detail.append(" • staticIP=").append(ipOk ? "ready" : "mismatch");

            GrowwClient.Result auth = ipOk
                    ? GrowwClient.refreshAndTestAuthentication(c)
                    : new GrowwClient.Result(false, false, 0, "Skipped because static IP did not match.");
            authOk = auth.success;
            detail.append(" • Groww=").append(authOk ? "ready" : "not-ready");

            try {
                InstrumentRepository.refreshIfStale(c);
                List<InstrumentRepository.Instrument> list = InstrumentRepository.load(c);
                instrumentsOk = list != null && !list.isEmpty();
                detail.append(" • instruments=").append(list == null ? 0 : list.size());
            } catch (Throwable t) {
                detail.append(" • instruments=error");
            }

            try { UnivestManager.reconcileAll(c); }
            catch (Throwable t) {
                DiagnosticsStore.error(c, "PREMARKET_RECONCILE_FAILED", "",
                        "Pre-market broker reconciliation failed.", t);
            }

            if ("FINAL".equalsIgnoreCase(phase)) {
                try { ResearchOrchestrator.tick(c); } catch (Throwable ignored) {}
            }

            boolean ready = queueOk && listenerOk && ipOk && authOk && instrumentsOk;
            String msg = ("FINAL".equalsIgnoreCase(phase) ? "08:55 FINAL" : "08:25 WARM-UP")
                    + " • " + (ready ? "MARKET READY" : "ACTION NEEDED")
                    + " • " + detail
                    + " • elapsed " + (System.currentTimeMillis() - started) + " ms";
            AppPrefs.setPreMarketReadiness(c, ready, phase, msg);
            DiagnosticsStore.runtime(c, "PREMARKET_" + (phase == null ? "RUN" : phase.toUpperCase()), "", msg);
            HistoryBackupManager.forceAutoBackup(c);
            return ready;
        } catch (Throwable t) {
            String msg = "Pre-market validation failed: " + safe(t) + " • " + detail;
            AppPrefs.setPreMarketReadiness(c, false, phase, msg);
            DiagnosticsStore.error(c, "PREMARKET_VALIDATION_FAILED", "", msg, t);
            return false;
        }
    }

    static String statusText(Context c) {
        long t = AppPrefs.getPreMarketReadinessTime(c);
        String s = AppPrefs.getPreMarketReadinessStatus(c);
        return (AppPrefs.isPreMarketReady(c) ? "READY" : "NOT READY")
                + (t > 0 ? " • " + s : " • not run yet");
    }

    private static String safe(Throwable t) {
        if (t == null) return "unknown";
        String m = t.getMessage();
        return m == null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m;
    }
}
