package com.suhas.multyfideliverybuy;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;

final class ResearchEventStore {
    static final int SCHEMA_VERSION = 2;
    private static final Object LOCK = new Object();
    private static final Map<String, Set<String>> MINUTE_SEEN = new HashMap<>();
    private static final Map<String, Set<String>> RAW_SEEN = new HashMap<>();

    private ResearchEventStore() {}

    static void appendDecisionSnapshot(Context c, String kind, JSONObject payload) {
        try {
            JSONObject row = new JSONObject();
            row.put("schemaVersion", SCHEMA_VERSION);
            row.put("capturedAt", System.currentTimeMillis());
            row.put("kind", kind == null ? "" : kind);
            row.put("payload", payload == null ? new JSONObject() : new JSONObject(payload.toString()));
            append(c, "decision-snapshots.jsonl", row);
        } catch (Exception e) {
            DiagnosticsStore.error(c, "RESEARCH_DECISION_SNAPSHOT_FAILED", "",
                    "Unable to persist immutable Research decision snapshot.", e);
        }
    }

    static void capturePreEventWindow(Context c, String symbol, long eventAt, String eventType) {
        if (symbol == null || symbol.trim().isEmpty() || eventAt <= 0) return;
        try {
            long start = Math.max(0L, eventAt - 20L * 60L * 1000L);
            List<GrowwClient.Candle> candles = GrowwClient.getHistoricalCandles(
                    c, symbol, start, eventAt + 60_000L, "1minute");
            appendMinuteCandles(c, symbol, candles, "OFFICIAL_" + eventType, eventAt);
            JSONObject event = new JSONObject();
            event.put("symbol", symbol.toUpperCase(Locale.US));
            event.put("eventType", eventType);
            event.put("eventAt", eventAt);
            event.put("preWindowMinutes", 20);
            event.put("candlesCaptured", candles == null ? 0 : candles.size());
            appendDecisionSnapshot(c, "OFFICIAL_EVENT_WINDOW", event);
        } catch (Throwable t) {
            DiagnosticsStore.error(c, "RESEARCH_EVENT_WINDOW_FAILED", symbol,
                    "Unable to capture pre-event one-minute window.", t);
        }
    }

    static void captureDeepShortlist(Context c, java.util.List<JSONObject> candidates, long now, int limit) {
        if (candidates == null || candidates.isEmpty()) return;
        int n = Math.min(Math.max(1, limit), candidates.size());
        for (int i = 0; i < n; i++) {
            JSONObject p = candidates.get(i);
            if (p == null) continue;
            String symbol = p.optString("symbol", "");
            if (symbol.isEmpty()) continue;
            try {
                Thread.sleep(175L);
                List<GrowwClient.Candle> candles = GrowwClient.getHistoricalCandles(
                        c, symbol, now - 7L * 24L * 60L * 60L * 1000L, now, "15minute");
                if (candles != null && !candles.isEmpty()) {
                    ResearchMath.Features f = ResearchMath.fromCandles(candles);
                    p.put("intraday15mPoints", candles.size());
                    p.put("intraday15mRsi14", f.rsi14);
                    p.put("intraday15mReturn5Pct", f.return5Pct);
                    p.put("intraday15mRelativeVolume20", f.relativeVolume20);
                    appendMinuteCandles(c, symbol, candles, "EOD_DEEP_15M_SHORTLIST", now);
                }
            } catch (Throwable t) {
                try { p.put("intraday15mStatus", "UNAVAILABLE"); } catch (Exception ignored) {}
            }
        }
    }

    static void captureTopCandidates(Context c, JSONArray predictions, long now, int limit) {
        if (predictions == null || predictions.length() == 0) return;
        int n = Math.min(Math.max(1, limit), predictions.length());
        for (int i = 0; i < n; i++) {
            JSONObject p = predictions.optJSONObject(i);
            if (p == null) continue;
            String symbol = p.optString("symbol", "");
            if (symbol.isEmpty()) continue;
            try {
                List<GrowwClient.Candle> candles = GrowwClient.getHistoricalCandles(
                        c, symbol, now - 50L * 60L * 1000L, now, "1minute");
                appendMinuteCandles(c, symbol, candles, "LIVE_TOP_CANDIDATE", now);
                if (candles != null && !candles.isEmpty()) {
                    GrowwClient.Candle last = candles.get(candles.size() - 1);
                    ResearchMath.Features live = ResearchMath.fromCandles(candles);
                    p.put("lastMinuteCaptureAt", now);
                    p.put("lastMinuteClose", last.close);
                    p.put("lastMinuteVolume", last.volume);
                    p.put("minuteDataPoints", candles.size());
                    p.put("minuteRsi14", live.rsi14);
                    p.put("minuteReturn5Pct", live.return5Pct);
                    p.put("minuteRelativeVolume20", live.relativeVolume20);
                    p.put("minuteVwap", vwap(candles));
                    ResearchDataQuality.annotate(p);
                }
            } catch (Throwable t) {
                try {
                    p.put("minuteCaptureError", t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
                } catch (Exception ignored) {}
            }
        }
        ResearchStore.savePredictions(c, predictions);
    }

    static void appendRawCandles(Context c, String symbol, List<GrowwClient.Candle> candles,
                                 String timeframe, String source, long anchorAt) {
        if (candles == null || candles.isEmpty() || symbol == null || symbol.trim().isEmpty()) return;
        String day = NseTradingCalendar.dayKey(anchorAt > 0 ? anchorAt : System.currentTimeMillis());
        String fileName = "raw-candles-" + day + ".jsonl";
        synchronized (LOCK) {
            try {
                File dir = dir(c);
                if (!dir.exists()) dir.mkdirs();
                File file = new File(dir, fileName);
                Set<String> seen = rawSeenKeys(file, fileName);
                try (Writer w = new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8)) {
                    for (GrowwClient.Candle x : candles) {
                        String key = (timeframe == null ? "" : timeframe) + "|"
                                + symbol.toUpperCase(Locale.US) + "|" + x.epochSeconds + "|"
                                + (source == null ? "" : source);
                        if (!seen.add(key)) continue;
                        JSONObject row = new JSONObject();
                        row.put("schemaVersion", SCHEMA_VERSION);
                        row.put("symbol", symbol.toUpperCase(Locale.US));
                        row.put("timeframe", timeframe == null ? "" : timeframe);
                        row.put("source", source == null ? "" : source);
                        row.put("anchorAt", anchorAt);
                        row.put("epochSeconds", x.epochSeconds);
                        row.put("open", x.open);
                        row.put("high", x.high);
                        row.put("low", x.low);
                        row.put("close", x.close);
                        row.put("volume", x.volume);
                        w.write(row.toString());
                        w.write('\n');
                    }
                }
            } catch (Exception e) {
                DiagnosticsStore.error(c, "RESEARCH_RAW_CANDLE_STORE_FAILED", symbol,
                        "Unable to persist raw point-in-time candles.", e);
            }
        }
    }

