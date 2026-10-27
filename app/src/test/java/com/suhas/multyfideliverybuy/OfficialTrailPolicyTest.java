package com.suhas.multyfideliverybuy;

import org.junit.Test;
import static org.junit.Assert.*;

public class OfficialTrailPolicyTest {
    @Test public void requiredStepExamplesAreExact() {
        assertEquals(0,OfficialTrailPolicy.floor(0,.99),0.00001);
        assertEquals(.95,OfficialTrailPolicy.floor(0,1.00),.00001);
        assertEquals(.95,OfficialTrailPolicy.floor(.95,1.09),.00001);
        assertEquals(1.05,OfficialTrailPolicy.floor(.95,1.10),.00001);
        assertEquals(1.15,OfficialTrailPolicy.floor(1.05,1.20),.00001);
        assertEquals(1.25,OfficialTrailPolicy.floor(1.15,1.30),.00001);
    }
    @Test public void neverRatchetsDownOrArmsEarly() {
        assertEquals(1.55,OfficialTrailPolicy.floor(1.55,.40),1e-8);
        assertEquals(1.55,OfficialTrailPolicy.floor(1.55,1.33),1e-8);
        assertEquals(0,OfficialTrailPolicy.floor(0,Double.NaN),1e-8);
        assertEquals(0,OfficialTrailPolicy.floor(0,Double.POSITIVE_INFINITY),1e-8);
    }
    @Test public void grossReturnAndTickAwareThreshold() {
        assertEquals(1.20,OfficialTrailPolicy.gainPct(100,101.20),1e-8);
        assertEquals(100.95,OfficialTrailPolicy.trigger(100,.05,.95),1e-8);
        assertTrue(OfficialTrailPolicy.trigger(60.13,.05,1.05)<=60.13*1.0105);
    }
}