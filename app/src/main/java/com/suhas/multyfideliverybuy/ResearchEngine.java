package com.suhas.multyfideliverybuy;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

final class ResearchEngine {
    private static final long DAY = TimeUnit.DAYS.toMillis(1);
    private static final int SCAN_THREADS = 4;
    private static final int FINAL_LIMIT = 10;
    static final String STRATEGY_VERSION = "R2.8-1";
    private static final Object SCAN_RATE_LOCK = new Object();
    private static long lastScanRequestAt = 0L;
    private static final long SCAN_REQUEST_SPACING_MS = 150L;

    private ResearchEngine() {}

    static boolean isOffMarketNowIst() {
        return !NseTradingCalendar.isRegularMarketOpen(System.currentTimeMillis());
    }

    static void runNightly(Context c) {
        long now = System.currentTimeMillis();
        if (!NseTradingCalendar.isTradingDay(now)) {
            AppPrefs.setResearchStatus(c, "NSE closed • " + NseTradingCalendar.describe(now)
                    + " • no new candle forecast generated; latest frozen forecast retained.");
            return;
        }
        if (!isOffMarketNowIst()) {
            AppPrefs.setResearchStatus(c, "Research is locked during regular NSE market hours.");
            return;
        }
        try {
            ResearchDiagnosticsImporter.importOfficialSignals(c);
            InstrumentRepository.refreshIfStale(c);
            List<InstrumentRepository.Instrument> all = InstrumentRepository.load(c);
            learn(c, all);

            JSONObject strategies = strategies(c);
            ResearchStore.saveStrategies(c, strategies);
            ResearchPlaybookEngine.rebuildRegistry(c);

            AppPrefs.setResearchStatus(c, "Scanning entire eligible NSE CASH universe…");
            JSONArray predictions = scanEntireNse(c, all);
            long frozenAt = System.currentTimeMillis();
            String targetSession = NseTradingCalendar.nextTradingDayKey(frozenAt);
            for (int i = 0; i < predictions.length(); i++) {
                JSONObject p = predictions.optJSONObject(i);
                if (p == null) continue;
                try {
                    p.put("forecastSessionKey", targetSession);
                    p.put("freezeType", "EOD_FROZEN");
                    ResearchDataQuality.annotate(p);
                } catch (Exception ignored) {}
            }
            ResearchStore.savePredictions(c, predictions);
            ResearchStore.appendForecastSnapshot(c, predictions, frozenAt, "EOD_FROZEN", targetSession);
            AppPrefs.setResearchForecastTargetKey(c, targetSession);
            JSONObject freeze = new JSONObject();
            try {
                freeze.put("frozenAt", frozenAt);
                freeze.put("targetSessionKey", targetSession);
                freeze.put("candidateCount", predictions.length());
                freeze.put("strategyVersion", STRATEGY_VERSION);
            } catch (Exception ignored) {}
            ResearchEventStore.appendDecisionSnapshot(c, "EOD_FORECAST_FREEZE", freeze);

            ResearchTradeEngine.replayAndScore(c);
            ResearchMonitorScheduler.ensureScheduled(c);
            AppPrefs.setResearchLastNightlyRun(c, frozenAt);
            AppPrefs.setResearchStatus(c, "Full NSE research complete • " + predictions.length()
                    + " frozen next-session candidates • " + all.size() + " instruments loaded.");
            DiagnosticsStore.runtime(c, "RESEARCH_COMPLETE", "", AppPrefs.getResearchStatus(c));
        } catch (Throwable t) {
            AppPrefs.setResearchStatus(c, "Research error: "
                    + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
            DiagnosticsStore.error(c, "RESEARCH_FAILED", "", "Research Lab failed.", t);
        }
    }

    private static void learn(Context c, List<InstrumentRepository.Instrument> instruments) {
        Set<String> done = new HashSet<>();
        for (JSONObject j : ResearchStore.featureSnapshots(c)) {
            done.add(j.optString("source") + "|" + j.optString("symbol") + "|" + j.optLong("signalAt"));
        }
        Map<String, InstrumentRepository.Instrument> map = new HashMap<>();
        for (InstrumentRepository.Instrument i : instruments) map.put(i.symbol.toUpperCase(Locale.US), i);

        List<JSONObject> signals = ResearchStore.signals(c);
        int n = 0;
        for (int idx = signals.size() - 1; idx >= 0 && n < 50; idx--) {
            JSONObject sig = signals.get(idx);
            String type = sig.optString("type");
            if (!"ENTRY".equals(type) && !"EXIT".equals(type)) continue;
            String symbol = sig.optString("symbol", "").toUpperCase(Locale.US);
            long at = sig.optLong("signalAt", 0L);
            String source = "ENTRY".equals(type) ? "OFFICIAL_UNIVEST_ENTRY" : "OFFICIAL_UNIVEST_EXIT";
            String key = source + "|" + symbol + "|" + at;
            if (symbol.isEmpty() || at <= 0 || done.contains(key)) continue;
            try {
                long asOfCutoff = completedDailyCutoff(at);
                List<GrowwClient.Candle> candles = GrowwClient.getHistoricalCandles(
                        c, symbol, at - 140 * DAY, asOfCutoff, "1day");
                ResearchMath.Features ft = ResearchMath.fromCandles(candles);
                if (!(ft.close > 0)) continue;
                ResearchMath.StrategyScores sc = ResearchMath.score(ft, 0, 0, false);
                JSONObject j = feature(symbol, at, ft, sc);
                InstrumentRepository.Instrument ins = map.get(symbol);
                j.put("companyName", ins == null ? symbol : ins.name);
                j.put("source", source);
                j.put("featureBoundary", "AS_OF_BEFORE_SIGNAL");
                j.put("asOfCutoffMillis", asOfCutoff);
                j.put("reasons", reasons(ft));
                j.put("counterSignals", counter(ft));
                j.put("fundamentalsStatus", "UNKNOWN_NOT_CONNECTED");
                try {
                    List<GrowwClient.Candle> minute = GrowwClient.getHistoricalCandles(
                            c, symbol, Math.max(0L, at - 20L * 60L * 1000L),
                            at + 8L * 60L * 60L * 1000L, "1minute");
                    ResearchEventMath.Profile ep = ResearchEventMath.profile(minute, at);
                    j.put("eventMinutePoints", ep.points);
                    j.put("eventPrice", ep.eventPrice);
                    j.put("asOfPre15ReturnPct", ep.pre15ReturnPct);
                    j.put("asOfPre15Vwap", ep.pre15Vwap);
                    j.put("outcomeAnchoredVwap", ep.anchoredVwap);
                    j.put("outcomePostMfe15Pct", ep.postMfe15Pct);
                    j.put("outcomePostMae15Pct", ep.postMae15Pct);
                    j.put("outcomePostMfe30Pct", ep.postMfe30Pct);
                    j.put("outcomePostMae30Pct", ep.postMae30Pct);
                    j.put("outcomePostMfeSessionPct", ep.postMfeSessionPct);
                    j.put("outcomePostMaeSessionPct", ep.postMaeSessionPct);
                    j.put("outcomeVolumeAcceleration", ep.volumeAcceleration);
                    j.put("outcomeTimeToPeakMinutes", ep.timeToPeakMinutes);
                    // Compatibility fields are retained for existing archive rendering only. Learning uses AS_OF fields.
                    j.put("pre15ReturnPct", ep.pre15ReturnPct);
                    j.put("pre15Vwap", ep.pre15Vwap);
                    j.put("anchoredVwap", ep.anchoredVwap);
                    j.put("postMfe15Pct", ep.postMfe15Pct);
                    j.put("postMae15Pct", ep.postMae15Pct);
                    j.put("postMfeSessionPct", ep.postMfeSessionPct);
                    ResearchEventStore.appendMinuteCandles(c, symbol, minute, source, at);
                } catch (Throwable intradayError) {
                    j.put("eventMinuteStatus", "UNAVAILABLE: " + (intradayError.getMessage() == null
                            ? intradayError.getClass().getSimpleName() : intradayError.getMessage()));
                }
                ResearchStore.appendFeature(c, j);
                done.add(key);
                n++;
            } catch (Throwable ignored) {}
        }
    }

    private static JSONObject strategies(Context c) {
        String[] names = {"VOLUME_BREAKOUT", "TREND_PULLBACK", "MOMENTUM_CONTINUATION",
                "QUALITY_RERATING", "CATALYST_SECTOR"};
        Map<String, Integer> count = new LinkedHashMap<>();
        Map<String, Double> sum = new LinkedHashMap<>();
        for (String n : names) { count.put(n, 0); sum.put(n, 0.0); }

        for (JSONObject j : ResearchStore.featureSnapshots(c)) {
            if (!"OFFICIAL_UNIVEST_ENTRY".equals(j.optString("source"))) continue;
            String n = j.optString("bestStrategy", "");
            if (!count.containsKey(n)) continue;
            count.put(n, count.get(n) + 1);
            sum.put(n, sum.get(n) + j.optInt("bestScore", 0));
        }

        JSONArray a = new JSONArray();
        String champion = "";
        double best = -1;
        for (String n : names) {
            int e = count.get(n);
            double avg = e == 0 ? 0 : sum.get(n) / e;
            double rank = e * avg;
            if (rank > best) { best = rank; champion = n; }
            JSONObject j = new JSONObject();
            try {
                j.put("name", n);
                j.put("evidence", e);
                j.put("avgMatch", avg);
                j.put("status", e >= 20 ? "CHALLENGER" : e >= 8 ? "DEVELOPING" : "EXPERIMENTAL");
                j.put("strategyVersion", STRATEGY_VERSION);
                a.put(j);
            } catch (Exception ignored) {}
        }
        if (count.containsKey(champion) && count.get(champion) >= 30) {
            for (int i = 0; i < a.length(); i++) {
                JSONObject j = a.optJSONObject(i);
                if (j != null && champion.equals(j.optString("name"))) {
                    if (j.optDouble("avgMatch", 0) >= 75.0) {
                        try { j.put("status", "CHAMPION"); } catch (Exception ignored) {}
                    }
                }
            }
        }
        JSONObject out = new JSONObject();
        try {
            out.put("updatedAt", System.currentTimeMillis());
            out.put("champion", champion);
            out.put("strategies", a);
        } catch (Exception ignored) {}
        return out;
    }

    private static JSONArray scanEntireNse(Context c, List<InstrumentRepository.Instrument> all) {
        List<InstrumentRepository.Instrument> eligible = new ArrayList<>();
        for (InstrumentRepository.Instrument i : all) if (i.buyAllowed) eligible.add(i);
        eligible.sort(Comparator.comparing(x -> x.symbol));
        if (eligible.isEmpty()) return new JSONArray();

        final long now = System.currentTimeMillis();
        final JSONObject marketContext = ResearchMarketContext.snapshot(c, now);
        ResearchEventStore.appendDecisionSnapshot(c, "MARKET_REGIME_SNAPSHOT", marketContext);
        ExecutorService pool = Executors.newFixedThreadPool(SCAN_THREADS);
        List<Future<JSONObject>> futures = new ArrayList<>();
        for (InstrumentRepository.Instrument ins : eligible) {
            futures.add(pool.submit(() -> scoreInstrument(c, ins, now, marketContext)));
        }
        pool.shutdown();

        ArrayList<JSONObject> candidates = new ArrayList<>();
        int processed = 0;
        for (Future<JSONObject> future : futures) {
            processed++;
            try {
                JSONObject j = future.get();
                if (j != null) candidates.add(j);
            } catch (Throwable ignored) {}
            if (processed % 100 == 0 || processed == eligible.size()) {
                AppPrefs.setResearchStatus(c, "Full NSE scan • " + processed + "/" + eligible.size()
                        + " • qualifying " + candidates.size());
            }
        }

        candidates.sort((a, b) -> Integer.compare(b.optInt("similarity"), a.optInt("similarity")));
        AppPrefs.setResearchStatus(c, "Full NSE scan complete • deep-analyzing top shortlist with 15-minute context…");
        ResearchEventStore.captureDeepShortlist(c, candidates, now, 50);
        int deepCount = Math.min(50, candidates.size());
        for (int i = 0; i < deepCount; i++) {
            JSONObject j = candidates.get(i);
            try {
                ResearchNewsClient.Summary n = ResearchNewsClient.fetch(
                        j.optString("companyName"), j.optString("symbol"));
                j.put("nationalNews", n.nationalCount);
                j.put("internationalNews", n.internationalCount);
                j.put("positiveCatalysts", n.positiveCatalysts);
                j.put("negativeCatalysts", n.negativeCatalysts);
                j.put("newsSignal", n.catalystLabel);
                int technical = j.optInt("similarity", 0);
                int catalystAdjustment = Math.min(6, n.positiveCatalysts * 2)
                        - Math.min(8, n.negativeCatalysts * 3);
                j.put("technicalSimilarity", technical);
                j.put("catalystAdjustment", catalystAdjustment);
                j.put("similarity", Math.max(0, Math.min(100, technical + catalystAdjustment)));
            } catch (Throwable ignored) {}
        }
        // News can modestly re-order the technically qualified shortlist, but cannot rescue an
        // instrument that failed the full-NSE technical/liquidity data scan.
        // Composite playbooks are non-exclusive: one stock may receive several independent votes.
        for (JSONObject candidate : candidates) ResearchPlaybookEngine.applyToCandidate(c, candidate);
        candidates.sort((a, b) -> Double.compare(
                b.optDouble("ensembleScore", b.optInt("similarity")),
                a.optDouble("ensembleScore", a.optInt("similarity"))));

        JSONArray candidatePool = new JSONArray();
        int poolLimit = Math.min(100, candidates.size());
        String poolTarget = NseTradingCalendar.nextTradingDayKey(now);
        for (int i = 0; i < poolLimit; i++) {
            JSONObject p = candidates.get(i);
            try {
                p.put("candidatePoolRank", i + 1);
                p.put("forecastSessionKey", poolTarget);
            } catch (Exception ignored) {}
            candidatePool.put(p);
        }
        ResearchStore.saveCandidatePool(c, candidatePool);

        if (candidates.size() > FINAL_LIMIT)
            candidates = new ArrayList<>(candidates.subList(0, FINAL_LIMIT));

        JSONArray intel = new JSONArray();
        for (JSONObject j : candidates) {
            try {
                JSONObject x = new JSONObject();
                x.put("symbol", j.optString("symbol"));
                x.put("national", j.optInt("nationalNews"));
                x.put("international", j.optInt("internationalNews"));
                x.put("signal", j.optString("newsSignal", "No material headline signal"));
                x.put("at", now);
                intel.put(x);
            } catch (Exception ignored) {}
        }
        ResearchStore.saveIntelligence(c, intel);

        JSONArray out = new JSONArray();
        for (JSONObject j : candidates) {
            ResearchDataQuality.annotate(j);
            out.put(j);
        }
        return out;
    }

    private static void throttleScanRequest() throws InterruptedException {
        synchronized (SCAN_RATE_LOCK) {
            long now = System.currentTimeMillis();
            long wait = SCAN_REQUEST_SPACING_MS - (now - lastScanRequestAt);
            if (wait > 0) Thread.sleep(wait);
            lastScanRequestAt = System.currentTimeMillis();
        }
    }

    private static JSONObject scoreInstrument(Context c, InstrumentRepository.Instrument ins, long now, JSONObject marketContext) {
        try {
            throttleScanRequest();
            List<GrowwClient.Candle> candles = GrowwClient.getHistoricalCandles(
                    c, ins.symbol, now - 140 * DAY, now, "1day");
            ResearchMath.Features f = ResearchMath.fromCandles(candles);
            if (!(f.close > 0) || f.dataPoints < 20) return null;
            ResearchMath.StrategyScores sc = ResearchMath.score(f, 0, 0, false);
            if (sc.bestScore < 60) return null;
            double[] z = ResearchMath.learnedZones(f, 0);
            JSONObject j = feature(ins.symbol, now, f, sc);
            j.put("companyName", ins.name);
            j.put("strategy", sc.bestStrategy);
            j.put("similarity", sc.bestScore);
            j.put("consensus", sc.consensus);
            j.put("strategyVersion", STRATEGY_VERSION);
            j.put("buyLow", z[0]);
            j.put("buyHigh", z[1]);
            j.put("chaseLimit", z[2]);
            j.put("sellLow", z[3]);
            j.put("sellHigh", z[4]);
            j.put("reasons", reasons(f));
            j.put("counterSignals", counter(f));
            j.put("scannedAt", now);
            j.put("fundamentalsStatus", "UNKNOWN_NOT_CONNECTED");
            j.put("marketRegimeStatus", ResearchMarketContext.label(marketContext));
            j.put("marketRegimeText", ResearchMarketContext.text(marketContext));
            j.put("sectorContextStatus", "NOT_CONNECTED");
            j.put("strategyVersion", STRATEGY_VERSION);
            ResearchDataQuality.annotate(j);
            return j;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static long completedDailyCutoff(long signalAt) {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"));
        cal.setTimeInMillis(signalAt);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    private static JSONObject feature(String symbol, long at, ResearchMath.Features f,
                                      ResearchMath.StrategyScores sc) {
        JSONObject j = new JSONObject();
        try {
            j.put("symbol", symbol);
            j.put("signalAt", at);
            j.put("close", f.close);
            j.put("sma20", f.sma20);
            j.put("sma50", f.sma50);
            j.put("rsi14", f.rsi14);
            j.put("atr14", f.atr14);
            j.put("relativeVolume20", f.relativeVolume20);
            j.put("return5Pct", f.return5Pct);
            j.put("return20Pct", f.return20Pct);
            j.put("return60Pct", f.return60Pct);
            j.put("prior20High", f.prior20High);
            j.put("volatility20Pct", f.volatility20Pct);
            j.put("dataPoints", f.dataPoints);
            j.put("volumeBreakoutScore", sc.volumeBreakout);
            j.put("trendPullbackScore", sc.trendPullback);
            j.put("momentumScore", sc.momentum);
            j.put("qualityReratingScore", sc.qualityRerating);
            j.put("catalystSectorScore", sc.catalystSector);
            j.put("bestStrategy", sc.bestStrategy);
            j.put("bestScore", sc.bestScore);
            j.put("consensus", sc.consensus);
        } catch (Exception ignored) {}
        return j;
    }

    private static String reasons(ResearchMath.Features f) {
        ArrayList<String> r = new ArrayList<>();
        if (f.relativeVolume20 >= 1.15) r.add(String.format(Locale.US, "Volume %.2fx average", f.relativeVolume20));
        if (f.prior20High > 0 && f.close >= f.prior20High * .985) r.add("Near/above 20-session resistance");
        if (f.sma20 > f.sma50 && f.sma50 > 0) r.add("20-day trend above 50-day");
        if (f.rsi14 >= 50 && f.rsi14 <= 75) r.add(String.format(Locale.US, "RSI %.1f constructive", f.rsi14));
        if (f.return20Pct > 0) r.add(String.format(Locale.US, "20-session momentum +%.1f%%", f.return20Pct));
        if (r.isEmpty()) r.add("Technical similarity comes from the combined strategy score");
        return join(r);
    }

    private static String counter(ResearchMath.Features f) {
        ArrayList<String> r = new ArrayList<>();
        if (f.rsi14 > 76) r.add("RSI extended");
        if (f.relativeVolume20 < .75) r.add("weak volume");
        if (f.return5Pct < -2) r.add("negative 5-session momentum");
        if (f.sma50 > 0 && f.close < f.sma50) r.add("below 50-day trend");
        if (r.isEmpty()) r.add("No major technical counter-signal");
        return join(r);
    }

    private static String join(List<String> a) {
        StringBuilder b = new StringBuilder();
        for (String x : a) { if (b.length() > 0) b.append(" | "); b.append(x); }
        return b.toString();
    }

    static String predictionsText(Context c, int limit) {
        JSONArray a = ResearchStore.predictions(c);
        if (a.length() == 0) return "No frozen full-NSE forecast completed yet.";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < a.length() && i < limit; i++) {
            JSONObject j = a.optJSONObject(i);
            if (j == null) continue;
            if (b.length() > 0) b.append("\n\n");
            b.append(i + 1).append(". ").append(j.optString("symbol"))
                    .append(" - ").append(j.optInt("similarity")).append("/100 - ")
                    .append(j.optString("strategy"))
                    .append("\nConsensus ").append(j.optInt("consensus")).append("/5 • FROZEN FORECAST")
                    .append(" • data ").append(j.optInt("dataConfidence")).append("%");
            if (!j.optString("bestPlaybook", "").isEmpty()) {
                b.append("\nPlaybook ").append(j.optString("bestPlaybook"))
                        .append(" • match ").append(String.format(Locale.US, "%.0f", j.optDouble("playbookScore", 0)))
                        .append("/100 • votes ").append(j.optInt("playbookVotes", 0))
                        .append(" • ensemble ").append(String.format(Locale.US, "%.0f",
                                j.optDouble("ensembleScore", j.optInt("similarity")))).append("/100");
            }
            b.append(String.format(Locale.US,
                            "\nBuy %.2f-%.2f • chase %.2f\nReference sell zone %.2f-%.2f",
                            j.optDouble("buyLow"), j.optDouble("buyHigh"), j.optDouble("chaseLimit"),
                            j.optDouble("sellLow"), j.optDouble("sellHigh")))
                    .append("\nWhy: ").append(j.optString("reasons"))
                    .append("\nCounter: ").append(j.optString("counterSignals"))
                    .append("\nMissing: ").append(j.optString("missingData", "not assessed"));
            if (!j.optString("newsSignal", "").isEmpty())
                b.append("\nNews: ").append(j.optString("newsSignal"));
            if (!j.optString("marketRegimeText", "").isEmpty())
                b.append("\nMarket: ").append(j.optString("marketRegimeText"));
        }
        return b.toString();
    }

    static String playbooksText(Context c) {
        return ResearchPlaybookEngine.summaryText(c);
    }

    static String forecastAccountabilityText(Context c) {
        return ResearchPlaybookEngine.accountabilityText(c);
    }

    static String strategiesText(Context c) {
        JSONObject o = ResearchStore.strategies(c);
        JSONArray a = o.optJSONArray("strategies");
        if (a == null || a.length() == 0) return "No strategy fingerprints learned yet.";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < a.length(); i++) {
            JSONObject j = a.optJSONObject(i);
            if (j == null) continue;
            if (b.length() > 0) b.append("\n\n");
            b.append(j.optString("name")).append(" - ").append(j.optString("status"))
                    .append(" • ").append(j.optString("strategyVersion", STRATEGY_VERSION))
                    .append("\nEvidence ").append(j.optInt("evidence"))
                    .append(" official entries • avg match ")
                    .append(String.format(Locale.US, "%.0f", j.optDouble("avgMatch"))).append("/100");
        }
        return b.toString();
    }

    static String intelligenceText(Context c) {
        JSONArray a = ResearchStore.intelligence(c);
        if (a.length() == 0) return "News intelligence will populate after the next full-NSE scan.";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < a.length() && i < 5; i++) {
            JSONObject j = a.optJSONObject(i);
            if (j == null) continue;
            if (b.length() > 0) b.append("\n\n");
            b.append(j.optString("symbol")).append(" - India ").append(j.optInt("national"))
                    .append(" - International ").append(j.optInt("international"))
                    .append("\n").append(j.optString("signal"));
        }
        return b.toString();
    }

    static String officialLifecycleText(Context c, int limit) {
        List<JSONObject> sig = new ArrayList<>(ResearchStore.signals(c));
        sig.sort(Comparator.comparingLong(x -> x.optLong("signalAt", 0)));
        Map<String, JSONObject> open = new HashMap<>();
        List<String> rows = new ArrayList<>();
        for (JSONObject s : sig) {
            String symbol = s.optString("symbol", "");
            if (symbol.isEmpty()) continue;
            if ("ENTRY".equals(s.optString("type"))) {
                open.put(symbol, s);
            } else if ("EXIT".equals(s.optString("type"))) {
                JSONObject e = open.get(symbol);
                if (e == null) continue;
                long ea = e.optLong("signalAt", 0), xa = s.optLong("signalAt", 0);
                String horizon = AppPrefs.istDayKey(ea).equals(AppPrefs.istDayKey(xa))
                        ? "SAME-DAY TACTICAL" : "MULTI-SESSION / EARLY EXIT";
                JSONObject ef = nearestFeature(c, symbol, ea, "OFFICIAL_UNIVEST_ENTRY");
                JSONObject xf = nearestFeature(c, symbol, xa, "OFFICIAL_UNIVEST_EXIT");
                String row = symbol + " • " + horizon
                        + "\nEntry fingerprint: " + (ef == null ? "pending replay" : ef.optString("reasons", ""))
                        + (ef == null ? "" : String.format(Locale.US,
                        "\nEntry event: pre15 %.1f%% • 15m MFE %.1f%% / MAE %.1f%% • session MFE %.1f%% • anchored VWAP ₹%.2f",
                        ef.optDouble("pre15ReturnPct"), ef.optDouble("postMfe15Pct"), ef.optDouble("postMae15Pct"),
                        ef.optDouble("postMfeSessionPct"), ef.optDouble("anchoredVwap")))
                        + "\nExit fingerprint: " + (xf == null ? "pending replay" : xf.optString("counterSignals", ""))
                        + (xf == null ? "" : String.format(Locale.US,
                        "\nExit event: pre15 %.1f%% • post-exit 15m MFE %.1f%% / MAE %.1f%%",
                        xf.optDouble("pre15ReturnPct"), xf.optDouble("postMfe15Pct"), xf.optDouble("postMae15Pct")));
                rows.add(row);
                open.remove(symbol);
            }
        }
        StringBuilder b = new StringBuilder();
        for (int i = rows.size() - 1; i >= 0 && b.length() < 5000 && rows.size() - i <= limit; i--) {
            if (b.length() > 0) b.append("\n\n");
            b.append(rows.get(i));
        }
        if (b.length() == 0)
            return "Completed official entry→exit campaigns will be classified here as same-day or multi-session.";
        return b.toString();
    }

    private static JSONObject nearestFeature(Context c, String symbol, long at, String source) {
        JSONObject best = null; long delta = Long.MAX_VALUE;
        for (JSONObject j : ResearchStore.featureSnapshots(c)) {
            if (!symbol.equalsIgnoreCase(j.optString("symbol")) || !source.equals(j.optString("source"))) continue;
            long d = Math.abs(j.optLong("signalAt", 0) - at);
            if (d < delta) { delta = d; best = j; }
        }
        return best;
    }
}
