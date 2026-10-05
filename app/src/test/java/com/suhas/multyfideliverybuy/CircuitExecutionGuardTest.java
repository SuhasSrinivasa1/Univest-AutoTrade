package com.suhas.multyfideliverybuy;

import org.junit.Test;

import static org.junit.Assert.*;

// v2.8.3 regression contract for Groww RMS circuit-band execution.
public class CircuitExecutionGuardTest {
    @Test public void buyPlanNeverExceedsUpperCircuit() {
        GrowwClient.QuoteSnapshot q = new GrowwClient.QuoteSnapshot(
                true, 700.00, 699.90, 700.10, 100, 100,
                713.45, 645.55, 100000, "OK");
        GrowwClient.CircuitOrderPlan p = GrowwClient.circuitOrderPlan(q, "BUY", 0.05);
        assertTrue(p.useLimit);
        assertTrue(p.limitPrice <= 713.45 + 1e-9);
        assertEquals(713.45, p.limitPrice, 0.0001);
    }

    @Test public void normalBuyUsesTwoPercentAggressiveLimitInsideBand() {
        GrowwClient.QuoteSnapshot q = new GrowwClient.QuoteSnapshot(
                true, 100.00, 99.95, 100.05, 100, 100,
                120.00, 80.00, 100000, "OK");
        GrowwClient.CircuitOrderPlan p = GrowwClient.circuitOrderPlan(q, "BUY", 0.05);
        assertTrue(p.useLimit);
        assertTrue(p.limitPrice >= 102.00 && p.limitPrice <= 102.10);
        assertTrue(p.limitPrice < 120.00);
    }

    @Test public void sellPlanNeverFallsBelowLowerCircuit() {
        GrowwClient.QuoteSnapshot q = new GrowwClient.QuoteSnapshot(
                true, 700.00, 699.80, 700.10, 100, 100,
                760.00, 690.00, 100000, "OK");
        GrowwClient.CircuitOrderPlan p = GrowwClient.circuitOrderPlan(q, "SELL", 0.05);
        assertTrue(p.useLimit);
        assertTrue(p.limitPrice >= 690.00 - 1e-9);
        assertTrue(p.limitPrice <= 699.80);
    }

    @Test public void missingCircuitFallsBackToLegacyMarketPath() {
        GrowwClient.QuoteSnapshot q = new GrowwClient.QuoteSnapshot(
                true, 100.00, 99.95, 100.05, 100, 100,
                0, 0, 100000, "OK");
        assertFalse(GrowwClient.circuitOrderPlan(q, "BUY", 0.05).useLimit);
        assertFalse(GrowwClient.circuitOrderPlan(q, "SELL", 0.05).useLimit);
    }

    @Test public void terminalBrokerStatusesAreRecognized() {
        assertTrue(GrowwClient.isTerminalFailureStatus("REJECTED • Circuit breach"));
        assertTrue(GrowwClient.isTerminalFailureStatus("FAILED"));
        assertTrue(GrowwClient.isTerminalFailureStatus("CANCELLED"));
        assertFalse(GrowwClient.isTerminalFailureStatus("OPEN"));
    }
}
