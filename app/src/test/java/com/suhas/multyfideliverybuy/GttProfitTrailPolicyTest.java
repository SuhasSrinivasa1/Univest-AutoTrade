package com.suhas.multyfideliverybuy;
import org.junit.Test;
import static org.junit.Assert.*;
public class GttProfitTrailPolicyTest {
    @Test public void specificProfitExamplesAreExact() {
        assertEquals(0,GttProfitTrailPolicy.newFloor(0,.599),1e-7);
        assertEquals(.5,GttProfitTrailPolicy.newFloor(0,.6),1e-7);
        assertEquals(.6,GttProfitTrailPolicy.newFloor(.5,.7),1e-7);
        assertEquals(.7,GttProfitTrailPolicy.newFloor(.6,.8),1e-7);
        assertEquals(.7,GttProfitTrailPolicy.newFloor(.7,.72),1e-7);
    }
    @Test public void netFloorIncludesEstimatedDeliveryFees() {
        double trigger=GttProfitTrailPolicy.stopPrice(100,200,.05,.5);
        assertTrue(trigger>100);
        assertTrue(GttProfitTrailPolicy.netPct(100,200,trigger)>=.5);
        assertEquals(0,GttProfitTrailPolicy.stopPrice(100,200,.05,0),0);
    }
    @Test public void neverLoosensAfterFallingQuotes() {
        assertEquals(.9,GttProfitTrailPolicy.newFloor(.9,.7),1e-6);
        assertEquals(1.2,GttProfitTrailPolicy.newFloor(.9,1.3),1e-6);
    }
}
