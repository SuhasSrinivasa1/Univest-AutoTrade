package com.suhas.multyfideliverybuy;

/** Conservative NSE equity-delivery charge estimator for broker-hosted GTT targets. */
final class DeliveryNetTarget {
    static final String CHARGE_MODEL_VERSION = "GROWW_NSE_DELIVERY_2026_10_03";
    private static final double BROKERAGE_RATE = 0.001;
    private static final double BROKERAGE_CAP = 20.0;
    private static final double BROKERAGE_MIN = 5.0;
    private static final double STT_DELIVERY = 0.001;
    private static final double STAMP_BUY = 0.00015;
    private static final double NSE_TXN = 0.0000297;
    private static final double SEBI = 0.000001;
    private static final double IPFT = 0.000001;
    private static final double GST = 0.18;
    private static final double DP_SELL_BASE = 20.0;
    private static final double ROUNDING_SAFETY = 2.0;

    private DeliveryNetTarget() {}

    /** Backward-compatible +1% net target based on executed buy value. */
    static double targetPrice(double averageBuyPrice, int quantity, double tickSize) {
        if (!(averageBuyPrice > 0) || quantity <= 0) return 0.0;
        return targetPriceForNetProfit(averageBuyPrice, quantity, tickSize, averageBuyPrice * quantity * 0.01);
    }

    /** Price required to leave at least desiredNetProfit after estimated delivery charges. */
    static double targetPriceForNetProfit(double averageBuyPrice, int quantity, double tickSize, double desiredNetProfit) {
        if (!(averageBuyPrice > 0) || quantity <= 0) return 0.0;
        desiredNetProfit = Math.max(0.0, desiredNetProfit);
        double buyValue = averageBuyPrice * quantity;
        double buyCharges = buyCharges(buyValue);
        double sellValue = buyValue + desiredNetProfit + buyCharges + sellCharges(buyValue * 1.02) + ROUNDING_SAFETY;
        for (int i = 0; i < 12; i++) {
            sellValue = buyValue + desiredNetProfit + buyCharges + sellCharges(sellValue) + ROUNDING_SAFETY;
        }
        double tick = tickSize > 0 ? tickSize : 0.05;
        double target = Math.ceil((sellValue / quantity) / tick - 1e-9) * tick;
        target = Math.round(target * 10000.0) / 10000.0;
        int guard = 0;
        while (estimatedNetProfit(averageBuyPrice, quantity, target) + 1e-7 < desiredNetProfit && guard++ < 1000) {
            target = Math.round((target + tick) * 10000.0) / 10000.0;
        }
        return target;
    }

    static double breakEvenPrice(double averageBuyPrice, int quantity, double tickSize) {
        return targetPriceForNetProfit(averageBuyPrice, quantity, tickSize, 0.0);
    }

    static double estimatedNetProfit(double averageBuyPrice, int quantity, double sellPrice) {
        if (!(averageBuyPrice > 0) || !(sellPrice > 0) || quantity <= 0) return Double.NEGATIVE_INFINITY;
        double buyValue = averageBuyPrice * quantity;
        double sellValue = sellPrice * quantity;
        return sellValue - buyValue - buyCharges(buyValue) - sellCharges(sellValue);
    }

    static double buyCharges(double value) {
        double brokerage = brokerage(value);
        double exchange = value * NSE_TXN;
        double sebi = value * SEBI;
        double ipft = value * IPFT;
        double gst = GST * (brokerage + exchange + sebi + ipft);
        return brokerage + (value * STT_DELIVERY) + (value * STAMP_BUY) + exchange + sebi + ipft + gst;
    }

    static double sellCharges(double value) {
        double brokerage = brokerage(value);
        double exchange = value * NSE_TXN;
        double sebi = value * SEBI;
        double ipft = value * IPFT;
        double dp = value >= 100.0 ? DP_SELL_BASE : 3.50;
        double gst = GST * (brokerage + exchange + sebi + ipft + dp);
        return brokerage + (value * STT_DELIVERY) + exchange + sebi + ipft + dp + gst;
    }

    private static double brokerage(double value) {
        if (!(value > 0)) return 0.0;
        double raw = Math.min(BROKERAGE_CAP, value * BROKERAGE_RATE);
        if (raw >= BROKERAGE_MIN) return raw;
        return Math.min(BROKERAGE_MIN, value * 0.025);
    }
}
