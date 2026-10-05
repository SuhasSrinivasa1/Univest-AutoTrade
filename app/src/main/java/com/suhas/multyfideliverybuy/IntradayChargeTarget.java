package com.suhas.multyfideliverybuy;

/** Groww/NSE CASH MIS charge estimator used for the Multyfi close-call short OCO. */
final class IntradayChargeTarget {
    private static final double BROKERAGE_RATE = 0.001;
    private static final double BROKERAGE_CAP = 20.0;
    private static final double BROKERAGE_MIN = 5.0;
    private static final double STT_SELL = 0.00025;
    private static final double STAMP_BUY = 0.00003;
    private static final double NSE_TXN = 0.0000297;
    private static final double SEBI = 0.000001;
    private static final double IPFT = 0.000001;
    private static final double GST = 0.18;

    private IntradayChargeTarget() {}

    static double netPnlForShort(double shortSellPrice, int qty, double coverBuyPrice) {
        if (!(shortSellPrice > 0) || !(coverBuyPrice > 0) || qty <= 0) return Double.NEGATIVE_INFINITY;
        double sellValue = shortSellPrice * qty;
        double buyValue = coverBuyPrice * qty;
        return sellValue - buyValue - sellCharges(sellValue) - buyCharges(buyValue);
    }

    static double targetPriceForNetProfit(double shortSellPrice, int qty, double tickSize, double desiredProfit) {
        double tick = tickSize > 0 ? tickSize : 0.05;
        desiredProfit = Math.max(0.0, desiredProfit);
        double p = shortSellPrice;
        int guard = 0;
        while (guard++ < 200000 && netPnlForShort(shortSellPrice, qty, p) < desiredProfit) p -= tick;
        p = Math.max(tick, Math.floor((p / tick) + 1e-9) * tick);
        return Math.round(p * 10000.0) / 10000.0;
    }

    static double stopTriggerForMaxLoss(double shortSellPrice, int qty, double tickSize, double maxLoss) {
        double tick = tickSize > 0 ? tickSize : 0.05;
        maxLoss = Math.max(0.0, maxLoss);
        double p = shortSellPrice;
        int guard = 0;
        while (guard++ < 200000) {
            double next = p + tick;
            if (netPnlForShort(shortSellPrice, qty, next) < -maxLoss) break;
            p = next;
        }
        // Trigger one tick before the estimated all-in loss would exceed the configured ceiling.
        return Math.round(Math.max(shortSellPrice + tick, p) * 10000.0) / 10000.0;
    }

    static double sellCharges(double value) {
        double brokerage = brokerage(value), exchange = value * NSE_TXN, sebi = value * SEBI, ipft = value * IPFT;
        double gst = GST * (brokerage + exchange + sebi + ipft);
        return brokerage + value * STT_SELL + exchange + sebi + ipft + gst;
    }

    static double buyCharges(double value) {
        double brokerage = brokerage(value), exchange = value * NSE_TXN, sebi = value * SEBI, ipft = value * IPFT;
        double gst = GST * (brokerage + exchange + sebi + ipft);
        return brokerage + value * STAMP_BUY + exchange + sebi + ipft + gst;
    }

    private static double brokerage(double value) {
        if (!(value > 0)) return 0.0;
        double raw = Math.min(BROKERAGE_CAP, value * BROKERAGE_RATE);
        if (raw >= BROKERAGE_MIN) return raw;
        return Math.min(BROKERAGE_MIN, value * 0.025);
    }
}
