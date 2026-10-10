package com.suhas.multyfideliverybuy;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Tiered intraday Research reranker. The full eligible NSE universe is still scanned off-market;
 * this live layer periodically reranks the strongest EOD pool so recommendations can emerge during
 * the session without forcing five trades or changing official Univest execution.
 */
final class ResearchIntradayScanner {
    private static final String PREF = "research_intraday_scanner";
    private static final int LIVE_POOL_LIMIT = 30;
    private static final int FINAL_LIMIT = 10;
    private static final long MIN_REFRESH_MS = 8L * 60L * 1000L;

    private ResearchIntradayScanner() {}

    static synchronized JSONArray refresh(Context context, long now) {
        Context c = context.getApplicationContext();
        JSONArray existing = ResearchStore.predictions(c);
        if (!NseTradingCalendar.isRegularMarketOpen(now)) return existing;
        SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        long last = p.getLong("last_at", 0L);
        if (last > 0 && now - last < MIN_REFRESH_MS) return existing;
        p.edit().putLong("last_at", now).apply();

        JSONArray pool = ResearchStore.candidatePool(c);
        if (pool.length() == 0) return existing;
        List<JSONObject> base = new ArrayList<>();
        for (int i = 0; i < pool.length() && i < LIVE_POOL_LIMIT; i++) {
            JSONObject x = pool.optJSONObject(i);
            if (x == null) continue;
            try { base.add(new JSONObject(x.toString())); } catch (Exception ignored) {}
        }
        if (base.isEmpty()) return existing;

        ExecutorService exec = Executors.newFixedThreadPool(4);
        List<Future<JSONObject>> futures = new ArrayList<>();
        for (JSONObject x : base) futures.add(exec.submit(() -> liveZoneScore(c, x, now)));
        exec.shutdown();
        List<JSONObject> scored = new ArrayList<>();
        for (Future<JSONObject> f : futures) {
            try { JSONObject x = f.get(); if (x != null) scored.add(x); } catch (Throwable ignored) {}
        }
        scored.sort((a,b) -> Double.compare(b.optDouble("intradayPreMinuteScore", 0), a.optDouble("intradayPreMinuteScore", 0)));

        JSONArray top = new JSONArray();
        for (int i = 0; i < scored.size() && i < FINAL_LIMIT; i++) top.put(scored.get(i));
        ResearchEventStore.captureTopCandidates(c, top, now, FINAL_LIMIT);

        List<JSONObject> refined = new ArrayList<>();
        for (int i = 0; i < top.length(); i++) {
            JSONObject x = top.optJSONObject(i); if (x == null) continue;
            refineMinuteScore(c, x, now);
            refined.add(x);
        }
        refined.sort((a,b) -> Double.compare(b.optDouble("intradayActionScore", 0), a.optDouble("intradayActionScore", 0)));
        JSONArray out = new JSONArray();
        for (int i = 0; i < refined.size() && i < FINAL_LIMIT; i++) {
            JSONObject x = refined.get(i);
            try {
                x.put("intradayRank", i + 1);
                x.put("forecastSessionKey", NseTradingCalendar.dayKey(now));
                x.put("freezeType", "INTRADAY_REFRESH");
                x.put("similarity", (int)Math.round(x.optDouble("intradayActionScore", x.optDouble("similarity", 0))));
            } catch (Exception ignored) {}
            out.put(x);
        }
        ResearchStore.savePredictions(c, out);
        ResearchStore.appendForecastSnapshot(c, out, now, "INTRADAY_REFRESH", NseTradingCalendar.dayKey(now));
        try {
            JSONObject event = new JSONObject();
            event.put("scannedAt", now);
            event.put("eodPoolConsidered", base.size());
            event.put("liveRanked", out.length());
            event.put("actionableQuota", 5);
            event.put("quotaIsMaximumNotMinimum", true);
            ResearchEventStore.appendDecisionSnapshot(c, "INTRADAY_RESEARCH_RERANK", event);
        } catch (Exception ignored) {}
        p.edit().putString("status", "Live rerank " + out.length() + " candidates from top " + base.size()
                + " EOD pool • " + AppPrefs.istDayKey(now)).apply();
        return out;
    }

    static String statusText(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString("status", "Waiting for the first regular-session intraday rerank.");
    }

    private static JSONObject liveZoneScore(Context c, JSONObject x, long now) {
        String symbol = x.optString("symbol", "");
        if (symbol.isEmpty()) return null;
        try {
            double ltp = GrowwClient.getLtpForAutomation(c, symbol);
            if (!(ltp > 0)) return null;
            double low = x.optDouble("buyLow", 0), high = x.optDouble("buyHigh", 0), chase = x.optDouble("chaseLimit", 0);
            double adj = 0; String zone = "OUTSIDE";
            if (low > 0 && high > 0 && ltp >= low && ltp <= high) { adj = 8; zone = "BUY_ZONE"; }
            else if (high > 0 && chase > 0 && ltp > high && ltp <= chase) { adj = 4; zone = "CHASE_OK"; }
            else if (low > 0 && ltp < low && ltp >= low * 0.98) { adj = 2; zone = "NEAR_RETEST"; }
            else if (chase > 0 && ltp > chase) { adj = -18; zone = "DO_NOT_CHASE"; }
            else adj = -3;
            ResearchPlaybookEngine.applyToCandidate(c, x);
            StockStrategyMemory.applyToCandidate(c, x);
            double base = x.optDouble("ensembleScore", x.optDouble("similarity", 0));
            x.put("intradayLtp", ltp);
            x.put("intradayZoneState", zone);
            x.put("intradayZoneAdjustment", adj);
            x.put("intradayPreMinuteScore", clamp(base + adj));
            x.put("intradayScannedAt", now);
            return x;
        } catch (Throwable ignored) { return null; }
    }

    private static void refineMinuteScore(Context c, JSONObject x, long now) {
        try {
            double score = x.optDouble("intradayPreMinuteScore", x.optDouble("ensembleScore", 0));
            double ret = x.optDouble("minuteReturn5Pct", 0);
            double rsi = x.optDouble("minuteRsi14", 0);
            double rv = x.optDouble("minuteRelativeVolume20", 0);
            double vwap = x.optDouble("minuteVwap", 0);
            double price = x.optDouble("intradayLtp", x.optDouble("lastMinuteClose", 0));
            if (ret > 0 && ret <= 2.5) score += 3; else if (ret < -1.0) score -= 6;
            if (rsi >= 50 && rsi <= 78) score += 2; else if (rsi > 84) score -= 5;
            if (rv >= 1.2) score += 3;
            if (vwap > 0 && price >= vwap) score += 3; else if (vwap > 0 && price < vwap * 0.985) score -= 5;
            if (x.optString("marketRegimeStatus", "").contains("RISK_OFF") && ret <= 0) score -= 5;
            double target = UnivestBenchmark.targetUpsidePct(c);
            x.put("benchmarkTargetUpsidePct", target);
            x.put("benchmarkMaxSessions", UnivestBenchmark.PRIMARY_MAX_SESSIONS);
            x.put("intradayActionScore", clamp(score));
            x.put("intradayScannedAt", now);
            ResearchDataQuality.annotate(x);
        } catch (Exception ignored) {}
    }

    private static double clamp(double x) { return Math.max(0, Math.min(100, Math.round(x * 10.0) / 10.0)); }
}
