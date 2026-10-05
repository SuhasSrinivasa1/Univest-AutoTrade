package com.suhas.multyfideliverybuy;

import org.junit.Test;

import static org.junit.Assert.*;

public class ResearchPlaybookEngineTest {
    @Test public void compositeSignatureKeepsMultipleStrongComponents() {
        String s = ResearchPlaybookEngine.signatureFromScores(92, 84, 79, 30, 25);
        assertTrue(s.contains("VOLUME_BREAKOUT"));
        assertTrue(s.contains("TREND_PULLBACK"));
        assertTrue(s.contains("MOMENTUM_CONTINUATION"));
    }

    @Test public void closeCompositeVectorsReceiveHighMatch() {
        int[] a = {90,82,76,35,42};
        int[] b = {86,80,79,38,40};
        double score = ResearchPlaybookEngine.matchScore(
                a, b, "VOLUME_BREAKOUT + TREND_PULLBACK + MOMENTUM_CONTINUATION");
        assertTrue(score >= 95.0);
    }

    @Test public void completedBarCutoffNeverIncludesSignalMinute() {
        long signal = 1_800_123L;
        long cutoff = ResearchSignalProfiler.completedBarCutoff(signal, 60_000L);
        assertTrue(cutoff < signal);
        assertEquals(1_799_999L, cutoff);
    }
}
