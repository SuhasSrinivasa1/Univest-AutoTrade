package com.suhas.multyfideliverybuy;

import java.util.List;

final class ResearchEventMath {
    static final class Profile {
        int points;
        double eventPrice;
        double pre15ReturnPct;
        double pre15Vwap;
        double anchoredVwap;
        double postMfe15Pct, postMae15Pct;
        double postMfe30Pct, postMae30Pct;
        double postMfeSessionPct, postMaeSessionPct;
        double volumeAcceleration;
        long timeToPeakMinutes;
    }

    private ResearchEventMath() {}

    static Profile profile(List<GrowwClient.Candle> candles, long eventAtMillis) {
        Profile p = new Profile();
        if (candles == null || candles.isEmpty() || eventAtMillis <= 0) return p;
        long eventSec = eventAtMillis / 1000L;
        double preFirst = 0, preLast = 0, prePv = 0, preVol = 0;
        double postPv = 0, postVol = 0, preVolSum = 0, post5Vol = 0;
        int preCount = 0, post5Count = 0;
        double eventPrice = 0, max15 = -1, min15 = Double.MAX_VALUE, max30 = -1, min30 = Double.MAX_VALUE;
        double maxAll = -1, minAll = Double.MAX_VALUE;
        long peakSec = 0;

        for (GrowwClient.Candle c : candles) {
            long t = c.epochSeconds;
            if (t < eventSec && t >= eventSec - 15L * 60L) {
                if (preFirst <= 0) preFirst = c.open > 0 ? c.open : c.close;
                if (c.close > 0) preLast = c.close;
                if (c.volume > 0 && c.close > 0) { prePv += c.close * c.volume; preVol += c.volume; }
                if (c.volume > 0) preVolSum += c.volume;
                preCount++;
            }
            if (t >= eventSec) {
                if (eventPrice <= 0) eventPrice = c.open > 0 ? c.open : c.close;
                if (c.volume > 0 && c.close > 0) { postPv += c.close * c.volume; postVol += c.volume; }
                long delta = t - eventSec;
                if (delta <= 5L * 60L && c.volume > 0) { post5Vol += c.volume; post5Count++; }
                if (delta <= 15L * 60L) {
                    if (c.high > max15) max15 = c.high;
                    if (c.low > 0 && c.low < min15) min15 = c.low;
                }
                if (delta <= 30L * 60L) {
                    if (c.high > max30) max30 = c.high;
                    if (c.low > 0 && c.low < min30) min30 = c.low;
                }
                if (c.high > maxAll) { maxAll = c.high; peakSec = t; }
                if (c.low > 0 && c.low < minAll) minAll = c.low;
                p.points++;
            }
        }
        p.eventPrice = eventPrice;
        if (preFirst > 0 && preLast > 0) p.pre15ReturnPct = (preLast / preFirst - 1.0) * 100.0;
        if (preVol > 0) p.pre15Vwap = prePv / preVol;
        if (postVol > 0) p.anchoredVwap = postPv / postVol;
        if (eventPrice > 0) {
            if (max15 > 0) p.postMfe15Pct = (max15 / eventPrice - 1.0) * 100.0;
            if (min15 < Double.MAX_VALUE) p.postMae15Pct = (min15 / eventPrice - 1.0) * 100.0;
            if (max30 > 0) p.postMfe30Pct = (max30 / eventPrice - 1.0) * 100.0;
            if (min30 < Double.MAX_VALUE) p.postMae30Pct = (min30 / eventPrice - 1.0) * 100.0;
            if (maxAll > 0) p.postMfeSessionPct = (maxAll / eventPrice - 1.0) * 100.0;
            if (minAll < Double.MAX_VALUE) p.postMaeSessionPct = (minAll / eventPrice - 1.0) * 100.0;
        }
        double preAvg = preCount > 0 ? preVolSum / preCount : 0;
        double postAvg = post5Count > 0 ? post5Vol / post5Count : 0;
        if (preAvg > 0) p.volumeAcceleration = postAvg / preAvg;
        if (peakSec > 0) p.timeToPeakMinutes = Math.max(0L, (peakSec - eventSec) / 60L);
        return p;
    }
}
