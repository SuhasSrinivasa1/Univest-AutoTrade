package com.suhas.multyfideliverybuy;

/**
 * Position management ONLY. Never applied to official Univest order flows or
 * FixedFormulaMath's selection formula.
 *
 * User's explicit examples define the percentages: once the estimated
 * fee-adjusted net gain reaches +0.60%, arm at +0.50%; thereafter protect
 * peak NET gain minus 0.10 percentage points, with floor never decreasing.
 */
final class GttProfitTrailPolicy {
    static final String VERSION = "CNC_GTT_NET_060_ACTIVATE_010_GAP_V1";
    static final double ACTIVATE_NET_PCT = 0.60;
    static final double FIRST_FLOOR_NET_PCT = 0.50;
    static final double PEAK_GAP_POINTS = 0.10;
    private GttProfitTrailPolicy() {}

    static double newFloor(double existingFloor, double peakNetPct) {
        if (!Double.isFinite(peakNetPct) || peakNetPct + 1e-9 < ACTIVATE_NET_PCT)
            return existingFloor;
        // A falling quote never lowers an already established broker stop.
        double proposed=Math.max(FIRST_FLOOR_NET_PCT, peakNetPct-PEAK_GAP_POINTS);
        proposed=Math.floor((proposed+1e-8)*1000.0)/1000.0;
        return Math.max(existingFloor,proposed);
    }

    static double netPct(double entry, int qty, double quote) {
        if (!(entry>0) || qty<1 || !(quote>0))return Double.NaN;
        return 100.0*DeliveryNetTarget.estimatedNetProfit(entry,qty,quote)/(entry*qty);
    }

    static double stopPrice(double entry, int qty, double tick, double floorNetPct) {
        if (!(entry>0) || qty<1 || !(floorNetPct>=FIRST_FLOOR_NET_PCT))return 0;
        return DeliveryNetTarget.targetPriceForNetProfit(entry,qty,tick,
                entry*qty*floorNetPct/100.0);
    }
}
