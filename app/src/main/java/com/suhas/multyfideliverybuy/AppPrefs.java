package com.suhas.multyfideliverybuy;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

final class AppPrefs {
    private static final String FILE = "fresh_delivery_buy_prefs";
    static final String MODE_PAPER = "PAPER";
    static final String MODE_LIVE = "LIVE";

    private AppPrefs() {}

    private static SharedPreferences p(Context c) { return c.getSharedPreferences(FILE, Context.MODE_PRIVATE); }

    static boolean isArmed(Context c) { return p(c).getBoolean("armed", false); }
    static void setArmed(Context c, boolean v) { p(c).edit().putBoolean("armed", v).apply(); }
    static boolean isSwingEnabled(Context c) { return p(c).getBoolean("swing", false); }
    static void setSwingEnabled(Context c, boolean v) { p(c).edit().putBoolean("swing", v).apply(); }
    static boolean isMultibaggerEnabled(Context c) { return p(c).getBoolean("multibagger", false); }
    static void setMultibaggerEnabled(Context c, boolean v) { p(c).edit().putBoolean("multibagger", v).apply(); }

    static boolean isUnivestEnabled(Context c) { return p(c).getBoolean("univest_enabled", false); }
    static void setUnivestEnabled(Context c, boolean v) { p(c).edit().putBoolean("univest_enabled", v).apply(); }

    static String getExecutionMode(Context c) { return p(c).getString("execution_mode", MODE_PAPER); }
    static boolean isPaperMode(Context c) { return !MODE_LIVE.equals(getExecutionMode(c)); }
    static boolean isLiveMode(Context c) { return MODE_LIVE.equals(getExecutionMode(c)); }
    static void setExecutionMode(Context c, String mode) {
        String normalized = MODE_LIVE.equals(mode) ? MODE_LIVE : MODE_PAPER;
        p(c).edit().putString("execution_mode", normalized)
                .putBoolean("univest_enabled", false)
                .putBoolean("research_autotrade_enabled", false).apply();
    }

    static boolean isAveragingEnabled(Context c) { return p(c).getBoolean("downward_averaging_enabled", true); }
    static void setAveragingEnabled(Context c, boolean v) { p(c).edit().putBoolean("downward_averaging_enabled", v).apply(); }
    static int getAveragingLevels(Context c) { return Math.max(1, Math.min(3, p(c).getInt("downward_averaging_levels", 3))); }
    static void setAveragingLevels(Context c, int v) { p(c).edit().putInt("downward_averaging_levels", Math.max(1, Math.min(3, v))).apply(); }
    static double getAveragingStepPct(Context c) { return 2.0; }

    static final int UNIVEST_BUDGET_MAX = 100000;
    static final int UNIVEST_BUDGET_STEP = 1000;

    static int normalizeUnivestBudget(int v) {
        int clamped = Math.max(0, Math.min(UNIVEST_BUDGET_MAX, v));
        int rounded = ((clamped + (UNIVEST_BUDGET_STEP / 2)) / UNIVEST_BUDGET_STEP) * UNIVEST_BUDGET_STEP;
        return Math.max(0, Math.min(UNIVEST_BUDGET_MAX, rounded));
    }

    static int getUnivestBudget(Context c) {
        return normalizeUnivestBudget(p(c).getInt("univest_budget", 20000));
    }
    static void setUnivestBudget(Context c, int v) {
        p(c).edit().putInt("univest_budget", normalizeUnivestBudget(v)).apply();
    }

    static int getUnivestAddBudget(Context c) {
        return normalizeUnivestBudget(p(c).getInt("univest_add_budget", 5000));
    }
    static void setUnivestAddBudget(Context c, int v) {
        p(c).edit().putInt("univest_add_budget", normalizeUnivestBudget(v)).apply();
    }

    // Re-entry and controlled downward averaging deliberately share one user-configurable budget.
    static int getReentryBudget(Context c) { return getUnivestAddBudget(c); }
    static int getAveragingBudget(Context c) { return getUnivestAddBudget(c); }

