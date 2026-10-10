package com.suhas.multyfideliverybuy;

import android.content.Context;

import org.json.JSONObject;

import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/** Standardized point-in-time schema for reverse-engineering official Univest entries. */
final class ResearchEntryFingerprint {
    static final int SCHEMA_VERSION = 2;
    private static final String[] PARAMETERS = {
            "dailyReturn1Pct", "return5Pct", "return20Pct", "return60Pct",
            "distanceSma20Pct", "distanceSma50Pct", "sma20Vs50Pct", "rsi14",
            "atrPct", "relativeVolume20", "distancePrior20HighPct", "volatility20Pct",
            "minute15Return1Pct", "minute15Return5Pct", "minute15Return20Pct", "minute15Rsi14",
            "minute15RelativeVolume20", "minute15VwapDistancePct", "intraday15mCandlePattern",
            "minute1Return1Pct", "minute1Return5Pct", "minute1Rsi14", "minute1RelativeVolume20",
            "minute1VwapDistancePct", "liveSpreadPct", "orderBookImbalancePct",
            "marketRegimeStatus", "marketReturn5Pct", "marketReturn20Pct", "sectorRelativePct",
            "revenueGrowthPct", "profitGrowthPct", "marginTrendPct", "roeOrRocePct",
            "debtEquity", "cashFlowQuality", "valuationVsSectorPct",
            "positiveCatalysts", "negativeCatalysts", "timeOfDayBucket"
    };

    private ResearchEntryFingerprint() {}

    static String[] parameterNames() { return PARAMETERS.clone(); }

    static void annotate(Context c, JSONObject row,
                         List<GrowwClient.Candle> daily,
                         List<GrowwClient.Candle> fifteen,
                         List<GrowwClient.Candle> oneMinute,
                         JSONObject marketContext) {
        if (row == null) return;
        try {
            double close = row.optDouble("close", 0);
            double sma20 = row.optDouble("sma20", 0);
            double sma50 = row.optDouble("sma50", 0);
            double atr = row.optDouble("atr14", 0);
            double priorHigh = row.optDouble("prior20High", 0);
            row.put("dailyReturn1Pct", returnPct(daily, 1));
            row.put("distanceSma20Pct", distancePct(close, sma20));
            row.put("distanceSma50Pct", distancePct(close, sma50));
            row.put("sma20Vs50Pct", distancePct(sma20, sma50));
            row.put("atrPct", close > 0 ? atr / close * 100.0 : 0);
            row.put("distancePrior20HighPct", priorHigh > 0 ? (close / priorHigh - 1.0) * 100.0 : 0);

            double v15 = vwap(fifteen);
            double c15 = lastClose(fifteen);
            row.put("minute15Return1Pct", returnPct(fifteen, 1));
            row.put("minute15VwapDistancePct", distancePct(c15, v15));
            double v1 = vwap(oneMinute);
            double c1 = lastClose(oneMinute);
            row.put("minute1Return1Pct", returnPct(oneMinute, 1));
            row.put("minute1VwapDistancePct", distancePct(c1, v1));

            if (marketContext != null && "AVAILABLE".equals(marketContext.optString("status"))) {
                row.put("marketRegimeStatus", ResearchMarketContext.label(marketContext));
                row.put("marketRegimeText", ResearchMarketContext.text(marketContext));
                row.put("marketReturn5Pct", marketContext.optDouble("return5Pct", 0));
                row.put("marketReturn20Pct", marketContext.optDouble("return20Pct", 0));
            } else {
                if (!row.has("marketRegimeStatus")) row.put("marketRegimeStatus", "NOT_CONNECTED");
                row.put("marketReturn5Pct", JSONObject.NULL);
                row.put("marketReturn20Pct", JSONObject.NULL);
            }

            ensureNullable(row, "sectorRelativePct");
            // Groww Trading API does not expose company financial statements. Keep these slots
            // explicitly missing instead of inventing values; a future point-in-time provider can fill them.
            ensureNullable(row, "revenueGrowthPct");
            ensureNullable(row, "profitGrowthPct");
            ensureNullable(row, "marginTrendPct");
            ensureNullable(row, "roeOrRocePct");
            ensureNullable(row, "debtEquity");
            ensureNullable(row, "cashFlowQuality");
            ensureNullable(row, "valuationVsSectorPct");

            long at = row.optLong("signalAt", System.currentTimeMillis());
            row.put("timeOfDayBucket", timeBucket(at));
            row.put("parameterSchemaVersion", SCHEMA_VERSION);
            row.put("parameterCount", PARAMETERS.length);
            applyCoverage(row);
        } catch (Exception ignored) {}
    }

