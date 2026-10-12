package com.suhas.multyfideliverybuy;

import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

import static org.junit.Assert.*;

public class ForecastPolicyTest {
    private static long ist(int y, int m, int d, int h, int min) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"));
        c.set(y, m - 1, d, h, min, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    @Test public void unboughtCallNeverSaysBuyNowAfterMarketClose() {
        long call = ist(2026, 10, 12, 10, 0);
        assertEquals("BUY NOW", ForecastPolicy.openCallState(false, call, ist(2026, 10, 12, 14, 0)));
        assertEquals("MARKET CLOSED", ForecastPolicy.openCallState(false, call, ist(2026, 10, 12, 20, 0)));
    }

    @Test public void thirdTradingSessionRemainsValidUntilClose() {
        long call = ist(2026, 10, 8, 10, 0);
        assertFalse(ForecastPolicy.horizonExpired(call, ist(2026, 10, 12, 15, 0)));
        assertTrue(ForecastPolicy.horizonExpired(call, ist(2026, 10, 12, 15, 31)));
        assertEquals("20261012", ForecastPolicy.expirySessionKey(call));
    }

    @Test public void weekendDoesNotConsumeExtraTradingSession() {
        long call = ist(2026, 10, 9, 10, 0);
        assertFalse(ForecastPolicy.horizonExpired(call, ist(2026, 10, 11, 20, 0)));
        assertFalse(ForecastPolicy.horizonExpired(call, ist(2026, 10, 13, 15, 0)));
        assertTrue(ForecastPolicy.horizonExpired(call, ist(2026, 10, 13, 15, 31)));
    }
}
