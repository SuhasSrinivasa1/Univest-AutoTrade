package com.suhas.multyfideliverybuy;

import org.junit.Test;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.TimeZone;

import static org.junit.Assert.*;

public class ResearchBenchmarkContractTest {
    private static long ist(int y, int m, int d, int h, int min) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"));
        c.set(y, m - 1, d, h, min, 0); c.set(Calendar.MILLISECOND, 0); return c.getTimeInMillis();
    }

    @Test public void upsideUsesOfficialSignalPrices() {
        assertEquals(2.0, UnivestBenchmark.grossUpsidePct(100.0, 102.0), 0.0001);
    }

    @Test public void sameDayAndNextSessionAreOneAndTwoSessions() {
        assertEquals(1, UnivestBenchmark.inclusiveTradingSessions(
                ist(2026,10,8,10,0), ist(2026,10,8,14,0)));
        assertEquals(2, UnivestBenchmark.inclusiveTradingSessions(
                ist(2026,10,8,10,0), ist(2026,10,9,11,0)));
    }

    @Test public void benchmarkWinRequiresTargetWithinTwoSessions() {
        assertEquals("BENCHMARK_WIN", UnivestBenchmark.classifyBenchmarkOutcome(1.8, 1.7, 2));
        assertEquals("PROFITABLE_MISS", UnivestBenchmark.classifyBenchmarkOutcome(1.2, 1.7, 2));
        assertEquals("HORIZON_MISS", UnivestBenchmark.classifyBenchmarkOutcome(2.0, 1.7, 3));
    }

    @Test public void forecastThirtyDayAverageUsesOnlyCompletedRecentOutcomes() throws Exception {
        long now = ist(2026,10,10,15,0);
        JSONArray positions = new JSONArray();
        positions.put(new JSONObject().put("state", "CLOSED").put("exitAt", now - 1_000L).put("netPct", 2.0));
        positions.put(new JSONObject().put("state", "CLOSED").put("exitAt", now - 2_000L).put("netPct", 4.0));
        positions.put(new JSONObject().put("state", "OPEN").put("exitAt", now - 3_000L).put("netPct", 50.0));
        positions.put(new JSONObject().put("state", "CLOSED").put("exitAt", now - 31L * 24L * 60L * 60L * 1000L).put("netPct", 20.0));

        assertEquals(3.0, ResearchTradeEngine.rollingAverageClosedUpsidePct(positions, now, 30), 0.0001);
        assertEquals(2, ResearchTradeEngine.rollingClosedCount(positions, now, 30));
    }

    @Test public void emptyForecastHistoryDisplaysAsZero() {
        assertEquals(0.0, ResearchTradeEngine.rollingAverageClosedUpsidePct(new JSONArray(), System.currentTimeMillis(), 30), 0.0001);
        assertEquals(0, ResearchTradeEngine.rollingClosedCount(new JSONArray(), System.currentTimeMillis(), 30));
    }

    @Test public void entryFingerprintHasExactlyFortyParameters() {
        String[] names = ResearchEntryFingerprint.parameterNames();
        assertEquals(40, names.length);
        java.util.Set<String> unique = new java.util.HashSet<>();
        java.util.Collections.addAll(unique, names);
        assertEquals(40, unique.size());
    }
}
