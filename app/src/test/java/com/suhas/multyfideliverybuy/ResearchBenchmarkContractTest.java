package com.suhas.multyfideliverybuy;

import org.junit.Test;

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

    @Test public void entryFingerprintHasExactlyFortyParameters() {
        String[] names = ResearchEntryFingerprint.parameterNames();
        assertEquals(40, names.length);
        java.util.Set<String> unique = new java.util.HashSet<>();
        java.util.Collections.addAll(unique, names);
        assertEquals(40, unique.size());
    }
}