    static void appendMinuteCandles(Context c, String symbol, List<GrowwClient.Candle> candles,
                                    String source, long anchorAt) {
        if (candles == null || candles.isEmpty()) return;
        String day = NseTradingCalendar.dayKey(anchorAt > 0 ? anchorAt : System.currentTimeMillis());
        synchronized (LOCK) {
            try {
                File dir = dir(c);
                if (!dir.exists()) dir.mkdirs();
                File file = new File(dir, "minute-" + day + ".jsonl");
                Set<String> seen = seenKeys(file, day);
                try (Writer w = new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8)) {
                    for (GrowwClient.Candle x : candles) {
                        String key = symbol.toUpperCase(Locale.US) + "|" + x.epochSeconds + "|" + (source == null ? "" : source);
                        if (!seen.add(key)) continue;
                        JSONObject row = new JSONObject();
                        row.put("schemaVersion", SCHEMA_VERSION);
                        row.put("symbol", symbol.toUpperCase(Locale.US));
                        row.put("source", source);
                        row.put("anchorAt", anchorAt);
                        row.put("epochSeconds", x.epochSeconds);
                        row.put("open", x.open);
                        row.put("high", x.high);
                        row.put("low", x.low);
                        row.put("close", x.close);
                        row.put("volume", x.volume);
                        w.write(row.toString());
                        w.write('\n');
                    }
                }
            } catch (Exception e) {
                DiagnosticsStore.error(c, "RESEARCH_MINUTE_STORE_FAILED", symbol,
                        "Unable to persist one-minute Research candles.", e);
            }
        }
    }

    private static double vwap(List<GrowwClient.Candle> candles) {
        double pv = 0.0, vol = 0.0;
        if (candles == null) return 0.0;
        for (GrowwClient.Candle x : candles) {
            if (x == null || x.volume <= 0 || x.close <= 0) continue;
            double typical = (x.high > 0 && x.low > 0) ? (x.high + x.low + x.close) / 3.0 : x.close;
            pv += typical * x.volume;
            vol += x.volume;
        }
        return vol > 0 ? pv / vol : 0.0;
    }

    private static Set<String> rawSeenKeys(File file, String cacheKey) {
        Set<String> cached = RAW_SEEN.get(cacheKey);
        if (cached != null) return cached;
        Set<String> out = new HashSet<>();
        if (file != null && file.exists()) {
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    try {
                        JSONObject j = new JSONObject(line);
                        out.add(j.optString("timeframe") + "|"
                                + j.optString("symbol").toUpperCase(Locale.US) + "|"
                                + j.optLong("epochSeconds") + "|" + j.optString("source"));
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
        }
        RAW_SEEN.put(cacheKey, out);
        return out;
    }

    private static Set<String> seenKeys(File file, String day) {
        Set<String> cached = MINUTE_SEEN.get(day);
        if (cached != null) return cached;
        Set<String> out = new HashSet<>();
        if (file != null && file.exists()) {
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    try {
                        JSONObject j = new JSONObject(line);
                        out.add(j.optString("symbol").toUpperCase(Locale.US) + "|"
                                + j.optLong("epochSeconds") + "|" + j.optString("source"));
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
        }
        MINUTE_SEEN.put(day, out);
        return out;
    }

    private static void append(Context c, String name, JSONObject row) throws Exception {
        synchronized (LOCK) {
            File dir = dir(c);
            if (!dir.exists()) dir.mkdirs();
            try (Writer w = new OutputStreamWriter(new FileOutputStream(new File(dir, name), true), StandardCharsets.UTF_8)) {
                w.write(row.toString());
                w.write('\n');
            }
        }
    }

    private static File dir(Context c) {
        return new File(new File(c.getFilesDir(), "research_lab"), "events");
    }
}
