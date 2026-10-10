package com.suhas.multyfideliverybuy;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Observable benchmark built only from official Univest ENTRY/EXIT notifications and point-in-time
 * market prices captured by the Research lane. It never changes official execution behavior.
 */
final class UnivestBenchmark {
    static final int WINDOW_DAYS = 30;
    static final int PRIMARY_MAX_SESSIONS = 2;
    static final int MIN_READY_COMPLETED = 8;
    private static final double FALLBACK_TARGET_PCT = 0.50;

    private UnivestBenchmark() {}

    static JSONArray completedCampaigns(Context c) {
        List<JSONObject> profiles = new ArrayList<>(ResearchStore.signalProfiles(c, 5000));
        profiles.sort(Comparator.comparingLong(x -> x.optLong("signalAt", 0L)));
        Map<String, List<JSONObject>> open = new LinkedHashMap<>();
        JSONArray out = new JSONArray();

        for (JSONObject p : profiles) {
            if (p == null) continue;
            String symbol = p.optString("symbol", "").trim().toUpperCase(Locale.US);
            String type = p.optString("signalType", "");
            long at = p.optLong("signalAt", 0L);
            if (symbol.isEmpty() || at <= 0) continue;

            if ("ENTRY".equals(type)) {
                List<JSONObject> q = open.get(symbol);
                if (q == null) { q = new ArrayList<>(); open.put(symbol, q); }
                q.add(p);
            } else if ("EXIT".equals(type)) {
                List<JSONObject> q = open.remove(symbol);
                if (q == null || q.isEmpty()) continue;
                double exitPrice = signalPrice(p);
                for (JSONObject entry : q) {
                    long entryAt = entry.optLong("signalAt", 0L);
                    double entryPrice = signalPrice(entry);
                    if (entryAt <= 0 || exitPrice <= 0 || entryPrice <= 0 || at <= entryAt) continue;
                    JSONObject row = new JSONObject();
                    try {
                        row.put("campaignId", symbol + "|" + entryAt);
                        row.put("symbol", symbol);
                        row.put("entryAt", entryAt);
                        row.put("exitAt", at);
                        row.put("entryPrice", entryPrice);
                        row.put("exitPrice", exitPrice);
                        row.put("upsidePct", grossUpsidePct(entryPrice, exitPrice));
                        row.put("holdingHours", (at - entryAt) / 3_600_000.0);
                        row.put("holdingSessions", inclusiveTradingSessions(entryAt, at));
                        row.put("compositeSignature", entry.optString("compositeSignature", ""));
                        row.put("marketRegimeStatus", entry.optString("marketRegimeStatus", "NOT_CONNECTED"));
                        row.put("parameterCoverageCount", entry.optInt("parameterCoverageCount", 0));
                        row.put("parameterCoveragePct", entry.optDouble("parameterCoveragePct", 0));
                        row.put("entryProfile", new JSONObject(entry.toString()));
                        row.put("exitProfile", new JSONObject(p.toString()));
                        out.put(row);
                    } catch (Exception ignored) {}
                }
            }
        }
        return out;
    }

    static JSONObject snapshot(Context c) {
        return snapshot(c, System.currentTimeMillis(), WINDOW_DAYS);
    }

    static JSONObject snapshot(Context c, long now, int days) {
        JSONArray campaigns = completedCampaigns(c);
        long cutoff = now - TimeUnit.DAYS.toMillis(Math.max(1, days));
        List<Double> upside = new ArrayList<>();
        List<Double> hours = new ArrayList<>();
        List<Integer> sessions = new ArrayList<>();
        int same = 0, by2 = 0, by3 = 0;
        for (int i = 0; i < campaigns.length(); i++) {
            JSONObject x = campaigns.optJSONObject(i);
            if (x == null || x.optLong("exitAt", 0L) < cutoff) continue;
            double u = x.optDouble("upsidePct", Double.NaN);
            if (!Double.isFinite(u)) continue;
            int s = Math.max(1, x.optInt("holdingSessions", 1));
            upside.add(u);
            hours.add(x.optDouble("holdingHours", 0));
            sessions.add(s);
            if (s == 1) same++;
            if (s <= 2) by2++;
            if (s <= 3) by3++;
        }

        JSONObject out = new JSONObject();
        try {
            int n = upside.size();
            double avgUpside = avgD(upside);
            double avgHours = avgD(hours);
            double avgSessions = avgI(sessions);
            out.put("windowDays", Math.max(1, days));
            out.put("completed", n);
            out.put("ready", n >= MIN_READY_COMPLETED);
            out.put("averageUpsidePct", one(avgUpside));
            out.put("medianUpsidePct", one(medianD(upside)));
            out.put("averageHoldingHours", one(avgHours));
            out.put("averageHoldingSessions", two(avgSessions));
            out.put("medianHoldingSessions", two(medianI(sessions)));
            out.put("sameSessionPct", pct(same, n));
            out.put("byTwoSessionsPct", pct(by2, n));
            out.put("byThreeSessionsPct", pct(by3, n));
            out.put("targetUpsidePct", one(n >= MIN_READY_COMPLETED && avgUpside > 0
                    ? Math.max(FALLBACK_TARGET_PCT, avgUpside) : FALLBACK_TARGET_PCT));
            out.put("primaryMaxSessions", PRIMARY_MAX_SESSIONS);
            out.put("calculatedAt", now);
        } catch (Exception ignored) {}
        return out;
    }

