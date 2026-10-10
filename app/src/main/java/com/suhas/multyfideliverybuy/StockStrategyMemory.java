package com.suhas.multyfideliverybuy;

import android.content.Context;

import org.json.JSONObject;

import java.util.Iterator;

/**
 * Per-stock adaptive memory. Generic frozen playbooks stop moving after promotion; this memory is
 * intentionally allowed to keep learning a stock's recurring official-entry signature.
 */
final class StockStrategyMemory {
    static final int SCHEMA_VERSION = 1;
    private StockStrategyMemory() {}

    static synchronized void updateFromOfficialEntry(Context c, JSONObject row) {
        if (row == null || !"ENTRY".equals(row.optString("signalType"))) return;
        String symbol = row.optString("symbol", "").trim().toUpperCase();
        if (symbol.isEmpty()) return;
        JSONObject root = ResearchStore.stockStrategyMemory(c);
        JSONObject stocks = root.optJSONObject("stocks");
        if (stocks == null) stocks = new JSONObject();
        JSONObject x = stocks.optJSONObject(symbol);
        if (x == null) x = new JSONObject();
        int oldN = x.optInt("evidence", 0);
        int n = oldN + 1;
        JSONObject oldCentroid = x.optJSONObject("centroid");
        if (oldCentroid == null) oldCentroid = new JSONObject();
        JSONObject v = ResearchPlaybookEngine.componentVector(row);
        JSONObject centroid = new JSONObject();
        try {
            for (String key : new String[]{"volumeBreakout","trendPullback","momentum","qualityRerating","catalystSector"}) {
                double prior = oldCentroid.optDouble(key, v.optDouble(key, 0));
                double next = oldN <= 0 ? v.optDouble(key, 0)
                        : prior + (v.optDouble(key, 0) - prior) / n;
                centroid.put(key, Math.round(next * 10.0) / 10.0);
            }
            JSONObject regimes = x.optJSONObject("regimes");
            if (regimes == null) regimes = new JSONObject();
            String regime = row.optString("marketRegimeStatus", "NOT_CONNECTED");
            regimes.put(regime, regimes.optInt(regime, 0) + 1);
            x.put("evidence", n);
            x.put("centroid", centroid);
            x.put("regimes", regimes);
            x.put("lastUpdated", System.currentTimeMillis());
            x.put("lastCompositeSignature", row.optString("compositeSignature", ""));
            stocks.put(symbol, x);
            root.put("schemaVersion", SCHEMA_VERSION);
            root.put("stocks", stocks);
            ResearchStore.saveStockStrategyMemory(c, root);
        } catch (Exception ignored) {}
    }

    static void applyToCandidate(Context c, JSONObject candidate) {
        if (candidate == null) return;
        String symbol = candidate.optString("symbol", "").trim().toUpperCase();
        if (symbol.isEmpty()) return;
        JSONObject stocks = ResearchStore.stockStrategyMemory(c).optJSONObject("stocks");
        if (stocks == null) return;
        JSONObject x = stocks.optJSONObject(symbol);
        if (x == null) return;
        int evidence = x.optInt("evidence", 0);
        JSONObject centroid = x.optJSONObject("centroid");
        if (evidence < 2 || centroid == null) return;
        double match = ResearchPlaybookEngine.matchScore(
                ResearchPlaybookEngine.componentVector(candidate), centroid, "");
        double base = candidate.optDouble("ensembleScore", candidate.optDouble("similarity", 0));
        double localWeight = Math.min(0.10, evidence / 100.0);
        double blended = base * (1.0 - localWeight) + match * localWeight;
        try {
            candidate.put("stockAffinityScore", Math.round(match * 10.0) / 10.0);
            candidate.put("stockAffinityEvidence", evidence);
            candidate.put("stockAffinityWeightPct", Math.round(localWeight * 1000.0) / 10.0);
            candidate.put("ensembleScore", Math.max(0, Math.min(100, Math.round(blended * 10.0) / 10.0)));
        } catch (Exception ignored) {}
    }

    static int trackedStockCount(Context c) {
        JSONObject stocks = ResearchStore.stockStrategyMemory(c).optJSONObject("stocks");
        if (stocks == null) return 0;
        int n = 0; Iterator<String> it = stocks.keys(); while (it.hasNext()) { it.next(); n++; }
        return n;
    }
}
