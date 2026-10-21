package com.suhas.multyfideliverybuy;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Forecast-only positive-pattern learner. 40 real point-in-time dimensions,
 * sourced from the immutable official Univest ENTRY fingerprint.
 *
 * Only official ENTRY -> EXIT campaigns with usable observed entry/exit prices
 * and at least +0.5% observed GROSS price movement can become examples.
 * SELL, uncompleted, loss and unverified campaigns are not training references.
 * No future/outcome data is used as a feature; it is used only as the label.
 *
 * Below MIN_MATURE_WINNERS, evidence is observational and MUST NOT rank/gate
 * candidates: two winners cannot identify a proprietary trading strategy.
 */
final class PositiveUnivestFortyModel {
    static final int PARAMETERS = 40;
    static final int MIN_MATURE_WINNERS = 8;
    static final int MIN_SHARED_FEATURES = 12;
    static final double MIN_OBSERVED_GROSS_GAIN_PCT = 0.50;
    static final String VERSION = "POSITIVE_UNIVEST_40_V1";
    static final String[] FEATURES = ResearchEntryFingerprint.parameterNames();

    // Fixed tolerances establish distances, not predicted win probability.
    private static final double[] SPANS = {
            2, 6, 14, 26, 8, 12, 8, 28, 4, 2, 12, 6,
            3, 7, 13, 28, 2, 6, 1,
            1.5, 4, 28, 2, 6, 1.5, 60,
            1, 5, 12, 7,
            25, 25, 14, 18, 2, 2, 28,
            4, 4, 1
    };
    private PositiveUnivestFortyModel() {}

    static final class Match {
        final double score;
        final int shared;
        final int winners;
        final String nearestSymbol;
        final boolean mature;

        Match(double score, int shared, int winners, String nearestSymbol, boolean mature) {
            this.score = score;
            this.shared = shared;
            this.winners = winners;
            this.nearestSymbol = nearestSymbol;
            this.mature = mature;
        }
    }

    static List<JSONObject> positiveEntryProfiles(JSONArray completedCampaigns) {
        List<JSONObject> winners = new ArrayList<>();
        if (completedCampaigns == null) return winners;
        Set<String> dedup = new HashSet<>();
        for (int i = 0; i < completedCampaigns.length(); i++) {
            JSONObject campaign = completedCampaigns.optJSONObject(i);
            if (campaign == null) continue;
            double grossGain = campaign.optDouble("upsidePct", Double.NaN);
            if (!Double.isFinite(grossGain) || grossGain < MIN_OBSERVED_GROSS_GAIN_PCT)
                continue;
            JSONObject entry = campaign.optJSONObject("entryProfile");
            if (entry == null || !"ENTRY".equals(entry.optString("signalType"))) continue;
            if (!"OFFICIAL_UNIVEST_ENTRY".equals(entry.optString("source"))) continue;
            long enteredAt = entry.optLong("signalAt", 0);
            long exitedAt = campaign.optLong("exitAt", 0);
            if (enteredAt <= 0 || exitedAt <= enteredAt) continue;
            // Distinguish independent campaigns from multiple snapshots.
            String symbol = entry.optString("symbol", "").trim().toUpperCase(Locale.US);
            String key = symbol + "|" + enteredAt;
            if (symbol.isEmpty() || !dedup.add(key)) continue;
            winners.add(entry);
        }
        return winners;
    }

    static List<JSONObject> currentPositiveEntries(Context c) {
        return positiveEntryProfiles(UnivestBenchmark.completedCampaigns(c));
    }

