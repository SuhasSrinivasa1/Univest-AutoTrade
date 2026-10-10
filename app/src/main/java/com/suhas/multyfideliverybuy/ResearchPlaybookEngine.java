package com.suhas.multyfideliverybuy;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Composite, non-exclusive Univest playbook learner.
 *
 * A playbook is a recurring combination of component scores rather than a mutually-exclusive
 * "family". A stock may strongly match several playbooks at the same time. The Top 5 active
 * playbooks are ranked primarily by evidence and genuine pre-Univest forecast hits.
 */
final class ResearchPlaybookEngine {
    static final int SCHEMA_VERSION = 2;
    static final int ACTIVE_LIMIT = 10;
    static final int FROZEN_TARGET = 10;
    static final int MIN_FROZEN_FOR_STABLE_DECISIONS = 5;
    static final int FREEZE_MIN_EVIDENCE = 30;
    static final int FREEZE_MIN_VALIDATION = 12;
    static final int FREEZE_MIN_UNIQUE_SYMBOLS = 8;
    static final double FREEZE_MIN_TOP10_RATE = 0.50;
    static final double FREEZE_MIN_RATING = 72.0;
    static final int FREEZE_MIN_COMPLETED_OUTCOMES = 8;
    static final double FREEZE_MIN_TWO_SESSION_RATE = 60.0;
    private static final String[] COMPONENTS = {
            "volumeBreakout", "trendPullback", "momentum", "qualityRerating", "catalystSector"
    };
    private static final String[] LABELS = {
            "VOLUME_BREAKOUT", "TREND_PULLBACK", "MOMENTUM_CONTINUATION",
            "QUALITY_RERATING", "CATALYST_SECTOR"
    };

    private ResearchPlaybookEngine() {}

    static JSONObject componentVector(JSONObject row) {
        JSONObject out = new JSONObject();
        if (row == null) return out;
        try {
            int vb = row.optInt("volumeBreakoutScore", -1);
            int tp = row.optInt("trendPullbackScore", -1);
            int mo = row.optInt("momentumScore", -1);
            int qr = row.optInt("qualityReratingScore", -1);
            int cs = row.optInt("catalystSectorScore", -1);
            if (vb < 0 || tp < 0 || mo < 0 || qr < 0 || cs < 0) {
                ResearchMath.Features f = new ResearchMath.Features();
                f.close = row.optDouble("close", 0);
                f.sma20 = row.optDouble("sma20", 0);
                f.sma50 = row.optDouble("sma50", 0);
                f.rsi14 = row.optDouble("rsi14", 0);
                f.atr14 = row.optDouble("atr14", 0);
                f.relativeVolume20 = row.optDouble("relativeVolume20", 0);
                f.return5Pct = row.optDouble("return5Pct", 0);
                f.return20Pct = row.optDouble("return20Pct", 0);
                f.return60Pct = row.optDouble("return60Pct", 0);
                f.prior20High = row.optDouble("prior20High", 0);
                f.volatility20Pct = row.optDouble("volatility20Pct", 0);
                f.dataPoints = row.optInt("dataPoints", 0);
                int catalysts = Math.max(0, row.optInt("positiveCatalysts", 0)
                        - row.optInt("negativeCatalysts", 0));
                double sector = row.optDouble("sectorRelativePct", 0);
                boolean fundamentals = row.optBoolean("fundamentalsPositive", false);
                ResearchMath.StrategyScores s = ResearchMath.score(f, catalysts, sector, fundamentals);
                vb = s.volumeBreakout;
                tp = s.trendPullback;
                mo = s.momentum;
                qr = s.qualityRerating;
                cs = s.catalystSector;
            }
            out.put(COMPONENTS[0], clamp(vb));
            out.put(COMPONENTS[1], clamp(tp));
            out.put(COMPONENTS[2], clamp(mo));
            out.put(COMPONENTS[3], clamp(qr));
            out.put(COMPONENTS[4], clamp(cs));
        } catch (Exception ignored) {}
        return out;
    }

