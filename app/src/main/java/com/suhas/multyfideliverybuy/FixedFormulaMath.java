package com.suhas.multyfideliverybuy;

/** FORMULA V1.0: immutable analyst-defined selection equation, independent of Forecast learning.
 * D35 + I25 + P15 + M10 + N5 + F5 + X5. Missing slots are NaN,
 * NOT a fabricated neutral/positive score. Weights and gates are fixed.
 */
final class FixedFormulaMath {
    static final String VERSION = "FORMULA_V1_20261015";
    static final int[] WEIGHTS = {35, 25, 15, 10, 5, 5, 5};
    static final String[] FACTORS = {"D","I","P","M","N","F","X"};
    static final double MIN_SCORE = 72.0;
    static final double TARGET_NET_PCT = 1.0;
    static final int MIN_DAILY_BEST = 80;
    static final int MIN_CONSENSUS = 2;
    static final int MIN_CONFIDENCE = 60;
    static final double MAX_TARGET_ATR = 2.2;
    private FixedFormulaMath() {}

    static double score(double[] groups) {
        if (groups == null || groups.length != WEIGHTS.length) return 0;
        double total = 0, available = 0, missing = 0;
        for (int i = 0; i < WEIGHTS.length; i++) {
            double g = groups[i];
            if (!Double.isFinite(g)) { missing += WEIGHTS[i]; continue; }
            total += Math.max(0, Math.min(100, g)) * WEIGHTS[i];
            available += WEIGHTS[i];
        }
        if (available == 0) return 0;
        double s = total / available - 15.0 * missing / 100.0;
        return Math.max(0, Math.min(100, Math.round(s * 10.0) / 10.0));
    }

    static boolean qualified(double score, int bestDaily, int consensus, int confidence,
                             double entry, double target, double dailyAtr) {
        return Double.isFinite(score) && score >= MIN_SCORE
                && bestDaily >= MIN_DAILY_BEST && consensus >= MIN_CONSENSUS
                && confidence >= MIN_CONFIDENCE && Double.isFinite(entry) && entry > 0
                && Double.isFinite(target) && target > entry
                && (!Double.isFinite(dailyAtr) || dailyAtr <= 0
                    || target - entry <= MAX_TARGET_ATR * dailyAtr);
    }
}