    static Match closest(JSONObject candidate, List<JSONObject> winners) {
        int n = winners == null ? 0 : winners.size();
        double best = 0;
        int sharedBest = 0;
        String symbol = "";
        if (candidate != null && winners != null) for (JSONObject winner : winners) {
            int shared = 0;
            double total = 0;
            for (int i = 0; i < FEATURES.length; i++) {
                Object left = readable(candidate, FEATURES[i]);
                Object right = readable(winner, FEATURES[i]);
                if (left == null || right == null) continue;
                double similarity;
                if (left instanceof Number && right instanceof Number) {
                    double a = ((Number)left).doubleValue();
                    double b = ((Number)right).doubleValue();
                    // Limited impact from outliers without hiding the mismatch.
                    similarity = Math.max(0, 100 - Math.min(100, 30 * Math.abs(a - b)
                            / Math.max(0.001, SPANS[i])));
                } else if (left instanceof String && right instanceof String) {
                    similarity = left.equals(right) ? 100 : 15;
                } else continue;
                total += similarity;
                shared++;
            }
            if (shared < MIN_SHARED_FEATURES) continue;
            // Sparse overlap cannot earn the same confidence as 40 observed fields.
            double coverage = shared / (double)FEATURES.length;
            double score = (total / shared) * (0.60 + 0.40 * coverage);
            if (sharedBest == 0 || score > best) {
                best = score;
                sharedBest = shared;
                symbol = winner.optString("symbol", "");
            }
        }
        return new Match(Math.round(best * 10.0) / 10.0,
                sharedBest, n, symbol, n >= MIN_MATURE_WINNERS);
    }

    static void annotateCandidate(JSONObject candidate, List<JSONObject> winners) {
        if (candidate == null) return;
        Match m = closest(candidate, winners);
        try {
            candidate.put("positive40Model", VERSION);
            candidate.put("positive40ParameterCount", PARAMETERS);
            candidate.put("positive40WinningCampaigns", m.winners);
            candidate.put("positive40Mature", m.mature);
            candidate.put("positive40ObservedShared", m.shared);
            candidate.put("positive40NearestWinner", m.nearestSymbol);
            candidate.put("positive40MatchScore", m.score);
            if (m.mature && m.shared >= MIN_SHARED_FEATURES) {
                // A measured, modest positive-only ranking influence after
                // sufficient independent outcomes; never overwrite raw technicals.
                double baseline = candidate.optDouble("ensembleScore",
                        candidate.optDouble("similarity", 0));
                double adjusted = 0.90 * baseline + 0.10 * m.score;
                candidate.put("ensembleScore", Math.max(0, Math.min(100,
                        Math.round(adjusted * 10.0) / 10.0)));
                candidate.put("positive40RankingWeightPct", 10);
            } else {
                candidate.put("positive40RankingWeightPct", 0);
            }
        } catch (Exception ignored) {}
    }

    /** Enrich only the observed live minute fields without overwriting EOD evidence. */
    static JSONObject liveCandidate(JSONObject frozen, ResearchMath.Features minute,
                                    double livePrice, double minuteVwap, long when) {
        JSONObject c;
        try { c = frozen == null ? new JSONObject() : new JSONObject(frozen.toString()); }
        catch (Exception ignored) { c = new JSONObject(); }
        if (minute == null || minute.dataPoints < 15) return c;
        try {
            c.put("minute1Return5Pct", minute.return5Pct);
            c.put("minute1Rsi14", minute.rsi14);
            c.put("minute1RelativeVolume20", minute.relativeVolume20);
            if (minuteVwap > 0 && livePrice > 0)
                c.put("minute1VwapDistancePct", (livePrice / minuteVwap - 1) * 100);
            // Only real, just-observed factors. No invented fundamentals/sector/book.
            c.put("positive40ObservedAt", when);
        } catch (Exception ignored) {}
        return c;
    }

    static String evidenceSummary(List<JSONObject> winners) {
        int size = winners == null ? 0 : winners.size();
        return VERSION + " • 40 measurable fields • " + size + " completed official"
                + " positive-price ENTRY→EXIT campaigns • " + (size >= MIN_MATURE_WINNERS
                ? "positive-pattern influence enabled"
                : "learning-only until " + MIN_MATURE_WINNERS + " positive campaigns")
                + " • missing sources never estimated • bearish/loss campaigns excluded";
    }

    private static Object readable(JSONObject x, String field) {
        if (x == null || !x.has(field) || x.isNull(field)) return null;
        Object v = x.opt(field);
        if (v instanceof Number) {
            double d = ((Number)v).doubleValue();
            return Double.isFinite(d) ? v : null;
        }
        if (!(v instanceof String)) return null;
        String s = ((String)v).trim().toUpperCase(Locale.US);
        if (s.isEmpty() || "UNAVAILABLE".equals(s) || "NOT_CONNECTED".equals(s)
                || "UNKNOWN_NOT_CONNECTED".equals(s)) return null;
        return s;
    }
}