    static long getNotificationListenerHeartbeat(Context c) { return p(c).getLong("notification_listener_heartbeat", 0L); }
    static String getNotificationListenerState(Context c) { return p(c).getString("notification_listener_state", "NOT_CONNECTED"); }
    static void setNotificationListenerHeartbeat(Context c, String state) {
        p(c).edit().putLong("notification_listener_heartbeat", System.currentTimeMillis())
                .putString("notification_listener_state", state == null ? "" : state).apply();
    }

    static String getUnivestStatus(Context c) { return p(c).getString("univest_status", "Univest automation is OFF."); }
    static long getUnivestStatusTime(Context c) { return p(c).getLong("univest_status_time", 0L); }
    static void setUnivestStatus(Context c, String v) {
        p(c).edit().putString("univest_status", v == null ? "" : v).putLong("univest_status_time", System.currentTimeMillis()).apply();
    }

    static int getIntradayBudget(Context c) { return p(c).getInt("intraday_budget", 100000); }
    static void setIntradayBudget(Context c, int v) {
        int clamped = Math.max(10000, Math.min(100000, ((v + 9999) / 10000) * 10000));
        p(c).edit().putInt("intraday_budget", clamped).apply();
    }

    // Broker credentials are encrypted with an Android-Keystore-backed key. Legacy plaintext values
    // are migrated lazily and deleted after the encrypted write succeeds.
    static String getApiKey(Context c) { return secureGet(c, "api_key"); }
    static void setApiKey(Context c, String v) { securePut(c, "api_key", v); }
    static String getTotpSecret(Context c) { return secureGet(c, "totp_secret"); }
    static void setTotpSecret(Context c, String v) { securePut(c, "totp_secret", v); }
    static String getExpectedStaticIp(Context c) { return p(c).getString("expected_static_ip", ""); }
    static void setExpectedStaticIp(Context c, String v) { p(c).edit().putString("expected_static_ip", clean(v)).apply(); }

    static String getAccessToken(Context c) { return secureGet(c, "access_token"); }
    static void setAccessToken(Context c, String v) { securePut(c, "access_token", v); }
    static void clearAccessToken(Context c) { SecurePrefs.remove(c, "access_token"); p(c).edit().remove("access_token").apply(); }

    static String getLastDetectedIp(Context c) { return p(c).getString("last_detected_ip", ""); }
    static boolean isStaticIpMatch(Context c) { return p(c).getBoolean("static_ip_match", false); }
    static long getStaticIpCheckTime(Context c) { return p(c).getLong("static_ip_check_time", 0L); }
    static void setStaticIpCheck(Context c, String detectedIp, boolean match) {
        p(c).edit().putString("last_detected_ip", clean(detectedIp)).putBoolean("static_ip_match", match)
                .putLong("static_ip_check_time", System.currentTimeMillis()).apply();
    }

    static boolean wasAuthTestSuccessful(Context c) { return p(c).getBoolean("auth_test_ok", false); }
    static long getAuthTestTime(Context c) { return p(c).getLong("auth_test_time", 0L); }
    static String getAuthTestMessage(Context c) { return p(c).getString("auth_test_message", "Not tested yet."); }
    static void setAuthTest(Context c, boolean ok, String message) {
        p(c).edit().putBoolean("auth_test_ok", ok).putLong("auth_test_time", System.currentTimeMillis())
                .putString("auth_test_message", message == null ? "" : message).apply();
    }

    static long getAuthCooldownUntil(Context c) { return p(c).getLong("auth_cooldown_until", 0L); }
    static void setAuthCooldownUntil(Context c, long until) { p(c).edit().putLong("auth_cooldown_until", until).apply(); }
    static void clearAuthCooldown(Context c) { p(c).edit().remove("auth_cooldown_until").apply(); }

    static void invalidateConnectionReadiness(Context c) {
        p(c).edit().remove("access_token").putBoolean("auth_test_ok", false).putBoolean("static_ip_match", false)
                .putBoolean("armed", false).putBoolean("univest_enabled", false)
                .putBoolean("research_autotrade_enabled", false).apply();
    }

