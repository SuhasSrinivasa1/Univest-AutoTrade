package com.suhas.multyfideliverybuy;

import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

import static org.junit.Assert.*;

public class ResearchHardeningTest {
    private long ist(int y, int m, int d, int h, int min) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"));
        c.set(y, m - 1, d, h, min, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    @Test public void historicalTimestampParserAcceptsSecondsMillisAndIstText() {
        long expected = ist(2026, 10, 1, 9, 15) / 1000L;
        assertEquals(expected, GrowwClient.parseEpochSeconds(expected));
        assertEquals(expected, GrowwClient.parseEpochSeconds(expected * 1000L));
        assertEquals(expected, GrowwClient.parseEpochSeconds("2026-10-01 09:15:00"));
    }

    @Test public void officialLearningDailyCutoffIsBeforeSignalDay() {
        long signal = ist(2026, 10, 1, 10, 30);
        assertEquals(ist(2026, 10, 1, 0, 0), ResearchEngine.completedDailyCutoff(signal));
        assertTrue(ResearchEngine.completedDailyCutoff(signal) < signal);
    }

    @Test public void tradingSessionHorizonSkipsHolidayAndWeekend() {
        long start = ist(2026, 10, 1, 10, 0);
        long monday = ist(2026, 10, 5, 15, 40);
        assertEquals(1, NseTradingCalendar.tradingSessionsElapsed(start, monday));
    }
}
