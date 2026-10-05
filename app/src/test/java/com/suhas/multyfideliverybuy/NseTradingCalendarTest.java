package com.suhas.multyfideliverybuy;

import org.junit.Test;
import java.util.Calendar;
import java.util.TimeZone;
import static org.junit.Assert.*;

public class NseTradingCalendarTest {
    private long ist(int year, int month, int day, int hour, int minute) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"));
        c.set(year, month - 1, day, hour, minute, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    @Test public void gandhiJayantiIsHolidayAndNextSessionSkipsWeekend() {
        long holiday = ist(2026, 10, 2, 10, 0);
        assertFalse(NseTradingCalendar.isTradingDay(holiday));
        assertTrue(NseTradingCalendar.holidayName(holiday).contains("Mahatma Gandhi"));
        assertEquals("20261005", NseTradingCalendar.nextTradingDayKey(holiday));
    }

    @Test public void regularSessionRecognizesMarketHours() {
        assertTrue(NseTradingCalendar.isRegularMarketOpen(ist(2026, 10, 1, 9, 30)));
        assertFalse(NseTradingCalendar.isRegularMarketOpen(ist(2026, 10, 1, 15, 31)));
    }
}
