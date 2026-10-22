package com.suhas.multyfideliverybuy;

import org.junit.Test;
import static org.junit.Assert.*;

public class ResearchEntryFreshnessTest {
    @Test public void rejectAlreadyChasedSpike() {
        assertTrue(ResearchEntryFreshness.reject(100, 104, 99, 103, 100).contains("chase"));
    }
    @Test public void rejectJumpWithinChase() {
        assertTrue(ResearchEntryFreshness.reject(100, 100.8, 99, 105, 200).contains("jumped"));
    }
    @Test public void acceptSmallSafeMove() {
        assertEquals("", ResearchEntryFreshness.reject(100, 100.20, 99, 102, 250));
    }
    @Test public void rejectTimedOutQuote() {
        assertTrue(ResearchEntryFreshness.reject(100, 100.10, 99, 102, 9000).contains("time"));
    }
    @Test public void rejectMissingQuoteOrZone() {
        assertFalse(ResearchEntryFreshness.reject(100, Double.NaN, 99, 102, 100).isEmpty());
        assertFalse(ResearchEntryFreshness.reject(100, 100, 0, 0, 100).isEmpty());
    }
    @Test public void fortyDimensionSchemaStillStable() {
        assertEquals(40, ResearchEntryFingerprint.parameterNames().length);
    }
}