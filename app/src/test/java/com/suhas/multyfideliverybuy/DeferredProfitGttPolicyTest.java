package com.suhas.multyfideliverybuy;

import org.junit.Test;
import static org.junit.Assert.*;

public class DeferredProfitGttPolicyTest {
    @Test public void halfPercentMeansNetAfterChargesNotAStopLoss() {
        double target = DeferredProfitGttPolicy.targetPrice(100.0, 100, 0.05);
        assertTrue(target > 100.50);
        assertTrue(DeliveryNetTarget.estimatedNetProfit(100, 100, target) >= 50.0 - 0.01);
    }
    @Test public void invalidHoldingCannotProduceSellPrice() {
        assertEquals(0, DeferredProfitGttPolicy.targetPrice(0, 100, 0.05), 0.00001);
        assertEquals(0, DeferredProfitGttPolicy.targetPrice(100, 0, 0.05), 0.00001);
    }
    @Test public void alreadyTriggeredIsNeverAConfirmedCancellation() {
        assertTrue(DeferredProfitGttPolicy.isTriggered("TRIGGERED"));
        assertTrue(DeferredProfitGttPolicy.isTriggered("COMPLETED"));
        assertFalse(DeferredProfitGttPolicy.isCancelled("TRIGGERED"));
        assertTrue(DeferredProfitGttPolicy.isCancelled("CANCELLED"));
        assertTrue(DeferredProfitGttPolicy.isCancelled("EXPIRED"));
        assertFalse(DeferredProfitGttPolicy.isCancelled("ACTIVE"));
    }
}
