package com.suhas.multyfideliverybuy;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Recovers a missing same-session Research/Formula daily candidate baseline
 * after clean installs, missed 17:30 Jobs, or an interrupted EOD build.
 * Does NOT loosen Forecast gates or Formula V1's immutable formula.
 */
final class ResearchSessionRecovery {
    private static final String PREF = "research_daily_session_recovery_v1";
    private static final long RETRY_COOLDOWN_MS = 12L * 60_000L;
    private static final TimeZone IST = TimeZone.getTimeZone("Asia/Kolkata");
    private ResearchSessionRecovery() {}

    static long startOfSession(long now) {
        Calendar c = Calendar.getInstance(IST);
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    static boolean eligibleTime(long now) {
        int minute = NseTradingCalendar.minuteOfDay(now);
        return NseTradingCalendar.isTradingDay(now)
                && minute >= 7 * 60 + 15 && minute < 14 * 60;
    }

    /**
     * A target key alone is NOT proof of a valid session: a crash could have
     * persisted the key before (or without) the underlying candidate files.
     */
    static boolean sessionReady(Context c, String session) {
        if (session == null || session.isEmpty()
                || !session.equals(AppPrefs.getResearchForecastTargetKey(c))) return false;
        JSONArray pool = ResearchStore.candidatePool(c);
        JSONArray predictions = ResearchStore.predictions(c);
        if (pool.length() == 0 || predictions.length() == 0) return false;
        JSONObject firstPool = pool.optJSONObject(0);
        JSONObject firstPrediction = predictions.optJSONObject(0);
        return firstPool != null && firstPrediction != null
                && session.equals(firstPool.optString("forecastSessionKey"))
                && session.equals(firstPrediction.optString("forecastSessionKey"));
    }

    /**
     * Full-universe EOD is expensive. Persist an attempt reservation so a
     * failed Groww scan cannot restart on every two-minute guardian tick.
     */
    static synchronized boolean reserveEodAttempt(Context c,long now) {
        SharedPreferences p=c.getSharedPreferences(PREF,Context.MODE_PRIVATE);
        String day=NseTradingCalendar.dayKey(now);
        long previous=day.equals(p.getString("eodDay",""))
                ?p.getLong("eodLastAttempt",0):0;
        if(previous>0 && now>=previous && now-previous<RETRY_COOLDOWN_MS)
            return false;
        return p.edit().putString("eodDay",day)
                .putLong("eodLastAttempt",now).commit();
    }

    static synchronized boolean recoverIfMissing(Context context, long now) {
        Context c = context.getApplicationContext();
        String session = NseTradingCalendar.dayKey(now);
        if (sessionReady(c, session)) return true;
        if (!eligibleTime(now)) return false;
        SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        long lastAt = session.equals(p.getString("session", ""))
                ? p.getLong("lastAttempt", 0L) : 0L;
        if (lastAt > 0 && now >= lastAt && now - lastAt < RETRY_COOLDOWN_MS) {
            AppPrefs.setResearchOrchestrator(c, "BASELINE_RECOVERY_PAUSED",
                    "Today's Forecast/Formula daily pool is missing. Broker scan retry "
                            + "is cooling down to prevent Groww 429 responses.");
            return false;
        }
        // Commit retry reservation before network calls. Concurrent lifecycle and
        // persisted JobScheduler ticks cannot create duplicate full NSE scans.
        if (!p.edit().putString("session", session).putLong("lastAttempt", now)
                .putString("result", "RUNNING").commit()) return false;
        AppPrefs.setResearchOrchestrator(c, "BASELINE_RECOVERING",
                "Rebuilding today's missing daily stock pool from PRIOR completed "
                        + "daily candles; no recommendation issued until checks pass.");
        DiagnosticsStore.runtime(c, "RESEARCH_SESSION_MISSING", "",
                "Missing usable Forecast/Formula pool for " + session
                        + " • rebuilding with strictly prior-session daily candles.");
        boolean ok = ResearchEngine.recoverForSession(c, now);
        p.edit().putString("result", ok ? "RECOVERED" : "RETRY_AFTER_BACKOFF")
                .putLong("finishedAt", System.currentTimeMillis()).apply();
        if (ok) {
            DiagnosticsStore.runtime(c, "RESEARCH_SESSION_RECOVERY_SUCCESS", "",
                    "Both Forecast and immutable Formula can now assess current-session "
                            + "candidates using a recovered previous-day-only baseline.");
        } else {
            AppPrefs.setResearchOrchestrator(c, "BASELINE_RECOVERY_UNAVAILABLE",
                    "No verified current-session pool; no fabricated calls. "
                            + "Retry scheduled after Groww cooldown.");
            DiagnosticsStore.runtime(c, "RESEARCH_SESSION_RECOVERY_RETRY", "",
                    "Daily baseline unavailable after scan; keep both call engines fail-closed.");
        }
        return ok;
    }

    static String diagnosticStatus(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        return p.getString("result", "NOT_NEEDED") + " • session "
                + p.getString("session", "") + " • last attempt "
                + p.getLong("lastAttempt", 0L);
    }
}
