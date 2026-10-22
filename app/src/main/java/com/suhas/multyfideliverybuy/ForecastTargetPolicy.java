package com.suhas.multyfideliverybuy;

/**
 * Same-session Forecast target policy. A prospective 1% NET objective is not
 * an achieved result; neither the official Univest average nor the realized
 * Forecast average can retroactively raise a frozen recommendation target.
 */
final class ForecastTargetPolicy {
    static final double MIN_NET_PCT = 1.0;
    private ForecastTargetPolicy() {}

    /** Legacy persisted policy upgrader; discard the former official high-water mark. */
    static double nextNetPct(double previouslySaved, int completedOfficialCalls,
                             double officialAverageUpsidePct) {
        return MIN_NET_PCT;
    }
}
