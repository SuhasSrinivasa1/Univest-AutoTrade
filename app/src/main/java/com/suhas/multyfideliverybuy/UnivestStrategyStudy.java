package com.suhas.multyfideliverybuy;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Off-market descriptive study of observable Univest behaviour. This does not claim access to
 * Univest's proprietary model; it summarizes recurring patterns in the official notifications we
 * actually observed and keeps future-outcome fields separate from pre-signal evidence.
 */
final class UnivestStrategyStudy {
    private UnivestStrategyStudy() {}

    static String runNightly(Context c) {
        UnivestHistoryDb.ensureInitialized(c);
        JSONArray a = UnivestHistoryDb.officialTradingSignals(c);
        int entries = 0, reentries = 0, exits = 0, completed = 0;
        long totalHoldMs = 0L;
        Map<String, Long> open = new HashMap<>();
        Map<Integer, Integer> entryHourCounts = new HashMap<>();

        for (int i = 0; i < a.length(); i++) {
            JSONObject r = a.optJSONObject(i);
            if (r == null) continue;
            String type = r.optString("signalType", "");
            String symbol = r.optString("symbol", "").toUpperCase(Locale.US);
            long t = r.optLong("eventTime", 0L);
            if ("ENTRY".equals(type)) {
                entries++;
                if (!symbol.isEmpty()) open.put(symbol, t);
                java.util.Calendar cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Kolkata"));
                cal.setTimeInMillis(t);
                int hour = cal.get(java.util.Calendar.HOUR_OF_DAY);
                entryHourCounts.put(hour, entryHourCounts.getOrDefault(hour, 0) + 1);
            } else if ("REENTRY".equals(type)) {
                reentries++;
            } else if ("EXIT".equals(type)) {
                exits++;
                Long start = open.remove(symbol);
                if (start != null && t > start) {
                    completed++;
                    totalHoldMs += t - start;
                }
            }
        }

        int bestHour = -1, bestCount = 0;
        for (Map.Entry<Integer,Integer> e : entryHourCounts.entrySet()) {
            if (e.getValue() > bestCount) { bestHour = e.getKey(); bestCount = e.getValue(); }
        }
        double meanDays = completed > 0 ? totalHoldMs / 86_400_000.0 / completed : 0.0;

        StringBuilder s = new StringBuilder();
        s.append("Observable official evidence • ").append(entries).append(" entries • ")
                .append(reentries).append(" back-in-range calls • ").append(exits).append(" exits");
        if (completed > 0) s.append(" • ").append(completed).append(" paired entry→exit lifecycles • mean calendar hold ")
                .append(String.format(Locale.US, "%.1f", meanDays)).append(" days");
        if (bestHour >= 0) s.append(" • most common observed entry hour ")
                .append(String.format(Locale.US, "%02d:00–%02d:59 IST", bestHour, bestHour));
        s.append(". Nightly Research replay keeps pre-signal features separate from later MFE/MAE/exit outcomes.");

        String summary = s.toString();
        AppPrefs.setUnivestStrategyStudy(c, summary);
        DiagnosticsStore.runtime(c, "UNIVEST_NIGHTLY_STRATEGY_STUDY", "", summary);
        return summary;
    }
}
