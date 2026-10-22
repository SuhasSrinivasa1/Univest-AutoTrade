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

    /**
     * Validate the immutable 1% NET same-session recommendation against its
     * signal-time share size. Legacy/stale prices and policy percentages are
     * NOT interchangeable with today's target, and must not be shown as hits.
     * A missing saved price may be reconstructed from the ORIGINAL quote.
     */
    static double verifiedSameDayTarget(double signalEntry, int signalQuantity,
                                        double tick, double savedNetPct, double savedPrice) {
        if (!Double.isFinite(savedNetPct) || Math.abs(savedNetPct - 1.0) > 0.00001)
            return 0;
        double expected = netPrice(signalEntry, signalQuantity, tick, 1.0);
        if (!(expected > signalEntry)) return 0;
        if (!Double.isFinite(savedPrice)) return 0;
        if (savedPrice <= 0) return expected;
        // Legacy sell zones are not attained prices.
        return Math.abs(savedPrice - expected) <= Math.max(0.011, tick + 1e-6)
                ? expected : 0;
    }

    /** Only full market minutes inside the post-call observation window count. */
    static boolean fullyObservedMinute(long epochSeconds, long callAtMillis, long endAtMillis) {
        if (epochSeconds <= 0 || callAtMillis <= 0 || endAtMillis <= callAtMillis) return false;
        if (epochSeconds > Long.MAX_VALUE / 1000L) return false;
        long barStart = epochSeconds * 1000L;
        return barStart >= callAtMillis && barStart <= endAtMillis - 60_000L;
    }

    static boolean observedHit(double observedHigh, double target) {
        return Double.isFinite(observedHigh) && Double.isFinite(target)
                && target > 0 && observedHigh + 1e-7 >= target;
    }
}
