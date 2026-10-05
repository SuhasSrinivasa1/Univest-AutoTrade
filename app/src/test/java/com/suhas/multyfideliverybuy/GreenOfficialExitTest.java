package com.suhas.multyfideliverybuy;

import org.junit.Test;

import static org.junit.Assert.*;

public class GreenOfficialExitTest {
    private GrowwClient.ExitSnapshot snap(double avg, double bid, double ltp, double lower, double upper) {
        GrowwClient.PositionSnapshot holding =
                new GrowwClient.PositionSnapshot(true, 42, avg, "holding OK");
        GrowwClient.QuoteSnapshot quote =
                new GrowwClient.QuoteSnapshot(true, ltp, bid, ltp, 100, 100,
                        upper, lower, 100000, "quote OK");
        return new GrowwClient.ExitSnapshot(true, holding, quote,
                bid > 0 ? bid : ltp, "snapshot");
    }

    @Test public void greenExitRequiresExecutablePriceAboveBrokerAverageByTick() {
        GrowwClient.GreenSellPlan p = GrowwClient.greenSellPlan(
                snap(680.00, 687.40, 687.50, 645.55, 713.45), 0.05);
        assertTrue(p.canSell);
        assertTrue(p.limitPrice >= 680.05 - 1e-9);
        assertTrue(p.limitPrice <= 687.40 + 1e-9);
    }

    @Test public void redOfficialExitIsDeferredNotSold() {
        GrowwClient.GreenSellPlan p = GrowwClient.greenSellPlan(
                snap(680.00, 674.00, 674.10, 645.55, 713.45), 0.05);
        assertFalse(p.canSell);
        assertTrue(p.reason.contains("Deferred"));
    }

    @Test public void tinyGreenBelowOneTickIsDeferred() {
        GrowwClient.GreenSellPlan p = GrowwClient.greenSellPlan(
                snap(680.00, 680.02, 680.02, 645.55, 713.45), 0.05);
        assertFalse(p.canSell);
        assertEquals(680.05, p.minimumGreenPrice, 0.0001);
    }

    @Test public void missingBrokerAverageFailsClosed() {
        GrowwClient.GreenSellPlan p = GrowwClient.greenSellPlan(
                snap(0.0, 700.00, 700.00, 645.55, 713.45), 0.05);
        assertFalse(p.canSell);
        assertTrue(p.reason.toLowerCase().contains("average"));
    }

    @Test public void lowerCircuitAndGreenFloorBothRespected() {
        GrowwClient.GreenSellPlan p = GrowwClient.greenSellPlan(
                snap(680.00, 695.00, 695.10, 690.00, 713.45), 0.05);
        assertTrue(p.canSell);
        assertTrue(p.limitPrice >= 690.00 - 1e-9);
        assertTrue(p.limitPrice >= 680.05 - 1e-9);
    }
}
