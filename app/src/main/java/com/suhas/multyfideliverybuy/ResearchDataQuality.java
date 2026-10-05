package com.suhas.multyfideliverybuy;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class ResearchDataQuality {
    private ResearchDataQuality() {}

    static int score(JSONObject p) {
        if (p == null) return 0;
        int s = 0;
        if (p.optInt("dataPoints", 0) >= 50) s += 45;
        else if (p.optInt("dataPoints", 0) >= 20) s += 30;
        if (p.optDouble("close", 0) > 0 && p.optDouble("atr14", 0) > 0) s += 15;
        if (p.optDouble("relativeVolume20", 0) > 0) s += 10;
        if (!p.optString("newsSignal", "").isEmpty()) s += 10;
        if (p.optInt("minuteDataPoints", 0) >= 10) s += 5;
        if (!"UNKNOWN_NOT_CONNECTED".equals(p.optString("fundamentalsStatus", "UNKNOWN_NOT_CONNECTED"))) s += 8;
        if (!"NOT_CONNECTED".equals(p.optString("marketRegimeStatus", "NOT_CONNECTED"))) s += 7;
        return Math.max(0, Math.min(100, s));
    }

    static String missing(JSONObject p) {
        if (p == null) return "all data unavailable";
        List<String> m = new ArrayList<>();
        if (p.optInt("dataPoints", 0) < 20) m.add("insufficient daily candles");
        if (p.optInt("minuteDataPoints", 0) < 10) m.add("minute context");
        if ("UNKNOWN_NOT_CONNECTED".equals(p.optString("fundamentalsStatus", "UNKNOWN_NOT_CONNECTED"))) m.add("fundamentals");
        if ("NOT_CONNECTED".equals(p.optString("marketRegimeStatus", "NOT_CONNECTED"))) m.add("broad-market regime");
        if ("NOT_CONNECTED".equals(p.optString("sectorContextStatus", "NOT_CONNECTED"))) m.add("sector relative strength");
        if (p.optString("newsSignal", "").isEmpty()) m.add("news enrichment");
        if (m.isEmpty()) return "complete";
        StringBuilder b = new StringBuilder();
        for (String x : m) { if (b.length() > 0) b.append(", "); b.append(x); }
        return b.toString();
    }

    static void annotate(JSONObject p) {
        if (p == null) return;
        try {
            p.put("dataConfidence", score(p));
            p.put("missingData", missing(p));
        } catch (Exception ignored) {}
    }
}
