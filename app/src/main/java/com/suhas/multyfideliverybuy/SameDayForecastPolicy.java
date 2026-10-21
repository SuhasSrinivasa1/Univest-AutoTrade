package com.suhas.multyfideliverybuy;

/**
 * Locked intraday Forecast strategy UNIVEST_DAY_V1.
 * Three independently measurable families inspired by Multify's organization,
 * not by its order-execution strategy. This does NOT alter FORMULA V1.
 */
final class SameDayForecastPolicy {
    static final String VERSION = "UNIVEST_DAY_V1_20261021";
    static final double AVERAGE_STEP_PCT = 0.5;
    static final int AVERAGE_LEVELS = 10; // -0.5%, -1.0%, ..., -5.0%
    static final int ENTRY_LAST_MINUTE_IST = 14 * 60; // leave time to close safely
    static final int EXIT_START_MINUTE_IST = 15 * 60 + 10;
    static final int EXIT_RETRY_END_MINUTE_IST = 15 * 60 + 29;
    private SameDayForecastPolicy() {}

    static double averagingTrigger(double originalEntry, int level) {
        if (!(originalEntry > 0) || level < 1 || level > AVERAGE_LEVELS) return Double.NaN;
        return originalEntry * (1.0 - AVERAGE_STEP_PCT * level / 100.0);
    }

    static boolean entryAllowed(long millis) {
        return NseTradingCalendar.isRegularMarketOpen(millis)
                && NseTradingCalendar.minuteOfDay(millis) < ENTRY_LAST_MINUTE_IST
                && NseTradingCalendar.calendarCoverageKnown(millis);
    }

    static boolean closingWindow(long millis) {
        return NseTradingCalendar.isTradingDay(millis)
                && NseTradingCalendar.minuteOfDay(millis) >= EXIT_START_MINUTE_IST
                && NseTradingCalendar.minuteOfDay(millis) <= EXIT_RETRY_END_MINUTE_IST;
    }

    static boolean sameSession(long a, long b) {
        return a > 0 && b >= a && NseTradingCalendar.dayKey(a).equals(NseTradingCalendar.dayKey(b));
    }

    static final class Vote {
        final double momentum, participation, structure, breadth;
        final int positiveFamilies;
        final boolean admissible;
        final String reason;
        Vote(double m, double p, double s, double b, int positive, boolean eligible, String reason) {
            momentum=m;participation=p;structure=s;breadth=b;
            positiveFamilies=positive;admissible=eligible;this.reason=reason;
        }
    }

    /**
     * Three score families on AVAILABLE observations. breadth is a sampled
     * research-universe advance ratio in [0,1], or NaN (unobserved), NOT NSE breadth.
     * No claim that these are Univest's proprietary weights.
     */
    static Vote evaluate(double ret5, double rsi14, double rvol, double price,
                         double vwap, double spreadPct, double closeVsSma20Pct,
                         double dailyAtrPct, String marketRegime, double sampleBreadth) {
        if (!(price > 0) || !(vwap > 0) || !Double.isFinite(ret5)
                || !Double.isFinite(rsi14) || !Double.isFinite(rvol))
            return new Vote(0,0,0,Double.NaN,0,false,"Missing fresh intraday observations");
        double m = 0;
        if (ret5 > 0 && ret5 <= 2.5) m += 40;
        if (rsi14 >= 52 && rsi14 <= 75) m += 35;
        if (price >= vwap) m += 25;
        double p = 0;
        if (rvol >= 1.25) p += 45;
        else if (rvol >= 0.85) p += 30;
        if (Double.isFinite(spreadPct) && spreadPct >= 0 && spreadPct <= 0.40) p += 30;
        if (Double.isFinite(dailyAtrPct) && dailyAtrPct >= 0.5 && dailyAtrPct <= 7.0) p += 25;
        double s = 0;
        if (price >= vwap) s += 40;
        if (Double.isFinite(closeVsSma20Pct) && closeVsSma20Pct > -1.5 && closeVsSma20Pct < 6.0)
            s += 35;
        if (rsi14 >= 48 && rsi14 <= 80) s += 25;
        int pos = (m >= 65?1:0) + (p >= 55?1:0) + (s >= 65?1:0);
        boolean adverse = marketRegime != null && marketRegime.contains("RISK_OFF");
        boolean relativeStrength = ret5 >= 0.35 && rvol >= 1.25 && m >= 85;
        if (adverse && !relativeStrength)
            return new Vote(m,p,s,sampleBreadth,pos,false,"Risk-off market needs verified countertrend strength");
        if (Double.isFinite(sampleBreadth) && sampleBreadth < 0.25 && !relativeStrength)
            return new Vote(m,p,s,sampleBreadth,pos,false,"Very weak observed research-universe breadth without relative strength");
        if (pos < 2) return new Vote(m,p,s,sampleBreadth,pos,false,"Fewer than two bullish evidence families");
        if (rvol < 0.85) return new Vote(m,p,s,sampleBreadth,pos,false,"Low trading participation");
        if (Double.isFinite(spreadPct) && (spreadPct < 0 || spreadPct > 0.5))
            return new Vote(m,p,s,sampleBreadth,pos,false,"Insufficient liquidity / excessive bid-ask spread");
        if (Double.isFinite(dailyAtrPct) && dailyAtrPct < 0.5)
            return new Vote(m,p,s,sampleBreadth,pos,false,"Insufficient same-session volatility potential");
        return new Vote(m,p,s,sampleBreadth,pos,true,"Two or more validated bullish families");
    }
}
