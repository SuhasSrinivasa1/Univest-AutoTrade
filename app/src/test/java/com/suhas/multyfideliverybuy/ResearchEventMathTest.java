package com.suhas.multyfideliverybuy;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class ResearchEventMathTest {
    @Test public void computesAnchoredVwapAndExcursions() {
        long event = 1_000_000L * 1000L;
        List<GrowwClient.Candle> c = new ArrayList<>();
        c.add(new GrowwClient.Candle(999100L, 98, 99, 97, 98, 100));
        c.add(new GrowwClient.Candle(999700L, 99, 100, 98, 100, 100));
        c.add(new GrowwClient.Candle(1_000_000L, 100, 102, 99, 101, 200));
        c.add(new GrowwClient.Candle(1_000_300L, 101, 105, 100, 104, 300));
        c.add(new GrowwClient.Candle(1_001_200L, 104, 110, 103, 109, 400));

        ResearchEventMath.Profile p = ResearchEventMath.profile(c, event);
        assertEquals(100.0, p.eventPrice, 0.0001);
        assertTrue(p.anchoredVwap > 100.0);
        assertEquals(5.0, p.postMfe15Pct, 0.0001);
        assertEquals(-1.0, p.postMae15Pct, 0.0001);
        assertEquals(10.0, p.postMfe30Pct, 0.0001);
        assertTrue(p.volumeAcceleration > 1.0);
    }
}
