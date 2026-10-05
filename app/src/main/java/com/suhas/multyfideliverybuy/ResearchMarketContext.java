package com.suhas.multyfideliverybuy;

import android.content.Context;
import org.json.JSONObject;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Broad-market regime using the actual NSE NIFTY cash index supported by Groww.
 */
final class ResearchMarketContext {
    private static final long DAY = TimeUnit.DAYS.toMillis(1);

    private ResearchMarketContext() {}

    static JSONObject snapshot(Context c, long now) {
        JSONObject out = new JSONObject();
        try {
            out.put("source", "NIFTY_INDEX");
            out.put("capturedAt", now);
            List<GrowwClient.Candle> candles = GrowwClient.getHistoricalCandles(
                    c, "NIFTY", now - 140L * DAY, now, "1day");
            ResearchMath.Features f = ResearchMath.fromCandles(candles);
            if (!(f.close > 0) || f.dataPoints < 20) {
                out.put("status", "UNAVAILABLE");
                return out;
            }
            String regime;
            if (f.sma20 > f.sma50 && f.return20Pct > 2.0) regime = "BULLISH_TREND";
            else if (f.sma20 < f.sma50 && f.return20Pct < -2.0) regime = "RISK_OFF";
            else if (Math.abs(f.return20Pct) < 2.0) regime = "RANGE_MIXED";
            else regime = "TRANSITION";
            out.put("status", "AVAILABLE");
            out.put("regime", regime);
            out.put("close", f.close);
            out.put("return5Pct", f.return5Pct);
            out.put("return20Pct", f.return20Pct);
            out.put("sma20", f.sma20);
            out.put("sma50", f.sma50);
            out.put("rsi14", f.rsi14);
        } catch (Throwable t) {
            try {
                out.put("status", "UNAVAILABLE");
                out.put("error", t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
            } catch (Exception ignored) {}
        }
        return out;
    }

    static String label(JSONObject m) {
        if (m == null || !"AVAILABLE".equals(m.optString("status"))) return "NOT_CONNECTED";
        return "NIFTY:" + m.optString("regime", "UNKNOWN");
    }

    static String text(JSONObject m) {
        if (m == null || !"AVAILABLE".equals(m.optString("status")))
            return "NIFTY broad-market context unavailable.";
        return String.format(Locale.US,
                "%s • 5-session %.1f%% • 20-session %.1f%% • RSI %.1f",
                label(m), m.optDouble("return5Pct"), m.optDouble("return20Pct"), m.optDouble("rsi14"));
    }
}
