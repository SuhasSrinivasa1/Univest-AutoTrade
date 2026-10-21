package com.suhas.multyfideliverybuy;

/**
 * Validation of CURRENT one-minute evidence when an adaptive Forecast call fires.
 * Distinct from the immutable FixedFormulaMath V1 model and official Univest orders.
 * Fail closed on missing or stale data: a high frozen score alone is insufficient.
 */
final class ForecastLiveQualityGate {
    private ForecastLiveQualityGate() {}
    static final long MAX_CANDLE_AGE_MS = 8L * 60_000L;
    static final int MIN_MINUTE_BARS = 15;

    static String reject(ResearchMath.Features minute, double livePrice,
                         double observedVwap, long lastCandleEpochSeconds, long now) {
        if (minute == null || minute.dataPoints < MIN_MINUTE_BARS)
            return "Recent one-minute candle history unavailable (<15 samples)";
        if (!(lastCandleEpochSeconds > 0)
                || lastCandleEpochSeconds * 1000L < now - MAX_CANDLE_AGE_MS
                || lastCandleEpochSeconds * 1000L > now + 60_000L)
            return "One-minute market data is stale or incorrectly timestamped";
        if (!Double.isFinite(livePrice) || livePrice <= 0
                || !Double.isFinite(observedVwap) || observedVwap <= 0)
            return "Live price or one-minute volume-weighted price unavailable";
        if (!Double.isFinite(minute.return5Pct)
                || minute.return5Pct <= 0 || minute.return5Pct > 2.5)
            return "No constructive, non-extended positive 5-minute price momentum";
        if (!Double.isFinite(minute.rsi14) || minute.rsi14 < 50 || minute.rsi14 > 78)
            return "Intraday RSI outside confirmed bullish range (50-78)";
        if (!Double.isFinite(minute.relativeVolume20) || minute.relativeVolume20 < 0.8)
            return "Insufficient confirmed one-minute trading participation";
        if (livePrice + 1e-8 < observedVwap)
            return "Last price remains below intraday VWAP";
        return "";
    }
}
