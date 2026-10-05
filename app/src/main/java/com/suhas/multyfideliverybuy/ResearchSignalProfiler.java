package com.suhas.multyfideliverybuy;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/**
 * Builds a causality-safe point-in-time profile around an official Univest signal.
 *
 * The notification timestamp is persisted immediately by ResearchStore. Network enrichment is
 * intentionally delayed a few seconds and performed off the execution thread so Research never
 * competes with the official BUY/SELL path for the critical first request. Historical windows are
 * cut at the last completed 1m/15m bar before the signal; daily data ends before the signal day.
 */
final class ResearchSignalProfiler {
    private static final long ENRICHMENT_DELAY_MS = 2500L;

    private ResearchSignalProfiler() {}

    static void captureAsync(Context context, UnivestParser.Signal signal, long signalAt) {
        if (signal == null) return;
        Context c = context.getApplicationContext();
        long at = signalAt > 0 ? signalAt : System.currentTimeMillis();
        String symbol = signal.symbol == null ? "" : signal.symbol.trim().toUpperCase(Locale.US);
        if (symbol.isEmpty()) return;

        JSONObject receipt = new JSONObject();
        try {
            receipt.put("symbol", symbol);
            receipt.put("signalType", signal.type.name());
            receipt.put("signalAt", at);
            receipt.put("capturedAt", System.currentTimeMillis());
            receipt.put("raw", signal.rawText == null ? "" : signal.rawText);
            receipt.put("executionBlocking", false);
        } catch (Exception ignored) {}
        ResearchEventStore.appendDecisionSnapshot(c, "POINT_IN_TIME_SIGNAL_RECEIVED", receipt);

        new Thread(() -> {
            try {
                Thread.sleep(ENRICHMENT_DELAY_MS);
                capture(c, signal, at);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                DiagnosticsStore.error(c, "POINT_IN_TIME_PROFILE_FAILED", symbol,
                        "Unable to build point-in-time Univest feature profile.", t);
            }
        }, "univest-point-in-time-profile").start();
    }

