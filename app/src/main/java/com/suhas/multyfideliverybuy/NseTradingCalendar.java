package com.suhas.multyfideliverybuy;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * NSE CASH trading-session calendar.
 * 2026 holidays are embedded from NSE's published equity holiday calendar.
 * Unknown future years fall back to weekday handling and are explicitly labelled.
 */
final class NseTradingCalendar {
    private static final TimeZone IST = TimeZone.getTimeZone("Asia/Kolkata");
    private static final Map<String,String> HOLIDAYS_2026;

    static {
        Map<String,String> h = new LinkedHashMap<>();
        h.put("20260115", "Municipal Corporation Election - Maharashtra");
        h.put("20260126", "Republic Day");
        h.put("20260303", "Holi");
        h.put("20260326", "Shri Ram Navami");
        h.put("20260331", "Shri Mahavir Jayanti");
        h.put("20260403", "Good Friday");
        h.put("20260414", "Dr. Baba Saheb Ambedkar Jayanti");
        h.put("20260501", "Maharashtra Day");
        h.put("20260528", "Bakri Id");
        h.put("20260626", "Muharram");
        h.put("20260914", "Ganesh Chaturthi");
        h.put("20261002", "Mahatma Gandhi Jayanti");
        h.put("20261020", "Dussehra");
        h.put("20261108", "Diwali Laxmi Pujan / Muhurat Trading special session");
        h.put("20261110", "Diwali-Balipratipada");
        h.put("20261124", "Prakash Gurpurb Sri Guru Nanak Dev");
        h.put("20261225", "Christmas");
        HOLIDAYS_2026 = Collections.unmodifiableMap(h);
    }

    private NseTradingCalendar() {}

    static String dayKey(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd", Locale.US);
        f.setTimeZone(IST);
        return f.format(new Date(ms));
    }

    static boolean isWeekend(long ms) {
        Calendar c = Calendar.getInstance(IST);
        c.setTimeInMillis(ms);
        int d = c.get(Calendar.DAY_OF_WEEK);
        return d == Calendar.SATURDAY || d == Calendar.SUNDAY;
    }

    static boolean isEmbeddedHoliday(long ms) {
        return HOLIDAYS_2026.containsKey(dayKey(ms));
    }

    static String holidayName(long ms) {
        if (isWeekend(ms)) return "Weekend";
        String h = HOLIDAYS_2026.get(dayKey(ms));
        return h == null ? "" : h;
    }

    static boolean calendarCoverageKnown(long ms) {
        Calendar c = Calendar.getInstance(IST);
        c.setTimeInMillis(ms);
        return c.get(Calendar.YEAR) == 2026;
    }

    static boolean isTradingDay(long ms) {
        if (isWeekend(ms)) return false;
        return !isEmbeddedHoliday(ms);
    }

    static int minuteOfDay(long ms) {
        Calendar c = Calendar.getInstance(IST);
        c.setTimeInMillis(ms);
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    static boolean isRegularMarketOpen(long ms) {
        if (!isTradingDay(ms)) return false;
        int m = minuteOfDay(ms);
        return m >= 9 * 60 + 15 && m < 15 * 60 + 30;
    }

    static long nextTradingDay(long fromMs) {
        Calendar c = Calendar.getInstance(IST);
        c.setTimeInMillis(fromMs);
        c.set(Calendar.HOUR_OF_DAY, 9);
        c.set(Calendar.MINUTE, 15);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= fromMs || !isTradingDay(c.getTimeInMillis())) {
            do { c.add(Calendar.DAY_OF_MONTH, 1); }
            while (!isTradingDay(c.getTimeInMillis()));
        }
        return c.getTimeInMillis();
    }

    static String nextTradingDayKey(long fromMs) {
        return dayKey(nextTradingDay(fromMs));
    }

    static int tradingSessionsElapsed(long startMs, long endMs) {
        if (startMs <= 0 || endMs <= startMs) return 0;
        Calendar c = Calendar.getInstance(IST);
        c.setTimeInMillis(startMs);
        c.set(Calendar.HOUR_OF_DAY, 12);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        String startKey = dayKey(startMs);
        int sessions = 0;
        while (c.getTimeInMillis() <= endMs && sessions < 400) {
            if (!dayKey(c.getTimeInMillis()).equals(startKey) && isTradingDay(c.getTimeInMillis())) sessions++;
            c.add(Calendar.DAY_OF_MONTH, 1);
        }
        return sessions;
    }

    static String describe(long ms) {
        if (isTradingDay(ms)) return calendarCoverageKnown(ms) ? "NSE trading day" : "Weekday (holiday calendar not embedded for this year)";
        return holidayName(ms);
    }
}
