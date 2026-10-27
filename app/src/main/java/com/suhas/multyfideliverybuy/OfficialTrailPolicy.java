package com.suhas.multyfideliverybuy;

/** Official Univest ONLY: gross price return, not brokerage-adjusted return.
 * +1.00% peak => +0.95% floor; +1.10% => +1.05%; +1.20% => +1.15%.
 * Peaks never decrease. This strategy does not define an exit before +1.00%.
 */
final class OfficialTrailPolicy {
    static final double ACTIVATE_PCT = 1.0;
    static final double INITIAL_FLOOR_PCT = .95;
    static final double STEP_PCT = .10;
    private OfficialTrailPolicy() {}

    static double gainPct(double filledEntry, double quote) {
        if (!(filledEntry > 0) || !(quote > 0) || !Double.isFinite(quote)) return Double.NaN;
        return 100.0 * (quote / filledEntry - 1.0);
    }

    static double floor(double previouslyArmedFloor, double observedPeakPct) {
        if (!Double.isFinite(observedPeakPct) || observedPeakPct + 1e-8 < ACTIVATE_PCT)
            return previouslyArmedFloor;
        int completedSteps = Math.max(0, (int) Math.floor((observedPeakPct - ACTIVATE_PCT + 1e-8) / STEP_PCT));
        double requested = INITIAL_FLOOR_PCT + completedSteps * STEP_PCT;
        return Math.max(previouslyArmedFloor, Math.round(requested * 100.0) / 100.0);
    }

    static double trigger(double filledEntry, double tick, double floorPct) {
        if (!(filledEntry > 0) || !(floorPct >= INITIAL_FLOOR_PCT)) return 0;
        double t = tick > 0 ? tick : .05;
        return Math.floor((filledEntry * (1.0 + floorPct / 100.0) + 1e-8) / t) * t;
    }
}