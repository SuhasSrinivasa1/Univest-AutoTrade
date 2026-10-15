package com.suhas.multyfideliverybuy;

import org.junit.Test;
import static org.junit.Assert.*;

public class FixedFormulaMathTest {
    @Test public void weightsNeverChangeAndTotalOneHundred() {
        assertEquals("FORMULA_V1_20261015", FixedFormulaMath.VERSION);
        assertArrayEquals(new int[]{35,25,15,10,5,5,5}, FixedFormulaMath.WEIGHTS);
        int sum=0;
        for(int w:FixedFormulaMath.WEIGHTS)sum+=w;
        assertEquals(100,sum);
    }

    @Test public void completelyMissingSignalsCannotManufactureRecommendation() {
        assertEquals(0, FixedFormulaMath.score(new double[]{
                Double.NaN,Double.NaN,Double.NaN,Double.NaN,
                Double.NaN,Double.NaN,Double.NaN}),0.001);
    }

    @Test public void dataNotConnectedLowersScoreNotRaisesIt() {
        double complete=FixedFormulaMath.score(new double[]{80,80,80,80,80,80,80});
        double missing=FixedFormulaMath.score(new double[]{
                80,80,Double.NaN,80,80,Double.NaN,80});
        assertEquals(80,complete,0.01);
        assertTrue(missing<complete);
    }

    @Test public void hardSelectionGatesRejectBadQuotesMomentumOrTarget() {
        assertFalse(FixedFormulaMath.qualified(95,79,2,80,100,102,3));
        assertFalse(FixedFormulaMath.qualified(95,80,1,80,100,102,3));
        assertFalse(FixedFormulaMath.qualified(95,80,2,59,100,102,3));
        assertFalse(FixedFormulaMath.qualified(95,80,2,80,100,99,3));
        assertFalse(FixedFormulaMath.qualified(95,80,2,80,100,120,3));
        assertTrue(FixedFormulaMath.qualified(85,90,2,92,100,102,3));
    }

    @Test public void targetNetIsExactlyOnePercentPolicyNotRealizedReturns() {
        assertEquals(1.0,FixedFormulaMath.TARGET_NET_PCT,0.0001);
        assertEquals(72.0,FixedFormulaMath.MIN_SCORE,0.0001);
    }
}