    static JSONObject performanceForSignature(Context c, String signature) {
        JSONArray campaigns = completedCampaigns(c);
        List<Double> returns = new ArrayList<>();
        int by2 = 0;
        for (int i = 0; i < campaigns.length(); i++) {
            JSONObject x = campaigns.optJSONObject(i);
            if (x == null || !safe(signature).equals(x.optString("compositeSignature", ""))) continue;
            returns.add(x.optDouble("upsidePct", 0));
            if (x.optInt("holdingSessions", 99) <= PRIMARY_MAX_SESSIONS) by2++;
        }
        JSONObject out = new JSONObject();
        try {
            out.put("completed", returns.size());
            out.put("averageUpsidePct", one(avgD(returns)));
            out.put("medianUpsidePct", one(medianD(returns)));
            out.put("byTwoSessionsPct", pct(by2, returns.size()));
        } catch (Exception ignored) {}
        return out;
    }

    static double targetUpsidePct(Context c) {
        return snapshot(c).optDouble("targetUpsidePct", FALLBACK_TARGET_PCT);
    }

    static String summaryText(Context c) {
        JSONObject b = snapshot(c);
        int n = b.optInt("completed", 0);
        if (n == 0) {
            return "No completed official Univest ENTRY→EXIT lifecycle has a usable signal-time price yet. "
                    + "Benchmarking starts automatically as official recommendations close.";
        }
        return String.format(Locale.US,
                "%d completed • avg upside %+.1f%% • median %+.1f%%\nAvg hold %.2f trading sessions (%.1f h) • same-session %.0f%% • by Session 2 %.0f%%\nPrimary Research target: ≥%+.1f%% within ≤%d sessions%s",
                n,
                b.optDouble("averageUpsidePct"), b.optDouble("medianUpsidePct"),
                b.optDouble("averageHoldingSessions"), b.optDouble("averageHoldingHours"),
                b.optDouble("sameSessionPct"), b.optDouble("byTwoSessionsPct"),
                b.optDouble("targetUpsidePct"), b.optInt("primaryMaxSessions", PRIMARY_MAX_SESSIONS),
                b.optBoolean("ready") ? "" : " • provisional until " + MIN_READY_COMPLETED + " completed campaigns");
    }

    static double grossUpsidePct(double entryPrice, double exitPrice) {
        if (!(entryPrice > 0) || !(exitPrice > 0)) return 0;
        return (exitPrice / entryPrice - 1.0) * 100.0;
    }

    static int inclusiveTradingSessions(long entryAt, long exitAt) {
        if (entryAt <= 0 || exitAt < entryAt) return 0;
        if (NseTradingCalendar.dayKey(entryAt).equals(NseTradingCalendar.dayKey(exitAt))) return 1;
        return 1 + NseTradingCalendar.tradingSessionsElapsed(entryAt, exitAt);
    }

    static String classifyBenchmarkOutcome(double realizedOrOpportunityPct, double targetPct, int holdingSessions) {
        if (holdingSessions <= 0) return "UNRESOLVED";
        if (realizedOrOpportunityPct >= targetPct && holdingSessions <= PRIMARY_MAX_SESSIONS)
            return "BENCHMARK_WIN";
        if (holdingSessions > PRIMARY_MAX_SESSIONS) return "HORIZON_MISS";
        if (realizedOrOpportunityPct > 0) return "PROFITABLE_MISS";
        return "FAIL";
    }

    private static double signalPrice(JSONObject p) {
        if (p == null) return 0;
        double v = p.optDouble("liveLastPrice", 0);
        if (v > 0) return v;
        v = p.optDouble("eventPrice", 0);
        if (v > 0) return v;
        return 0;
    }

    private static double avgD(List<Double> a) {
        if (a == null || a.isEmpty()) return 0;
        double s = 0; for (double x : a) s += x; return s / a.size();
    }
    private static double avgI(List<Integer> a) {
        if (a == null || a.isEmpty()) return 0;
        double s = 0; for (int x : a) s += x; return s / a.size();
    }
    private static double medianD(List<Double> a) {
        if (a == null || a.isEmpty()) return 0;
        List<Double> x = new ArrayList<>(a); Collections.sort(x); int n = x.size();
        return n % 2 == 1 ? x.get(n / 2) : (x.get(n / 2 - 1) + x.get(n / 2)) / 2.0;
    }
    private static double medianI(List<Integer> a) {
        if (a == null || a.isEmpty()) return 0;
        List<Integer> x = new ArrayList<>(a); Collections.sort(x); int n = x.size();
        return n % 2 == 1 ? x.get(n / 2) : (x.get(n / 2 - 1) + x.get(n / 2)) / 2.0;
    }
    private static double pct(int n, int d) { return d <= 0 ? 0 : one(100.0 * n / d); }
    private static double one(double x) { return Math.round(x * 10.0) / 10.0; }
    private static double two(double x) { return Math.round(x * 100.0) / 100.0; }
    private static String safe(String s) { return s == null ? "" : s; }
}
