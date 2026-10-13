package com.suhas.multyfideliverybuy;

import org.junit.Test;
import static org.junit.Assert.*;

public class ForecastTargetMathTest {
    @Test public void panoramaTargetUsesFeesInsteadOfUnseenModelZone() {
        double target = ForecastTargetMath.netPrice(60.13, 498, 0.05, 0.5);
        assertTrue(target > 60.13 && target < 62.0);
        assertTrue(ForecastTargetMath.observedHit(61.30, target));
        assertFalse(ForecastTargetMath.observedHit(61.30, 70.65));
    }

    @Test public void zeroAndInvalidTargetsNeverRegisterAsHits() {
        assertEquals(0, ForecastTargetMath.netPrice(0, 10, 0.05, 1), 0.00001);
        assertEquals(0, ForecastTargetMath.netPrice(50, 0, 0.05, 1), 0.00001);
        assertEquals(0, ForecastTargetMath.netPrice(50, 10, 0.05, 0), 0.00001);
        assertFalse(ForecastTargetMath.observedHit(100, 0));
        assertFalse(ForecastTargetMath.observedHit(Double.NaN, 61));
    }
}
