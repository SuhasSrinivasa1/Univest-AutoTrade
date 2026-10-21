package com.suhas.multyfideliverybuy;
import org.junit.Test;
import static org.junit.Assert.*;

public class SharedIntradayAveragingTest {
    @Test public void tenUniqueHalfPercentLevelsStopAtMinusFive() {
        assertEquals(10,SharedIntradayAveraging.LEVELS);
        double p=1000;
        for(int l=1;l<=10;l++) {
            double expected=1000*(1-0.005*l);
            assertEquals(expected,SharedIntradayAveraging.triggerRaw(p,l),0.0001);
            if(l>1)assertTrue(SharedIntradayAveraging.triggerRaw(p,l) <
                    SharedIntradayAveraging.triggerRaw(p,l-1));
        }
        assertEquals(950,SharedIntradayAveraging.triggerRaw(p,10),0.001);
        assertTrue(Double.isNaN(SharedIntradayAveraging.triggerRaw(p,11)));
    }
}