    static JSONObject capture(Context c, UnivestParser.Signal signal, long signalAt) {
        String symbol = signal.symbol == null ? "" : signal.symbol.trim().toUpperCase(Locale.US);
        JSONObject row = new JSONObject();
        try {
            InstrumentRepository.Instrument resolved =
                    InstrumentRepository.resolve(InstrumentRepository.load(c), symbol);
            if (resolved != null) symbol = resolved.symbol;

            row.put("schemaVersion", 1);
            row.put("symbol", symbol);
            row.put("signalType", signal.type.name());
            row.put("signalAt", signalAt);
            row.put("capturedAt", System.currentTimeMillis());
            row.put("captureLatencyMs", Math.max(0L, System.currentTimeMillis() - signalAt));
            row.put("source", signal.type == UnivestParser.Type.EXIT
                    ? "OFFICIAL_UNIVEST_EXIT" : "OFFICIAL_UNIVEST_ENTRY");
            row.put("featureBoundary", "POINT_IN_TIME_BEFORE_OR_AT_SIGNAL");
            row.put("raw", signal.rawText == null ? "" : signal.rawText);

            long oneMinuteCutoff = completedBarCutoff(signalAt, 60_000L);
            long fifteenMinuteCutoff = completedBarCutoff(signalAt, 15L * 60L * 1000L);
            long dailyCutoff = ResearchEngine.completedDailyCutoff(signalAt);

            List<GrowwClient.Candle> oneMinute = GrowwClient.getHistoricalCandles(
                    c, symbol, Math.max(0L, oneMinuteCutoff - 75L * 60L * 1000L),
                    oneMinuteCutoff, "1minute");
            List<GrowwClient.Candle> fifteen = GrowwClient.getHistoricalCandles(
                    c, symbol, Math.max(0L, fifteenMinuteCutoff - 14L * 24L * 60L * 60L * 1000L),
                    fifteenMinuteCutoff, "15minute");
            List<GrowwClient.Candle> daily = GrowwClient.getHistoricalCandles(
                    c, symbol, Math.max(0L, dailyCutoff - 180L * 24L * 60L * 60L * 1000L),
                    dailyCutoff, "1day");

            ResearchEventStore.appendRawCandles(c, symbol, oneMinute,
                    "1minute", "POINT_IN_TIME_" + signal.type.name(), signalAt);
            ResearchEventStore.appendRawCandles(c, symbol, fifteen,
                    "15minute", "POINT_IN_TIME_" + signal.type.name(), signalAt);
            ResearchEventStore.appendRawCandles(c, symbol, daily,
                    "1day", "POINT_IN_TIME_" + signal.type.name(), signalAt);

            ResearchMath.Features d = ResearchMath.fromCandles(daily);
            ResearchMath.Features m15 = ResearchMath.fromCandles(fifteen);
            ResearchMath.Features m1 = ResearchMath.fromCandles(oneMinute);
            copyDaily(row, d);
            row.put("minute1Points", oneMinute == null ? 0 : oneMinute.size());
            row.put("minute1Rsi14", m1.rsi14);
            row.put("minute1Return5Pct", m1.return5Pct);
            row.put("minute1RelativeVolume20", m1.relativeVolume20);
            row.put("minute15Points", fifteen == null ? 0 : fifteen.size());
            row.put("minute15Rsi14", m15.rsi14);
            row.put("minute15Return5Pct", m15.return5Pct);
            row.put("minute15Return20Pct", m15.return20Pct);
            row.put("minute15RelativeVolume20", m15.relativeVolume20);
            row.put("preSignal1mVwap", vwap(oneMinute));
            row.put("preSignal15mVwap", vwap(fifteen));
            row.put("dailyCandlePattern", candlePattern(daily));
            row.put("intraday15mCandlePattern", candlePattern(fifteen));

            JSONObject cached = cachedPreSignalContext(c, symbol, signalAt);
            if (cached != null) {
                copyIfPresent(cached, row, "positiveCatalysts");
                copyIfPresent(cached, row, "negativeCatalysts");
                copyIfPresent(cached, row, "newsSignal");
                copyIfPresent(cached, row, "marketRegimeStatus");
                copyIfPresent(cached, row, "marketRegimeText");
                copyIfPresent(cached, row, "sectorContextStatus");
                copyIfPresent(cached, row, "sectorRelativePct");
                copyIfPresent(cached, row, "fundamentalsStatus");
                copyIfPresent(cached, row, "fundamentalsPositive");
                row.put("cachedContextAt", cached.optLong("scannedAt", 0L));
                row.put("cachedContextCausal", cached.optLong("scannedAt", 0L) > 0
                        && cached.optLong("scannedAt", 0L) <= signalAt);
            } else {
                row.put("fundamentalsStatus", "UNKNOWN_NOT_CONNECTED");
                row.put("sectorContextStatus", "NOT_CONNECTED");
            }

            ResearchMath.StrategyScores sc = ResearchMath.score(
                    d,
                    Math.max(0, row.optInt("positiveCatalysts", 0) - row.optInt("negativeCatalysts", 0)),
                    row.optDouble("sectorRelativePct", 0),
                    row.optBoolean("fundamentalsPositive", false));
            copyScores(row, sc);

            try {
                GrowwClient.QuoteSnapshot q = GrowwClient.getQuoteForAutomation(c, symbol);
                row.put("liveQuoteSuccess", q.success);
                row.put("liveLastPrice", q.lastPrice);
                row.put("liveBid", q.bidPrice);
                row.put("liveOffer", q.offerPrice);
                row.put("liveSpreadPct", q.spreadPct());
                row.put("liveBidQty", q.bidQuantity);
                row.put("liveOfferQty", q.offerQuantity);
                row.put("liveVolume", q.volume);
                row.put("upperCircuit", q.upperCircuit);
                row.put("lowerCircuit", q.lowerCircuit);
                row.put("quoteCapturedAt", System.currentTimeMillis());
                row.put("quoteLatencyFromSignalMs", Math.max(0L, System.currentTimeMillis() - signalAt));
            } catch (Throwable quoteError) {
                row.put("liveQuoteSuccess", false);
                row.put("quoteError", safe(quoteError));
            }

            int rank = ResearchPlaybookEngine.forecastRankBeforeSignal(c, symbol, signalAt);
            long lead = ResearchPlaybookEngine.forecastLeadMinutesBeforeSignal(c, symbol, signalAt);
            row.put("preUnivestForecastRank", rank);
            row.put("preUnivestTop10Hit", rank > 0 && rank <= 10);
            row.put("preUnivestTop5Hit", rank > 0 && rank <= 5);
            row.put("preUnivestTop3Hit", rank > 0 && rank <= 3);
            row.put("preUnivestForecastLeadMinutes", lead);

            ResearchDataQuality.annotate(row);
            ResearchStore.appendFeature(c, row);
            ResearchStore.appendSignalProfile(c, row);
            ResearchEventStore.appendDecisionSnapshot(c, "POINT_IN_TIME_SIGNAL_PROFILE", row);

            if (signal.type == UnivestParser.Type.ENTRY) {
                ResearchPlaybookEngine.captureMatchedControls(c, row);
                ResearchPlaybookEngine.rebuildRegistry(c);
            }
            HistoryBackupManager.scheduleAutoBackup(c);
            return row;
        } catch (Throwable t) {
            try {
                row.put("profileError", safe(t));
                row.put("capturedAt", System.currentTimeMillis());
                ResearchStore.appendSignalProfile(c, row);
                ResearchEventStore.appendDecisionSnapshot(c, "POINT_IN_TIME_SIGNAL_PROFILE_ERROR", row);
            } catch (Throwable ignored) {}
            DiagnosticsStore.error(c, "POINT_IN_TIME_PROFILE_FAILED", symbol,
                    "Point-in-time Research profile failed.", t);
            return row;
        }
    }

    static long completedBarCutoff(long signalAt, long intervalMs) {
        if (signalAt <= 0 || intervalMs <= 0) return 0;
        long currentOpen = (signalAt / intervalMs) * intervalMs;
        return Math.max(0L, currentOpen - 1L);
    }