    static void annotateQuote(JSONObject row, GrowwClient.QuoteSnapshot q) {
        if (row == null) return;
        try {
            if (q != null && q.success) {
                row.put("liveSpreadPct", q.spreadPct());
                double denom = Math.max(1.0, q.bidQuantity + (double)q.offerQuantity);
                row.put("orderBookImbalancePct", (q.bidQuantity - q.offerQuantity) / denom * 100.0);
            } else {
                row.put("liveSpreadPct", JSONObject.NULL);
                row.put("orderBookImbalancePct", JSONObject.NULL);
            }
            applyCoverage(row);
        } catch (Exception ignored) {}
    }

    static String coverageSummary(Context c) {
        List<JSONObject> rows = ResearchStore.signalProfiles(c, 100);
        int n = 0, total = 0, fundamentals = 0, sector = 0;
        for (int i = rows.size() - 1; i >= 0; i--) {
            JSONObject r = rows.get(i);
            if (r == null || !"ENTRY".equals(r.optString("signalType"))) continue;
            n++;
            total += r.optInt("parameterCoverageCount", 0);
            if (!"UNKNOWN_NOT_CONNECTED".equals(r.optString("fundamentalsStatus", "UNKNOWN_NOT_CONNECTED"))) fundamentals++;
            if (!"NOT_CONNECTED".equals(r.optString("sectorContextStatus", "NOT_CONNECTED"))) sector++;
            if (n >= 30) break;
        }
        if (n == 0) return "40-parameter point-in-time fingerprint is armed; waiting for the next official Univest ENTRY.";
        return String.format(Locale.US,
                "40-parameter entry fingerprint • recent average %.1f/40 available\nFundamentals connected on %d/%d • sector context connected on %d/%d\nMissing fields reduce confidence; they are never guessed or backfilled from future data.",
                total / (double)n, fundamentals, n, sector, n);
    }

    private static void applyCoverage(JSONObject row) throws Exception {
        int n = 0;
        for (String p : PARAMETERS) if (available(row, p)) n++;
        row.put("parameterCoverageCount", n);
        row.put("parameterCoveragePct", Math.round(1000.0 * n / PARAMETERS.length) / 10.0);
    }

    private static boolean available(JSONObject row, String key) {
        if (!row.has(key) || row.isNull(key)) return false;
        Object v = row.opt(key);
        if (v instanceof Number) return Double.isFinite(((Number)v).doubleValue());
        String s = String.valueOf(v).trim();
        return !s.isEmpty() && !"NOT_CONNECTED".equals(s) && !"UNKNOWN_NOT_CONNECTED".equals(s)
                && !"UNAVAILABLE".equals(s);
    }

    private static void ensureNullable(JSONObject row, String key) throws Exception {
        if (!row.has(key)) row.put(key, JSONObject.NULL);
    }

    private static double lastClose(List<GrowwClient.Candle> c) {
        return c == null || c.isEmpty() ? 0 : c.get(c.size() - 1).close;
    }

    private static double returnPct(List<GrowwClient.Candle> c, int bars) {
        if (c == null || c.size() < 2) return 0;
        int end = c.size() - 1;
        int start = Math.max(0, end - Math.max(1, bars));
        double a = c.get(start).close, b = c.get(end).close;
        return a > 0 ? (b / a - 1.0) * 100.0 : 0;
    }

    private static double vwap(List<GrowwClient.Candle> candles) {
        if (candles == null || candles.isEmpty()) return 0;
        double pv = 0, vol = 0;
        for (GrowwClient.Candle x : candles) {
            if (x == null || x.volume <= 0 || x.close <= 0) continue;
            double typical = (x.high + x.low + x.close) / 3.0;
            pv += typical * x.volume; vol += x.volume;
        }
        return vol > 0 ? pv / vol : 0;
    }

    private static double distancePct(double value, double reference) {
        return reference > 0 ? (value / reference - 1.0) * 100.0 : 0;
    }

    private static String timeBucket(long at) {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"));
        cal.setTimeInMillis(at);
        int m = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        if (m < 10 * 60 + 30) return "OPENING";
        if (m < 13 * 60 + 30) return "MIDDAY";
        return "AFTERNOON";
    }
}
