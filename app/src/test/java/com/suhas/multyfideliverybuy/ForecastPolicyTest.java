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

    @Test public void onceSessionEndsUnboughtCallIsAMissNotABuy() {
        long call = ist(2026, 10, 12, 10, 0);
        assertEquals("BUY NOW", ForecastPolicy.openCallState(false, call, ist(2026, 10, 12, 14, 0)));
        assertEquals("MISSED", ForecastPolicy.openCallState(false, call, ist(2026, 10, 12, 20, 0)));
    }

    @Test public void sameDayForecastExpiresAtNseCashClose() {
        long call = ist(2026, 10, 8, 10, 0);
        assertEquals(1, ForecastPolicy.FORECAST_TARGET_MAX_SESSIONS);
        assertFalse(ForecastPolicy.horizonExpired(call, ist(2026, 10, 8, 15, 29)));
        assertTrue(ForecastPolicy.horizonExpired(call, ist(2026, 10, 8, 15, 30)));
        assertEquals("20261008", ForecastPolicy.expirySessionKey(call));
    }

    @Test public void weekendCannotExtendAForecastTarget() {
        long call = ist(2026, 10, 9, 10, 0);
        assertFalse(ForecastPolicy.horizonExpired(call, ist(2026, 10, 9, 15, 0)));
        assertTrue(ForecastPolicy.horizonExpired(call, ist(2026, 10, 9, 15, 31)));
        assertTrue(ForecastPolicy.horizonExpired(call, ist(2026, 10, 11, 20, 0)));
    }

    @Test public void officialBenchmarkAndFixedFormulaStillUseThreeSessions() {
        long call = ist(2026, 10, 8, 10, 0);
        assertFalse(ForecastPolicy.horizonExpired(call, ist(2026, 10, 12, 15, 0),
                UnivestBenchmark.PRIMARY_MAX_SESSIONS));
        assertTrue(ForecastPolicy.horizonExpired(call, ist(2026, 10, 12, 15, 31),
                UnivestBenchmark.PRIMARY_MAX_SESSIONS));
        assertTrue("The SAME-DAY Forecast has already expired",
                ForecastPolicy.horizonExpired(call, ist(2026, 10, 12, 15, 0)));
    }
}
