package com.suhas.multyfideliverybuy;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class ResearchMathTest {
    @Test public void scoresConstructiveBreakout() {
        List<GrowwClient.Candle> c=new ArrayList<>();
        long t=1700000000L;
        for(int i=0;i<70;i++){
            double close=100+i*0.45;
            double vol=i==69?220000:100000;
            c.add(new GrowwClient.Candle(t+i*86400,close-0.5,close+1,close-1,close,vol));
        }
        ResearchMath.Features f=ResearchMath.fromCandles(c);
        ResearchMath.StrategyScores s=ResearchMath.score(f,0,0,false);
        assertTrue(f.close>0);
        assertTrue(f.relativeVolume20>1.5);
        assertTrue(s.bestScore>=60);
        assertNotNull(s.bestStrategy);
    }

    @Test public void learnedZonesAreOrdered() {
        ResearchMath.Features f=new ResearchMath.Features();
        f.close=100;f.atr14=3;f.dataPoints=60;
        double[] z=ResearchMath.learnedZones(f,8);
        assertTrue(z[0]<z[1]);
        assertTrue(z[1]<z[2]);
        assertTrue(z[2]<z[3]);
        assertTrue(z[3]<z[4]);
    }
}
