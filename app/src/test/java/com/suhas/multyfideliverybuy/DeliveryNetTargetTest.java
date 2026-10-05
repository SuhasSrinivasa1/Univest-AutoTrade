package com.suhas.multyfideliverybuy;

import org.junit.Test;
import static org.junit.Assert.*;

public class DeliveryNetTargetTest {
    @Test public void targetClearsOnePercentNetForTypicalOneLakhBuy() {
        double avg = 500.0;
        int qty = 200;
        double target = DeliveryNetTarget.targetPrice(avg, qty, 0.05);
        double net = DeliveryNetTarget.estimatedNetProfit(avg, qty, target);
        assertTrue(target > avg * 1.01);
        assertTrue(net >= avg * qty * 0.01);
    }

    @Test public void targetUsesActualFillAndTick() {
        double avg = 1704.43;
        int qty = 58;
        double target = DeliveryNetTarget.targetPrice(avg, qty, 0.05);
        double units = target / 0.05;
        assertEquals(Math.rint(units), units, 1e-7);
        assertTrue(DeliveryNetTarget.estimatedNetProfit(avg, qty, target) >= avg * qty * 0.01);
    }
    @Test public void halfPercentBudgetTargetClearsRequestedRupees() {
        double avg=1000.0; int qty=99; double desired=500.0;
        double target=DeliveryNetTarget.targetPriceForNetProfit(avg,qty,0.05,desired);
        assertTrue(DeliveryNetTarget.estimatedNetProfit(avg,qty,target) >= desired);
    }
}
