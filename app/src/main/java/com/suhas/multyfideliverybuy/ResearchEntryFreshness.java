package com.suhas.multyfideliverybuy;

/** A final, quote-time guard against recommending the already-completed spike.
 * This does not change the mathematical Forecast / Formula score. */
final class ResearchEntryFreshness {
    static final long MAX_QUOTE_ROUND_TRIP_MS = 8_000L;
    static final double MAX_UPWARD_JUMP_PCT = 0.65;

    private ResearchEntryFreshness() {}

    static String reject(double evaluatedAtPrice, double latestPrice,
                         double zoneLow, double chaseLimit, long quoteRoundTripMs) {
        if (!(latestPrice > 0) || !Double.isFinite(latestPrice)) return "Fresh price unavailable";
        if (quoteRoundTripMs < 0 || quoteRoundTripMs > MAX_QUOTE_ROUND_TRIP_MS)
            return "Fresh price request exceeded time budget";
        if (!(zoneLow > 0 && chaseLimit > zoneLow)) return "Entry zone invalid";
        if (latestPrice < zoneLow) return "Stock slipped below the buy zone";
        if (latestPrice > chaseLimit) return "Spike already exceeded the chase ceiling";
        if (!(evaluatedAtPrice > 0)) return "Previous price unavailable";
        double jumpPct = 100.0 * (latestPrice / evaluatedAtPrice - 1.0);
        if (jumpPct > MAX_UPWARD_JUMP_PCT)
            return "Price jumped during validation: " + String.format(java.util.Locale.US, "%.2f", jumpPct) + "%";
        return "";
    }
}