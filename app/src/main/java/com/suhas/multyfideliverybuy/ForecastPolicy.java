package com.suhas.multyfideliverybuy;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Pure Forecast lifecycle policy.
 * Session 1 is the trigger session; Forecast target calls only remain open through the originating NSE session.
 * Terminal calls are archived from the live Forecast table on the next trading day.
 */
final class ForecastPolicy {
    private static final TimeZone IST = TimeZone.getTimeZone("Asia/Kolkata");
    static final int FORECAST_TARGET_MAX_SESSIONS = 1;

    private ForecastPolicy() {}

    static boolean horizonExpired(long entryAt, long now) {
        return horizonExpired(entryAt, now, FORECAST_TARGET_MAX_SESSIONS);
    }

    /** Explicit separate horizon for the unchanged 3-session official benchmark / Formula. */
    static boolean horizonExpired(long entryAt, long now, int allowedSessions) {
        if (entryAt <= 0 || now < entryAt || allowedSessions < 1) return false;
        int sessions = Math.max(1, UnivestBenchmark.inclusiveTradingSessions(entryAt, now));
        if (sessions > allowedSessions) return true;
        if (sessions < allowedSessions) return false;

        // Only the originating regular NSE day is eligible. Intraday closure is tracked separately.
        if (!NseTradingCalendar.isTradingDay(now)) return true;
        return NseTradingCalendar.minuteOfDay(now) >= 15 * 60 + 30;
    }

    static boolean liveCallVisible(long entryAt, long now) {
        return entryAt > 0 && !horizonExpired(entryAt, now);
    }

    static String openCallState(boolean liveBrokerPosition, long entryAt, long now) {
        if (horizonExpired(entryAt, now)) return "MISSED";
        if (liveBrokerPosition) return "BOUGHT";
        return NseTradingCalendar.isRegularMarketOpen(now) ? "BUY NOW" : "MARKET CLOSED";
    }

    static String expirySessionKey(long entryAt) {
        if (entryAt <= 0) return "";
        Calendar day = Calendar.getInstance(IST);
        day.setTimeInMillis(entryAt);
        day.set(Calendar.HOUR_OF_DAY, 12);
        day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0);
        day.set(Calendar.MILLISECOND, 0);

        int seen = 0;
        int guard = 0;
        while (guard++ < 400) {
            long t = day.getTimeInMillis();
            if (NseTradingCalendar.isTradingDay(t)) {
                seen++;
                if (seen >= FORECAST_TARGET_MAX_SESSIONS) return NseTradingCalendar.dayKey(t);
            }
            day.add(Calendar.DAY_OF_MONTH, 1);
        }
        return "";
    }
}
