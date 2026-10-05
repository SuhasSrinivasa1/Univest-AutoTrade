package com.suhas.multyfideliverybuy;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class ResearchPlaybookEngineTest {
    @Test public void compositeSignatureKeepsMultipleStrongComponents() throws Exception {
        JSONObject v = new JSONObject();
        v.put("volumeBreakout", 92);
        v.put("trendPullback", 84);
        v.put("momentum", 79);
        v.put("qualityRerating", 30);
        v.put("catalystSector", 25);
        String s = ResearchPlaybookEngine.signature(v);
        assertTrue(s.contains("VOLUME_BREAKOUT"));
        assertTrue(s.contains("TREND_PULLBACK"));
        assertTrue(s.contains("MOMENTUM_CONTINUATION"));
    }

    @Test public void closeCompositeVectorsReceiveHighMatch() throws Exception {
        JSONObject a = new JSONObject();
        JSONObject b = new JSONObject();
        String[] k = {"volumeBreakout","trendPullback","momentum","qualityRerating","catalystSector"};
        int[] x = {90,82,76,35,42};
        int[] y = {86,80,79,38,40};
        for (int i=0;i<k.length;i++) { a.put(k[i],x[i]); b.put(k[i],y[i]); }
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
