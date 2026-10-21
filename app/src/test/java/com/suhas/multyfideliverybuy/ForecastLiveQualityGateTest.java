package com.suhas.multyfideliverybuy;

import org.junit.Test;
import static org.junit.Assert.*;

public class ForecastLiveQualityGateTest {
    private static ResearchMath.Features good() {
        ResearchMath.Features f = new ResearchMath.Features();
        f.dataPoints=30;
        f.return5Pct=0.35;
        f.rsi14=61.0;
        f.relativeVolume20=1.2;
        return f;
    }

    @Test public void freshConfirmedSetupPasses() {
        long now=1_800_000_000_000L;
        assertEquals("",ForecastLiveQualityGate.reject(good(),101,100,now/1000-60,now));
    }

    @Test public void staleOrMissingIntradayDataNeverBecomesARecommendation() {
        long now=1_800_000_000_000L;
        assertTrue(ForecastLiveQualityGate.reject(null,101,100,now/1000,now).contains("history"));
        ResearchMath.Features f=good(); f.dataPoints=8;
        assertTrue(ForecastLiveQualityGate.reject(f,101,100,now/1000,now).contains("history"));
        assertTrue(ForecastLiveQualityGate.reject(good(),101,100,now/1000-600,now).contains("stale"));
    }

    @Test public void bearishOrOverextendedEntryDoesNotPass() {
        long now=1_800_000_000_000L;
        ResearchMath.Features f=good(); f.return5Pct=-0.3;
        assertTrue(ForecastLiveQualityGate.reject(f,101,100,now/1000,now).contains("momentum"));
        f=good(); f.rsi14=88;
        assertTrue(ForecastLiveQualityGate.reject(f,101,100,now/1000,now).contains("RSI"));
        f=good(); f.relativeVolume20=0.4;
        assertTrue(ForecastLiveQualityGate.reject(f,101,100,now/1000,now).contains("participation"));
        assertTrue(ForecastLiveQualityGate.reject(good(),99,100,now/1000,now).contains("VWAP"));
    }
}
