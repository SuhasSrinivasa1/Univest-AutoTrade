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

    @Test public void forecastWaitsFiveTradingSessionsBeforeCallingAMiss() {
        long call = ist(2026, 10, 8, 10, 0); // Thursday
        assertEquals(5, ForecastPolicy.FORECAST_TARGET_MAX_SESSIONS);
        assertFalse(ForecastPolicy.horizonExpired(call, ist(2026, 10, 8, 15, 31))); // day 1
        assertFalse(ForecastPolicy.horizonExpired(call, ist(2026, 10, 12, 15, 31))); // day 3
        assertFalse(ForecastPolicy.horizonExpired(call, ist(2026, 10, 14, 15, 0))); // day 5
        assertTrue(ForecastPolicy.horizonExpired(call, ist(2026, 10, 14, 15, 31)));
        assertEquals("20261014", ForecastPolicy.expirySessionKey(call));
    }

    @Test public void weekendDoesNotConsumeExtraTradingSession() {
        long call = ist(2026, 10, 9, 10, 0); // Friday
        assertFalse(ForecastPolicy.horizonExpired(call, ist(2026, 10, 11, 20, 0)));
        assertFalse(ForecastPolicy.horizonExpired(call, ist(2026, 10, 13, 15, 31)));
        assertFalse(ForecastPolicy.horizonExpired(call, ist(2026, 10, 15, 15, 0)));
        assertTrue(ForecastPolicy.horizonExpired(call, ist(2026, 10, 15, 15, 31)));
    }

    @Test public void originalThreeSessionBenchmarkAndFormulaHorizonRemainUnchanged() {
        long call = ist(2026, 10, 8, 10, 0);
        assertFalse(ForecastPolicy.horizonExpired(call, ist(2026, 10, 12, 15, 0),
                UnivestBenchmark.PRIMARY_MAX_SESSIONS));
        assertTrue(ForecastPolicy.horizonExpired(call, ist(2026, 10, 12, 15, 31),
                UnivestBenchmark.PRIMARY_MAX_SESSIONS));
        // Forecast is still not a failure after this original 3-session benchmark expires.
        assertFalse(ForecastPolicy.horizonExpired(call, ist(2026, 10, 12, 15, 31)));
    }
}
