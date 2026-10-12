package com.suhas.multyfideliverybuy;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Edge-trigger state for Research recommendations.
 * A candidate must remain READY across consecutive live scans before a call is emitted.
 * Once emitted, it cannot emit again until it first leaves READY and later re-enters.
 */
final class ResearchTriggerState {
    private static final String PREF = "research_trigger_state";
    private static final long CONFIRM_MS = 60_000L;
    private static final String IDLE = "IDLE";
    private static final String ARMED = "ARMED";
    private static final String TRIGGERED = "TRIGGERED";

    private ResearchTriggerState() {}

    static synchronized boolean shouldTrigger(Context context, String symbol, String sessionKey, long now) {
        Context c = context.getApplicationContext();
        String k = key(symbol);
        SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String priorSession = p.getString(k + "_session", "");
        String state = p.getString(k + "_state", IDLE);
        long since = p.getLong(k + "_since", 0L);
        if (!sessionKey.equals(priorSession)) {
            state = IDLE;
            since = 0L;
        }
        if (IDLE.equals(state)) {
            p.edit().putString(k + "_session", sessionKey).putString(k + "_state", ARMED)
                    .putLong(k + "_since", now).apply();
            return false;
        }
        if (ARMED.equals(state)) {
            if (since <= 0L) since = now;
            if (now - since < CONFIRM_MS) return false;
            p.edit().putString(k + "_session", sessionKey).putString(k + "_state", TRIGGERED)
                    .putLong(k + "_triggeredAt", now).apply();
            return true;
        }
        return false;
    }

    static synchronized void markNotReady(Context context, String symbol, String sessionKey) {
        Context c = context.getApplicationContext();
        String k = key(symbol);
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString(k + "_session", sessionKey)
                .putString(k + "_state", IDLE)
                .putLong(k + "_since", 0L)
                .apply();
    }

    private static String key(String symbol) {
        String s = symbol == null ? "" : symbol.trim().toUpperCase(java.util.Locale.US);
        return "s_" + Integer.toHexString(s.hashCode());
    }
}
