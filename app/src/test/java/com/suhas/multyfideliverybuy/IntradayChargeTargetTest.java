package com.suhas.multyfideliverybuy;

import org.junit.Test;
import static org.junit.Assert.*;

public class IntradayChargeTargetTest {
    @Test public void shortTargetClearsHalfPercentBudgetNet() {
        double entry=500.0; int qty=200; double desired=500.0;
        double target=IntradayChargeTarget.targetPriceForNetProfit(entry,qty,0.05,desired);
        assertTrue(target < entry);
        assertTrue(IntradayChargeTarget.netPnlForShort(entry,qty,target) >= desired);
    }
    @Test public void stopGuardIsAboveShortEntryAndNearLossCeiling() {
        double entry=500.0; int qty=200; double maxLoss=1000.0;
        double stop=IntradayChargeTarget.stopTriggerForMaxLoss(entry,qty,0.05,maxLoss);
        assertTrue(stop > entry);
        assertTrue(IntradayChargeTarget.netPnlForShort(entry,qty,stop) >= -maxLoss - 0.01);
    }
}
