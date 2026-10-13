package com.suhas.multyfideliverybuy;

/** Frozen Forecast targets based on fee-aware return, never an unobserved sell zone. */
final class ForecastTargetMath {
    private ForecastTargetMath() {}

    static double netPrice(double entry, int quantity, double tick, double targetNetPct) {
        if (!Double.isFinite(entry) || entry <= 0 || quantity <= 0
                || !Double.isFinite(tick) || tick <= 0
                || !Double.isFinite(targetNetPct) || targetNetPct <= 0) return 0;
        double price = DeliveryNetTarget.targetPriceForNetProfit(
                entry, quantity, tick, entry * quantity * targetNetPct / 100.0);
        return Double.isFinite(price) && price > entry ? price : 0;
    }

    static boolean observedHit(double observedHigh, double target) {
        return Double.isFinite(observedHigh) && Double.isFinite(target)
                && target > 0 && observedHigh + 1e-7 >= target;
    }
}