    static String signature(JSONObject vector) {
        int[] scores = new int[COMPONENTS.length];
        for (int i = 0; i < COMPONENTS.length; i++)
            scores[i] = vector == null ? 0 : vector.optInt(COMPONENTS[i], 0);
        return signatureFromScores(scores[0], scores[1], scores[2], scores[3], scores[4]);
    }

    static String signatureFromScores(int volumeBreakout, int trendPullback, int momentum,
                                      int qualityRerating, int catalystSector) {
        final int[] scores = {
                clamp(volumeBreakout), clamp(trendPullback), clamp(momentum),
                clamp(qualityRerating), clamp(catalystSector)
        };
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < COMPONENTS.length; i++) idx.add(i);
        Collections.sort(idx, (a, b) -> Integer.compare(scores[b], scores[a]));
        StringBuilder out = new StringBuilder();
        int kept = 0;
        for (int i : idx) {
            int score = scores[i];
            if (kept >= 3) break;
            if (score < 45 && kept >= 2) break;
            if (out.length() > 0) out.append(" + ");
            out.append(LABELS[i]);
            kept++;
        }
        return out.length() == 0 ? "UNCLASSIFIED_COMPOSITE" : out.toString();
    }

    static synchronized JSONObject rebuildRegistry(Context context) {
        Context c = context.getApplicationContext();
        JSONObject previous = ResearchStore.playbookRegistry(c);
        Map<String, Aggregate> groups = new LinkedHashMap<>();
        List<JSONObject> history = ResearchStore.forecastHistory(c, 500);

        for (JSONObject row : ResearchStore.featureSnapshots(c)) {
            if (!"OFFICIAL_UNIVEST_ENTRY".equals(row.optString("source"))
                    || !"ENTRY".equals(row.optString("signalType", "ENTRY"))) continue;
            long signalAt = row.optLong("signalAt", 0L);
            if (signalAt <= 0) continue;
            JSONObject v = componentVector(row);
            String sig = signature(v);
            Aggregate a = groups.get(sig);
            if (a == null) { a = new Aggregate(sig); groups.put(sig, a); }
            a.add(v, row.optString("symbol", ""), row.optString("marketRegimeStatus", "NOT_CONNECTED"));
            ForecastHit hit = forecastHit(history, row.optString("symbol", ""), signalAt);
            if (hit.eligible) {
                a.validationEvents++;
                if (hit.rank > 0 && hit.rank <= 10) a.top10Hits++;
                if (hit.rank > 0 && hit.rank <= 5) a.top5Hits++;
                if (hit.rank > 0 && hit.rank <= 3) a.top3Hits++;
                if (hit.rank > 0) { a.rankSum += hit.rank; a.rankHits++; }
                if (hit.leadMinutes >= 0) { a.leadMinutesSum += hit.leadMinutes; a.leadCount++; }
            }
        }

        List<JSONObject> ranked = new ArrayList<>();
        Map<String,JSONObject> currentById = new LinkedHashMap<>();
        for (Aggregate a : groups.values()) {
            JSONObject row = a.toJson();
            JSONObject perf = UnivestBenchmark.performanceForSignature(c, row.optString("signature"));
            try { row.put("officialOutcomePerformance", perf); } catch (Exception ignored) {}
            ranked.add(row);
            currentById.put(row.optString("id"), row);
        }
        Collections.sort(ranked, (a, b) -> {
            int r = Double.compare(b.optDouble("rating", 0), a.optDouble("rating", 0));
            if (r != 0) return r;
            return Integer.compare(b.optInt("evidence", 0), a.optInt("evidence", 0));
        });

        JSONArray frozen = new JSONArray();
        JSONArray priorFrozen = previous.optJSONArray("frozenChampions");
        if (priorFrozen != null) {
            for (int i = 0; i < priorFrozen.length() && frozen.length() < FROZEN_TARGET; i++) {
                JSONObject old = priorFrozen.optJSONObject(i);
                if (old == null) continue;
                JSONObject kept = copy(old);
                JSONObject live = currentById.get(kept.optString("id"));
                try {
                    kept.put("status", "FROZEN_CHAMPION");
                    kept.put("decisionCoreFrozen", true);
                    if (live != null) {
                        kept.put("monitorEvidence", live.optInt("evidence"));
                        kept.put("monitorValidationEvents", live.optInt("validationEvents"));
                        kept.put("monitorTop10Hits", live.optInt("top10Hits"));
                        kept.put("monitorRating", live.optDouble("rating"));
                        kept.put("monitorUniqueSymbols", live.optInt("uniqueSymbolCount"));
                        kept.put("monitorUpdatedAt", System.currentTimeMillis());
                    }
                    frozen.put(kept);
                } catch (Exception ignored) {}
            }
        }

        JSONObject currentBenchmark = UnivestBenchmark.snapshot(c);
        for (JSONObject row : ranked) {
            if (frozen.length() >= FROZEN_TARGET) break;
            String id = row.optString("id");
            if (containsId(frozen, id)) continue;
            if (!freezeEligible(row.optInt("evidence"), row.optInt("validationEvents"),
                    row.optInt("top10Hits"), row.optInt("uniqueSymbolCount"), row.optDouble("rating"))) continue;
            JSONObject perf = row.optJSONObject("officialOutcomePerformance");
            if (perf == null || !freezeOutcomeEligible(perf.optInt("completed"),
                    perf.optDouble("averageUpsidePct"), perf.optDouble("byTwoSessionsPct"),
                    currentBenchmark.optDouble("averageUpsidePct", 0))) continue;
            JSONObject locked = copy(row);
            try {
                locked.put("status", "FROZEN_CHAMPION");
                locked.put("decisionCoreFrozen", true);
                locked.put("frozenAt", System.currentTimeMillis());
                locked.put("frozenEvidence", row.optInt("evidence"));
                locked.put("frozenValidationEvents", row.optInt("validationEvents"));
                locked.put("frozenRating", row.optDouble("rating"));
                locked.put("freezeReason", "Enough independent evidence, pre-Univest validation and symbol diversity. Core centroid will not move again.");
                frozen.put(locked);
                DiagnosticsStore.runtime(c, "PLAYBOOK_FROZEN", id,
                        "Generic Research playbook frozen permanently: " + row.optString("signature"));
            } catch (Exception ignored) {}
        }

        JSONArray all = new JSONArray();
        for (JSONObject row : ranked) all.put(row);

        JSONArray decision = new JSONArray();
        for (int i = 0; i < frozen.length() && decision.length() < ACTIVE_LIMIT; i++) decision.put(frozen.optJSONObject(i));
        // While fewer than five immutable generic champions exist, challengers may supplement decisions.
        // Once five are frozen, the generic decision core stops learning and uses frozen playbooks only.
        if (frozen.length() < MIN_FROZEN_FOR_STABLE_DECISIONS) {
            for (JSONObject row : ranked) {
                if (decision.length() >= ACTIVE_LIMIT) break;
                if (!containsId(decision, row.optString("id"))) decision.put(row);
            }
        }

        JSONArray activeTop5 = new JSONArray();
        for (int i = 0; i < decision.length() && i < 5; i++) activeTop5.put(decision.optJSONObject(i));
        JSONArray hall = previous.optJSONArray("hallOfFame");
        if (hall == null) hall = new JSONArray();

        JSONObject root = new JSONObject();
        try {
            root.put("schemaVersion", SCHEMA_VERSION);
            root.put("updatedAt", System.currentTimeMillis());
            root.put("objective", "Predict official Univest ENTRY before notification and preserve proven generic patterns without endless drift");
            root.put("componentModel", "NON_EXCLUSIVE_COMPOSITE");
            root.put("frozenTarget", FROZEN_TARGET);
            root.put("minimumFrozenForStableDecisionCore", MIN_FROZEN_FOR_STABLE_DECISIONS);
            root.put("frozenChampions", frozen);
            root.put("decisionPlaybooks", decision);
            root.put("activeTop5", activeTop5);
            root.put("allPlaybooks", all);
            root.put("hallOfFame", hall);
        } catch (Exception ignored) {}
        ResearchStore.savePlaybookRegistry(c, root);
        DiagnosticsStore.runtime(c, "PLAYBOOK_REGISTRY_REBUILT", "",
                "Composite playbooks audited • " + ranked.size() + " discovered • "
                        + frozen.length() + "/" + FROZEN_TARGET + " immutable generic champions • "
                        + decision.length() + " decision playbooks.");
        return root;
    }

    static boolean freezeEligible(int evidence, int validationEvents, int top10Hits,
                                  int uniqueSymbols, double rating) {
        double rate = validationEvents > 0 ? top10Hits / (double)validationEvents : 0;
        return evidence >= FREEZE_MIN_EVIDENCE
                && validationEvents >= FREEZE_MIN_VALIDATION
                && uniqueSymbols >= FREEZE_MIN_UNIQUE_SYMBOLS
                && rate >= FREEZE_MIN_TOP10_RATE
                && rating >= FREEZE_MIN_RATING;
    }

    static boolean freezeOutcomeEligible(int completed, double averageUpsidePct,
                                         double byTwoSessionsPct, double benchmarkAverageUpsidePct) {
        double requiredUpside = Math.max(0.50, benchmarkAverageUpsidePct * 0.80);
        return completed >= FREEZE_MIN_COMPLETED_OUTCOMES
                && averageUpsidePct >= requiredUpside
                && byTwoSessionsPct >= FREEZE_MIN_TWO_SESSION_RATE;
    }

    static void applyToCandidate(Context c, JSONObject candidate) {
        if (candidate == null) return;
        JSONObject registry = ResearchStore.playbookRegistry(c);
        JSONArray active = registry.optJSONArray("decisionPlaybooks");
        if (active == null || active.length() == 0) active = registry.optJSONArray("activeTop5");
        if (active == null || active.length() == 0) return;

        JSONObject v = componentVector(candidate);
        double best = 0;
        String bestId = "";
        String bestSig = "";
        JSONObject bestOutcomePerformance = null;
        int votes = 0;
        int frozenVotes = 0;
        int usableEvidence = 0;
        double weighted = 0;
        double weight = 0;
        JSONArray matches = new JSONArray();
        String regime = candidate.optString("marketRegimeStatus", "NOT_CONNECTED");

        for (int i = 0; i < active.length(); i++) {
            JSONObject p = active.optJSONObject(i);
            if (p == null) continue;
            JSONObject centroid = p.optJSONObject("centroid");
            if (centroid == null) continue;
            double match = matchScore(v, centroid, p.optString("signature", ""));
            boolean frozen = "FROZEN_CHAMPION".equals(p.optString("status"));
            if (frozen && !"NOT_CONNECTED".equals(regime) && regime.equals(p.optString("dominantRegime", "")))
                match = Math.min(100, match + 3.0);
            int evidence = frozen ? p.optInt("frozenEvidence", p.optInt("evidence", 0)) : p.optInt("evidence", 0);
            usableEvidence += evidence;
            double w = Math.max(1.0, Math.min(25.0, evidence))
                    * Math.max(0.25, p.optDouble("rating", p.optDouble("frozenRating", 0)) / 100.0)
                    * (frozen ? 1.15 : 1.0);
            weighted += match * w; weight += w;
            if (match >= 75.0) { votes++; if (frozen) frozenVotes++; }
            if (match > best) {
                best = match; bestId = p.optString("id"); bestSig = p.optString("signature");
                bestOutcomePerformance = p.optJSONObject("officialOutcomePerformance");
            }
            try {
                JSONObject m = new JSONObject();
                m.put("id", p.optString("id"));
                m.put("signature", p.optString("signature"));
                m.put("match", one(match));
                m.put("rating", p.optDouble("rating", p.optDouble("frozenRating", 0)));
                m.put("frozen", frozen);
                matches.put(m);
            } catch (Exception ignored) {}
        }

        double playbook = weight > 0 ? weighted / weight : best;
        int technical = candidate.optInt("similarity", candidate.optInt("bestScore", 0));
        double ensemble = usableEvidence >= 5
                ? 0.65 * technical + 0.35 * playbook + Math.min(5.0, votes * 1.5)
                : technical;
        ensemble = Math.max(0, Math.min(100, ensemble));
        try {
            candidate.put("playbookScore", one(playbook));
            candidate.put("playbookVotes", votes);
            candidate.put("frozenPlaybookVotes", frozenVotes);
            candidate.put("bestPlaybookId", bestId);
            candidate.put("bestPlaybook", bestSig);
            candidate.put("playbookMatches", matches);
            candidate.put("ensembleScore", one(ensemble));
            if (bestOutcomePerformance != null) {
                candidate.put("playbookOfficialCompleted", bestOutcomePerformance.optInt("completed", 0));
                candidate.put("playbookAvgOfficialUpsidePct", bestOutcomePerformance.optDouble("averageUpsidePct", 0));
                candidate.put("playbookOfficialByTwoSessionsPct", bestOutcomePerformance.optDouble("byTwoSessionsPct", 0));
            }
        } catch (Exception ignored) {}
    }

    static synchronized void captureMatchedControls(Context c, JSONObject selectedProfile) {
        if (selectedProfile == null) return;
        String symbol = selectedProfile.optString("symbol", "");
        long signalAt = selectedProfile.optLong("signalAt", 0L);
        if (symbol.isEmpty() || signalAt <= 0) return;
        JSONArray pool = ResearchStore.candidatePool(c);
        if (pool.length() == 0) return;

        JSONObject selected = componentVector(selectedProfile);
        List<JSONObject> rows = new ArrayList<>();
        for (int i = 0; i < pool.length(); i++) {
            JSONObject p = pool.optJSONObject(i);
            if (p == null || symbol.equalsIgnoreCase(p.optString("symbol"))) continue;
            JSONObject copy;
            try { copy = new JSONObject(p.toString()); }
            catch (Exception e) { continue; }
            double distance = componentDistance(selected, componentVector(p));
            try { copy.put("_controlDistance", distance); } catch (Exception ignored) {}
            rows.add(copy);
        }
        Collections.sort(rows, Comparator.comparingDouble(x -> x.optDouble("_controlDistance", 9999)));

        JSONArray controls = new JSONArray();
        for (int i = 0; i < rows.size() && i < 12; i++) {
            JSONObject p = rows.get(i);
            JSONObject x = new JSONObject();
            try {
                x.put("symbol", p.optString("symbol"));
                x.put("distance", one(p.optDouble("_controlDistance", 0)));
                x.put("similarity", p.optInt("similarity", 0));
                x.put("ensembleScore", p.optDouble("ensembleScore", p.optInt("similarity", 0)));
                x.put("components", componentVector(p));
                controls.put(x);
            } catch (Exception ignored) {}
        }

        JSONObject record = new JSONObject();
        try {
            record.put("schemaVersion", 1);
            record.put("signalAt", signalAt);
            record.put("signalType", selectedProfile.optString("signalType", "ENTRY"));
            record.put("symbol", symbol);
            record.put("selectedComponents", selected);
            record.put("controls", controls);
            record.put("purpose", "Matched non-selected controls distinguish Univest selection-specific conditions from broad market conditions.");
        } catch (Exception ignored) {}
        ResearchStore.appendMatchedControls(c, record);
    }

    static String summaryText(Context c) {
        JSONObject r = ResearchStore.playbookRegistry(c);
        JSONArray frozen = r.optJSONArray("frozenChampions");
        JSONArray a = r.optJSONArray("decisionPlaybooks");
        if (a == null || a.length() == 0)
            return "No composite playbooks learned yet. Generic strategies will freeze only after enough independent evidence.";
        StringBuilder b = new StringBuilder();
        b.append("Frozen generic strategies ").append(frozen == null ? 0 : frozen.length())
                .append("/").append(FROZEN_TARGET)
                .append(" • stock-specific memory remains adaptive across ").append(StockStrategyMemory.trackedStockCount(c)).append(" stocks");
        for (int i = 0; i < a.length(); i++) {
            JSONObject p = a.optJSONObject(i); if (p == null) continue;
            if (b.length() > 0) b.append("\n\n");
            b.append(i + 1).append(". ").append(p.optString("signature"))
                    .append("\nRating ").append(String.format(Locale.US, "%.0f", p.optDouble("rating", p.optDouble("frozenRating", 0)))).append("/100")
                    .append(" • ").append(p.optString("status"))
                    .append(" • evidence ").append(p.optInt("evidence", p.optInt("frozenEvidence", 0)));
            if ("FROZEN_CHAMPION".equals(p.optString("status")))
                b.append(" • CORE LOCKED");
            int v = p.optInt("validationEvents", p.optInt("frozenValidationEvents", 0));
            if (v > 0) b.append("\nPre-Univest validation ").append(v)
                    .append(" • Top10 ").append(p.optInt("top10Hits", p.optInt("monitorTop10Hits", 0))).append("/").append(v)
                    .append(" • unique stocks ").append(p.optInt("uniqueSymbolCount", p.optInt("monitorUniqueSymbols", 0)))
                    .append(" • regime ").append(p.optString("dominantRegime", "mixed/unknown"));
        }
        return b.toString();
    }

    static String frozenSummaryText(Context c) {
        JSONObject r = ResearchStore.playbookRegistry(c);
        JSONArray frozen = r.optJSONArray("frozenChampions");
        int n = frozen == null ? 0 : frozen.length();
        return n + "/" + FROZEN_TARGET + " generic strategies frozen permanently. "
                + (n >= MIN_FROZEN_FOR_STABLE_DECISIONS
                ? "Generic decision core is now locked; new evidence audits performance but does not move centroids."
                : "Challengers may supplement decisions until at least " + MIN_FROZEN_FOR_STABLE_DECISIONS + " independent generic champions qualify.")
                + " Stock-specific memory continues adapting separately.";
    }

    static String accountabilityText(Context c) {
        List<JSONObject> signals = ResearchStore.signals(c);
        List<JSONObject> history = ResearchStore.forecastHistory(c, 400);
        int eligible = 0, t10 = 0, t5 = 0, t3 = 0, rankSum = 0, rankHits = 0;
        double lead = 0; int leadN = 0;
        for (JSONObject s : signals) {
            if (!"ENTRY".equals(s.optString("type"))) continue;
            ForecastHit h = forecastHit(history, s.optString("symbol"), s.optLong("signalAt", 0));
            if (!h.eligible) continue;
            eligible++;
            if (h.rank > 0 && h.rank <= 10) t10++;
            if (h.rank > 0 && h.rank <= 5) t5++;
            if (h.rank > 0 && h.rank <= 3) t3++;
            if (h.rank > 0) { rankSum += h.rank; rankHits++; }
            if (h.leadMinutes >= 0) { lead += h.leadMinutes; leadN++; }
        }
        if (eligible == 0)
            return "No official ENTRY has occurred yet with an earlier frozen forecast available for a fair prediction test.";
        StringBuilder b = new StringBuilder();
        b.append("Unseen-call scorecard • ").append(eligible).append(" eligible official entries")
                .append("\nTop 10 before Univest: ").append(t10).append("/").append(eligible)
                .append(" • Top 5: ").append(t5).append("/").append(eligible)
                .append(" • Top 3: ").append(t3).append("/").append(eligible);
        if (rankHits > 0) b.append("\nAverage rank when predicted: ")
                .append(String.format(Locale.US, "%.1f", rankSum / (double)rankHits));
        if (leadN > 0) b.append(" • average lead ").append(formatLead(lead / leadN));
        b.append("\nPrimary objective: predict the actual Univest stock before the official notification, not merely explain it afterward.");
        return b.toString();
    }

    static int forecastRankBeforeSignal(Context c, String symbol, long signalAt) {
        return forecastHit(ResearchStore.forecastHistory(c, 400), symbol, signalAt).rank;
    }

    static long forecastLeadMinutesBeforeSignal(Context c, String symbol, long signalAt) {
        ForecastHit h = forecastHit(ResearchStore.forecastHistory(c, 400), symbol, signalAt);
        return h.leadMinutes < 0 ? -1L : (long)h.leadMinutes;
    }

    static double matchScore(JSONObject vector, JSONObject centroid, String signature) {
        int[] a = new int[COMPONENTS.length];
        int[] b = new int[COMPONENTS.length];
        for (int i = 0; i < COMPONENTS.length; i++) {
            a[i] = (int)Math.round(vector.optDouble(COMPONENTS[i], 0));
            b[i] = (int)Math.round(centroid.optDouble(COMPONENTS[i], 0));
        }
        return matchScore(a, b, signature);
    }

    static double matchScore(int[] vector, int[] centroid, String signature) {
        if (vector == null || centroid == null
                || vector.length < COMPONENTS.length || centroid.length < COMPONENTS.length) return 0;
        double weightedDiff = 0;
        double weights = 0;
        for (int i = 0; i < COMPONENTS.length; i++) {
            double w = signature != null && signature.contains(LABELS[i]) ? 2.0 : 1.0;
            weightedDiff += Math.abs(vector[i] - centroid[i]) * w;
            weights += w;
        }
        return Math.max(0, Math.min(100, 100.0 - weightedDiff / Math.max(1.0, weights)));
    }

    private static double componentDistance(JSONObject a, JSONObject b) {
        double sum = 0;
        for (String k : COMPONENTS) {
            double d = a.optDouble(k, 0) - b.optDouble(k, 0);
            sum += d * d;
        }
        return Math.sqrt(sum / COMPONENTS.length);
    }

    private static ForecastHit forecastHit(List<JSONObject> history, String symbol, long signalAt) {
        ForecastHit out = new ForecastHit();
        if (symbol == null || symbol.trim().isEmpty() || signalAt <= 0) return out;
        String day = AppPrefs.istDayKey(signalAt);
        JSONObject best = null;
        long bestAt = -1;
        for (JSONObject snap : history) {
            if (snap == null) continue;
            if (!day.equals(snap.optString("targetSessionKey"))) continue;
            long frozenAt = snap.optLong("frozenAt", 0);
            if (frozenAt <= 0 || frozenAt >= signalAt || frozenAt < bestAt) continue;
            best = snap;
            bestAt = frozenAt;
        }
        if (best == null) return out;
        out.eligible = true;
        out.leadMinutes = Math.max(0, (signalAt - bestAt) / 60000.0);
        JSONArray p = best.optJSONArray("predictions");
        if (p == null) return out;
        for (int i = 0; i < p.length(); i++) {
            JSONObject row = p.optJSONObject(i);
            if (row != null && symbol.equalsIgnoreCase(row.optString("symbol"))) {
                out.rank = i + 1;
                return out;
            }
        }
        return out;
    }

    private static JSONObject copy(JSONObject x) {
        try { return x == null ? new JSONObject() : new JSONObject(x.toString()); }
        catch (Exception e) { return new JSONObject(); }
    }

    private static boolean containsId(JSONArray a, String id) {
        if (a == null || id == null || id.isEmpty()) return false;
        for (int i = 0; i < a.length(); i++) {
            JSONObject x = a.optJSONObject(i);
            if (x != null && id.equals(x.optString("id"))) return true;
        }
        return false;
    }

    private static int clamp(int x) { return Math.max(0, Math.min(100, x)); }
    private static double one(double x) { return Math.round(x * 10.0) / 10.0; }
    private static String formatLead(double minutes) {
        if (minutes < 60) return String.format(Locale.US, "%.0f min", minutes);
        return String.format(Locale.US, "%.1f h", minutes / 60.0);
    }

    private static String idFor(String signature) {
        return "PB-" + Integer.toHexString(signature == null ? 0 : signature.hashCode()).toUpperCase(Locale.US);
    }

    private static final class ForecastHit {
        boolean eligible;
        int rank;
        double leadMinutes = -1;
    }

    private static final class Aggregate {
        final String signature;
        int evidence;
        int validationEvents;
        int top10Hits, top5Hits, top3Hits;
        int rankSum, rankHits;
        double leadMinutesSum;
        int leadCount;
        final double[] sums = new double[COMPONENTS.length];
        final Set<String> symbols = new HashSet<>();
        final Map<String,Integer> regimes = new LinkedHashMap<>();

        Aggregate(String signature) { this.signature = signature; }

        void add(JSONObject v, String symbol, String regime) {
            evidence++;
            if (symbol != null && !symbol.trim().isEmpty()) symbols.add(symbol.trim().toUpperCase(Locale.US));
            String r = regime == null || regime.trim().isEmpty() ? "NOT_CONNECTED" : regime.trim();
            regimes.put(r, regimes.containsKey(r) ? regimes.get(r) + 1 : 1);
            for (int i = 0; i < COMPONENTS.length; i++) sums[i] += v.optDouble(COMPONENTS[i], 0);
        }

        JSONObject toJson() {
            JSONObject p = new JSONObject();
            JSONObject centroid = new JSONObject();
            try {
                for (int i = 0; i < COMPONENTS.length; i++)
                    centroid.put(COMPONENTS[i], evidence > 0 ? one(sums[i] / evidence) : 0);
                double prediction = validationEvents > 0
                        ? 35.0 * top10Hits / validationEvents
                            + 15.0 * top5Hits / validationEvents
                            + 10.0 * top3Hits / validationEvents
                        : 0.0;
                double evidenceScore = Math.min(40.0, evidence * 4.0);
                double rating = Math.min(100.0, evidenceScore + prediction);
                String status;
                if (freezeEligible(evidence, validationEvents, top10Hits, symbols.size(), rating)) status = "FREEZE_READY";
                else if (validationEvents >= 5 || evidence >= 10) status = "CHALLENGER";
                else if (evidence >= 3) status = "DEVELOPING";
                else status = "EXPERIMENTAL";

                String dominantRegime = "NOT_CONNECTED"; int dominantCount = 0;
                JSONObject regimeCounts = new JSONObject();
                for (Map.Entry<String,Integer> e : regimes.entrySet()) {
                    regimeCounts.put(e.getKey(), e.getValue());
                    if (e.getValue() > dominantCount) { dominantCount = e.getValue(); dominantRegime = e.getKey(); }
                }
                p.put("id", idFor(signature));
                p.put("signature", signature);
                p.put("evidence", evidence);
                p.put("uniqueSymbolCount", symbols.size());
                p.put("validationEvents", validationEvents);
                p.put("top10Hits", top10Hits);
                p.put("top5Hits", top5Hits);
                p.put("top3Hits", top3Hits);
                p.put("top10HitRate", validationEvents > 0 ? one(100.0 * top10Hits / validationEvents) : 0);
                p.put("avgRank", rankHits > 0 ? one(rankSum / (double)rankHits) : 0);
                p.put("avgLeadMinutes", leadCount > 0 ? one(leadMinutesSum / leadCount) : 0);
                p.put("rating", one(rating));
                p.put("status", status);
                p.put("centroid", centroid);
                p.put("dominantRegime", dominantRegime);
                p.put("regimeCounts", regimeCounts);
            } catch (Exception ignored) {}
            return p;
        }
    }

}
