package com.suhas.multyfideliverybuy;

import java.util.Locale;

/** Dedicated broker-hosted recovery target for an official Univest exit.
 * +0.5% means net profit AFTER estimated NSE delivery charges, never a stop-loss.
 */
final class DeferredProfitGttPolicy {
    private DeferredProfitGttPolicy() {}

    static double targetPrice(double average, int quantity, double tickSize) {
        if (!Double.isFinite(average) || average <= 0 || quantity <= 0
                || !Double.isFinite(tickSize) || tickSize <= 0) return 0;
        return DeliveryNetTarget.targetPriceForNetProfit(
                average, quantity, tickSize, average * quantity * 0.005);
    }

    static boolean isCancelled(String status) {
        String v = norm(status);
        return v.contains("CANCEL") || v.contains("EXPIRE") || v.contains("REJECT") || v.contains("FAILED");
    }

    static boolean isTriggered(String status) {
        String v = norm(status);
        return v.contains("TRIGGER") || v.contains("EXECUT") || v.contains("COMPLET") || v.contains("FILLED");
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.US);
    }
}