    private static void copyDaily(JSONObject row, ResearchMath.Features f) throws Exception {
        row.put("close", f.close);
        row.put("sma20", f.sma20);
        row.put("sma50", f.sma50);
        row.put("rsi14", f.rsi14);
        row.put("atr14", f.atr14);
        row.put("relativeVolume20", f.relativeVolume20);
        row.put("return5Pct", f.return5Pct);
        row.put("return20Pct", f.return20Pct);
        row.put("return60Pct", f.return60Pct);
        row.put("prior20High", f.prior20High);
        row.put("volatility20Pct", f.volatility20Pct);
        row.put("dataPoints", f.dataPoints);
    }

    private static void copyScores(JSONObject row, ResearchMath.StrategyScores s) throws Exception {
        row.put("volumeBreakoutScore", s.volumeBreakout);
        row.put("trendPullbackScore", s.trendPullback);
        row.put("momentumScore", s.momentum);
        row.put("qualityReratingScore", s.qualityRerating);
        row.put("catalystSectorScore", s.catalystSector);
        row.put("bestStrategy", s.bestStrategy);
        row.put("bestScore", s.bestScore);
        row.put("consensus", s.consensus);
        row.put("compositeSignature", ResearchPlaybookEngine.signature(
                ResearchPlaybookEngine.componentVector(row)));
    }

    private static JSONObject cachedPreSignalContext(Context c, String symbol, long signalAt) {
        JSONArray pool = ResearchStore.candidatePool(c);
        for (int i = 0; i < pool.length(); i++) {
            JSONObject p = pool.optJSONObject(i);
            if (p == null || !symbol.equalsIgnoreCase(p.optString("symbol"))) continue;
            long scannedAt = p.optLong("scannedAt", 0L);
            if (scannedAt > 0 && scannedAt <= signalAt) return p;
        }
        JSONArray predictions = ResearchStore.predictions(c);
        for (int i = 0; i < predictions.length(); i++) {
            JSONObject p = predictions.optJSONObject(i);
            if (p == null || !symbol.equalsIgnoreCase(p.optString("symbol"))) continue;
            long scannedAt = p.optLong("scannedAt", 0L);
            if (scannedAt > 0 && scannedAt <= signalAt) return p;
        }
        return null;
    }

    private static void copyIfPresent(JSONObject from, JSONObject to, String key) throws Exception {
        if (from.has(key) && !from.isNull(key)) to.put(key, from.get(key));
    }

    private static double vwap(List<GrowwClient.Candle> candles) {
        if (candles == null || candles.isEmpty()) return 0;
        double pv = 0, vol = 0;
        for (GrowwClient.Candle x : candles) {
            if (x == null || x.volume <= 0 || x.close <= 0) continue;
            double typical = x.high > 0 && x.low > 0
                    ? (x.high + x.low + x.close) / 3.0 : x.close;
            pv += typical * x.volume;
            vol += x.volume;
        }
        return vol > 0 ? pv / vol : 0;
    }

    private static String candlePattern(List<GrowwClient.Candle> candles) {
        if (candles == null || candles.isEmpty()) return "UNAVAILABLE";
        GrowwClient.Candle x = candles.get(candles.size() - 1);
        double range = Math.max(0.000001, x.high - x.low);
        double body = Math.abs(x.close - x.open);
        double upper = x.high - Math.max(x.open, x.close);
        double lower = Math.min(x.open, x.close) - x.low;
        StringBuilder b = new StringBuilder();
        if (body / range <= 0.12) add(b, "DOJI");
        if (body / range >= 0.75 && upper / range <= 0.12 && lower / range <= 0.12)
            add(b, x.close >= x.open ? "BULLISH_MARUBOZU" : "BEARISH_MARUBOZU");
        if (lower >= body * 2.0 && upper <= Math.max(body, range * 0.20))
            add(b, "HAMMER_LIKE");
        if (upper >= body * 2.0 && lower <= Math.max(body, range * 0.20))
            add(b, "SHOOTING_STAR_LIKE");

        if (candles.size() >= 2) {
            GrowwClient.Candle p = candles.get(candles.size() - 2);
            boolean bullEngulf = x.close > x.open && p.close < p.open
                    && x.open <= p.close && x.close >= p.open;
            boolean bearEngulf = x.close < x.open && p.close > p.open
                    && x.open >= p.close && x.close <= p.open;
            if (bullEngulf) add(b, "BULLISH_ENGULFING");
            if (bearEngulf) add(b, "BEARISH_ENGULFING");
            if (x.high < p.high && x.low > p.low) add(b, "INSIDE_BAR");
            if (x.high > p.high && x.low < p.low) add(b, "OUTSIDE_BAR");
        }
        return b.length() == 0 ? "NO_MAJOR_PATTERN" : b.toString();
    }

    private static void add(StringBuilder b, String s) {
        if (b.length() > 0) b.append("|");
        b.append(s);
    }

    private static String safe(Throwable t) {
        if (t == null) return "unknown";
        String m = t.getMessage();
        return m == null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m;
    }
}
