package com.suhas.multyfideliverybuy;

/**
 * Forecast recommendation target policy; this is an aspiration, NEVER a realized-return metric.
 * Official Univest completed-call returns only raise the persistent target floor.
 */
final class ForecastTargetPolicy {
    static final double MIN_NET_PCT = 1.0;

    private ForecastTargetPolicy() {}

    static double nextNetPct(double previouslySaved, int completedOfficialCalls, double officialAverageUpsidePct) {
        double prior = Double.isFinite(previouslySaved) && previouslySaved >= MIN_NET_PCT
                ? previouslySaved : MIN_NET_PCT;
        if (completedOfficialCalls <= 0 || !Double.isFinite(officialAverageUpsidePct)) return prior;
        // The official average is a gross reference. The forecast target is a minimum NET
        // gain; charge-aware price sizing is performed separately by ForecastTargetMath.
        return Math.max(prior, officialAverageUpsidePct);
    }
}
