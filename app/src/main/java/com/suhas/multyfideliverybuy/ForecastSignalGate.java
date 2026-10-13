package com.suhas.multyfideliverybuy;

import java.util.Locale;

/** Conditional risk/feasibility gate using *available* point-in-time observations only.
 * Unknown sector/market-breadth readings are not fabricated or treated as favorable signals.
 */
final class ForecastSignalGate {
    private ForecastSignalGate() {}

    static String rejectReason(String regime, double minuteReturn5Pct,
                               int positiveNews, int negativeNews,
                               double entry, double target, double dailyAtr,
                               boolean establishedFrozenChampions, int playbookVotes) {
        if (!Double.isFinite(entry) || !Double.isFinite(target) || entry <= 0 || target <= entry)
            return "Invalid or unavailable fee-adjusted Forecast target";
        if (negativeNews >= 2 && negativeNews > positiveNews)
            return "Multiple adverse stock-news catalysts";
        String market = regime == null ? "" : regime.toUpperCase(Locale.US);
        if (market.contains("RISK_OFF") && minuteReturn5Pct <= 0)
            return "Bearish NIFTY regime without positive short-term momentum";
        if (establishedFrozenChampions && playbookVotes <= 0)
            return "No match with an established frozen strategy champion";
        // Three-session targets whose required move exceeds 2.2 daily ATR are not
        // credible enough to issue. A missing ATR is not invented.
        if (Double.isFinite(dailyAtr) && dailyAtr > 0 && target - entry > 2.2 * dailyAtr)
            return "Target exceeds 2.2 times observed daily ATR";
        return "";
    }
}
