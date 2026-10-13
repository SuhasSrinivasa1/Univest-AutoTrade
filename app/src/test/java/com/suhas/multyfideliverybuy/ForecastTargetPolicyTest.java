package com.suhas.multyfideliverybuy;

import org.junit.Test;
import static org.junit.Assert.*;

public class ForecastTargetPolicyTest {
    @Test public void noOfficialHistoryStartsAtOnePercent() {
        assertEquals(1.0, ForecastTargetPolicy.nextNetPct(0, 0, 0), 0.00001);
        assertEquals(1.0, ForecastTargetPolicy.nextNetPct(0, 0, Double.NaN), 0.00001);
    }
    @Test public void growingUnivestAverageRaisesButNeverLowersTargetPolicy() {
        double pct = ForecastTargetPolicy.nextNetPct(1.0, 6, 1.6);
        assertEquals(1.6, pct, 0.00001);
        pct = ForecastTargetPolicy.nextNetPct(pct, 10, 2.15);
        assertEquals(2.15, pct, 0.00001);
        pct = ForecastTargetPolicy.nextNetPct(pct, 12, 0.3);
        assertEquals(2.15, pct, 0.00001);
        assertEquals(2.15, ForecastTargetPolicy.nextNetPct(pct, 0, 0), 0.00001);
    }
    @Test public void negativeOrInvalidAverageDoesNotFalsifyTarget() {
        assertEquals(1.0, ForecastTargetPolicy.nextNetPct(-8, 5, -2), 0.00001);
        assertEquals(1.0, ForecastTargetPolicy.nextNetPct(Double.NaN, 5, Double.NaN), 0.00001);
    }
    @Test public void riskGateRejectsNewsBearMarketAndUnattainablePrice() {
        assertTrue(ForecastSignalGate.rejectReason("NIFTY:RISK_OFF", -1, 0, 0, 60, 61, 2, false, 0).contains("Bearish"));
        assertTrue(ForecastSignalGate.rejectReason("NIFTY:BULLISH_TREND", 1, 0, 2, 60, 61, 2, false, 0).contains("news"));
        assertTrue(ForecastSignalGate.rejectReason("NIFTY:BULLISH_TREND", 1, 2, 0, 60, 70, 2, false, 0).contains("ATR"));
        assertTrue(ForecastSignalGate.rejectReason("NIFTY:BULLISH_TREND", 1, 2, 0, 60, 61, 2, true, 0).contains("champion"));
        assertEquals("", ForecastSignalGate.rejectReason("NIFTY:BULLISH_TREND", 1, 2, 0, 60, 61, 2, true, 2));
    }
}