    static boolean isAuthTestFresh(Context c) {
        if (!wasAuthTestSuccessful(c) || getAccessToken(c).isEmpty()) return false;
        long testedAt = getAuthTestTime(c);
        if (testedAt <= 0L) return false;
        return testedAt >= currentGrowwTokenWindowStart(System.currentTimeMillis());
    }

    static boolean isReadyForBuy(Context c) {
        long windowStart = currentGrowwTokenWindowStart(System.currentTimeMillis());
        boolean ipFresh = isStaticIpMatch(c) && getStaticIpCheckTime(c) >= windowStart;
        return isAuthTestFresh(c) && ipFresh && !getExpectedStaticIp(c).isEmpty();
    }

    private static long currentGrowwTokenWindowStart(long now) {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"));
        cal.setTimeInMillis(now); cal.set(Calendar.HOUR_OF_DAY, 6); cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0);
        if (now < cal.getTimeInMillis()) cal.add(Calendar.DAY_OF_MONTH, -1);
        return cal.getTimeInMillis();
    }

    static int getManualBudget(Context c) { return p(c).getInt("manual_budget", 50000); }
    static void setManualBudget(Context c, int v) {
        int clamped = Math.max(0, Math.min(100000, (v / 10000) * 10000));
        p(c).edit().putInt("manual_budget", clamped).apply();
    }
    static String getManualStatus(Context c) { return p(c).getString("manual_status", "No manual LONG/SHORT order submitted yet."); }
    static long getManualStatusTime(Context c) { return p(c).getLong("manual_status_time", 0L); }
    static void setManualStatus(Context c, String v) {
        p(c).edit().putString("manual_status", v == null ? "" : v).putLong("manual_status_time", System.currentTimeMillis()).apply();
    }
    static String getLastStatus(Context c) { return p(c).getString("last_status", "No order submitted yet."); }
    static void setLastStatus(Context c, String v) {
        p(c).edit().putString("last_status", v == null ? "" : v).putLong("last_status_time", System.currentTimeMillis()).apply();
    }
    static long getLastStatusTime(Context c) { return p(c).getLong("last_status_time", 0L); }

    static synchronized boolean claimFingerprint(Context c, String fingerprint) {
        SharedPreferences prefs = p(c); long now = System.currentTimeMillis();
        long prior = prefs.getLong("fp_" + fingerprint, 0L);
        if (prior > 0L && now - prior < 24L * 60L * 60L * 1000L) return false;
        prefs.edit().putLong("fp_" + fingerprint, now).apply(); return true;
    }

    static synchronized boolean claimRecent(Context c, String key, long windowMs) {
        SharedPreferences prefs = p(c); long now = System.currentTimeMillis();
        String safe = safeKey(key); long prior = prefs.getLong("recent_" + safe, 0L);
        if (prior > 0L && now - prior < Math.max(1000L, windowMs)) return false;
        prefs.edit().putLong("recent_" + safe, now).apply(); return true;
    }

    // Exact duplicate Android notifications often arrive twice within milliseconds. Suppress execution noise only;
    // the first copy is still archived.
    static synchronized boolean claimNotificationFingerprint(Context c, String fingerprint) {
        return claimRecent(c, "notif_" + fingerprint, 30000L);
    }

    // Signal idempotency is scoped to the IST trading date and execution mode. PAPER never blocks LIVE.
    static synchronized boolean claimDailySignal(Context c, String mode, String fingerprint) {
        String key = "daily_" + istDayKey(System.currentTimeMillis()) + "_" + clean(mode) + "_" + safeKey(fingerprint);
        SharedPreferences prefs = p(c);
        if (prefs.getBoolean(key, false)) return false;
        prefs.edit().putBoolean(key, true).apply();
        return true;
    }

    static String istDayKey(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("Asia/Kolkata")); return f.format(new Date(ms));
    }


    // v2.3 Research Lab is deliberately isolated from official Univest execution state.
    static boolean isResearchAutoTradeEnabled(Context c) { return p(c).getBoolean("research_autotrade_enabled", false); }
    static void setResearchAutoTradeEnabled(Context c, boolean v) { p(c).edit().putBoolean("research_autotrade_enabled", v).apply(); }
    static int getResearchBudget(Context c) { return Math.max(1000, Math.min(20000, p(c).getInt("research_budget", 5000))); }
    static void setResearchBudget(Context c, int v) { p(c).edit().putInt("research_budget", Math.max(1000, Math.min(20000, v))).apply(); }
    static int getResearchMaxPositions(Context c) { return Math.max(1, Math.min(5, p(c).getInt("research_max_positions", 2))); }
    static void setResearchMaxPositions(Context c, int v) { p(c).edit().putInt("research_max_positions", Math.max(1, Math.min(5, v))).apply(); }
    static String getResearchStatus(Context c) { return p(c).getString("research_status", "Research Lab has not run yet."); }
    static long getResearchStatusTime(Context c) { return p(c).getLong("research_status_time", 0L); }
    static void setResearchStatus(Context c, String v) {
        p(c).edit().putString("research_status", v == null ? "" : v)
                .putLong("research_status_time", System.currentTimeMillis()).apply();
    }
    static int getResearchScanCursor(Context c) { return Math.max(0, p(c).getInt("research_scan_cursor", 0)); }
    static void setResearchScanCursor(Context c, int v) { p(c).edit().putInt("research_scan_cursor", Math.max(0, v)).apply(); }
    static long getResearchLastNightlyRun(Context c) { return p(c).getLong("research_last_nightly", 0L); }
    static void setResearchLastNightlyRun(Context c, long v) { p(c).edit().putLong("research_last_nightly", v).apply(); }

    static String getResearchActionSymbol(Context c) { return p(c).getString("research_action_symbol", ""); }
    static String getResearchActionType(Context c) { return p(c).getString("research_action_type", ""); }
    static void setResearchAction(Context c, String symbol, String type) {
        p(c).edit().putString("research_action_symbol", clean(symbol))
                .putString("research_action_type", clean(type)).apply();
    }
    static String getResearchAccuracyText(Context c) {
        return p(c).getString("research_accuracy_text", "No closed Research trades yet.");
    }
    static void setResearchAccuracyText(Context c, String v) {
        p(c).edit().putString("research_accuracy_text", v == null ? "" : v).apply();
    }

    static String getResearchOrchestratorStage(Context c) { return p(c).getString("research_orch_stage", "NOT_STARTED"); }
    static String getResearchOrchestratorStatus(Context c) { return p(c).getString("research_orch_status", "Research orchestration has not started."); }
    static long getResearchOrchestratorUpdatedAt(Context c) { return p(c).getLong("research_orch_updated_at", 0L); }
    static void setResearchOrchestrator(Context c, String stage, String status) {
        p(c).edit().putString("research_orch_stage", clean(stage))
                .putString("research_orch_status", status == null ? "" : status)
                .putLong("research_orch_updated_at", System.currentTimeMillis()).apply();
    }
    static String getResearchEodScanKey(Context c) { return p(c).getString("research_eod_scan_key", ""); }
    static void setResearchEodScanKey(Context c, String v) { p(c).edit().putString("research_eod_scan_key", clean(v)).apply(); }
    static String getResearchPreopenFreezeKey(Context c) { return p(c).getString("research_preopen_freeze_key", ""); }
    static void setResearchPreopenFreezeKey(Context c, String v) { p(c).edit().putString("research_preopen_freeze_key", clean(v)).apply(); }
    static String getResearchReplayKey(Context c) { return p(c).getString("research_replay_key", ""); }
    static void setResearchReplayKey(Context c, String v) { p(c).edit().putString("research_replay_key", clean(v)).apply(); }
    static String getResearchForecastTargetKey(Context c) { return p(c).getString("research_forecast_target_key", ""); }
    static void setResearchForecastTargetKey(Context c, String v) { p(c).edit().putString("research_forecast_target_key", clean(v)).apply(); }

    static int getResearchCapitalLimit(Context c) {
        int v = p(c).getInt("research_capital_limit", 100000);
        return Math.max(10000, Math.min(500000, v));
    }
    static void setResearchCapitalLimit(Context c, int v) {
        int clamped = Math.max(10000, Math.min(500000, (v / 10000) * 10000));
        p(c).edit().putInt("research_capital_limit", clamped)
                .putBoolean("research_autotrade_enabled", false).apply();
    }

    static String getResearchFailureSummary(Context c) {
        return p(c).getString("research_failure_summary", "No repeated Research failure clusters yet.");
    }
    static void setResearchFailureSummary(Context c, String v) {
        p(c).edit().putString("research_failure_summary", v == null ? "" : v).apply();
    }

    static String getResearchScheduleMethod(Context c) { return p(c).getString("research_schedule_method", "NOT_SCHEDULED"); }
    static long getResearchNextScheduledAt(Context c) { return p(c).getLong("research_next_scheduled_at", 0L); }
    static String getResearchScheduleError(Context c) { return p(c).getString("research_schedule_error", ""); }
    static long getResearchScheduleUpdatedAt(Context c) { return p(c).getLong("research_schedule_updated_at", 0L); }
    static void setResearchScheduleState(Context c, String method, long nextAt, String error) {
        p(c).edit()
                .putString("research_schedule_method", method == null ? "" : method)
                .putLong("research_next_scheduled_at", Math.max(0L, nextAt))
                .putString("research_schedule_error", error == null ? "" : error)
                .putLong("research_schedule_updated_at", System.currentTimeMillis())
                .apply();
    }


    static String getHistoryBackupUri(Context c) { return p(c).getString("history_backup_uri", ""); }
    static void setHistoryBackupUri(Context c, String v) { p(c).edit().putString("history_backup_uri", clean(v)).apply(); }
    static long getHistoryBackupTime(Context c) { return p(c).getLong("history_backup_time", 0L); }
    static String getHistoryBackupStatus(Context c) { return p(c).getString("history_backup_status", ""); }
    static void setHistoryBackupState(Context c, long when, String status) {
        p(c).edit().putLong("history_backup_time", Math.max(0L, when))
                .putString("history_backup_status", status == null ? "" : status).apply();
    }

    static boolean isPreMarketReady(Context c) { return p(c).getBoolean("premarket_ready", false); }
    static long getPreMarketReadinessTime(Context c) { return p(c).getLong("premarket_ready_time", 0L); }
    static String getPreMarketReadinessPhase(Context c) { return p(c).getString("premarket_ready_phase", ""); }
    static String getPreMarketReadinessStatus(Context c) { return p(c).getString("premarket_ready_status", "Pre-market validation has not run."); }
    static void setPreMarketReadiness(Context c, boolean ready, String phase, String status) {
        p(c).edit().putBoolean("premarket_ready", ready)
                .putLong("premarket_ready_time", System.currentTimeMillis())
                .putString("premarket_ready_phase", clean(phase))
                .putString("premarket_ready_status", status == null ? "" : status).apply();
    }

    static String getUnivestStrategyStudy(Context c) {
        return p(c).getString("univest_strategy_study", "No off-market Univest strategy study has run yet.");
    }
    static void setUnivestStrategyStudy(Context c, String v) {
        p(c).edit().putString("univest_strategy_study", v == null ? "" : v).apply();
    }

    static boolean isUnivestV2Migrated(Context c) { return p(c).getBoolean("univest_v2_migrated", false); }
    static void setUnivestV2Migrated(Context c, boolean v) { p(c).edit().putBoolean("univest_v2_migrated", v).apply(); }

    private static String secureGet(Context c, String key) {
        String legacy = p(c).getString(key, "");
        String value = SecurePrefs.get(c, key, legacy);
        if (!legacy.isEmpty() && legacy.equals(value) && SecurePrefs.put(c, key, legacy)) {
            p(c).edit().remove(key).apply();
        }
        return value;
    }

    private static void securePut(Context c, String key, String value) {
        String v = clean(value);
        if (SecurePrefs.put(c, key, v)) {
            p(c).edit().remove(key).apply();
        } else {
            // Keystore failure must not silently destroy a user's broker configuration.
            p(c).edit().putString(key, v).apply();
        }
    }

    private static String safeKey(String key) { return key == null ? "" : key.replaceAll("[^A-Za-z0-9_.-]", "_"); }
    private static String clean(String v) { return v == null ? "" : v.trim(); }
}
